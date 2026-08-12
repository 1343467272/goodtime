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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.WebSocketSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Outbound half of a peer. Opens a WebSocket to [host]:[port] and runs a
 * [SyncPeerConnection] for its lifetime, closing the underlying client afterwards.
 */
class SyncClient(
    private val host: String,
    private val port: Int,
    private val json: Json,
    private val connectionFactory: suspend (WebSocketSession, String) -> SyncPeerConnection?,
    private val coroutineScope: CoroutineScope,
    private val log: Logger,
) {
    /**
     * Establishes the connection and returns once the handshake completes (success) or the
     * attempt fails (false). The inbound frame pump keeps running in the background until the
     * peer disconnects.
     */
    suspend fun connect(): Boolean {
        log.i { "connecting to peer $host:$port" }
        val client =
            HttpClient(CIO) {
                install(WebSockets)
            }
        return try {
            val session = client.webSocketSession("ws://$host:$port/sync")
            val connection = connectionFactory(session, host) ?: run {
                runCatching { client.close() }
                return true
            }
            coroutineScope.launch {
                try {
                    connection.start()
                } finally {
                    client.close()
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e { "sync connection to $host:$port failed: $e" }
            runCatching { client.close() }
            false
        }
    }
}
