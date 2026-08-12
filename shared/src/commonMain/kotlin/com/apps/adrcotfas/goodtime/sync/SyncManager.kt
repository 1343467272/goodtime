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
import com.apps.adrcotfas.goodtime.data.settings.PairedPeer
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    /**
     * True only for the first inbound timer-state exchange after the process started. While set,
     * an overwritten timer is adopted as-is instead of being force-paused, so opening the app
     * while the other device is running its timer mirrors that timer instead of stopping it.
     * Cleared as soon as the first inbound snapshot/timer state is processed; the normal
     * last-write-wins + pause-on-overwrite mechanism applies to every later exchange.
     */
    private var startupYieldActive = true

    /** Whether [startInternal] has already run in this process (re-enabling sync is not a fresh open). */
    private var hasStartedBefore = false

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    /**
     * Whether the sync settings screen is open. Unpaired devices are only searched for while it
     * is; in the background the app only reconnects to already paired devices.
     */
    private val _syncScreenVisible = MutableStateFlow(false)
    val syncScreenVisible: StateFlow<Boolean> = _syncScreenVisible.asStateFlow()

    /** Hosts with an outbound connect attempt in flight, to avoid duplicate concurrent attempts. */
    private val connectingHosts = mutableSetOf<String>()

    private var server: SyncServer? = null
    private var discovery: DiscoveryManager? = null
    private var discoveryJob: Job? = null
    private var searchJob: Job? = null
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

        // Only a genuine cold start (the first sync start in this process) yields to peers;
        // re-enabling sync mid-session must keep the normal pause-on-overwrite mechanism.
        if (hasStartedBefore) {
            startupYieldActive = false
        }
        hasStartedBefore = true

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
        startPeriodicSearch()
        _status.update { it.copy(serverRunning = true) }
        log.i { "sync started, deviceId=$deviceId" }
    }

    private suspend fun startDiscovery() {
        discovery?.stop()
        discovery = null
        discoveryJob?.cancel()
        discoveryJob = null
        searchJob?.cancel()
        searchJob = null
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
                manager.start(
                    ownDeviceId = { ownDeviceId },
                    announcement = {
                        val s = settingsRepo.settings.first().syncSettings
                        DiscoveryAnnouncement(deviceId = s.deviceId, deviceName = s.serverName, port = s.port)
                    },
                )
                updateSearchState()
                runSearchCoordinator(manager)
            }
    }

    /**
     * While searching, keeps trying paired peers by their last known address. This is the
     * fallback when discovery does not see a peer (e.g. its announcement is dropped); the
     * discovery-driven path handles the common case, including changed addresses.
     */
    private suspend fun startPeriodicSearch() {
        searchJob =
            coroutineScope.launch {
                while (coroutineContext.isActive) {
                    if (shouldSearch()) {
                        tryConnectPairedHosts()
                    }
                    delay(SEARCH_INTERVAL_MS)
                }
            }
    }

    /**
     * Surfaces unpaired devices to the UI only while the sync settings screen is open, and
     * auto-connects to discovered peers that are already paired (searching for paired devices).
     * Once one paired device is connected and the settings screen is closed, searching stops.
     */
    private suspend fun runSearchCoordinator(manager: DiscoveryManager) {
        manager.discoveredPeers.collect { peers ->
            val pairedIds = pairedDeviceIds()
            if (_syncScreenVisible.value) {
                _discoveredPeers.value = peers.filterNot { it.deviceId in pairedIds }
            }
            if (shouldSearch()) {
                peers.filter { it.deviceId in pairedIds }.forEach { peer ->
                    coroutineScope.launch {
                        connectTo(peer.host, peer.port, reportError = false)
                    }
                }
            }
        }
    }

    /** Whether to keep searching: always on the sync screen, otherwise until one device is connected. */
    private suspend fun shouldSearch(): Boolean {
        if (_syncScreenVisible.value) return true
        return connectionMutex.withLock { connections.isEmpty() }
    }

    private suspend fun updateSearchState() {
        val searching = shouldSearch()
        discovery?.setSearching(searching)
        if (!searching) {
            _discoveredPeers.value = emptyList()
        }
    }

    /** Called by the UI when the sync settings screen is opened or closed. */
    fun setSyncScreenVisible(visible: Boolean) {
        _syncScreenVisible.value = visible
        coroutineScope.launch { updateSearchState() }
    }

    private suspend fun tryConnectPairedHosts() {
        val settings = settingsRepo.settings.first().syncSettings
        val localIps = getLocalIpAddresses().toSet()
        val connectedHosts = connectionMutex.withLock { connections.map { it.host }.toSet() }
        settings.pairedPeers
            .filterNot { it.host in connectedHosts || it.host in localIps }
            .forEach { peer ->
                coroutineScope.launch {
                    connectTo(peer.host, peer.port.takeIf { it > 0 }, reportError = false)
                }
            }
    }

    private suspend fun pairedDeviceIds(): Set<String> =
        settingsRepo.settings.first().syncSettings.pairedPeers.map { it.deviceId }.toSet()

    suspend fun stop() {
        lifecycleMutex.withLock {
            _syncScreenVisible.value = false
            collectJob?.cancel()
            collectJob = null
            settingsJob?.cancel()
            settingsJob = null
            server?.stop()
            server = null
            discoveryJob?.cancel()
            discoveryJob = null
            searchJob?.cancel()
            searchJob = null
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
        val acquired =
            connectionMutex.withLock {
                if (host in connectingHosts) {
                    false
                } else {
                    connectingHosts.add(host)
                    true
                }
            }
        if (!acquired) {
            log.i { "already connecting to $host, skipping" }
            return false
        }
        try {
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
            } else if (reportError) {
                _status.update { it.copy(lastConnectError = "Connection failed") }
            }
            updateSearchState()
            return result
        } finally {
            connectionMutex.withLock { connectingHosts.remove(host) }
        }
    }

    /**
     * Records a successful pairing. The peer is then reconnected automatically whenever sync
     * runs, without the user having to connect by hand again. The most recently used peers
     * come first; the list is capped.
     */
    private suspend fun rememberPeer(peer: PairedPeer) {
        val current = settingsRepo.settings.first().syncSettings
        val updated =
            (listOf(peer) + current.pairedPeers.filterNot { it.deviceId == peer.deviceId })
                .take(MAX_SAVED_PEERS)
        if (updated != current.pairedPeers) {
            settingsRepo.setSyncSettings(current.copy(pairedPeers = updated))
        }
    }

    /**
     * Forgets a pairing (like Bluetooth "forget"). The peer stays connected until it
     * disconnects, but it will no longer be auto-reconnected or listed as paired.
     */
    suspend fun forgetPeer(deviceId: String) {
        val current = settingsRepo.settings.first().syncSettings
        val updated = current.pairedPeers.filterNot { it.deviceId == deviceId }
        if (updated != current.pairedPeers) {
            settingsRepo.setSyncSettings(current.copy(pairedPeers = updated))
        }
        connectionMutex.withLock {
            connections.filter { it.peerDeviceId == deviceId }.forEach { conn ->
                coroutineScope.launch { conn.close() }
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
        updateLastSyncTimestamp()
        return true
    }

    /**
     * Sends this device's full state to every connected peer as a forced overwrite: the peers
     * replace their local state with it instead of merging. Returns whether any peer was connected.
     */
    suspend fun overwritePeers(): Boolean {
        val active = connectionMutex.withLock { connections.toList() }
        if (active.isEmpty()) return false
        val snapshot = buildSnapshot()
        active.forEach { connection -> safeSend { connection.sendOverwriteSnapshot(snapshot) } }
        updateLastSyncTimestamp()
        return true
    }

    /**
     * Asks every connected peer to send its full state; each answer replaces this device's
     * local state (see [onOverwriteSnapshotInternal]). Returns whether any peer was connected.
     */
    suspend fun pullFromPeers(): Boolean {
        val active = connectionMutex.withLock { connections.toList() }
        if (active.isEmpty()) return false
        active.forEach { connection -> safeSend { connection.sendPullRequest() } }
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
                            localPort = syncSettings.port,
                            onHello = { conn, hello ->
                                rememberPeer(
                                    PairedPeer(
                                        deviceId = conn.peerDeviceId,
                                        deviceName = conn.peerDeviceName,
                                        host = conn.host,
                                        port = hello.port,
                                    ),
                                )
                                updatePeersStatus()
                            },
                            onSnapshot = ::onSnapshotInternal,
                            onOverwriteSnapshot = ::onOverwriteSnapshotInternal,
                            onPullRequest = { connection ->
                                coroutineScope.launch {
                                    safeSend { connection.sendOverwriteSnapshot(buildSnapshot()) }
                                }
                            },
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

    /**
     * Atomically takes the startup yield flag: reports whether this inbound exchange is the
     * first one of the process and disables the yield for all subsequent exchanges.
     */
    private suspend fun consumeStartupYield(): Boolean =
        stateMutex.withLock {
            val active = startupYieldActive
            startupYieldActive = false
            active
        }

    private suspend fun removeConnection(connection: SyncPeerConnection) {
        connectionMutex.withLock { connections.remove(connection) }
        log.i { "sync peer disconnected" }
        updateSearchState()
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
        val startupYield = consumeStartupYield()
        val resolvedTimerState =
            mergedTimerState?.let {
                if (startupYield) {
                    it
                } else {
                    syncEngine.pauseOnOverwrite(local.timerState, it, timeProvider.now())
                }
            }

        localDataRepo.applySyncedSessions(sessionMerge.sessionsToApply, sessionMerge.deletedSyncIds)
        localDataRepo.applySyncedLabels(mergedLabels)
        localDataRepo.applySyncedTimerProfiles(mergedProfiles)
        settingsRepo.saveSessionTombstones(sessionMerge.outgoingTombstones)

        if (mergedSettings != null && mergedSettings != local.settings) {
            settingsRepo.saveSyncedSettings(mergedSettings)
            settingsRepo.applySyncedSettings(mergedSettings)
        }

        val timerManager = timerManagerProvider()
        if (resolvedTimerState != null && resolvedTimerState != local.timerState) {
            timerManager.applySyncedTimerState(resolvedTimerState)
        }

        updateLastSyncTimestamp()

        val mergedSnapshot =
            SnapshotPayload(
                sessions = sessionMerge.outgoingSessions,
                deletedSessionTombstones = sessionMerge.outgoingTombstones,
                labels = mergedLabels,
                timerProfiles = mergedProfiles,
                settings = mergedSettings,
                timerState = resolvedTimerState ?: local.timerState,
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

    /**
     * Applies a forced overwrite from a peer: this device's sessions, labels, timer profiles,
     * settings, tombstones and timer state are all replaced by the received snapshot, without
     * merging. Used by "overwrite other device" (this device is the target) and "pull from
     * device" (this device requested the snapshot). The local flow collectors then broadcast
     * the adopted state to the remaining peers so the whole group converges.
     */
    private suspend fun onOverwriteSnapshotInternal(remote: SnapshotPayload) {
        localDataRepo.replaceAllSyncedSessions(remote.sessions)
        localDataRepo.replaceAllSyncedLabels(remote.labels)
        localDataRepo.replaceAllSyncedTimerProfiles(remote.timerProfiles)
        settingsRepo.saveSessionTombstones(remote.deletedSessionTombstones)

        if (remote.settings != null) {
            settingsRepo.saveSyncedSettings(remote.settings)
            settingsRepo.applySyncedSettings(remote.settings)
        }

        remote.timerState?.let { state -> timerManagerProvider().applySyncedTimerState(state) }

        updateLastSyncTimestamp()
        log.i { "sync state overwritten by peer (${remote.sessions.size} sessions)" }
    }

    private suspend fun updateLastSyncTimestamp() {
        val syncTimestamp = timeProvider.now()
        val currentSyncSettings = settingsRepo.settings.first().syncSettings
        settingsRepo.setSyncSettings(currentSyncSettings.copy(lastSyncTimestamp = syncTimestamp))
        _status.update { it.copy(lastSyncTimestamp = syncTimestamp) }
    }

    private suspend fun onTimerStateInternal(remote: SyncedTimerState) {
        val timerManager = timerManagerProvider()
        val local = timerManager.toSyncedTimerState()
        val merged = syncEngine.mergeTimerState(local, remote) ?: return
        val startupYield = consumeStartupYield()
        // If this device's own state got overwritten by a different RUNNING session, pause
        // instead of running it; the fresh pause timestamp propagates back to the winner.
        // A freshly opened device skips the pause and mirrors the peer instead, so opening the
        // app doesn't stop a timer that is already running on the other device.
        val resolved =
            if (startupYield) {
                merged
            } else {
                syncEngine.pauseOnOverwrite(local, merged, timeProvider.now())
            }
        if (resolved != local) {
            timerManager.applySyncedTimerState(resolved)
        }
        stateMutex.withLock {
            if (resolved != lastPublishedTimerState) {
                lastPublishedTimerState = resolved
            } else {
                return
            }
        }
        broadcastTimerState(resolved)
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
        /** How often the fallback reconnect-by-address loop retries paired devices. */
        private const val SEARCH_INTERVAL_MS = 15_000L
    }
}
