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

import com.apps.adrcotfas.goodtime.data.local.DATABASE_NAME
import java.io.File

class DesktopInstallDateProvider : InstallDateProvider {
    override fun isInstallOlderThan10Days(): Boolean {
        return try {
            val dbFile = File(desktopDataDir(), DATABASE_NAME)
            if (!dbFile.exists()) return false
            val millisSinceInstall = System.currentTimeMillis() - dbFile.lastModified()
            millisSinceInstall > INSTALL_AGE_THRESHOLD_MILLIS
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val INSTALL_AGE_THRESHOLD_MILLIS = 10L * 24L * 60L * 60L * 1000L
    }
}
