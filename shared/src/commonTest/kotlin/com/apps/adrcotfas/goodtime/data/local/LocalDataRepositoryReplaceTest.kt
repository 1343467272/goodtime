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
package com.apps.adrcotfas.goodtime.data.local

import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.model.TimerProfile
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.fakes.FakeLabelDao
import com.apps.adrcotfas.goodtime.fakes.FakeSessionDao
import com.apps.adrcotfas.goodtime.fakes.FakeSettingsRepository
import com.apps.adrcotfas.goodtime.fakes.FakeTimerProfileDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the forced-overwrite paths used by "overwrite other device" / "pull from device":
 * the local sessions, labels and timer profiles are replaced wholesale, not merged.
 */
class LocalDataRepositoryReplaceTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var repo: LocalDataRepository

    @BeforeTest
    fun setup() = runTest(testDispatcher) {
        settingsRepo = FakeSettingsRepository()
        repo =
            LocalDataRepositoryImpl(
                sessionDao = FakeSessionDao(),
                labelDao = FakeLabelDao(),
                timerProfileDao = FakeTimerProfileDao(),
                settingsRepo = settingsRepo,
                coroutineScope = testScope,
            )
        // replace the internal default label with the real one, like the app does
        repo.updateDefaultLabel(Label.defaultLabel())
    }

    private fun session(syncId: String) =
        Session(
            id = 0L,
            timestamp = 1L,
            duration = 25L,
            interruptions = 0L,
            label = "label",
            notes = "",
            isWork = true,
            isArchived = false,
            syncId = syncId,
            updatedAt = 1L,
        )

    @Test
    fun `replacing sessions drops local sessions and adopts the remote ones`() = runTest(testDispatcher) {
        repo.insertSession(session("local"))
        repo.replaceAllSyncedSessions(listOf(session("remote")))
        val sessions = repo.selectAllSessionsOnce()
        assertEquals(listOf("remote"), sessions.map { it.syncId })
    }

    @Test
    fun `replacing labels keeps the default label and drops the local ones`() = runTest(testDispatcher) {
        repo.insertLabel(Label(name = "local", updatedAt = 1L))
        val remoteDefault = Label.defaultLabel().copy(updatedAt = 2L)
        repo.replaceAllSyncedLabels(listOf(remoteDefault, Label(name = "remote", updatedAt = 2L)))
        val names = repo.selectAllLabelsOnce().map { it.name }
        assertTrue(Label.DEFAULT_LABEL_NAME in names)
        assertTrue("remote" in names)
        assertTrue("local" !in names)
    }

    @Test
    fun `replacing timer profiles drops local profiles and adopts the remote ones`() = runTest(testDispatcher) {
        repo.replaceAllSyncedTimerProfiles(
            listOf(
                TimerProfile(
                    name = TimerProfile.DEFAULT_PROFILE_NAME,
                    workDuration = 50,
                    updatedAt = 2L,
                ),
            ),
        )
        val profiles = repo.selectAllTimerProfilesOnce()
        assertEquals(listOf(TimerProfile.DEFAULT_PROFILE_NAME), profiles.map { it.name })
        assertEquals(50, profiles.first().workDuration)
    }
}
