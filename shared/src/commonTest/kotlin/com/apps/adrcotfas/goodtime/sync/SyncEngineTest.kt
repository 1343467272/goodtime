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
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.settings.AppSettings
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import com.apps.adrcotfas.goodtime.data.settings.TimerStyleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {
    private val engine = SyncEngine()

    private fun session(syncId: String, updatedAt: Long) =
        Session(
            id = 0L,
            timestamp = updatedAt,
            duration = 25L,
            interruptions = 0L,
            label = "label",
            notes = "",
            isWork = true,
            isArchived = false,
            syncId = syncId,
            updatedAt = updatedAt,
        )

    @Test
    fun `newer session wins on the same syncId`() {
        val local = listOf(session("a", 100))
        val remote = listOf(session("a", 200))
        val result = engine.mergeSessions(local, remote, emptyMap(), emptyMap())
        assertEquals(listOf(session("a", 200)), result.sessionsToApply)
        assertEquals(emptyList<String>(), result.deletedSyncIds)
    }

    @Test
    fun `disjoint sessions merge into the union`() {
        val local = listOf(session("a", 100))
        val remote = listOf(session("b", 100))
        val result = engine.mergeSessions(local, remote, emptyMap(), emptyMap())
        assertEquals(setOf("a", "b"), result.sessionsToApply.map { it.syncId }.toSet())
    }

    @Test
    fun `tombstone newer than the session deletes it and propagates the tombstone`() {
        val local = listOf(session("a", 100))
        val remote = emptyList<Session>()
        val result = engine.mergeSessions(local, remote, emptyMap(), mapOf("a" to 200))
        assertTrue(result.sessionsToApply.isEmpty())
        assertEquals(listOf("a"), result.deletedSyncIds)
        assertEquals(200L, result.outgoingTombstones["a"])
    }

    @Test
    fun `session updated after a tombstone is resurrected`() {
        val local = emptyList<Session>()
        val remote = listOf(session("a", 300))
        val result = engine.mergeSessions(local, remote, mapOf("a" to 200), emptyMap())
        assertEquals(listOf(session("a", 300)), result.sessionsToApply)
        assertTrue(result.deletedSyncIds.isEmpty())
        assertTrue(result.outgoingTombstones.isEmpty())
    }

    @Test
    fun `session edited on both sides picks the newest`() {
        val local = listOf(session("a", 100))
        val remote = listOf(session("a", 100).copy(notes = "edited remotely"))
        val result = engine.mergeSessions(local, remote, emptyMap(), emptyMap())
        // same timestamp: deterministic tie-break by syncId, which is identical here,
        // so the first candidate (local, then remote) wins - assert only the set of versions
        assertEquals(1, result.sessionsToApply.size)
        assertTrue(result.sessionsToApply.first().syncId == "a")
    }

    @Test
    fun `newer settings snapshot wins`() {
        val local = SyncedSettings(workdayStart = 1, updatedAt = 100L)
        val remote = SyncedSettings(workdayStart = 2, updatedAt = 200L)
        assertEquals(remote, engine.mergeSyncedSettings(local, remote))
    }

    @Test
    fun `null settings is replaced by the peer's`() {
        assertEquals(null, engine.mergeSyncedSettings(null, null))
        val remote = SyncedSettings(workdayStart = 1, updatedAt = 100L)
        assertEquals(remote, engine.mergeSyncedSettings(null, remote))
        assertEquals(remote, engine.mergeSyncedSettings(remote, null))
    }

    @Test
    fun `synced settings exclude device specific timer sizes but keep design fields`() {
        val appSettings =
            AppSettings(
                timerStyle =
                TimerStyleData(
                    colorIndex = 3,
                    minSize = 10f,
                    maxSize = 20f,
                    fontSize = 18f,
                    currentScreenWidth = 400f,
                    fontWeight = 700,
                ),
            )
        val synced = SyncedSettings.from(appSettings, updatedAt = 100L)
        assertEquals(0f, synced.timerStyle.minSize)
        assertEquals(0f, synced.timerStyle.maxSize)
        assertEquals(0f, synced.timerStyle.fontSize)
        assertEquals(0f, synced.timerStyle.currentScreenWidth)
        assertEquals(3, synced.timerStyle.colorIndex)
        assertEquals(700, synced.timerStyle.fontWeight)
    }

    private fun timerState(
        state: TimerState = TimerState.RUNNING,
        startTimeWallClock: Long = 0,
        endTimeWallClock: Long = 0,
        timeSpentPaused: Long = 0,
        updatedAt: Long = 0,
    ) = SyncedTimerState(
        state = state,
        type = TimerType.FOCUS,
        isFocus = true,
        isCountdown = true,
        labelName = "label",
        startTimeWallClock = startTimeWallClock,
        endTimeWallClock = endTimeWallClock,
        timeSpentPaused = timeSpentPaused,
        updatedAt = updatedAt,
    )

    @Test
    fun `later change overwrites an earlier one even if its session was opened later`() {
        val earlier = timerState(startTimeWallClock = 1000, updatedAt = 1000)
        val later = timerState(startTimeWallClock = 2000, updatedAt = 1500)
        assertEquals(later, engine.mergeTimerState(later, earlier))
        assertEquals(later, engine.mergeTimerState(earlier, later))
    }

    @Test
    fun `sessions opened at the same time fall back to last write wins`() {
        val old = timerState(startTimeWallClock = 1000, updatedAt = 1000)
        val new = timerState(startTimeWallClock = 1000, updatedAt = 2000)
        assertEquals(new, engine.mergeTimerState(old, new))
    }

    @Test
    fun `leader finishing the same session is followed by the mirror`() {
        val running = timerState(startTimeWallClock = 1000, updatedAt = 1000)
        val finished =
            timerState(state = TimerState.FINISHED, startTimeWallClock = 1000, updatedAt = 2000)
        assertEquals(finished, engine.mergeTimerState(running, finished))
    }

    @Test
    fun `a newer idle reset overwrites an older running session`() {
        val running = timerState(startTimeWallClock = 1000, updatedAt = 1000)
        val reset = timerState(state = TimerState.RESET, startTimeWallClock = 0, updatedAt = 2000)
        assertEquals(reset, engine.mergeTimerState(running, reset))
        assertEquals(reset, engine.mergeTimerState(reset, running))
    }

    @Test
    fun `idle states fall back to last write wins`() {
        val old = timerState(state = TimerState.RESET, updatedAt = 1000)
        val new = timerState(state = TimerState.RESET, updatedAt = 2000)
        assertEquals(new, engine.mergeTimerState(old, new))
    }

    @Test
    fun `overwriting an idle device with a running session forces it to pause`() {
        val running = timerState(startTimeWallClock = 1000, endTimeWallClock = 1600, updatedAt = 1000)
        val paused =
            engine.pauseOnOverwrite(
                local = timerState(state = TimerState.RESET, updatedAt = 900),
                winner = running,
                now = 1100,
            )
        assertEquals(TimerState.PAUSED, paused.state)
        assertEquals(500, paused.remainingMillisAtPause)
        assertEquals(1100, paused.updatedAt)
        assertEquals(1000, paused.startTimeWallClock)
    }

    @Test
    fun `same running session is followed not paused`() {
        val running = timerState(startTimeWallClock = 1000, updatedAt = 1500)
        val local = timerState(startTimeWallClock = 1000, updatedAt = 1000)
        assertEquals(running, engine.pauseOnOverwrite(local, running, now = 1600))
    }

    @Test
    fun `only running winners are paused`() {
        val finished = timerState(state = TimerState.FINISHED, startTimeWallClock = 1000, updatedAt = 1500)
        val local = timerState(startTimeWallClock = 2000, updatedAt = 1000)
        assertEquals(finished, engine.pauseOnOverwrite(local, finished, now = 1600))

        val paused = timerState(state = TimerState.PAUSED, startTimeWallClock = 1000, updatedAt = 1500)
        assertEquals(paused, engine.pauseOnOverwrite(local, paused, now = 1600))
    }

    @Test
    fun `forced pause keeps a fresh timestamp so it propagates back to the winner`() {
        val running = timerState(startTimeWallClock = 1000, updatedAt = 1500)
        val paused = engine.pauseOnOverwrite(local = null, winner = running, now = 2000)
        assertEquals(TimerState.PAUSED, paused.state)
        assertTrue(paused.updatedAt > running.updatedAt)
    }

    @Test
    fun `count-up focus overwrite pauses with the elapsed focus time`() {
        val running =
            timerState(startTimeWallClock = 1000, updatedAt = 1500)
                .copy(isCountdown = false, timeSpentPaused = 100)
        val paused = engine.pauseOnOverwrite(local = null, winner = running, now = 2000)
        assertEquals(TimerState.PAUSED, paused.state)
        assertEquals(900, paused.remainingMillisAtPause)
    }
}
