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
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fixed UDP port used to announce and discover sync-capable devices on the local network. */
object DiscoveryConfig {
    const val PORT = 58123
    /** Broadcast target for announcements. */
    const val BROADCAST_ADDRESS = "255.255.255.255"
    /** How often a device announces itself. */
    const val BROADCAST_INTERVAL_MS = 3_000L
    /** A peer that has not announced itself within this window is considered gone. */
    const val PEER_TTL_MS = 15_000L
    const val PRUNE_INTERVAL_MS = 5_000L
}

/** Payload broadcast on the discovery port by every sync-enabled device. */
@Serializable
data class DiscoveryAnnouncement(
    val deviceId: String,
    val deviceName: String,
    /** The TCP port the device's sync server listens on. */
    val port: Int,
)

/** A sync-capable device seen on the local network. */
data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    /** Epoch millis of the last announcement received from this device. */
    val lastSeen: Long,
)

/**
 * LAN device discovery via UDP broadcast. Every sync-enabled device announces itself on a
 * fixed port; all devices listen on the same port and merge the announcements they hear,
 * dropping peers whose announcements have gone stale.
 */
class DiscoveryManager(
    private val coroutineScope: CoroutineScope,
    private val json: Json,
    private val log: Logger,
    private val nowMillis: () -> Long,
) {
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var socket: BoundDatagramSocket? = null
    private var receiveJob: Job? = null
    private var broadcastJob: Job? = null
    private var pruneJob: Job? = null

    /**
     * Starts listening and announcing. [ownDeviceId] is used to ignore our own announcements;
     * [announcement] is invoked on every broadcast tick so name/port changes are reflected
     * without restarting discovery.
     */
    suspend fun start(ownDeviceId: () -> String, announcement: suspend () -> DiscoveryAnnouncement) {
        if (receiveJob != null) return
        val bound =
            runCatching {
                aSocket(ActorSelectorManager(Dispatchers.IO))
                    .udp()
                    .bind(InetSocketAddress("0.0.0.0", DiscoveryConfig.PORT)) {
                        reuseAddress = true
                        broadcast = true
                    }
            }.getOrElse {
                log.e { "discovery socket bind failed on port ${DiscoveryConfig.PORT}: $it" }
                return
            }
        // The caller may have stopped discovery while we were binding; give up if so.
        coroutineContext.ensureActive()
        socket = bound

        receiveJob =
            coroutineScope.launch {
                try {
                    while (isActive) {
                        try {
                            val datagram = bound.receive()
                            val text =
                                try {
                                    datagram.packet.readString()
                                } finally {
                                    runCatching { datagram.packet.close() }
                                }
                            val senderHost =
                                (datagram.address as? InetSocketAddress)?.hostname ?: continue
                            val message =
                                runCatching {
                                    json.decodeFromString(DiscoveryAnnouncement.serializer(), text)
                                }.getOrNull() ?: continue
                            if (message.deviceId.isEmpty() || message.deviceId == ownDeviceId()) {
                                continue
                            }
                            _discoveredPeers.update { current ->
                                val rest = current.filter { it.deviceId != message.deviceId }
                                (
                                    rest +
                                        DiscoveredPeer(
                                            deviceId = message.deviceId,
                                            deviceName = message.deviceName,
                                            host = senderHost,
                                            port = message.port,
                                            lastSeen = nowMillis(),
                                        )
                                    ).sortedBy { it.deviceName.lowercase() }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.e { "discovery receive error: $e" }
                        }
                    }
                } finally {
                    runCatching { bound.close() }
                }
            }

        broadcastJob =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        val message = announcement()
                        if (message.deviceId.isNotEmpty()) {
                            val payload =
                                json.encodeToString(DiscoveryAnnouncement.serializer(), message)
                            bound.send(
                                Datagram(
                                    Buffer().apply { writeString(payload) },
                                    InetSocketAddress(DiscoveryConfig.BROADCAST_ADDRESS, DiscoveryConfig.PORT),
                                ),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.e { "discovery broadcast failed: $e" }
                    }
                    delay(DiscoveryConfig.BROADCAST_INTERVAL_MS)
                }
            }

        pruneJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(DiscoveryConfig.PRUNE_INTERVAL_MS)
                    val cutoff = nowMillis() - DiscoveryConfig.PEER_TTL_MS
                    _discoveredPeers.update { current ->
                        current.filter { it.lastSeen >= cutoff }
                    }
                }
            }
        log.i { "discovery started on port ${DiscoveryConfig.PORT}" }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        broadcastJob?.cancel()
        broadcastJob = null
        pruneJob?.cancel()
        pruneJob = null
        socket?.let { runCatching { it.close() } }
        socket = null
        _discoveredPeers.value = emptyList()
        log.i { "discovery stopped" }
    }
}
