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
import kotlinx.coroutines.CoroutineExceptionHandler
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
    private val connectionFactory: suspend (WebSocketSession, String) -> SyncPeerConnection,
    private val log: Logger,
    /** Called with a message when the listen socket fails to bind/start, so the UI can report it. */
    private val onStatus: (String?) -> Unit = {},
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        // CIO binds the listening socket in an engine-owned coroutine; a failure there (e.g.
        // SocketException EPERM when the OS denies socket creation) is an uncaught exception
        // unless a handler is present, crashing the process even though runCatching below
        // catches the synchronous part. Inject a handler via parentCoroutineContext so the
        // engine failure is logged instead of killing the app.
        val exceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                log.e { "sync server coroutine failed: $throwable" }
                onStatus(throwable.message ?: "sync server failed")
            }
        val newServer =
            coroutineScope.embeddedServer(
                factory = CIO,
                host = "0.0.0.0",
                port = port,
                parentCoroutineContext = exceptionHandler,
            ) {
                install(WebSockets)
                routing {
                    webSocket("/sync") {
                        connectionFactory(this, call.request.local.remoteHost).start()
                    }
                }
            }
        server = newServer
        coroutineScope.launch {
            runCatching { newServer.start(wait = false) }.onFailure {
                log.e { "sync server failed to start on port $port: $it" }
                onStatus(it.message ?: "sync server failed to start")
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
