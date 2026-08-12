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
package com.apps.adrcotfas.goodtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apps.adrcotfas.goodtime.billing.PurchaseManager
import com.apps.adrcotfas.goodtime.di.MAIN_SCOPE
import com.apps.adrcotfas.goodtime.di.coreBackupModule
import com.apps.adrcotfas.goodtime.di.coreModule
import com.apps.adrcotfas.goodtime.di.coroutineScopeModule
import com.apps.adrcotfas.goodtime.di.distributionModule
import com.apps.adrcotfas.goodtime.di.localDataModule
import com.apps.adrcotfas.goodtime.di.mainModule
import com.apps.adrcotfas.goodtime.di.platformModule
import com.apps.adrcotfas.goodtime.di.syncModule
import com.apps.adrcotfas.goodtime.di.timerManagerModule
import com.apps.adrcotfas.goodtime.di.viewModelModule
import com.apps.adrcotfas.goodtime.main.MainViewModel
import com.apps.adrcotfas.goodtime.platform.Distribution
import com.apps.adrcotfas.goodtime.platform.PlatformContext
import com.apps.adrcotfas.goodtime.settings.reminders.ReminderManager
import com.apps.adrcotfas.goodtime.sync.SyncManager
import com.apps.adrcotfas.goodtime.ui.ProvideDesktopLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.qualifier.named

fun main() = application {
    // Desktop has no billing backend, so "Pro" features are free and the Pro
    // screen becomes a support/donation page (same semantics as the F-Droid build).
    Distribution.isFdroid = true
    Window(
        onCloseRequest = ::exitApplication,
        title = "Goodtime",
        state = rememberWindowState(width = 420.dp, height = 820.dp),
    ) {
        AppWithKoin()
    }
}

@Composable
private fun AppWithKoin() {
    KoinApplication(
        application = {
            modules(
                coroutineScopeModule,
                coreModule(isDebug = System.getProperty("goodtime.debug") == "true"),
                localDataModule,
                coreBackupModule,
                distributionModule,
                timerManagerModule,
                syncModule,
                mainModule,
                viewModelModule,
                platformModule,
            )
        },
    ) {
        val mainViewModel: MainViewModel = koinViewModel()
        val purchaseManager: PurchaseManager = koinInject()

        initReminderManager()
        initSyncManager()

        LaunchedEffect(Unit) {
            purchaseManager.start()
        }

        val platformContext = remember { PlatformContext() }

        ProvideDesktopLifecycleOwner {
            GoodtimeApp(
                platformContext = platformContext,
                mainViewModel = mainViewModel,
                onUpdateClicked = null,
            )
        }
    }
}

@Composable
private fun initReminderManager() {
    val reminderManager: ReminderManager = koinInject()
    val scope: CoroutineScope = koinInject(named(MAIN_SCOPE))
    scope.launch {
        reminderManager.init()
    }
}

@Composable
private fun initSyncManager() {
    val syncManager: SyncManager = koinInject()
    val scope: CoroutineScope = koinInject(named(MAIN_SCOPE))
    scope.launch {
        syncManager.ensureStarted()
    }
}
