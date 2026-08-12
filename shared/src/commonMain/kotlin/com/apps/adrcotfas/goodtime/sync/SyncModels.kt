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

import com.apps.adrcotfas.goodtime.bl.TimerState
import com.apps.adrcotfas.goodtime.bl.TimerType
import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.model.TimerProfile
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import kotlinx.serialization.Serializable

/**
 * Single frame type on the wire. The [type] discriminates the payload which is
 * carried as a raw JSON string so that unknown/newer message types can be safely
 * ignored by older peers.
 */
@Serializable
data class SyncEnvelope(
    val type: String,
    val payload: String = "",
)

object SyncMessageTypes {
    const val HELLO = "hello"
    const val SNAPSHOT = "snapshot"
    const val TIMER_STATE = "timerState"
    const val SETTINGS = "settings"
}

/** A connected sync peer whose identity has been exchanged. */
data class SyncPeerInfo(
    val deviceId: String,
    val deviceName: String,
    val host: String,
)

/** Runtime diagnostics surfaced to the sync settings screen. */
data class SyncStatus(
    val serverRunning: Boolean = false,
    val connectedPeers: Int = 0,
    /** Epoch millis of the last successful peer exchange. */
    val lastSyncTimestamp: Long = 0L,
    /** Host of a manual connect attempt currently in progress, if any. */
    val connectingTo: String? = null,
    /** Human-readable failure of the last manual connect attempt, if any. */
    val lastConnectError: String? = null,
    /** Peers that announced themselves with HELLO and are still connected. */
    val peers: List<SyncPeerInfo> = emptyList(),
)

@Serializable
data class HelloPayload(
    val deviceId: String,
    val serverName: String,
)

/**
 * Full syncable state. Exchanged on connect and whenever the local state changes;
 * both sides merge it last-write-wins so the devices converge.
 */
@Serializable
data class SnapshotPayload(
    val sessions: List<Session> = emptyList(),
    /** Deleted session syncIds -> deletion epoch millis, so deletions propagate and win over re-uploads. */
    val deletedSessionTombstones: Map<String, Long> = emptyMap(),
    val labels: List<Label> = emptyList(),
    val timerProfiles: List<TimerProfile> = emptyList(),
    val settings: SyncedSettings? = null,
    val timerState: SyncedTimerState? = null,
)

@Serializable
data class SettingsPayload(
    val settings: SyncedSettings,
)

@Serializable
data class TimerStatePayload(
    val timerState: SyncedTimerState?,
)

/**
 * The active timer, expressed in wall-clock terms so it can be applied on a device with
 * a different boot-relative clock.
 *
 * @param endTimeWallClock epoch millis when a countdown (or count-up break) session ends; 0 for count-up focus
 * @param startTimeWallClock epoch millis when the current work session (re)started; used for count-up focus
 * @param remainingMillisAtPause remaining millis at pause (only meaningful when [state] is PAUSED)
 * @param timeSpentPaused total millis paused during the session
 */
@Serializable
data class SyncedTimerState(
    val state: TimerState,
    val type: TimerType,
    val isFocus: Boolean,
    val isCountdown: Boolean,
    val labelName: String,
    val remainingMillisAtPause: Long = 0,
    val endTimeWallClock: Long = 0,
    val startTimeWallClock: Long = 0,
    val timeSpentPaused: Long = 0,
    /** Epoch millis of the last timer event, used for last-write-wins resolution. */
    val updatedAt: Long = 0,
)
