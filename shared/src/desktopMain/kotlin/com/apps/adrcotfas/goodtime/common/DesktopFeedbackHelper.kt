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
package com.apps.adrcotfas.goodtime.common

import java.net.URLEncoder
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import goodtime_productivity.shared.generated.resources.Res
import goodtime_productivity.shared.generated.resources.contact_address
import goodtime_productivity.shared.generated.resources.feedback_title

class DesktopFeedbackHelper : FeedbackHelper {
    override fun sendFeedback() {
        runBlocking {
            val emailAddress = getString(Res.string.contact_address)
            val subject = getString(Res.string.feedback_title)
            val body =
                getFeedbackEmailBody(
                    deviceInfo = getDeviceInfo(),
                    appVersion = "$DESKTOP_VERSION_NAME($DESKTOP_VERSION_NAME)",
                )

            val mailtoUrl = "mailto:$emailAddress?subject=${encode(subject)}&body=${encode(body)}"
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().mail(URI(mailtoUrl))
                }
            } catch (e: Exception) {
                // Opening the mail client is best-effort; ignore failures.
            }
        }
    }

    private fun getDeviceInfo(): String = "Windows ${System.getProperty("os.version") ?: "Unknown"}"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
