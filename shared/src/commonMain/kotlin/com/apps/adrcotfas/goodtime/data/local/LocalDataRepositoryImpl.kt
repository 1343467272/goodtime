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

import androidx.paging.PagingSource
import com.apps.adrcotfas.goodtime.bl.TimeProvider
import com.apps.adrcotfas.goodtime.bl.generateUuid
import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.model.TimerProfile
import com.apps.adrcotfas.goodtime.data.model.toExternal
import com.apps.adrcotfas.goodtime.data.model.toLocal
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class LocalDataRepositoryImpl(
    sessionDao: SessionDao,
    labelDao: LabelDao,
    timerProfileDao: TimerProfileDao,
    private val settingsRepo: SettingsRepository,
    private val coroutineScope: CoroutineScope,
) : LocalDataRepository {
    private data class Daos(
        val sessionDao: SessionDao,
        val labelDao: LabelDao,
        val timerProfileDao: TimerProfileDao,
    )

    // All flow-returning methods route through this via flatMapLatest so that live
    // collectors (TimerManager, ViewModels) transparently switch to the new database
    // after a backup restore (see reopen).
    private val daos = MutableStateFlow(Daos(sessionDao, labelDao, timerProfileDao))

    private val sessionDao get() = daos.value.sessionDao
    private val labelDao get() = daos.value.labelDao
    private val timerProfileDao get() = daos.value.timerProfileDao

    init {
        insertDefaultLabel()
    }

    override fun reopen(database: ProductivityDatabase) {
        daos.value = Daos(database.sessionsDao(), database.labelsDao(), database.timerProfileDao())
        insertDefaultLabel()
    }

    private fun insertDefaultLabel() {
        coroutineScope.launch {
            val insert = !settingsRepo.settings.map { it.timeProfilesInitialized }.first()
            if (insert) {
                timerProfileDao.insert(
                    TimerProfile(
                        name = LocalTimerProfile.DEFAULT_PROFILE_NAME,
                        workDuration = 25,
                        breakDuration = 5,
                        isLongBreakEnabled = false,
                    ).toLocal().copy(updatedAt = TimeProvider.now()),
                )
                timerProfileDao.insert(
                    TimerProfile(
                        name = LocalTimerProfile.PROFILE_50_10_NAME,
                        workDuration = 50,
                        breakDuration = 10,
                        isLongBreakEnabled = false,
                    ).toLocal().copy(updatedAt = TimeProvider.now()),
                )
                timerProfileDao.insert(
                    TimerProfile(
                        name = LocalTimerProfile.POMODORO_PROFILE_NAME,
                        workDuration = 25,
                        breakDuration = 5,
                        isLongBreakEnabled = true,
                        longBreakDuration = 15,
                        sessionsBeforeLongBreak = 4,
                    ).toLocal().copy(updatedAt = TimeProvider.now()),
                )
                timerProfileDao.insert(
                    TimerProfile(
                        name = LocalTimerProfile.THIRD_TIME_PROFILE_NAME,
                        isCountdown = false,
                        workBreakRatio = 3,
                    ).toLocal().copy(updatedAt = TimeProvider.now()),
                )
                settingsRepo.setTimeProfilesInitialized(true)
            }

            val localLabel = Label.defaultLabel().toLocal().copy(updatedAt = TimeProvider.now())
            labelDao.insert(localLabel)
        }
    }

    override suspend fun insertSession(session: Session): Long {
        val now = TimeProvider.now()
        val newSession = session.copy(
            syncId = if (session.syncId.isEmpty()) generateUuid() else session.syncId,
            updatedAt = now,
        )
        return sessionDao.insert(newSession.toLocal())
    }

    override suspend fun updateSession(
        id: Long,
        newSession: Session,
    ) {
        val localSession = newSession.copy(
            syncId = if (newSession.syncId.isEmpty()) generateUuid() else newSession.syncId,
            updatedAt = TimeProvider.now(),
        ).toLocal()
        sessionDao.update(
            newTimestamp = localSession.timestamp,
            newDuration = localSession.duration,
            newInterruptions = localSession.interruptions,
            newLabel = localSession.labelName,
            newNotes = localSession.notes,
            newIsWork = localSession.isWork,
            newUpdatedAt = localSession.updatedAt,
            id = id,
        )
    }

    override suspend fun updateSessionsLabelByIds(
        newLabel: String,
        ids: List<Long>,
    ) {
        sessionDao.updateLabelByIds(newLabel, TimeProvider.now(), ids)
    }

    override suspend fun updateSessionsLabelByIdsExcept(
        newLabel: String,
        unselectedIds: List<Long>,
        selectedLabels: List<String>,
        considerBreaks: Boolean,
    ) {
        sessionDao.updateLabelByIdsExcept(newLabel, TimeProvider.now(), unselectedIds, selectedLabels, considerBreaks)
    }

    override fun selectAllSessions(): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectAll() }
        .map { it.map { sessions -> sessions.toExternal() } }

    override fun selectSessionsAfter(timestamp: Long): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectAfter(timestamp) }
        .map { sessions -> sessions.map { it.toExternal() } }

    override fun selectSessionById(id: Long): Flow<Session> = daos
        .flatMapLatest { it.sessionDao.selectById(id) }
        .map { it.toExternal() }

    override fun selectSessionsByIsArchived(isArchived: Boolean): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectByIsArchived(isArchived) }
        .map { sessions -> sessions.map { it.toExternal() } }

    override fun selectSessionsByLabel(label: String): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectByLabel(label) }
        .map { sessions ->
            sessions.map {
                it.toExternal()
            }
        }

    override fun selectSessionsByLabels(labels: List<String>): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectByLabels(labels) }
        .map { sessions -> sessions.map { it.toExternal() } }

    override fun selectSessionsByLabels(
        labels: List<String>,
        after: Long,
    ): Flow<List<Session>> = daos
        .flatMapLatest { it.sessionDao.selectByLabels(labels, after) }
        .map { sessions -> sessions.map { it.toExternal() } }

    override fun selectSessionsForTimelinePaged(
        labels: List<String>,
        showBreaks: Boolean,
    ): PagingSource<Int, LocalSession> = sessionDao.selectSessionsForTimelinePaged(labels, showBreaks)

    override fun selectNumberOfSessionsAfter(timestamp: Long): Flow<Int> = daos.flatMapLatest { it.sessionDao.selectNumberOfSessionsAfter(timestamp) }

    override suspend fun deleteSessions(ids: List<Long>) {
        sessionDao.delete(ids)
    }

    override suspend fun deleteSessionsExcept(
        unselectedIds: List<Long>,
        selectedLabels: List<String>,
        considerBreaks: Boolean,
    ) {
        sessionDao.deleteExcept(unselectedIds, selectedLabels, considerBreaks)
    }

    override suspend fun deleteAllSessions() {
        sessionDao.deleteAll()
    }

    override suspend fun insertLabel(label: Label): Long = labelDao.insert(label.toLocal().copy(updatedAt = TimeProvider.now()))

    override suspend fun insertLabelAndBulkRearrange(
        label: Label,
        labelsToUpdate: List<Pair<String, Long>>,
    ) {
        val now = TimeProvider.now()
        labelDao.insertLabelAndBulkRearrange(label.toLocal().copy(updatedAt = now), labelsToUpdate, now)
    }

    override suspend fun updateLabelOrderIndex(
        name: String,
        newOrderIndex: Long,
    ) {
        labelDao.updateOrderIndex(newOrderIndex.toInt(), TimeProvider.now(), name)
    }

    override suspend fun bulkUpdateLabelOrderIndex(labelsToUpdate: List<Pair<String, Long>>) {
        labelDao.bulkUpdateLabelOrderIndex(labelsToUpdate, TimeProvider.now())
    }

    override suspend fun updateLabel(
        name: String,
        newLabel: Label,
    ) {
        if (newLabel.name.isEmpty()) return
        val localLabel = newLabel.toLocal().copy(updatedAt = TimeProvider.now())
        labelDao.updateLabel(
            newName = localLabel.name,
            newColorIndex = localLabel.colorIndex,
            newUseDefaultTimeProfile = localLabel.useDefaultTimeProfile,
            newTimerProfileName = localLabel.timerProfileName,
            newIsCountdown = localLabel.isCountdown,
            newWorkDuration = localLabel.workDuration,
            newIsBreakEnabled = localLabel.isBreakEnabled,
            newBreakDuration = localLabel.breakDuration,
            newIsLongBreakEnabled = localLabel.isLongBreakEnabled,
            newLongBreakDuration = localLabel.longBreakDuration,
            newSessionsBeforeLongBreak = localLabel.sessionsBeforeLongBreak,
            newWorkBreakRatio = localLabel.workBreakRatio,
            newUpdatedAt = localLabel.updatedAt,
            name = name,
        )
    }

    override suspend fun updateDefaultLabel(newDefaultLabel: Label) {
        updateLabel(Label.DEFAULT_LABEL_NAME, newDefaultLabel)
    }

    override fun selectDefaultLabel() = selectLabelByName(Label.DEFAULT_LABEL_NAME)

    override suspend fun updateLabelIsArchived(
        name: String,
        newIsArchived: Boolean,
    ) {
        labelDao.updateIsArchived(newIsArchived, TimeProvider.now(), name)
    }

    override fun selectLabelByName(name: String): Flow<Label?> = daos.flatMapLatest { d ->
        d.labelDao.selectByName(name).flatMapLatest { localLabel ->
            if (localLabel?.timerProfileName != null) {
                d.timerProfileDao.selectByName(localLabel.timerProfileName).map { timerProfile ->
                    localLabel.toExternal(timerProfile?.toExternal())
                }
            } else {
                flowOf(localLabel?.toExternal())
            }
        }
    }

    override fun selectAllLabels(): Flow<List<Label>> = daos.flatMapLatest { d ->
        d.labelDao.selectAll().flatMapLatest { localLabels ->
            val timerProfileNames = localLabels.mapNotNull { it.timerProfileName }.distinct()
            if (timerProfileNames.isEmpty()) {
                flowOf(localLabels.map { it.toExternal() })
            } else {
                d.timerProfileDao.selectByNames(timerProfileNames).map { timerProfiles ->
                    localLabels.map { localLabel ->
                        val matchingProfile =
                            timerProfiles.find { it.name == localLabel.timerProfileName }
                        localLabel.toExternal(matchingProfile?.toExternal())
                    }
                }
            }
        }
    }

    override fun selectLabelsByArchived(isArchived: Boolean): Flow<List<Label>> = daos
        .flatMapLatest { it.labelDao.selectByArchived(isArchived) }
        .map { labels -> labels.map { it.toExternal() } }

    override suspend fun deleteLabel(name: String) {
        labelDao.deleteByName(name)
    }

    override suspend fun deleteAllLabels() {
        labelDao.deleteAll()
    }

    override suspend fun archiveAllButDefault() {
        labelDao.archiveAllButDefault()
    }

    override suspend fun insertTimerProfile(profile: TimerProfile) {
        timerProfileDao.insert(profile.toLocal().copy(updatedAt = TimeProvider.now()))
    }

    override suspend fun insertTimerProfileAndSetDefault(profile: TimerProfile) {
        timerProfileDao.insertTimerProfileAndSetDefault(profile.toLocal().copy(updatedAt = TimeProvider.now()))
    }

    override suspend fun deleteTimerProfile(name: String) {
        timerProfileDao.deleteByName(name)
    }

    override suspend fun selectTimerProfile(name: String): Flow<TimerProfile?> = daos
        .flatMapLatest { it.timerProfileDao.selectByName(name) }
        .map { it?.toExternal() }

    override suspend fun selectAllTimerProfiles(): Flow<List<TimerProfile>> = daos
        .flatMapLatest { it.timerProfileDao.selectAll() }
        .map { profiles ->
            profiles.map {
                it.toExternal()
            }
        }

    override suspend fun selectAllSessionsOnce(): List<Session> =
        sessionDao.selectAllOnce().map { it.toExternal() }

    override suspend fun selectAllLabelsOnce(): List<Label> {
        val localLabels = labelDao.selectAllOnce()
        val timerProfileNames = localLabels.mapNotNull { it.timerProfileName }.distinct()
        if (timerProfileNames.isEmpty()) {
            return localLabels.map { it.toExternal() }
        }
        val timerProfiles = timerProfileDao.selectAllOnce().associateBy { it.name }
        return localLabels.map { localLabel ->
            localLabel.toExternal(timerProfiles[localLabel.timerProfileName]?.toExternal())
        }
    }

    override suspend fun selectAllTimerProfilesOnce(): List<TimerProfile> =
        timerProfileDao.selectAllOnce().map { it.toExternal() }

    override suspend fun applySyncedSessions(
        sessions: List<Session>,
        deletedSyncIds: List<String>,
    ) {
        deletedSyncIds.forEach { sessionDao.deleteBySyncId(it) }
        for (s in sessions) {
            val local = s.toLocal()
            if (local.syncId.isEmpty()) continue
            val updated = sessionDao.updateBySyncId(
                newTimestamp = local.timestamp,
                newDuration = local.duration,
                newInterruptions = local.interruptions,
                newLabel = local.labelName,
                newNotes = local.notes,
                newIsWork = local.isWork,
                newIsArchived = local.isArchived,
                newUpdatedAt = local.updatedAt,
                syncId = local.syncId,
            )
            if (updated == 0) {
                sessionDao.insert(local.copy(id = 0))
            }
        }
    }

    override suspend fun applySyncedLabels(labels: List<Label>) {
        for (l in labels) {
            val local = l.toLocal()
            val updated = labelDao.updateLabelSync(
                newColorIndex = local.colorIndex,
                newOrderIndex = local.orderIndex,
                newUseDefaultTimeProfile = local.useDefaultTimeProfile,
                newTimerProfileName = local.timerProfileName,
                newIsCountdown = local.isCountdown,
                newWorkDuration = local.workDuration,
                newIsBreakEnabled = local.isBreakEnabled,
                newBreakDuration = local.breakDuration,
                newIsLongBreakEnabled = local.isLongBreakEnabled,
                newLongBreakDuration = local.longBreakDuration,
                newSessionsBeforeLongBreak = local.sessionsBeforeLongBreak,
                newWorkBreakRatio = local.workBreakRatio,
                newIsArchived = local.isArchived,
                newUpdatedAt = local.updatedAt,
                name = local.name,
            )
            if (updated == 0) {
                labelDao.insert(local)
            }
        }
    }

    override suspend fun applySyncedTimerProfiles(profiles: List<TimerProfile>) {
        for (p in profiles) {
            val local = p.toLocal()
            val updated = timerProfileDao.updateProfileSync(
                newIsCountdown = local.isCountdown,
                newWorkDuration = local.workDuration,
                newIsBreakEnabled = local.isBreakEnabled,
                newBreakDuration = local.breakDuration,
                newIsLongBreakEnabled = local.isLongBreakEnabled,
                newLongBreakDuration = local.longBreakDuration,
                newSessionsBeforeLongBreak = local.sessionsBeforeLongBreak,
                newWorkBreakRatio = local.workBreakRatio,
                newUpdatedAt = local.updatedAt,
                name = local.name,
            )
            if (updated == 0) {
                timerProfileDao.insert(local)
            }
        }
    }
}
