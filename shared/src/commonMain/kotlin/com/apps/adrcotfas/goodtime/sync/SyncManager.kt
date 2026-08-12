/**
 *     Goodtime Productivity
 *     Copyright (C) 2025 Adrian Cotfas
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.apps.adrcotfas.goodtime.sync

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.bl.TimeProvider
import com.apps.adrcotfas.goodtime.bl.TimerManager
import com.apps.adrcotfas.goodtime.bl.generateUuid
import com.apps.adrcotfas.goodtime.data.local.LocalDataRepository
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Orchestrates LAN sync: runs the inbound [SyncServer], spawns outbound [SyncClient]s,
 * observes local state changes, merges remote snapshots last-write-wins and broadcasts
 * the converged state to every connected peer.
 *
 * Convergence: both peers merge from the same inputs with the same deterministic rules,
 * so after one exchange they hold identical state. Idempotent applies + a
 * "last published" comparison break broadcast loops.
 */
class SyncManager(
    private val localDataRepo: LocalDataRepository,
    private val settingsRepo: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val json: Json,
    private val timeProvider: TimeProvider,
    private val timerManagerProvider: () -> TimerManager,
    private val coroutineScope: CoroutineScope,
    private val log: Logger,
) {
    private val lifecycleMutex = Mutex()
    private val connectionMutex = Mutex()
    private val connections = mutableListOf<SyncPeerConnection>()

    private val stateMutex = Mutex()
    private var lastPublishedSnapshot: SnapshotPayload? = null
    private var lastPublishedTimerState: SyncedTimerState? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var server: SyncServer? = null
    private var discovery: DiscoveryManager? = null
    private var discoveryJob: Job? = null
    private var collectJob: Job? = null
    private var settingsJob: Job? = null

    suspend fun ensureStarted() {
        lifecycleMutex.withLock {
            if (collectJob != null) return@withLock
            startInternal()
        }
    }

    private suspend fun startInternal() {
        val syncSettings = settingsRepo.settings.first().syncSettings
        if (!syncSettings.enabled) return

        val deviceId =
            syncSettings.deviceId.ifEmpty {
                generateUuid().also {
                    settingsRepo.setSyncSettings(syncSettings.copy(deviceId = it))
                }
            }
        server?.stop()
        _status.update { it.copy(serverError = null) }
        server =
            SyncServer(
                coroutineScope = coroutineScope,
                json = json,
                port = syncSettings.port,
                connectionFactory = ::createConnection,
                log = log,
                onStatus = { error ->
                    if (error != null) {
                        _status.update { it.copy(serverError = error, serverRunning = false) }
                    }
                },
            ).also { it.start() }
        startDiscovery()
        startCollectors()
        startAutoReconnect()
        _status.update { it.copy(serverRunning = true) }
        log.i { "sync started, deviceId=$deviceId" }
    }

    private suspend fun startDiscovery() {
        discovery?.stop()
        discovery = null
        discoveryJob?.cancel()
        discoveryJob = null
        val ownDeviceId = settingsRepo.settings.first().syncSettings.deviceId
        val manager =
            DiscoveryManager(
                coroutineScope = coroutineScope,
                json = json,
                log = log,
                nowMillis = { timeProvider.now() },
            )
        discovery = manager
        discoveryJob =
            coroutineScope.launch {
                manager.discoveredPeers.collect { _discoveredPeers.value = it }
            }
        coroutineScope.launch {
            manager.start(
                ownDeviceId = { ownDeviceId },
                announcement = {
                    val s = settingsRepo.settings.first().syncSettings
                    DiscoveryAnnouncement(deviceId = s.deviceId, deviceName = s.serverName, port = s.port)
                },
            )
        }
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            collectJob?.cancel()
            collectJob = null
            settingsJob?.cancel()
            settingsJob = null
            server?.stop()
            server = null
            discoveryJob?.cancel()
            discoveryJob = null
            discovery?.stop()
            discovery = null
            _discoveredPeers.value = emptyList()
            connectionMutex.withLock {
                connections.toList().forEach { it.close() }
                connections.clear()
            }
            stateMutex.withLock {
                lastPublishedSnapshot = null
                lastPublishedTimerState = null
            }
            _status.update { it.copy(serverRunning = false, serverError = null, connectedPeers = 0, peers = emptyList()) }
        }
    }

    /**
     * Opens an outbound connection to [host]. Suspends until the handshake succeeds or fails
     * (with a timeout), updating [SyncStatus] so the UI can show progress and errors.
     *
     * @param port port of the peer's sync server; defaults to this device's configured port
     * @param reportError whether failures are surfaced to the UI (e.g. auto-reconnects run
     * silently and only log)
     */
    suspend fun connectTo(host: String, port: Int? = null, reportError: Boolean = true): Boolean {
        if (connectionMutex.withLock { connections.any { it.host == host } }) {
            log.i { "already connected to $host" }
            return true
        }
        if (reportError) {
            _status.update { it.copy(connectingTo = host, lastConnectError = null) }
        }
        val result =
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    val effectivePort = port ?: settingsRepo.settings.first().syncSettings.port
                    SyncClient(
                        host = host,
                        port = effectivePort,
                        json = json,
                        connectionFactory = ::createConnection,
                        coroutineScope = coroutineScope,
                        log = log,
                    ).connect()
                }
            } catch (e: TimeoutCancellationException) {
                log.e { "connect to $host timed out" }
                if (reportError) {
                    _status.update {
                        it.copy(
                            connectingTo = null,
                            lastConnectError = e.message ?: "Connection timed out",
                        )
                    }
                }
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e { "connect to $host failed: $e" }
                if (reportError) {
                    _status.update {
                        it.copy(
                            connectingTo = null,
                            lastConnectError = e.message ?: "Connection failed",
                        )
                    }
                }
                return false
            }
        if (reportError) {
            _status.update { it.copy(connectingTo = null) }
        }
        if (result) {
            log.i { "connected to $host" }
            rememberPeer(host)
        } else if (reportError) {
            _status.update { it.copy(lastConnectError = "Connection failed") }
        }
        return result
    }

    /**
     * Remembers a peer we connected to so it can be reconnected automatically next time
     * sync starts. Most recently used hosts come first; the list is capped.
     */
    private suspend fun rememberPeer(host: String) {
        val current = settingsRepo.settings.first().syncSettings
        val updated = (listOf(host) + current.peerHosts.filterNot { it == host }).take(MAX_SAVED_PEERS)
        if (updated != current.peerHosts) {
            settingsRepo.setSyncSettings(current.copy(peerHosts = updated))
        }
    }

    /**
     * Reconnects to the peers this device has connected to before, silently. Skips hosts we
     * are already connected to (e.g. the peer connected to us first) and our own addresses.
     */
    private suspend fun startAutoReconnect() {
        val settings = settingsRepo.settings.first().syncSettings
        val localIps = getLocalIpAddresses().toSet()
        val connectedHosts = connectionMutex.withLock { connections.map { it.host }.toSet() }
        settings.peerHosts
            .filterNot { it in connectedHosts || it in localIps }
            .forEach { host ->
                coroutineScope.launch {
                    connectTo(host, reportError = false)
                }
            }
    }

    fun clearConnectError() {
        _status.update { it.copy(lastConnectError = null) }
    }

    /**
     * Forces a snapshot exchange with every connected peer, so both sides converge even if no
     * automatic change triggered a broadcast. Returns whether any peer was connected.
     */
    suspend fun syncNow(): Boolean {
        val active = connectionMutex.withLock { connections.toList() }
        if (active.isEmpty()) return false
        broadcastSnapshot(buildSnapshot())
        return true
    }

    /**
     * Called by [SyncEventListener] whenever the local timer transitions. Mirrors do not
     * announce their own transitions - only the leading device broadcasts, avoiding
     * echo loops. State of an idle device is still shared via the snapshot on connect.
     */
    fun publishTimerState() {
        coroutineScope.launch {
            val hasConnections = connectionMutex.withLock { connections.isNotEmpty() }
            if (!hasConnections) return@launch
            val timerManager = timerManagerProvider()
            if (timerManager.isMirroring) return@launch
            val state = timerManager.toSyncedTimerState() ?: return@launch
            stateMutex.withLock {
                val merged = syncEngine.mergeTimerState(lastPublishedTimerState, state)
                lastPublishedTimerState = merged ?: state
            }
            broadcastTimerState(lastPublishedTimerState!!)
        }
    }

    private suspend fun createConnection(session: WebSocketSession, host: String): SyncPeerConnection? {
        val syncSettings = settingsRepo.settings.first().syncSettings
        var connection: SyncPeerConnection? = null
        val added =
            connectionMutex.withLock {
                if (connections.any { it.host == host }) {
                    log.i { "already connected to $host, skipping duplicate connection" }
                    false
                } else {
                    connection =
                        SyncPeerConnection(
                            session = session,
                            json = json,
                            host = host,
                            deviceId = syncSettings.deviceId,
                            serverName = syncSettings.serverName,
                            onHello = { _, _ -> updatePeersStatus() },
                            onSnapshot = ::onSnapshotInternal,
                            onTimerState = ::onTimerStateInternal,
                            onSettings = ::onSettingsInternal,
                            onDisconnected = { conn ->
                                removeConnection(conn)
                                updatePeersStatus()
                            },
                            log = log,
                        )
                    connections.add(connection)
                    true
                }
            }
        if (!added) {
            runCatching { session.close() }
            return null
        }
        updatePeersStatus()
        val addedConnection = connection ?: return null
        // Hand the new peer our current state; the merge-and-broadcast path then converges.
        coroutineScope.launch {
            safeSend { addedConnection.sendSnapshot(buildSnapshot()) }
        }
        return addedConnection
    }

    private suspend fun updatePeersStatus() {
        connectionMutex.withLock {
            val peers =
                connections.mapNotNull { conn ->
                    if (conn.peerDeviceId.isEmpty()) {
                        null
                    } else {
                        SyncPeerInfo(conn.peerDeviceId, conn.peerDeviceName, conn.host)
                    }
                }
            _status.update { it.copy(peers = peers, connectedPeers = connections.size) }
        }
    }

    private suspend fun removeConnection(connection: SyncPeerConnection) {
        connectionMutex.withLock { connections.remove(connection) }
        log.i { "sync peer disconnected" }
    }

    private suspend fun buildSnapshot(): SnapshotPayload {
        val timerManager = timerManagerProvider()
        return SnapshotPayload(
            sessions = localDataRepo.selectAllSessionsOnce(),
            deletedSessionTombstones = settingsRepo.sessionTombstones.first(),
            labels = localDataRepo.selectAllLabelsOnce(),
            timerProfiles = localDataRepo.selectAllTimerProfilesOnce(),
            settings = settingsRepo.syncedSettings.first(),
            timerState = timerManager.toSyncedTimerState(),
        )
    }

    private suspend fun onSnapshotInternal(remote: SnapshotPayload) {
        val local = buildSnapshot()
        val sessionMerge =
            syncEngine.mergeSessions(
                local = local.sessions,
                remote = remote.sessions,
                localTombstones = local.deletedSessionTombstones,
                remoteTombstones = remote.deletedSessionTombstones,
            )
        val mergedLabels = syncEngine.mergeLabels(local.labels, remote.labels)
        val mergedProfiles = syncEngine.mergeTimerProfiles(local.timerProfiles, remote.timerProfiles)
        val mergedSettings = syncEngine.mergeSyncedSettings(local.settings, remote.settings)
        val mergedTimerState = syncEngine.mergeTimerState(local.timerState, remote.timerState)

        localDataRepo.applySyncedSessions(sessionMerge.sessionsToApply, sessionMerge.deletedSyncIds)
        localDataRepo.applySyncedLabels(mergedLabels)
        localDataRepo.applySyncedTimerProfiles(mergedProfiles)
        settingsRepo.saveSessionTombstones(sessionMerge.outgoingTombstones)

        if (mergedSettings != null && mergedSettings != local.settings) {
            settingsRepo.saveSyncedSettings(mergedSettings)
            settingsRepo.applySyncedSettings(mergedSettings)
        }

        val timerManager = timerManagerProvider()
        if (mergedTimerState != null && mergedTimerState != local.timerState) {
            timerManager.applySyncedTimerState(mergedTimerState)
        }

        val syncTimestamp = timeProvider.now()
        val currentSyncSettings = settingsRepo.settings.first().syncSettings
        settingsRepo.setSyncSettings(currentSyncSettings.copy(lastSyncTimestamp = syncTimestamp))
        _status.update { it.copy(lastSyncTimestamp = syncTimestamp) }

        val mergedSnapshot =
            SnapshotPayload(
                sessions = sessionMerge.outgoingSessions,
                deletedSessionTombstones = sessionMerge.outgoingTombstones,
                labels = mergedLabels,
                timerProfiles = mergedProfiles,
                settings = mergedSettings,
                timerState = mergedTimerState ?: local.timerState,
            )

        stateMutex.withLock {
            if (mergedSnapshot != lastPublishedSnapshot) {
                lastPublishedSnapshot = mergedSnapshot
            } else {
                return
            }
        }
        broadcastSnapshot(mergedSnapshot)
    }

    private suspend fun onTimerStateInternal(remote: SyncedTimerState) {
        val timerManager = timerManagerProvider()
        val local = timerManager.toSyncedTimerState()
        val merged = syncEngine.mergeTimerState(local, remote) ?: return
        if (merged != local) {
            timerManager.applySyncedTimerState(merged)
        }
        stateMutex.withLock {
            if (merged != lastPublishedTimerState) {
                lastPublishedTimerState = merged
            } else {
                return
            }
        }
        broadcastTimerState(merged)
    }

    private suspend fun onSettingsInternal(remote: SyncedSettings) {
        val local = settingsRepo.syncedSettings.first()
        val merged = syncEngine.mergeSyncedSettings(local, remote) ?: return
        if (merged != local) {
            settingsRepo.saveSyncedSettings(merged)
            settingsRepo.applySyncedSettings(merged)
        }
    }

    private suspend fun startCollectors() {
        collectJob =
            coroutineScope.launch {
                combine(
                    localDataRepo.selectAllSessions(),
                    localDataRepo.selectAllLabels(),
                    localDataRepo.selectAllTimerProfiles(),
                    settingsRepo.sessionTombstones,
                    settingsRepo.syncedSettings,
                ) { sessions, labels, profiles, tombstones, syncedSettings ->
                    SnapshotPayload(
                        sessions = sessions,
                        deletedSessionTombstones = tombstones,
                        labels = labels,
                        timerProfiles = profiles,
                        settings = syncedSettings,
                        timerState = timerManagerProvider().toSyncedTimerState(),
                    )
                }.collect { snapshot ->
                    stateMutex.withLock {
                        if (snapshot == lastPublishedSnapshot) return@collect
                        lastPublishedSnapshot = snapshot
                    }
                    broadcastSnapshot(snapshot)
                }
            }
        settingsJob =
            coroutineScope.launch {
                settingsRepo.settings
                    .combine(settingsRepo.syncedSettings) { appSettings, synced -> appSettings to synced }
                    .collect { (appSettings, synced) ->
                        val candidate = SyncedSettings.from(appSettings, synced?.updatedAt ?: 0L)
                        if (candidate != synced) {
                            settingsRepo.saveSyncedSettings(candidate.copy(updatedAt = timeProvider.now()))
                        }
                    }
            }
    }

    private suspend fun broadcastSnapshot(snapshot: SnapshotPayload) {
        val active = connectionMutex.withLock { connections.toList() }
        active.forEach { connection -> safeSend { connection.sendSnapshot(snapshot) } }
    }

    private suspend fun broadcastTimerState(state: SyncedTimerState) {
        val active = connectionMutex.withLock { connections.toList() }
        active.forEach { connection -> safeSend { connection.sendTimerState(state) } }
    }

    private suspend fun safeSend(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // connection died mid-send; it will be removed by the read loop's finally block
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MAX_SAVED_PEERS = 8
    }
}
