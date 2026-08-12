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
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Inbound half of a peer. Listens on [port] and wraps every accepted WebSocket
 * connection in a [SyncPeerConnection] supplied by [connectionFactory].
 */
class SyncServer(
    private val coroutineScope: CoroutineScope,
    private val json: Json,
    private val port: Int,
    private val connectionFactory: suspend (WebSocketSession) -> SyncPeerConnection,
    private val log: Logger,
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        val newServer =
            embeddedServer(
                factory = CIO,
                host = "0.0.0.0",
                port = port,
            ) {
                install(WebSockets)
                routing {
                    webSocket("/sync") {
                        connectionFactory(this).start()
                    }
                }
            }
        server = newServer
        coroutineScope.launch {
            runCatching { newServer.start(wait = false) }.onFailure {
                log.e { "sync server failed to start on port $port: $it" }
            }
        }
        log.i { "sync server listening on port $port" }
    }

    fun stop() {
        server?.let { active ->
            server = null
            runCatching { active.stop(1000, 5000) }
        }
    }
}
