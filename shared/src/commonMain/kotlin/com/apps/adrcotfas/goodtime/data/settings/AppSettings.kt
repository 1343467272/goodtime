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
package com.apps.adrcotfas.goodtime.data.settings

import com.apps.adrcotfas.goodtime.data.model.Label
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable

data class AppSettings(
    private val version: Int = 1,
    val isPro: Boolean = false,
    val timeProfilesInitialized: Boolean = false,
    val shouldAskForReview: Boolean = false,
    /** Epoch millis of the last in-app review request, or 0 if never asked */
    val lastAskedForReviewTime: Long = 0,
    val productivityReminderSettings: ProductivityReminderSettings = ProductivityReminderSettings(),
    val uiSettings: UiSettings = UiSettings(),
    val timerStyle: TimerStyleData = TimerStyleData(),
    val workdayStart: Int = LocalTime(5, 0).toSecondOfDay(),
    val firstDayOfWeek: Int = DayOfWeek.MONDAY.isoDayNumber,
    /** The name/URI of the sound file or empty for default*/
    val workFinishedSound: String = "",
    /** The name/URI of the sound file or empty for default*/
    val breakFinishedSound: String = "",
    val userSounds: Set<SoundData> = emptySet(),
    val vibrationStrength: Int = 3,
    val enableTorch: Boolean = false,
    val flashScreen: Boolean = false,
    val insistentNotification: Boolean = false,
    /** Desktop: play an insistent looping sound when a break ends until dismissed */
    val breakEndAlarm: Boolean = false,
    /** only valid with insistentNotification off **/
    val autoStartFocus: Boolean = false,
    /** only valid with insistentNotification off and for countdown timers **/
    val autoStartBreak: Boolean = false,
    val labelName: String = Label.DEFAULT_LABEL_NAME,
    val longBreakData: LongBreakData = LongBreakData(),
    val breakBudgetData: BreakBudgetData = BreakBudgetData(),
    val notificationPermissionState: NotificationPermissionState = NotificationPermissionState.NOT_ASKED,
    val lastInsertedSessionId: Long = Long.MAX_VALUE,
    val statisticsSettings: StatisticsSettings = StatisticsSettings(),
    val historyChartSettings: HistoryChartSettings = HistoryChartSettings(),
    val showOnboarding: Boolean = true,
    val showTutorial: Boolean = true,
    val backupSettings: BackupSettings = BackupSettings(),
    /** The version code of the last dismissed update, or 0 if no update has been dismissed */
    val lastDismissedUpdateVersionCode: Long = 0,
    val persistedTimerState: PersistedTimerState? = null,
    val syncSettings: SyncSettings = SyncSettings(),
)

enum class NotificationPermissionState {
    NOT_ASKED,
    GRANTED,
    DENIED,
}

@Serializable
data class ProductivityReminderSettings(
    /** A list representing the days when the reminder was enabled, Monday is 1 and Sunday is 7. */
    val days: List<Int> = emptyList(),
    /** The time of the reminder represented in seconds of the day */
    val secondOfDay: Int = LocalTime(9, 0).toSecondOfDay(),
)

@Serializable
data class UiSettings(
    val themePreference: ThemePreference = ThemePreference.DARK,
    val useDynamicColor: Boolean = false,
    val fullscreenMode: Boolean = false,
    val trueBlackMode: Boolean = false,
    val keepScreenOn: Boolean = true,
    val screensaverMode: Boolean = false,
    val dndDuringWork: Boolean = false,
    val showWhenLocked: Boolean = false,
    val pipMode: Boolean = true,
)

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

fun ThemePreference.isDarkTheme(isSystemInDarkTheme: Boolean): Boolean = this == ThemePreference.DARK ||
    (this == ThemePreference.SYSTEM && isSystemInDarkTheme)

@Serializable
data class TimerStyleData(
    val colorIndex: Int = Label.DEFAULT_LABEL_COLOR_INDEX,
    val minSize: Float = 0f, // in em
    val maxSize: Float = 0f, // in em
    val fontSize: Float = 0f, // in em
    val fontWeight: Int = 200,
    val currentScreenWidth: Float = 0f, // in dp
    val minutesOnly: Boolean = false,
    val showStatus: Boolean = true,
    val showStreak: Boolean = true,
    val showBreakBudget: Boolean = true,
) {
    fun inUseFontSize() = fontSize
}

