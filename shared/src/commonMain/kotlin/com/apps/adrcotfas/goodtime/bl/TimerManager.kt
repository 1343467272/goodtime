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
package com.apps.adrcotfas.goodtime.bl

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.data.local.LocalDataRepository
import com.apps.adrcotfas.goodtime.data.model.Session
import com.apps.adrcotfas.goodtime.data.settings.AppSettings
import com.apps.adrcotfas.goodtime.data.settings.PersistedTimerState
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.sync.SyncedTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Manages the timer state and provides methods to start, pause, resume and finish the timer.
 */
class TimerManager(
    private val localDataRepo: LocalDataRepository,
    private val settingsRepo: SettingsRepository,
    private val listeners: List<EventListener>,
    private val timeProvider: TimeProvider,
    private val finishedSessionsHandler: FinishedSessionsHandler,
    private val breakBudgetManager: BreakBudgetManager,
    private val streakManager: StreakManager,
    private val log: Logger,
    private val coroutineScope: CoroutineScope,
    private val timerStateRestoration: TimerStateRestoration,
) {
    private var mainJob: Job? = null
    private var initJob: Job? = null

    private val _timerData: MutableStateFlow<DomainTimerData> = MutableStateFlow(DomainTimerData())

    /** Wall-clock epoch millis of the last timer transition, the LWW timestamp for sync. */
    private var lastTimerEventWallClock: Long = 0

    /** Whether the current runtime was mirrored from a peer (see [DomainTimerData.isMirrored]). */
    val isMirroring: Boolean
        get() = _timerData.value.isMirrored

    // defaults are only visible until the first settings emission, which also sets isReady;
    // callers gate on isReady before reading auto-start flags
    private var settings = AppSettings()

    val timerData: StateFlow<DomainTimerData> = _timerData

    init {
        setup()
    }

    fun setup() {
        mainJob?.cancel()
        mainJob =
            coroutineScope.launch {
                initAndObserveLabelChange()
            }
        initJob =
            coroutineScope.launch {
                initPersistentData()
                timerStateRestoration.restoreTimerState { runtimeState ->
                    _timerData.update {
                        it.copy(
                            runtime = runtimeState,
                            completedMinutes =
                            if (runtimeState.state == TimerState.FINISHED) {
                                FinishedSessionFactory.durationMinutes(runtimeState)
                            } else {
                                it.completedMinutes
                            },
                        )
                    }
                }
            }
    }

    /**
     * Waits until persisted state was restored and the label/settings are loaded.
     * Callers that can run right after process creation (e.g. AlarmReceiver)
     * must await this before issuing commands, otherwise they operate on default data.
     */
    suspend fun awaitReady() {
        initJob?.join()
        timerData.first { it.isReady }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun initAndObserveLabelChange() {
        settingsRepo.settings
            .map {
                settings = it
                it.labelName
            }.distinctUntilChanged()
            .flatMapLatest { labelName ->
                localDataRepo
                    .selectLabelByName(labelName)
                    .combine(
                        localDataRepo.selectDefaultLabel().filterNotNull(),
                    ) { label, defaultLabel ->
                        val defaultTimerProfile = defaultLabel.timerProfile
                        if (label == null) {
                            settingsRepo.activateDefaultLabel()
                            DomainLabel(defaultLabel, defaultTimerProfile)
                        } else {
                            // TODO: move this logic to DomainLabel; have DomainLabel a typedef of Label since it contains a TimerProfile already
                            DomainLabel(
                                label,
                                if (label.useDefaultTimeProfile) defaultTimerProfile else label.timerProfile,
                            )
                        }
                    }
            }.distinctUntilChanged()
            .collect {
                log.i { "new timerProfile: $it" }
                val value = _timerData.value
                val isActive = value.runtime.state.isActive
                val isCountdown = value.label.isCountdown

                if (isCountdown != it.isCountdown) {
                    log.i { "reset the timer because the profile type changed" }
                    reset()
                }

                _timerData.update { data ->
                    data.copy(
                        isReady = true,
                        label = it,
                    )
                }
                if (isActive) {
                    onActiveLabelChanged()
                }
            }
    }

    private suspend fun initPersistentData() {
        val longBreakData = streakManager.initialLongBreakData()
        log.i { "new long break data: $longBreakData" }
        val breakBudget = breakBudgetManager.initialBreakBudget()
        log.i { "new break budget: ${breakBudget.getRemainingBreakBudget(timeProvider.elapsedRealtime())}" }
        _timerData.update { data ->
            data.copy(longBreakData = longBreakData, breakBudgetData = breakBudget)
        }
    }

    fun start(
        timerType: TimerType = timerData.value.runtime.type,
        autoStarted: Boolean = false,
    ) {
        log.i { "Starting timer..." }
        val data = timerData.value
        if (!data.isReady) {
            log.e { "timer data not ready" }
            return
        }

        val elapsedRealTime = timeProvider.elapsedRealtime()

        if (data.runtime.state.isReset) {
            updateBreakBudgetIfNeeded()
        }

        val newTimerData =
            timerData.value.copy(
                runtime =
                data.runtime.copy(
                    startTime = elapsedRealTime,
                    lastStartTime = elapsedRealTime,
                    endTime = data.getEndTime(timerType, elapsedRealTime),
                    state = TimerState.RUNNING,
                    type = timerType,
                    timeSpentPaused = 0,
                ),
                isMirrored = if (autoStarted) data.isMirrored else false,
            )

        _timerData.update { newTimerData }
        lastTimerEventWallClock = timeProvider.now()

        handlePersistentDataAtStart()
        finishedSessionsHandler.resetLastInsertedSessionId()

        val timerData = _timerData.value
        val isCountdown = timerData.isCurrentSessionCountdown()
        val countUpEndTime =
            computeCountUpEndTime(timerData.getBaseTime(timeProvider))

        listeners.forEach {
            it.onEvent(
                Event.Start(
                    isFocus = timerData.runtime.type.isFocus,
                    autoStarted = autoStarted,
                    endTime = if (isCountdown) timerData.runtime.endTime else countUpEndTime,
                    labelName = timerData.label.label.name,
                    isDefaultLabel = timerData.label.isDefault(),
                    labelColorIndex = timerData.label.label.colorIndex,
                    isBreakEnabled = timerData.label.profile.isBreakEnabled,
                    isCountdown = isCountdown,
                    runtimeState = timerData.runtime,
                ),
            )
        }
    }

    private fun updateBreakBudgetIfNeeded() {
        breakBudgetManager.updatedBreakBudget(timerData.value)?.let { newData ->
            _timerData.update { it.copy(breakBudgetData = newData) }
        }
    }

    /**
     * Clears the accumulated break budget.
     * While a count-up focus session is running, the budget shown is the persisted value plus what
     * accrued since [TimerRuntimeState.lastStartTime], so that also moves to now - otherwise the
     * accrual since the session started would immediately undo the reset.
     */
    fun resetBreakBudget() {
        val newData = breakBudgetManager.resetBreakBudget()
        _timerData.update {
            it.copy(
                breakBudgetData = newData,
                runtime =
                if (it.runtime.state.isRunning) {
                    it.runtime.copy(lastStartTime = newData.breakBudgetStart)
                } else {
                    it.runtime
                },
            )
        }
    }

    fun addOneMinute() {
        val data = timerData.value
        if (!data.runtime.state.isActive) {
            log.e { "Trying to add one minute when the timer is not running" }
            return
        }
        if (!data.getTimerProfile().isCountdown) {
            log.e { "Trying to add a minute to a timer that is not a countdown" }
            return
        }
        val newEndTime = data.runtime.endTime + 1.minutes.inWholeMilliseconds
        val newRemainingTimeAtPause =
            if (data.runtime.state.isPaused) {
                data.runtime.timeAtPause + 1.minutes.inWholeMilliseconds
            } else {
                0
            }

        _timerData.update {
            it.copy(
                runtime =
                it.runtime.copy(
                    endTime = newEndTime,
                    timeAtPause = newRemainingTimeAtPause,
                ),
                isMirrored = false,
            )
        }
        lastTimerEventWallClock = timeProvider.now()
        log.i { "Added one minute" }
        listeners.forEach { it.onEvent(Event.AddOneMinute(newEndTime)) }
    }

    fun toggle() {
        when (timerData.value.runtime.state) {
            TimerState.RUNNING -> pause()
            TimerState.PAUSED -> resume()
            else -> log.e { "Trying to toggle the timer when it is not running or paused" }
        }
    }

    private fun pause() {
        val timerDataValue = timerData.value
        val isBreakOfCountUpProfile = !timerDataValue.label.isCountdown && timerDataValue.runtime.type != TimerType.FOCUS
        if (isBreakOfCountUpProfile) {
            log.e { "Trying to pause a break timer of a count up profile" }
            return
        }
        val elapsedRealtime = timeProvider.elapsedRealtime()
        updateBreakBudgetIfNeeded()
        _timerData.update {
            it.copy(
                runtime =
                it.runtime.copy(
                    timeAtPause =
                    if (it.label.profile.isCountdown) {
                        it.runtime.endTime - elapsedRealtime
                    } else {
                        elapsedRealtime - it.runtime.startTime - it.runtime.timeSpentPaused
                    },
                    lastPauseTime = elapsedRealtime,
                    state = TimerState.PAUSED,
                ),
                isMirrored = false,
            )
        }
        lastTimerEventWallClock = timeProvider.now()
        log.i { "Paused: ${timerData.value}" }
        listeners.forEach { it.onEvent(Event.Pause(runtimeState = _timerData.value.runtime)) }
    }

    private fun resume() {
        val elapsedRealTime = timeProvider.elapsedRealtime()
        updateBreakBudgetIfNeeded()
        updatePausedTime()
        val isCountdown = timerData.value.label.profile.isCountdown
        val newEndTime =
            if (isCountdown) {
                timerData.value.runtime.timeAtPause + elapsedRealTime
            } else {
                timerData.value.runtime.endTime
            }
        _timerData.update {
            it.copy(
                runtime =
                it.runtime.copy(
                    lastStartTime = elapsedRealTime,
                    endTime = newEndTime,
                    state = TimerState.RUNNING,
                    timeAtPause = 0,
                ),
                isMirrored = false,
            )
        }
        lastTimerEventWallClock = timeProvider.now()
        log.i { "Resumed: ${timerData.value}" }

        val timerData = _timerData.value
        val countUpEndTime =
            computeCountUpEndTime(timerData.getBaseTime(timeProvider))
        val isCurrentSessionCountdown = timerData.isCurrentSessionCountdown()
        listeners.forEach {
            it.onEvent(
                Event.Start(
                    isFocus = timerData.runtime.type.isFocus,
                    endTime = if (isCurrentSessionCountdown) timerData.runtime.endTime else countUpEndTime,
                    labelName = timerData.label.label.name,
                    isDefaultLabel = timerData.label.isDefault(),
                    labelColorIndex = timerData.label.label.colorIndex,
                    isBreakEnabled = timerData.label.profile.isBreakEnabled,
                    isCountdown = isCurrentSessionCountdown,
                    runtimeState = timerData.runtime,
                ),
            )
        }
    }

    private fun updatePausedTime() {
        val data = timerData.value
        if (data.runtime.lastPauseTime != 0L) {
            val elapsedRealTime = timeProvider.elapsedRealtime()
            val pausedTime = data.runtime.timeSpentPaused + elapsedRealTime - data.runtime.lastPauseTime
            log.i { "updatePausedTime: ${pausedTime.milliseconds}" }
            _timerData.update {
                it.copy(
                    runtime =
                    it.runtime.copy(
                        timeSpentPaused = pausedTime,
                        lastPauseTime = 0,
                    ),
                )
            }
        }
    }

    /**
     * Skips the current session and starts the next one.
     * This is called manually by the user before a session is finished, interrupting the current session.
     */
    fun skip() {
        nextInternal(finishActionType = FinishActionType.MANUAL_SKIP)
    }

    fun next(actionType: FinishActionType = FinishActionType.MANUAL_NEXT) {
        nextInternal(actionType)
    }

    /**
     * Updates a finished session with new duration/timestamp and/or notes.
     * This ensures atomic updates without race conditions.
     * @param updateDuration if true, update duration and timestamp (for idle time inclusion)
     * @param notes notes to save (can be empty)
     */
    fun updateFinishedSession(
        updateDuration: Boolean,
        notes: String,
    ) {
        val data = timerData.value
        if (data.runtime.state != TimerState.FINISHED) {
            log.w { "Trying to update a session that is not in FINISHED state" }
            return
        }

        if (updateDuration) {
            // Update endTime to include idle time
            _timerData.update {
                it.copy(
                    runtime =
                    it.runtime.copy(
                        endTime = timeProvider.elapsedRealtime(),
                    ),
                )
            }
            // Create session with new duration, timestamp, and notes
            val session = createFinishedSession(notes = notes)
            session?.let {
                finishedSessionsHandler.updateSession(it)
            }
        } else {
            // Only update notes
            finishedSessionsHandler.updateLastFinishedSessionNotes(notes.trim())
        }
    }

    /**
     * Called automatically when autoStart is enabled and the time is up or manually at the end of a session.
     */
    private fun nextInternal(finishActionType: FinishActionType) {
        val data = timerData.value
        if (!data.isReady) {
            log.e { "timer data not ready" }
            return
        }
        val state = data.runtime.state
        val timerProfile = data.label

        if (state == TimerState.RESET) {
            log.e { "Trying to start the next session but the timer is reset" }
            return
        }

        val isWork = data.runtime.type.isFocus
        val isCountDown = data.getTimerProfile().isCountdown

        updateBreakBudgetIfNeeded()

        val breakBudget = data.getBreakBudget(timeProvider.elapsedRealtime())
        if (isWork && !isCountDown && breakBudget < 1.minutes) {
            log.e { "Break budget is depleted, cannot start break" }
            return
        }

        if (finishActionType != FinishActionType.AUTO) {
            // Update endTime to current time before saving session
            // This ensures we save the actual time spent, not the expected duration
            _timerData.update {
                it.copy(
                    runtime =
                    it.runtime.copy(
                        endTime = timeProvider.elapsedRealtime(),
                    ),
                )
            }
            handleFinishedSession(finishActionType = finishActionType)
        }

        _timerData.update { it.reset() }

        // A manual next/skip turns a mirrored timer into the local leader; automatic
        // chaining keeps mirroring the leader.
        if (finishActionType != FinishActionType.AUTO) {
            _timerData.update { it.copy(isMirrored = false) }
        }

        val nextType =
            when {
                !isWork || (isWork && !timerProfile.profile.isBreakEnabled) -> TimerType.FOCUS
                !isCountDown -> TimerType.BREAK
                shouldConsiderStreak(timeProvider.elapsedRealtime()) -> TimerType.LONG_BREAK
                else -> TimerType.BREAK
            }
        log.i { "Next: $nextType" }

        val autoStarted =
            (nextType.isFocus && settings.autoStartFocus) ||
                (nextType.isBreak && settings.autoStartBreak)

        start(nextType, autoStarted)
    }

    /**
     * Called when the time is up for countdown timers.
     * A finished [Session] is created and sent to the listeners.
     */
    fun finish(actionType: FinishActionType = FinishActionType.AUTO) {
        val data = timerData.value
        if (!data.isReady) {
            log.e { "timer data not ready" }
            return
        }

        val state = data.runtime.state
        val timerProfile = data.label
        val type = data.runtime.type

        if (state.isReset || state.isFinished) {
            log.e { "Trying to finish the timer when it is reset or finished" }
            return
        }

        _timerData.update {
            it.copy(
                runtime =
                it.runtime.copy(
                    state = TimerState.FINISHED,
                    endTime = if (actionType == FinishActionType.AUTO) timeProvider.elapsedRealtime() else data.runtime.endTime,
                ),
            )
        }
        log.i { "Finish: $data" }

        updateBreakBudgetIfNeeded()
        handleFinishedSession(finishActionType = actionType)

        // Skip autostart when the timer expired long ago and we're only finishing it now: the
        // user isn't actively continuing, so silently starting (and chaining) the next session
        // would be wrong. This only bites when finish() catches up on an expiry it couldn't act
        // on in time — iOS returning to the foreground after a long background (no background
        // execution there), or Android restoring after the device was off well past the end
        // (BootReceiver, FORCE_FINISH). When finish is driven by the expiry itself — the Android
        // alarm, or the foreground monitor with the app open — the gap is ~0 and autostart runs.
        val timeSinceExpectedEnd = timeProvider.elapsedRealtime() - data.runtime.endTime
        val withinAutoStartWindow = timeSinceExpectedEnd < AUTOSTART_TIMEOUT

        val autoStart =
            !data.isMirrored &&
                withinAutoStartWindow &&
                (
                    (settings.autoStartFocus && (type.isBreak || !timerProfile.profile.isBreakEnabled)) ||
                        (settings.autoStartBreak && type.isFocus && timerProfile.profile.isBreakEnabled)
                    )
        lastTimerEventWallClock = timeProvider.now()

        log.i { "AutoStart: $autoStart (timeSinceExpectedEnd: ${timeSinceExpectedEnd.milliseconds}, withinWindow: $withinAutoStartWindow)" }
        listeners.forEach {
            it.onEvent(
                Event.Finished(
                    type = type,
                    autostartNextSession = autoStart,
                    runtimeState = _timerData.value.runtime,
                ),
            )
        }
        if (autoStart) {
            next(actionType = FinishActionType.AUTO)
        }
    }

    /**
     * Resets(stops) the timer.
     * This is also part of the flow after [finish] when the user has the option of starting a new session.
     * @see [finish]
     */
    fun reset(actionType: FinishActionType = FinishActionType.MANUAL_RESET) {
        val data = timerData.value
        if (data.runtime.state == TimerState.RESET) {
            log.w { "Trying to reset the timer when it is already reset" }
            return
        }
        log.i { "Reset: $data" }
        updateBreakBudgetIfNeeded()

        if (actionType != FinishActionType.MANUAL_DO_NOTHING) {
            // Update endTime to current time before saving session
            // This ensures we save the actual time spent, not the expected duration
            _timerData.update {
                it.copy(
                    runtime =
                    it.runtime.copy(
                        endTime = timeProvider.elapsedRealtime(),
                    ),
                )
            }
            handleFinishedSession(finishActionType = actionType)
        }

        listeners.forEach { it.onEvent(Event.Reset) }
        _timerData.update { it.reset().copy(isMirrored = false) }
        lastTimerEventWallClock = timeProvider.now()
    }

    private fun handlePersistentDataAtStart() {
        if (timerData.value.runtime.type == TimerType.FOCUS) {
            // filter out the case when some time passes since the last work session
            // preemptively reset the streak if the current work session cannot end in time
            resetStreakIfNeeded(timerData.value.runtime.endTime)
        }
    }

    private fun handleFinishedSession(finishActionType: FinishActionType) {
        val data = timerData.value
        val isWork = data.runtime.type.isFocus
        val isFinished = data.runtime.state.isFinished
        val isCountDown = data.getTimerProfile().isCountdown
        val longBreakEnabled = data.getTimerProfile().isLongBreakEnabled

        val session = createFinishedSession()
        // A mirrored timer doesn't save its own finished session or advance the streak:
        // the leading device already did both, and the session reaches this device via sync.
        if (!data.isMirrored) {
            session?.let {
                if (!isFinished ||
                    (isFinished && (finishActionType != FinishActionType.MANUAL_NEXT))
                ) {
                    finishedSessionsHandler.saveSession(it)
                }
            }

            if (isWork &&
                isCountDown &&
                longBreakEnabled &&
                (
                    finishActionType == FinishActionType.AUTO ||
                        finishActionType == FinishActionType.MANUAL_SKIP ||
                        finishActionType == FinishActionType.FORCE_FINISH
                    )
            ) {
                incrementStreak()
            }
        }
    }

    private fun createFinishedSession(notes: String = ""): Session? {
        updatePausedTime()
        val result =
            FinishedSessionFactory.create(
                data = timerData.value,
                now = timeProvider.now(),
                elapsedRealtime = timeProvider.elapsedRealtime(),
                notes = notes,
            )
        if (result == null) {
            log.i { "The session was shorter than 1 minute" }
            return null
        }
        val (session, completedMinutes) = result
        _timerData.update {
            it.copy(completedMinutes = completedMinutes)
        }
        return session
    }

    private fun incrementStreak() {
        val newData = streakManager.incrementStreak(timerData.value.longBreakData)
        _timerData.update { it.copy(longBreakData = newData) }
    }

    fun resetStreakIfNeeded(millis: Long = timeProvider.elapsedRealtime()) {
        val data = timerData.value
        streakManager.resetStreakIfNeeded(data.longBreakData, data.label.profile, millis)?.let { resetData ->
            _timerData.update { it.copy(longBreakData = resetData) }
        }
    }

    private fun shouldConsiderStreak(workEndTime: Long): Boolean {
        val data = timerData.value
        return streakManager.shouldConsiderStreak(data.longBreakData, data.label.profile, workEndTime)
    }

    fun onSendToBackground() {
        val timerData = _timerData.value
        val isCountdown = timerData.isCurrentSessionCountdown()
        val countUpEndTime =
            computeCountUpEndTime(timerData.getBaseTime(timeProvider))

        listeners.forEach {
            it.onEvent(
                Event.SendToBackground(
                    isTimerRunning = timerData.runtime.state.isRunning,
                    endTime =
                    if (isCountdown) {
                        timerData.runtime.endTime
                    } else {
                        countUpEndTime
                    },
                ),
            )
        }
    }

    fun onBringToForeground() {
        listeners.forEach {
            it.onEvent(Event.BringToForeground)
        }
    }

    private fun onActiveLabelChanged() {
        listeners.forEach {
            it.onEvent(Event.UpdateActiveLabel)
        }
    }

    /**
     * Current timer expressed in wall-clock terms for the LAN sync protocol, or null when
     * the timer isn't ready yet. The [SyncedTimerState.updatedAt] is the timestamp of the
     * last timer transition (not the read time) so last-write-wins resolution is stable.
     */
    fun toSyncedTimerState(): SyncedTimerState? {
        val data = timerData.value
        if (!data.isReady) return null
        val runtime = data.runtime
        val isCountdown = data.isCurrentSessionCountdown()
        val now = timeProvider.now()
        val elapsedRealtime = timeProvider.elapsedRealtime()
        val type = runtime.type
        return SyncedTimerState(
            state = runtime.state,
            type = type,
            isFocus = type.isFocus,
            isCountdown = isCountdown,
            labelName = data.getLabelName(),
            remainingMillisAtPause = runtime.timeAtPause,
            endTimeWallClock = if (runtime.endTime > 0) now - elapsedRealtime + runtime.endTime else 0,
            startTimeWallClock =
            if (runtime.startTime > 0) {
                now - elapsedRealtime + runtime.startTime
            } else {
                0
            },
            timeSpentPaused = runtime.timeSpentPaused,
            durationMillis =
            if (runtime.state == TimerState.FINISHED) {
                (runtime.endTime - runtime.startTime).coerceAtLeast(0)
            } else {
                0
            },
            updatedAt = lastTimerEventWallClock,
        )
    }

    /**
     * Applies a peer's timer state (already resolved as the last-write-wins winner by the
     * sync engine) to the local timer. The wall-clock timestamps are converted back into
     * this device's boot-relative clock. The applied runtime is marked as mirrored so the
     * local device won't save a duplicate finished session or echo transitions back.
     */
    fun applySyncedTimerState(state: SyncedTimerState) {
        val current = timerData.value
        if (!current.isReady) return

        val elapsedRealtime = timeProvider.elapsedRealtime()
        val now = timeProvider.now()
        val offset = now - elapsedRealtime

        // The session's wall-clock start is synced so the mirror can reconstruct the actual
        // elapsed focus time and so the "earlier session wins" merge can compare starts.
        val sessionStart =
            if (state.startTimeWallClock > 0) {
                state.startTimeWallClock - offset
            } else {
                0
            }

        val runtime =
            when (state.state) {
                TimerState.RESET -> TimerRuntimeState(state = TimerState.RESET)

                TimerState.RUNNING ->
                    TimerRuntimeState(
                        startTime = sessionStart,
                        lastStartTime = sessionStart,
                        endTime = if (state.isCountdown && state.endTimeWallClock > 0) state.endTimeWallClock - offset else 0,
                        state = TimerState.RUNNING,
                        type = state.type,
                        timeSpentPaused = state.timeSpentPaused,
                    )

                TimerState.PAUSED ->
                    TimerRuntimeState(
                        startTime = sessionStart,
                        lastStartTime = sessionStart,
                        lastPauseTime = elapsedRealtime,
                        endTime = if (state.isCountdown && state.endTimeWallClock > 0) state.endTimeWallClock - offset else 0,
                        timeAtPause = state.remainingMillisAtPause,
                        state = TimerState.PAUSED,
                        type = state.type,
                        timeSpentPaused = state.timeSpentPaused,
                    )

                TimerState.FINISHED -> {
                    // Anchor startTime so the finished session duration is the actual one, not
                    // "elapsedRealtime - 0" (device uptime), which previously inflated the
                    // reported focus time and could be saved as a bogus session after restart.
                    val start =
                        if (state.durationMillis > 0) {
                            (elapsedRealtime - state.durationMillis).coerceAtLeast(0)
                        } else {
                            sessionStart
                        }
                    TimerRuntimeState(
                        startTime = start,
                        endTime = elapsedRealtime,
                        state = TimerState.FINISHED,
                        type = state.type,
                        timeSpentPaused = state.timeSpentPaused,
                    )
                }
            }

        _timerData.update {
            it.copy(
                runtime = runtime,
                isMirrored = true,
                completedMinutes =
                if (state.state == TimerState.FINISHED) {
                    FinishedSessionFactory.durationMinutes(runtime)
                } else {
                    it.completedMinutes
                },
            )
        }
        lastTimerEventWallClock = state.updatedAt
        persistMirroredState(runtime, state.endTimeWallClock, now)
        log.i { "Applied synced timer state: $state" }
    }

    private fun persistMirroredState(runtime: TimerRuntimeState, endTimeWallClock: Long, now: Long) {
        coroutineScope.launch {
            if (runtime.state.isReset) {
                settingsRepo.clearPersistedTimerState()
            } else {
                settingsRepo.setPersistedTimerState(
                    PersistedTimerState.from(
                        runtime = runtime,
                        savedAtWallClock = now,
                        endTimeWallClock = endTimeWallClock,
                    ),
                )
            }
        }
    }

    private fun computeCountUpEndTime(baseTime: Long) = timeProvider.elapsedRealtime() + (COUNT_UP_HARD_LIMIT - baseTime)

    companion object {
        val COUNT_UP_HARD_LIMIT = 900.minutes.inWholeMilliseconds

        // finish() can run well after the expected end (iOS foreground-return, or Android
        // restoring after a kill/reboot); don't auto-start the next session past this gap.
        val AUTOSTART_TIMEOUT = 30.minutes.inWholeMilliseconds
    }
}

enum class FinishActionType {
    // finish triggered by observing the current time rather than a scheduled alarm: the app is in
    // the foreground and the time is up, or we're catching up on a countdown that expired while
    // we couldn't act — iOS in the background, or Android after a process kill / device reboot
    FORCE_FINISH,
    MANUAL_RESET, // the user manually reset a session
    MANUAL_SKIP, // the user manually skipped a session, increment streak even if session is shorter than 1 minute
    MANUAL_NEXT, // at the end of a session, the user continues
    MANUAL_DO_NOTHING, // used when updating an existing finished session with extra idle time, notes etc
    AUTO, // without user interaction as the result of a trigger (AlarmReceiver), also relevant for "auto-start" sessions
}
