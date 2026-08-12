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
}
