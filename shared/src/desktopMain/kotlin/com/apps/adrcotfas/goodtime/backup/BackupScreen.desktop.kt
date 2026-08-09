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
package com.apps.adrcotfas.goodtime.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.adrcotfas.goodtime.common.UnlockFeaturesActionCard
import com.apps.adrcotfas.goodtime.ui.SubtleHorizontalDivider
import com.apps.adrcotfas.goodtime.ui.TopBar
import goodtime_productivity.shared.generated.resources.Res
import goodtime_productivity.shared.generated.resources.backup_and_restore_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopBackupScreen(
    onNavigateToPro: () -> Unit,
    onNavigateBack: () -> Boolean,
    onNavigateToMainAndReset: () -> Unit,
) {
    val backupViewModel: BackupViewModel = koinViewModel()

    val backupUiState by backupViewModel.uiState.collectAsStateWithLifecycle()

    // TODO: replace with loading indicator
    if (backupUiState.isLoading) return

    val listState = rememberScrollState()
    Scaffold(
        topBar = {
            TopBar(
                title = stringResource(Res.string.backup_and_restore_title),
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
            UnlockFeaturesActionCard(
                backupUiState.isPro,
                onNavigateToPro = onNavigateToPro,
            )

            LocalBackupSection(
                enabled = backupUiState.isPro,
                localAutoBackupEnabled = false,
                localAutoBackupChecked = false,
                localAutoBackupPath = "",
                onLocalAutoBackupToggle = { /* Not used on desktop */ },
                lastLocalAutoBackupTimestamp = 0L,
                backupInProgress = backupUiState.isBackupInProgress,
                restoreInProgress = backupUiState.isRestoreInProgress,
                onLocalBackup = { backupViewModel.backup() },
                onLocalRestore = { backupViewModel.restore() },
            )

            SubtleHorizontalDivider()

            ExportCsvJsonSection(
                enabled = backupUiState.isPro,
                isCsvBackupInProgress = backupUiState.isCsvBackupInProgress,
                isJsonBackupInProgress = backupUiState.isJsonBackupInProgress,
                onExportCsv = { backupViewModel.exportCsv() },
                onExportJson = { backupViewModel.exportJson() },
            )
        }
    }
}
