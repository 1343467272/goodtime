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

import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.model.TimerProfile
import com.apps.adrcotfas.goodtime.data.settings.SyncedSettings
import kotlinx.serialization.json.Json

/**
 * Pure last-write-wins merge logic for the LAN sync feature. Kept free of IO so it
 * can be unit tested and used identically on both sides of a connection.
 *
 * Both peers compute the merged result from the same inputs, so an exchange converges
 * after at most one reply (ties are broken deterministically).
 */
class SyncEngine(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class SessionMergeResult(
        val sessionsToApply: List<Session>,
        val deletedSyncIds: List<String>,
        val outgoingSessions: List<Session>,
        val outgoingTombstones: Map<String, Long>,
    )

    fun mergeSessions(
        local: List<Session>,
        remote: List<Session>,
        localTombstones: Map<String, Long>,
        remoteTombstones: Map<String, Long>,
    ): SessionMergeResult {
        val localBySyncId = local.filter { it.syncId.isNotEmpty() }.associateBy { it.syncId }
        val remoteBySyncId = remote.filter { it.syncId.isNotEmpty() }.associateBy { it.syncId }
        val keys = localBySyncId.keys + remoteBySyncId.keys + localTombstones.keys + remoteTombstones.keys

        val winnerSet = mutableMapOf<String, Session>()
        val deleted = sortedSetOf<String>()
        val outgoingTombstones = mutableMapOf<String, Long>()

        for (key in keys) {
            val localItem = localBySyncId[key]
            val remoteItem = remoteBySyncId[key]
            val localTomb = localTombstones[key]
            val remoteTomb = remoteTombstones[key]

            val times = listOfNotNull(
                localItem?.updatedAt,
                remoteItem?.updatedAt,
                localTomb,
                remoteTomb,
            )
            val lastTime = times.maxOrNull() ?: continue
            val lastEntityCandidates =
                listOfNotNull(
                    localItem?.takeIf { it.updatedAt == lastTime },
                    remoteItem?.takeIf { it.updatedAt == lastTime },
                ).filterNotNull()
            val hasLastTombstone = localTomb == lastTime || remoteTomb == lastTime

            val winner =
                if (lastEntityCandidates.isNotEmpty()) {
                    // concurrent entity + tombstone at the same timestamp: the update wins
                    lastEntityCandidates.maxByOrNull { it.syncId }
                } else if (hasLastTombstone) {
                    null
                } else {
                    lastEntityCandidates.firstOrNull()
                }

            if (winner != null) {
                winnerSet[key] = winner
            } else {
                deleted.add(key)
                outgoingTombstones[key] = lastTime
            }
        }

        val merged = winnerSet.values.toList()
        return SessionMergeResult(
            sessionsToApply = merged,
            deletedSyncIds = deleted.toList(),
            outgoingSessions = merged,
            outgoingTombstones = outgoingTombstones,
        )
    }

    fun mergeLabels(
        local: List<Label>,
        remote: List<Label>,
    ): List<Label> {
        val localByKey = local.associateBy { it.name }
        val remoteByKey = remote.associateBy { it.name }
        val keys = localByKey.keys + remoteByKey.keys
        return keys.mapNotNull { key ->
            val l = localByKey[key]
            val r = remoteByKey[key]
            when {
                l != null && r == null -> l
                l == null -> r
                else ->
                    if (r!!.updatedAt > l.updatedAt || (r.updatedAt == l.updatedAt && r.name > l.name)) {
                        r
                    } else {
                        l
                    }
            }
        }
    }

    fun mergeTimerProfiles(
        local: List<TimerProfile>,
        remote: List<TimerProfile>,
    ): List<TimerProfile> {
        val localByKey = local.filter { it.name != null }.associateBy { it.name }
        val remoteByKey = remote.filter { it.name != null }.associateBy { it.name }
        val keys = localByKey.keys + remoteByKey.keys
        return keys.mapNotNull { key ->
            val l = localByKey[key]
            val r = remoteByKey[key]
            when {
                l != null && r == null -> l
                l == null -> r
                else ->
                    if (r!!.updatedAt > l.updatedAt || (r.updatedAt == l.updatedAt && r.name.orEmpty() > l.name.orEmpty())) {
                        r
                    } else {
                        l
                    }
            }
        }
    }

    fun mergeSyncedSettings(
        local: SyncedSettings?,
        remote: SyncedSettings?,
    ): SyncedSettings? {
        if (local == null) return remote
        if (remote == null) return local
        if (remote.updatedAt > local.updatedAt) return remote
        if (remote.updatedAt < local.updatedAt) return local
        // Tie: compare canonical encodings so both devices pick the same winner.
        val localJson = json.encodeToString(SyncedSettings.serializer(), local)
        val remoteJson = json.encodeToString(SyncedSettings.serializer(), remote)
        return if (remoteJson > localJson) remote else local
    }

    fun mergeTimerState(
        local: SyncedTimerState?,
        remote: SyncedTimerState?,
    ): SyncedTimerState? {
        if (local == null) return remote
        if (remote == null) return local
        if (remote.updatedAt > local.updatedAt) return remote
        if (remote.updatedAt < local.updatedAt) return local
        return if (remote.labelName > local.labelName) remote else local
    }
}
