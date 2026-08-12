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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val connectionMutex = Mutex()
    private val connections = mutableListOf<SyncPeerConnection>()

    private val stateMutex = Mutex()
    private var lastPublishedSnapshot: SnapshotPayload? = null
    private var lastPublishedTimerState: SyncedTimerState? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var server: SyncServer? = null
    private var collectJob: Job? = null
    private var settingsJob: Job? = null

    suspend fun ensureStarted() {
        if (collectJob != null) return
        val syncSettings = settingsRepo.settings.first().syncSettings
        if (!syncSettings.enabled) return

        val deviceId =
            syncSettings.deviceId.ifEmpty {
                generateUuid().also {
                    settingsRepo.setSyncSettings(syncSettings.copy(deviceId = it))
                }
            }
        server?.stop()
        server =
            SyncServer(
                coroutineScope = coroutineScope,
                json = json,
                port = syncSettings.port,
                connectionFactory = ::createConnection,
                log = log,
            ).also { it.start() }
        startCollectors()
        _status.update { it.copy(serverRunning = true) }
        log.i { "sync started, deviceId=$deviceId" }
    }

    suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        settingsJob?.cancel()
        settingsJob = null
        server?.stop()
        server = null
        connectionMutex.withLock {
            connections.toList().forEach { it.close() }
            connections.clear()
        }
        stateMutex.withLock {
            lastPublishedSnapshot = null
            lastPublishedTimerState = null
        }
        _status.update { it.copy(serverRunning = false, connectedPeers = 0) }
    }

    fun connectTo(host: String) {
        coroutineScope.launch {
            val port = settingsRepo.settings.first().syncSettings.port
            SyncClient(
                host = host,
                port = port,
                json = json,
                connectionFactory = ::createConnection,
                coroutineScope = coroutineScope,
                log = log,
            ).connect()
        }
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

    private suspend fun createConnection(session: WebSocketSession): SyncPeerConnection {
        val syncSettings = settingsRepo.settings.first().syncSettings
        val connection =
            SyncPeerConnection(
                session = session,
                json = json,
                deviceId = syncSettings.deviceId,
                serverName = syncSettings.serverName,
                onSnapshot = ::onSnapshotInternal,
                onTimerState = ::onTimerStateInternal,
                onSettings = ::onSettingsInternal,
                onDisconnected = ::removeConnection,
                log = log,
            )
        connectionMutex.withLock { connections.add(connection) }
        _status.update { it.copy(connectedPeers = connections.size) }
        // Hand the new peer our current state; the merge-and-broadcast path then converges.
        coroutineScope.launch {
            safeSend { connection.sendSnapshot(buildSnapshot()) }
        }
        return connection
    }

    private suspend fun removeConnection(connection: SyncPeerConnection) {
        connectionMutex.withLock { connections.remove(connection) }
        _status.update { it.copy(connectedPeers = connections.size) }
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
}
