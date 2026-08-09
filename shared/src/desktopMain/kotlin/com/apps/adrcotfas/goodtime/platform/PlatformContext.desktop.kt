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
package com.apps.adrcotfas.goodtime.platform

actual class PlatformContext

actual fun PlatformContext.setFullscreen(enabled: Boolean) {
    // Fullscreen is managed by the desktop window itself.
}

actual fun PlatformContext.setShowWhenLocked(enabled: Boolean) {
    // Not applicable on desktop.
}

actual fun PlatformContext.setKeepScreenOn(enabled: Boolean) {
    // The app only runs while the window is open.
}

actual fun PlatformContext.configureSystemBars(isDarkTheme: Boolean) {
    // No system bars on desktop.
}
