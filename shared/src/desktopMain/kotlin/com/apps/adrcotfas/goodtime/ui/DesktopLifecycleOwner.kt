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
package com.apps.adrcotfas.goodtime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A desktop app is always "in the foreground" while its window is open.
 * Compose Multiplatform's default desktop lifecycle owner is not driven to
 * [Lifecycle.State.RESUMED], which breaks code that relies on the current
 * navigation entry being RESUMED (e.g. `NavController.popBackStack2()`).
 * This provides a lifecycle owner that is permanently resumed.
 */
@Composable
fun ProvideDesktopLifecycleOwner(content: @Composable () -> Unit) {
    val lifecycleOwner =
        remember {
            object : LifecycleOwner {
                private val lifecycleRegistry = LifecycleRegistry(this)
                init {
                    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                }
                override val lifecycle: Lifecycle
                    get() = lifecycleRegistry
            }
        }
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        content()
    }
}
