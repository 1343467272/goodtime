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
package com.apps.adrcotfas.goodtime.bl.notifications

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.bl.Event
import com.apps.adrcotfas.goodtime.bl.EventListener
import com.apps.adrcotfas.goodtime.bl.TimerType
import com.apps.adrcotfas.goodtime.bl.isFocus
import goodtime_productivity.shared.generated.resources.Res
import goodtime_productivity.shared.generated.resources.main_break_complete
import goodtime_productivity.shared.generated.resources.main_focus_complete
import goodtime_productivity.shared.generated.resources.main_productivity_reminder_desc
import goodtime_productivity.shared.generated.resources.settings_productivity_reminder_title
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

/**
 * Windows notifications delivered through the system tray.
 *
 * Windows 10/11 surface tray-icon balloons as toast notifications in the Action
 * Center, which is how a plain-JDK desktop app can notify the user without extra
 * native dependencies.
 *
 * Windows silently drops a balloon that is shown in the same moment the tray icon
 * is added, so the icon is registered right after startup and any message sent
 * while the icon is still settling is deferred briefly.
 */
class DesktopNotifications(
    private val logger: Logger,
) : EventListener {

    private var trayIcon: TrayIcon? = null
    private var iconReadyAt = 0L

    init {
        Timer(true).schedule(
            object : TimerTask() {
                override fun run() {
                    trayIcon()
                }
            },
            ICON_REGISTRATION_DELAY_MS,
        )
    }

    override fun onEvent(event: Event) {
        if (event is Event.Finished) {
            notifyFinished(event.type)
        }
    }

    /** Shows the "session finished" notification, localized like the Android build. */
    fun notifyFinished(type: TimerType) {
        val text = resolveStrings(
            {
                runBlocking {
                    if (type.isFocus) getString(Res.string.main_focus_complete)
                    else getString(Res.string.main_break_complete)
                }
            },
            fallback = if (type.isFocus) "Focus complete" else "Break complete",
        )
        show("Goodtime", text)
    }

    /** Shows the scheduled productivity reminder notification. */
    fun notifyReminder() {
        val title = resolveStrings(
            { runBlocking { getString(Res.string.settings_productivity_reminder_title) } },
            fallback = "Productivity reminder",
        )
        val message = resolveStrings(
            { runBlocking { getString(Res.string.main_productivity_reminder_desc) } },
            fallback = "It's time to be productive!",
        )
        show(title, message)
    }

    fun show(title: String, message: String) {
        val icon = trayIcon() ?: return
        val waitMs = iconReadyAt - System.currentTimeMillis()
        if (waitMs > 0) {
            Timer(true).schedule(
                object : TimerTask() {
                    override fun run() {
                        display(icon, title, message)
                    }
                },
                waitMs,
            )
        } else {
            display(icon, title, message)
        }
    }

    private fun display(icon: TrayIcon, title: String, message: String) {
        try {
            icon.displayMessage(title, message, TrayIcon.MessageType.INFO)
        } catch (e: Exception) {
            logger.w("Failed to show Windows notification: ${e.message}")
        }
    }

    @Synchronized
    private fun trayIcon(): TrayIcon? {
        if (trayIcon == null) {
            trayIcon = createTrayIcon()
            if (trayIcon != null) {
                iconReadyAt = System.currentTimeMillis() + ICON_REGISTRATION_GRACE_MS
            }
        }
        return trayIcon
    }

    private fun createTrayIcon(): TrayIcon? {
        if (!SystemTray.isSupported()) {
            logger.w("System tray is not supported; Windows notifications disabled")
            return null
        }
        return try {
            TrayIcon(createIcon(), "Goodtime").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
                logger.d("Registered Goodtime tray icon for notifications")
            }
        } catch (e: Exception) {
            logger.w("Failed to register tray icon: ${e.message}")
            null
        }
    }

    private fun createIcon(): BufferedImage {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color(0x17, 0x6B, 0x53) // brand green
        g.stroke = BasicStroke(size * 0.16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val m = (size * 0.24f).toInt()
        g.drawLine(m, m, size - m, size - m)
        g.drawLine(m, size - m, size - m, m)
        g.dispose()
        return image
    }

    private fun resolveStrings(block: () -> String, fallback: String): String =
        try {
            block()
        } catch (e: Exception) {
            logger.w("Failed to resolve notification text, using fallback: ${e.message}")
            fallback
        }

    private companion object {
        const val ICON_REGISTRATION_DELAY_MS = 1_000L
        const val ICON_REGISTRATION_GRACE_MS = 2_000L
    }
}
