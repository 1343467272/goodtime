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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.RoomDatabase
import com.apps.adrcotfas.goodtime.bl.EventListener
import com.apps.adrcotfas.goodtime.bl.TimeProvider
import com.apps.adrcotfas.goodtime.bl.TimerStatePersistenceListener
import com.apps.adrcotfas.goodtime.bl.TimerStateRestoration
import com.apps.adrcotfas.goodtime.bl.notifications.DesktopNotifications
import com.apps.adrcotfas.goodtime.bl.notifications.DesktopSoundPlayer
import com.apps.adrcotfas.goodtime.bl.notifications.DesktopTorchManager
import com.apps.adrcotfas.goodtime.bl.notifications.DesktopVibrationPlayer
import com.apps.adrcotfas.goodtime.bl.notifications.SoundPlayer
import com.apps.adrcotfas.goodtime.bl.notifications.SoundVibrationAndTorchPlayer
import com.apps.adrcotfas.goodtime.bl.notifications.TorchManager
import com.apps.adrcotfas.goodtime.bl.notifications.VibrationPlayer
import com.apps.adrcotfas.goodtime.common.DesktopFeedbackHelper
import com.apps.adrcotfas.goodtime.common.DesktopInstallDateProvider
import com.apps.adrcotfas.goodtime.common.DesktopTimeFormatProvider
import com.apps.adrcotfas.goodtime.common.DesktopUrlOpener
import com.apps.adrcotfas.goodtime.common.FeedbackHelper
import com.apps.adrcotfas.goodtime.common.InstallDateProvider
import com.apps.adrcotfas.goodtime.common.TimeFormatProvider
import com.apps.adrcotfas.goodtime.common.UrlOpener
import com.apps.adrcotfas.goodtime.common.desktopCacheDir
import com.apps.adrcotfas.goodtime.common.desktopDataDir
import com.apps.adrcotfas.goodtime.data.local.DATABASE_NAME
import com.apps.adrcotfas.goodtime.data.local.ProductivityDatabase
import com.apps.adrcotfas.goodtime.data.local.getDatabaseBuilder
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.settings.reminders.ReminderScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single<RoomDatabase.Builder<ProductivityDatabase>> { getDatabaseBuilder() }

        single<FileSystem> { FileSystem.SYSTEM }

        single<String>(named(DB_PATH_KEY)) { File(desktopDataDir(), DATABASE_NAME).absolutePath }

        single<String>(named(CACHE_DIR_PATH_KEY)) { desktopCacheDir().absolutePath }

        single<DataStore<Preferences>>(named(SETTINGS_NAME)) {
            getDataStore(
                producePath = {
                    File(desktopDataDir(), SETTINGS_FILE_NAME).absolutePath
                },
            )
        }

        single<UrlOpener> { DesktopUrlOpener() }
        single<FeedbackHelper> { DesktopFeedbackHelper() }
        single<TimeFormatProvider> { DesktopTimeFormatProvider() }
        single<InstallDateProvider> { DesktopInstallDateProvider() }

        single<TimerStateRestoration> {
            TimerStateRestoration(
                settingsRepo = get<SettingsRepository>(),
                timeProvider = get<TimeProvider>(),
                log = getWith("TimerStateRestoration"),
            )
        }

        single<SoundPlayer> {
            DesktopSoundPlayer(
                settingsRepo = get<SettingsRepository>(),
                logger = getWith("SoundPlayer"),
                ioScope = get<CoroutineScope>(named(IO_SCOPE)),
                playerScope = get<CoroutineScope>(named(WORKER_SCOPE)),
            )
        }

        single<VibrationPlayer> {
            DesktopVibrationPlayer(
                logger = getWith("VibrationPlayer"),
            )
        }

        single<TorchManager> { DesktopTorchManager() }

        single<DesktopNotifications> {
            DesktopNotifications(
                logger = getWith("DesktopNotifications"),
            )
        }

        single<SoundVibrationAndTorchPlayer> {
            SoundVibrationAndTorchPlayer(
                soundPlayer = get(),
                vibrationPlayer = get(),
                torchManager = get(),
                timeProvider = get(),
                logger = getWith("SoundVibrationAndTorchPlayer"),
            )
        }

        single<TimerStatePersistenceListener> {
            TimerStatePersistenceListener(
                settingsRepo = get<SettingsRepository>(),
                timeProvider = get<TimeProvider>(),
                coroutineScope = get<CoroutineScope>(named(IO_SCOPE)),
                log = getWith("TimerStatePersistence"),
            )
        }

        single<List<EventListener>> {
            listOf(
                get<SoundVibrationAndTorchPlayer>(),
                get<TimerStatePersistenceListener>(),
                get<DesktopNotifications>(),
            )
        }

        single<ReminderScheduler> {
            ReminderScheduler(
                logger = getWith("ReminderScheduler"),
                soundPlayer = get<SoundPlayer>(),
                notifications = get<DesktopNotifications>(),
            )
        }
    }
