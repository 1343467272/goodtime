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
package com.apps.adrcotfas.goodtime.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.adrcotfas.goodtime.common.TimeFormatProvider
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.data.settings.SyncSettings
import com.apps.adrcotfas.goodtime.sync.DiscoveredPeer
import com.apps.adrcotfas.goodtime.sync.SyncManager
import com.apps.adrcotfas.goodtime.sync.SyncPeerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class SyncUiState(
    val isLoading: Boolean = true,
    val syncSettings: SyncSettings = SyncSettings(),
    val serverRunning: Boolean = false,
    val connectedPeers: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncLabel: String? = null,
    val connecting: Boolean = false,
    val connectFailed: Boolean = false,
    val connectErrorDetail: String? = null,
    val peers: List<SyncPeerInfo> = emptyList(),
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
)

class SyncViewModel(
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    private val timeFormatProvider: TimeFormatProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState =
        _uiState
            .onStart {
                load()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncUiState())

    private fun load() {
        viewModelScope.launch {
            combine(
                flow = settingsRepository.settings.map { it.syncSettings }.distinctUntilChanged(),
                flow2 = syncManager.status,
                flow3 = syncManager.discoveredPeers,
            ) { syncSettings, status, discovered -> Triple(syncSettings, status, discovered) }
                .collect { (syncSettings, status, discovered) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            syncSettings = syncSettings,
                            serverRunning = status.serverRunning,
                            connectedPeers = status.connectedPeers,
                            lastSyncTimestamp = status.lastSyncTimestamp,
                            lastSyncLabel = status.lastSyncTimestamp.formatSyncTime(timeFormatProvider),
                            connecting = status.connectingTo != null,
                            connectFailed = status.lastConnectError != null,
                            connectErrorDetail = status.lastConnectError,
                            peers = status.peers,
                            discoveredPeers = discovered,
                        )
                    }
                }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.syncSettings
            settingsRepository.setSyncSettings(current.copy(enabled = enabled))
            if (enabled) {
                syncManager.ensureStarted()
            } else {
                syncManager.stop()
            }
        }
    }

    fun setServerName(name: String) {
        viewModelScope.launch {
            settingsRepository.setSyncSettings(_uiState.value.syncSettings.copy(serverName = name))
        }
    }

    fun setPort(port: Int) {
        viewModelScope.launch {
            val enabled = _uiState.value.syncSettings.enabled
            settingsRepository.setSyncSettings(_uiState.value.syncSettings.copy(port = port))
            if (enabled) {
                syncManager.stop()
                syncManager.ensureStarted()
            }
        }
    }

    fun connectTo(host: String) {
        viewModelScope.launch {
            val ok = syncManager.connectTo(host)
            if (!ok) {
                _uiState.update {
                    it.copy(
                        connectFailed = true,
                        connectErrorDetail = syncManager.status.value.lastConnectError,
                    )
                }
            }
        }
    }

    fun connectTo(peer: DiscoveredPeer) {
        viewModelScope.launch {
            val ok = syncManager.connectTo(peer.host, peer.port)
            if (!ok) {
                _uiState.update {
                    it.copy(
                        connectFailed = true,
                        connectErrorDetail = syncManager.status.value.lastConnectError,
                    )
                }
            }
        }
    }

    fun clearConnectError() {
        syncManager.clearConnectError()
        _uiState.update {
            it.copy(connectFailed = false, connectErrorDetail = null)
        }
    }

    private fun Long.formatSyncTime(provider: TimeFormatProvider): String? {
        if (this <= 0L) return null
        val local = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
        return if (provider.is24HourFormat()) {
            "%02d:%02d".format(local.hour, local.minute)
        } else {
            val hour = if (local.hour % 12 == 0) 12 else local.hour % 12
            val ampm = if (local.hour < 12) "AM" else "PM"
            "$hour:%02d $ampm".format(local.minute)
        }
    }
}
