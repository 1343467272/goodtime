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
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

/**
 * A single bidirectional WebSocket connection to a sync peer, shared by the server
 * (accepted connections) and the client (outgoing connections).
 *
 * Wire format: [SyncEnvelope] frames. A peer announces itself with a HELLO frame and
 * the full state is exchanged with SNAPSHOT frames; live timer transitions use
 * TIMER_STATE frames. Unknown message types are ignored so mixed app versions remain
 * compatible.
 *
 * All merge/apply logic lives in the callbacks supplied by the [SyncManager]; this
 * class is pure transport.
 */
class SyncPeerConnection(
    private val session: WebSocketSession,
    private val json: Json,
    /** Address of the remote peer; used for display and to reconnect from a discovered device. */
    val host: String,
    private val deviceId: String,
    private val serverName: String,
    /** This device's sync server port, advertised to the peer so it can pair with us. */
    private val localPort: Int,
    private val onHello: suspend (SyncPeerConnection, HelloPayload) -> Unit,
    private val onSnapshot: suspend (SnapshotPayload) -> Unit,
    private val onOverwriteSnapshot: suspend (SnapshotPayload) -> Unit,
    private val onPullRequest: suspend (SyncPeerConnection) -> Unit,
    private val onTimerState: suspend (SyncedTimerState) -> Unit,
    private val onSettings: suspend (SyncedSettings) -> Unit,
    private val onDisconnected: suspend (SyncPeerConnection) -> Unit,
    private val log: Logger,
) {
    /** The remote peer's advertised identity, populated once its HELLO frame arrives. */
    var peerDeviceId: String = ""
        private set

    var peerDeviceName: String = ""
        private set

    val isActive: Boolean
        get() = session.isActive

    /**
     * Sends the HELLO frame and then pumps inbound frames until the connection closes.
     * Suspends for the lifetime of the connection.
     */
    suspend fun start() {
        sendEnvelope(
            SyncMessageTypes.HELLO,
            json.encodeToString(
                HelloPayload.serializer(),
                HelloPayload(deviceId, serverName, localPort),
            ),
        )
        try {
            session.incoming.consumeAsFlow().collect { frame ->
                val text = (frame as? Frame.Text)?.readText() ?: return@collect
                val envelope =
                    runCatching { json.decodeFromString(SyncEnvelope.serializer(), text) }.getOrNull()
                        ?: return@collect
                when (envelope.type) {
                    SyncMessageTypes.HELLO -> {
                        val hello =
                            runCatching { json.decodeFromString(HelloPayload.serializer(), envelope.payload) }
                                .getOrNull() ?: return@collect
                        peerDeviceId = hello.deviceId
                        peerDeviceName = hello.serverName
                        onHello(this, hello)
                    }

                    SyncMessageTypes.SNAPSHOT -> {
                        val remote =
                            runCatching { json.decodeFromString(SnapshotPayload.serializer(), envelope.payload) }
                                .getOrNull() ?: return@collect
                        onSnapshot(remote)
                    }

                    SyncMessageTypes.OVERWRITE_SNAPSHOT -> {
                        val remote =
                            runCatching { json.decodeFromString(SnapshotPayload.serializer(), envelope.payload) }
                                .getOrNull() ?: return@collect
                        onOverwriteSnapshot(remote)
                    }

                    SyncMessageTypes.PULL_REQUEST -> {
                        onPullRequest(this)
                    }

                    SyncMessageTypes.TIMER_STATE -> {
                        val payload =
                            runCatching { json.decodeFromString(TimerStatePayload.serializer(), envelope.payload) }
                                .getOrNull() ?: return@collect
                        payload.timerState?.let { onTimerState(it) }
                    }

                    SyncMessageTypes.SETTINGS -> {
                        val payload =
                            runCatching { json.decodeFromString(SettingsPayload.serializer(), envelope.payload) }
                                .getOrNull() ?: return@collect
                        onSettings(payload.settings)
                    }
                }
            }
        } finally {
            onDisconnected(this)
        }
    }

    suspend fun sendSnapshot(snapshot: SnapshotPayload) {
        sendEnvelope(SyncMessageTypes.SNAPSHOT, json.encodeToString(SnapshotPayload.serializer(), snapshot))
    }

    suspend fun sendOverwriteSnapshot(snapshot: SnapshotPayload) {
        sendEnvelope(
            SyncMessageTypes.OVERWRITE_SNAPSHOT,
            json.encodeToString(SnapshotPayload.serializer(), snapshot),
        )
    }

    suspend fun sendPullRequest() {
        sendEnvelope(SyncMessageTypes.PULL_REQUEST, "")
    }

    suspend fun sendTimerState(state: SyncedTimerState) {
        sendEnvelope(
            SyncMessageTypes.TIMER_STATE,
            json.encodeToString(TimerStatePayload.serializer(), TimerStatePayload(timerState = state)),
        )
    }

    suspend fun close() {
        try {
            session.close()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // already closed or failed; nothing to do
        }
    }

    private suspend fun sendEnvelope(type: String, payload: String) {
        try {
            session.send(Frame.Text(json.encodeToString(SyncEnvelope.serializer(), SyncEnvelope(type, payload))))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "sync send ($type) failed: $e" }
        }
    }
}
