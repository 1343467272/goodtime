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
package com.apps.adrcotfas.goodtime.sync

import com.apps.adrcotfas.goodtime.bl.Event
import com.apps.adrcotfas.goodtime.bl.EventListener

/**
 * Bridges the timer to sync: every local timer transition is forwarded to the
 * [SyncManager] so it can announce the new state to connected peers.
 */
class SyncEventListener(
    private val syncManager: SyncManager,
) : EventListener {
    override fun onEvent(event: Event) {
        when (event) {
            is Event.Start,
            is Event.Pause,
            is Event.Finished,
            is Event.Reset,
            is Event.AddOneMinute,
            -> syncManager.publishTimerState()

            else -> Unit
        }
    }
}
