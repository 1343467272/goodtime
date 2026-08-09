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

import java.io.File

/** Version name reported on desktop builds. */
internal const val DESKTOP_VERSION_NAME = "3.2.6"

/**
 * Directory used for the app's persistent data (settings + database).
 * Uses %APPDATA%\goodtime on Windows, ~/.goodtime elsewhere.
 */
internal fun desktopDataDir(): File {
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    return File(base, "goodtime")
}

/** Directory used for temporary files (backup staging, exports). */
internal fun desktopCacheDir(): File {
    val base = System.getProperty("java.io.tmpdir")
    return File(base, "goodtime")
}