@Serializable
data class SoundData(
    val name: String = "",
    val uriString: String = "",
    val isSilent: Boolean = false,
)

@Serializable
data class BackupSettings(
    val cloudAutoBackupEnabled: Boolean = false,
    /**
     * Android-only: local auto-backups to a user-selected folder (tree URI) using WorkManager.
     */
    val autoBackupEnabled: Boolean = false,
    /**
     * Android-only: persisted folder URI (tree URI) used for the *local* auto-backup worker.
     */
    val path: String = "",
    /** Timestamp in milliseconds for cloud backups (iCloud on iOS, Google Drive on Android) */
    val cloudLastBackupTimestamp: Long = 0L,
    /** Timestamp in milliseconds for Android local folder backups */
    val localLastBackupTimestamp: Long = 0L,
)

/**
 * Configuration of the LAN (local network) sync feature. Not itself synced across devices.
 */
@Serializable
data class SyncSettings(
    val enabled: Boolean = false,
    /** TCP port the local sync server listens on. All synced devices must share the same port. */
    val port: Int = DEFAULT_PORT,
    /** Human-readable name shown to peers for this device. */
    val serverName: String = DEFAULT_SERVER_NAME,
    /** Stable unique id of this device, generated on first enable. */
    val deviceId: String = "",
    /** Epoch millis of the last successful sync, used for diagnostics/UI. */
    val lastSyncTimestamp: Long = 0L,
) {
    companion object {
        const val DEFAULT_PORT = 46371
        const val DEFAULT_SERVER_NAME = "Goodtime"
    }
}

/**
 * The subset of user settings that participate in LAN sync, treated as a single
 * last-write-wins document so devices converge to the most recently edited settings.
 */
@Serializable
data class SyncedSettings(
    val labelName: String = Label.DEFAULT_LABEL_NAME,
    val workdayStart: Int = LocalTime(5, 0).toSecondOfDay(),
    val firstDayOfWeek: Int = DayOfWeek.MONDAY.isoDayNumber,
    val workFinishedSound: String = "",
    val breakFinishedSound: String = "",
    val userSounds: Set<SoundData> = emptySet(),
    val vibrationStrength: Int = 3,
    val enableTorch: Boolean = false,
    val flashScreen: Boolean = false,
    val insistentNotification: Boolean = false,
    val breakEndAlarm: Boolean = false,
    val autoStartFocus: Boolean = false,
    val autoStartBreak: Boolean = false,
    val longBreakData: LongBreakData = LongBreakData(),
    val breakBudgetData: BreakBudgetData = BreakBudgetData(),
    val productivityReminderSettings: ProductivityReminderSettings = ProductivityReminderSettings(),
    val uiSettings: UiSettings = UiSettings(),
    val timerStyle: TimerStyleData = TimerStyleData(),
    /** Epoch millis of the last edit, used for last-write-wins conflict resolution. */
    val updatedAt: Long = 0L,
) {
    companion object {
        fun from(appSettings: AppSettings, updatedAt: Long) = SyncedSettings(
            labelName = appSettings.labelName,
            workdayStart = appSettings.workdayStart,
            firstDayOfWeek = appSettings.firstDayOfWeek,
            workFinishedSound = appSettings.workFinishedSound,
            breakFinishedSound = appSettings.breakFinishedSound,
            userSounds = appSettings.userSounds,
            vibrationStrength = appSettings.vibrationStrength,
            enableTorch = appSettings.enableTorch,
            flashScreen = appSettings.flashScreen,
            insistentNotification = appSettings.insistentNotification,
            breakEndAlarm = appSettings.breakEndAlarm,
            autoStartFocus = appSettings.autoStartFocus,
            autoStartBreak = appSettings.autoStartBreak,
            longBreakData = appSettings.longBreakData,
            breakBudgetData = appSettings.breakBudgetData,
            productivityReminderSettings = appSettings.productivityReminderSettings,
            uiSettings = appSettings.uiSettings,
            timerStyle = appSettings.timerStyle,
            updatedAt = updatedAt,
        )
    }
}
