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
package com.apps.adrcotfas.goodtime.settings.reminders

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.bl.notifications.DesktopNotifications
import com.apps.adrcotfas.goodtime.bl.notifications.SoundPlayer
import com.apps.adrcotfas.goodtime.data.settings.SoundData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import java.time.DayOfWeek as JavaDayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop reminder scheduler.
 * Windows has no equivalent of Android's AlarmManager, so reminders fire only while the
 * app is running. Each reminder plays the default completion sound and shows a Windows
 * notification at the configured time.
 */
actual class ReminderScheduler(
    private val logger: Logger,
    private val soundPlayer: SoundPlayer,
    private val notifications: DesktopNotifications,
) {
    private val scheduledTasks = mutableMapOf<String, ScheduledFuture<*>>()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ReminderScheduler").apply { isDaemon = true }
        }

    actual suspend fun scheduleWeeklyReminder(
        dayOfWeek: DayOfWeek,
        secondOfDay: Int,
        identifier: String,
    ) {
        cancel(identifier)
        val initialDelay = nextFireDelayMillis(dayOfWeek, secondOfDay)
        logger.d("Scheduling desktop reminder for $dayOfWeek at ${secondOfDay / 3600}:${(secondOfDay % 3600) / 60} (in ${initialDelay / 1000}s), identifier: $identifier")
        scheduledTasks[identifier] =
            executor.scheduleAtFixedRate(
                { fire(identifier) },
                initialDelay,
                TimeUnit.DAYS.toMillis(7),
                TimeUnit.MILLISECONDS,
            )
    }

    actual fun cancelAllReminders() {
        scheduledTasks.values.forEach { it.cancel(false) }
        scheduledTasks.clear()
    }

    private fun cancel(identifier: String) {
        scheduledTasks.remove(identifier)?.cancel(false)
    }

    private fun fire(identifier: String) {
        logger.d("Firing desktop reminder: $identifier")
        soundPlayer.play(SoundData(), loop = false)
        notifications.notifyReminder()
    }

    private fun nextFireDelayMillis(
        dayOfWeek: DayOfWeek,
        secondOfDay: Int,
    ): Long {
        val now = ZonedDateTime.now()
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60
        val target = now.toLocalDate().atTime(hour, minute, second).atZone(now.zone)
        var next = target
        while (!next.isAfter(now) || next.dayOfWeek != JavaDayOfWeek.of(dayOfWeek.isoDayNumber)) {
            next = next.plusDays(1).withHour(hour).withMinute(minute).withSecond(second).withNano(0)
        }
        return next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
    }
}
