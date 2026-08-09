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
package com.apps.adrcotfas.goodtime.di

import com.apps.adrcotfas.goodtime.DistributionScreens
import com.apps.adrcotfas.goodtime.backup.BackupFileManager
import com.apps.adrcotfas.goodtime.backup.BackupPrompter
import com.apps.adrcotfas.goodtime.backup.BackupViewModel
import com.apps.adrcotfas.goodtime.backup.DesktopBackupScreen
import com.apps.adrcotfas.goodtime.billing.DesktopProScreen
import com.apps.adrcotfas.goodtime.billing.DesktopPurchaseManager
import com.apps.adrcotfas.goodtime.billing.PurchaseManager
import com.apps.adrcotfas.goodtime.data.backup.DesktopBackupPrompter
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val distributionModule: Module =
    module {
        single<BackupPrompter> {
            DesktopBackupPrompter(
                logger = getWith("DesktopBackupPrompter"),
            )
        }

        single<PurchaseManager> {
            DesktopPurchaseManager(
                settingsRepository = get(),
                dataRepository = get(),
                ioScope = get(named(IO_SCOPE)),
                log = getWith("PurchaseManager"),
            )
        }

        single<DistributionScreens> {
            DistributionScreens(
                backupScreen = { onNavigateToPro, onNavigateBack, onNavigateToMainAndReset ->
                    DesktopBackupScreen(onNavigateToPro, onNavigateBack, onNavigateToMainAndReset)
                },
                proScreen = { onNavigateBack -> DesktopProScreen(onNavigateBack) },
            )
        }

        viewModel {
            BackupViewModel(
                backupManager = get<BackupFileManager>(),
                settingsRepository = get<SettingsRepository>(),
                coroutineScope = get<CoroutineScope>(named(IO_SCOPE)),
            )
        }
    }
