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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.adrcotfas.goodtime.ui.BetterListItem
import com.apps.adrcotfas.goodtime.ui.CheckboxListItem
import com.apps.adrcotfas.goodtime.ui.CompactPreferenceGroupTitle
import com.apps.adrcotfas.goodtime.ui.SubtleHorizontalDivider
import com.apps.adrcotfas.goodtime.ui.TopBar
import goodtime_productivity.shared.generated.resources.Res
import goodtime_productivity.shared.generated.resources.main_cancel
import goodtime_productivity.shared.generated.resources.main_ok
import goodtime_productivity.shared.generated.resources.settings_sync
import goodtime_productivity.shared.generated.resources.settings_sync_connect_button
import goodtime_productivity.shared.generated.resources.settings_sync_connect_failed
import goodtime_productivity.shared.generated.resources.settings_sync_connect_hint
import goodtime_productivity.shared.generated.resources.settings_sync_connect_title
import goodtime_productivity.shared.generated.resources.settings_sync_connected_devices
import goodtime_productivity.shared.generated.resources.settings_sync_connecting
import goodtime_productivity.shared.generated.resources.settings_sync_device_id
import goodtime_productivity.shared.generated.resources.settings_sync_device_name
import goodtime_productivity.shared.generated.resources.settings_sync_device_name_desc
import goodtime_productivity.shared.generated.resources.settings_sync_discover_empty
import goodtime_productivity.shared.generated.resources.settings_sync_discover_title
import goodtime_productivity.shared.generated.resources.settings_sync_edit_name
import goodtime_productivity.shared.generated.resources.settings_sync_edit_port
import goodtime_productivity.shared.generated.resources.settings_sync_enable_desc
import goodtime_productivity.shared.generated.resources.settings_sync_enable_title
import goodtime_productivity.shared.generated.resources.settings_sync_last_sync
import goodtime_productivity.shared.generated.resources.settings_sync_local_ip
import goodtime_productivity.shared.generated.resources.settings_sync_never_synced
import goodtime_productivity.shared.generated.resources.settings_sync_port
import goodtime_productivity.shared.generated.resources.settings_sync_port_desc
import goodtime_productivity.shared.generated.resources.settings_sync_server_running
import goodtime_productivity.shared.generated.resources.settings_sync_server_failed
import goodtime_productivity.shared.generated.resources.settings_sync_server_starting
import goodtime_productivity.shared.generated.resources.settings_sync_status
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onNavigateBack: () -> Boolean) {
    val viewModel: SyncViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncSettings = uiState.syncSettings

    var showEditName by remember { mutableStateOf(false) }
    var showEditPort by remember { mutableStateOf(false) }
    var hostInput by remember { mutableStateOf("") }

    val listState = rememberScrollState()

    Scaffold(
        topBar = {
            TopBar(
                title = stringResource(Res.string.settings_sync),
                onNavigateBack = { onNavigateBack() },
                showSeparator = listState.canScrollBackward,
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(listState)
                .background(MaterialTheme.colorScheme.background),
        ) {
            CheckboxListItem(
                title = stringResource(Res.string.settings_sync_enable_title),
                subtitle = stringResource(Res.string.settings_sync_enable_desc),
                checked = syncSettings.enabled,
            ) {
                viewModel.setEnabled(it)
            }

            if (syncSettings.enabled) {
                SubtleHorizontalDivider()
                CompactPreferenceGroupTitle(text = stringResource(Res.string.settings_sync_status))
                if (uiState.serverRunning) {
                    BetterListItem(
                        title = stringResource(Res.string.settings_sync_server_running, syncSettings.port),
                    )
                } else {
                    BetterListItem(
                        title = stringResource(Res.string.settings_sync_server_starting),
                    )
                }
                val serverError = uiState.serverError
                if (serverError != null) {
                    Text(
                        modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                        text = stringResource(Res.string.settings_sync_server_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                        text = serverError,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (uiState.localIps.isNotEmpty()) {
                    BetterListItem(
                        title = stringResource(Res.string.settings_sync_local_ip),
                    )
                    uiState.localIps.forEach { ip ->
                        BetterListItem(
                            title = ip,
                        )
                    }
                }
                BetterListItem(
                    title = stringResource(Res.string.settings_sync_connected_devices, uiState.connectedPeers),
                )
                uiState.peers.forEach { peer ->
                    BetterListItem(
                        title = peer.deviceName,
                        subtitle = peer.host,
                    )
                }
                val lastSyncLabel =
                    uiState.lastSyncLabel ?: stringResource(Res.string.settings_sync_never_synced)
                BetterListItem(
                    title = stringResource(Res.string.settings_sync_last_sync),
                    trailing = lastSyncLabel,
                )
            }

            SubtleHorizontalDivider()

            BetterListItem(
                title = stringResource(Res.string.settings_sync_device_name),
                subtitle = stringResource(Res.string.settings_sync_device_name_desc),
                trailing = syncSettings.serverName,
                enabled = syncSettings.enabled,
                onClick = { showEditName = true },
            )
            BetterListItem(
                title = stringResource(Res.string.settings_sync_port),
                subtitle = stringResource(Res.string.settings_sync_port_desc),
                trailing = syncSettings.port.toString(),
                enabled = syncSettings.enabled,
                onClick = { showEditPort = true },
            )
            BetterListItem(
                title = stringResource(Res.string.settings_sync_device_id),
                trailing = syncSettings.deviceId.ifEmpty { "—" },
                enabled = syncSettings.enabled,
            )

            if (syncSettings.enabled) {
                SubtleHorizontalDivider()
                CompactPreferenceGroupTitle(text = stringResource(Res.string.settings_sync_connect_title))
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = hostInput,
                        maxLines = 1,
                        enabled = !uiState.connecting,
                        onValueChange = {
                            hostInput = it
                            viewModel.clearConnectError()
                        },
                        label = { Text(stringResource(Res.string.settings_sync_connect_hint)) },
                    )
                    Button(
                        enabled = hostInput.isNotBlank() && !uiState.connecting,
                        onClick = {
                            viewModel.connectTo(hostInput.trim())
                            hostInput = ""
                        },
                    ) {
                        if (uiState.connecting) {
                            Text(stringResource(Res.string.settings_sync_connecting))
                        } else {
                            Text(stringResource(Res.string.settings_sync_connect_button))
                        }
                    }
                }
                if (uiState.connectFailed) {
                    Text(
                        modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                        text = stringResource(Res.string.settings_sync_connect_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    uiState.connectErrorDetail?.let { detail ->
                        Text(
                            modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            text = detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (syncSettings.enabled) {
                SubtleHorizontalDivider()
                CompactPreferenceGroupTitle(text = stringResource(Res.string.settings_sync_discover_title))
                if (uiState.discoveredPeers.isEmpty()) {
                    BetterListItem(
                        title = stringResource(Res.string.settings_sync_discover_empty),
                    )
                } else {
                    uiState.discoveredPeers.forEach { peer ->
                        BetterListItem(
                            title = peer.deviceName,
                            subtitle = peer.host,
                            trailing = {
                                TextButton(
                                    enabled = !uiState.connecting,
                                    onClick = { viewModel.connectTo(peer) },
                                ) {
                                    Text(stringResource(Res.string.settings_sync_connect_button))
                                }
                            },
                        )
                    }
                }
            }
        }

        if (showEditName) {
            SyncEditDialog(
                title = stringResource(Res.string.settings_sync_edit_name),
                initialValue = syncSettings.serverName,
                maxLength = 32,
                numeric = false,
                onDismiss = { showEditName = false },
                onConfirm = {
                    viewModel.setServerName(it)
                    showEditName = false
                },
            )
        }
        if (showEditPort) {
            SyncEditDialog(
                title = stringResource(Res.string.settings_sync_edit_port),
                initialValue = syncSettings.port.toString(),
                maxLength = 5,
                numeric = true,
                onDismiss = { showEditPort = false },
                onConfirm = {
                    it.toIntOrNull()?.let(viewModel::setPort)
                    showEditPort = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncEditDialog(
    title: String,
    initialValue: String,
    maxLength: Int,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val valid =
        if (numeric) {
            value.toIntOrNull()?.let { it in 1024..65535 } == true
        } else {
            value.isNotBlank()
        }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .background(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                ),
        ) {
            Column(
                modifier =
                Modifier
                    .padding(
                        top = 24.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
            ) {
                Text(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedTextField(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    value = value,
                    maxLines = 1,
                    onValueChange = { input ->
                        if (!numeric || input.all { it.isDigit() }) {
                            if (input.length <= maxLength) {
                                value = input
                            }
                        }
                    },
                    label = { Text(title) },
                    isError = !valid,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource(Res.string.main_cancel))
                    }
                    TextButton(
                        enabled = valid,
                        onClick = { onConfirm(value) },
                    ) {
                        Text(stringResource(Res.string.main_ok))
                    }
                }
            }
        }
    }
}
