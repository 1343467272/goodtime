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
package com.apps.adrcotfas.goodtime.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.apps.adrcotfas.goodtime.bl.FinishActionType
import com.apps.adrcotfas.goodtime.bl.TimerManager
import com.apps.adrcotfas.goodtime.bl.getLabelData
import com.apps.adrcotfas.goodtime.common.isPortrait
import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.settings.isDarkTheme
import com.apps.adrcotfas.goodtime.main.dialcontrol.DialConfig
import com.apps.adrcotfas.goodtime.main.dialcontrol.DialControl
import com.apps.adrcotfas.goodtime.main.dialcontrol.DialControlButton
import com.apps.adrcotfas.goodtime.main.dialcontrol.rememberCustomDialControlState
import com.apps.adrcotfas.goodtime.main.dialcontrol.updateEnabledOptions
import com.apps.adrcotfas.goodtime.main.finishedsession.FinishedSessionSheet
import com.apps.adrcotfas.goodtime.onboarding.tutorial.TutorialScreen
import com.apps.adrcotfas.goodtime.platform.isFDroid
import com.apps.adrcotfas.goodtime.settings.permissions.getPermissionsState
import com.apps.adrcotfas.goodtime.settings.permissions.rememberAlarmPermissionRequester
import com.apps.adrcotfas.goodtime.settings.timerstyle.InitTimerStyle
import com.apps.adrcotfas.goodtime.sync.SyncManager
import com.apps.adrcotfas.goodtime.ui.ConfirmationDialog
import compose.icons.EvaIcons
import compose.icons.evaicons.Outline
import compose.icons.evaicons.outline.CloudDownload
import compose.icons.evaicons.outline.CloudUpload
import compose.icons.evaicons.outline.Sync
import goodtime_productivity.shared.generated.resources.Res
import goodtime_productivity.shared.generated.resources.main_reset_break_budget
import goodtime_productivity.shared.generated.resources.main_sync_done
import goodtime_productivity.shared.generated.resources.main_sync_no_connections
import goodtime_productivity.shared.generated.resources.main_sync_now
import goodtime_productivity.shared.generated.resources.main_sync_overwrite
import goodtime_productivity.shared.generated.resources.main_sync_overwrite_desc
import goodtime_productivity.shared.generated.resources.main_sync_overwrite_done
import goodtime_productivity.shared.generated.resources.main_sync_pull
import goodtime_productivity.shared.generated.resources.main_sync_pull_desc
import goodtime_productivity.shared.generated.resources.main_sync_pull_done
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MainScreen(
    navController: NavController,
    onSurfaceClick: () -> Unit,
    hideBottomBar: () -> Boolean,
    viewModel: TimerViewModel = koinViewModel(),
    mainViewModel: MainViewModel,
    onUpdateClicked: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle(TimerMainUiState())
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle(MainUiState())

    val syncManager: SyncManager = koinInject()
    val syncStatus by syncManager.status.collectAsStateWithLifecycle()

    val updateAvailable = mainUiState.isUpdateAvailable
    val wasNotificationPermissionDenied = mainUiState.wasNotificationPermissionDenied

    if (uiState.isLoading) return

    val permissionState = getPermissionsState()
    val requestAlarmPermission = rememberAlarmPermissionRequester(permissionState)
    val coroutineScope = rememberCoroutineScope()

    InitTimerStyle(viewModel)

    LifecycleResumeEffect(Unit) {
        viewModel.refreshStartOfToday()
        onPauseOrDispose {
            // do nothing
        }
    }

    val timerUiState by viewModel.timerUiState.collectAsStateWithLifecycle(TimerUiState())

    // Collected without reading .value here, so the ticking values only recompose the leaf
    // composables that call the lambdas (the timer text / the finished sheet), not this screen.
    val displayTimeState = viewModel.displayTime.collectAsStateWithLifecycle()
    val idleTimeState = viewModel.idleTime.collectAsStateWithLifecycle()

    val timerStyle = uiState.timerStyle
    val label = timerUiState.label

    val haptic = LocalHapticFeedback.current

    val dialControlState =
        rememberCustomDialControlState(
            config = DialConfig(),
            onLeft = viewModel::skip,
            onTop = viewModel::addOneMinute,
            onRight = viewModel::skip,
            onBottom = viewModel::resetTimer,
        )

    dialControlState.updateEnabledOptions(timerUiState)
    val gestureModifier =
        dialControlState.let {
            Modifier
                .pointerInput(it) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        it.onDown(position = down.position)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        var change =
                            awaitTouchSlopOrCancellation(pointerId = down.id) { change, _ ->
                                change.consume()
                            }
                        while (change != null && change.pressed) {
                            change =
                                awaitDragOrCancellation(change.id)?.also { inputChange ->
                                    if (inputChange.pressed && timerUiState.isActive) {
                                        dialControlState.onDrag(dragAmount = inputChange.positionChange())
                                    }
                                }
                        }
                        it.onRelease()
                    }
                }
        }

    val yOffset = remember { Animatable(0f) }
    ScreensaverMode(
        enabled = uiState.screensaverMode,
        isTimerActive = timerUiState.isActive,
        yOffset = yOffset,
    )

    val isDarkTheme = uiState.darkThemePreference.isDarkTheme(isSystemInDarkTheme())
    val backgroundColor by animateColorAsState(
        if (isDarkTheme &&
            uiState.trueBlackMode &&
            timerUiState.isActive
        ) {
            Color.Black
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "main background color",
    )

    val flashScreenBackgroundColor by
        if (timerUiState.isFinished && uiState.flashScreen) {
            val infiniteTransition = rememberInfiniteTransition(label = "flash")
            infiniteTransition.animateColor(
                initialValue = backgroundColor,
                targetValue = MaterialTheme.colorScheme.onSurface,
                animationSpec =
                infiniteRepeatable(
                    animation = tween(200, easing = FastOutLinearInEasing, delayMillis = 2000),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "flashColor",
            )
        } else {
            rememberUpdatedState(backgroundColor)
        }

    val actionBadgeItemCount = permissionState.count() + if (updateAvailable) 1 else 0
    val interactionSource = remember { MutableInteractionSource() }

    var showNavigationSheet by rememberSaveable { mutableStateOf(false) }
    var showSelectLabelDialog by rememberSaveable { mutableStateOf(false) }
    var showResetBreakBudgetDialog by rememberSaveable { mutableStateOf(false) }

    val showTutorial = uiState.showTutorial
    val isPortrait = isPortrait()

    AnimatedVisibility(
        timerUiState.isReady,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Surface(
                modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        onSurfaceClick()
                    },
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(flashScreenBackgroundColor)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    contentAlignment = Alignment.Center,
                ) {
                    val tutorialModifier =
                        if (showTutorial) Modifier.blur(radius = 4.dp) else Modifier
                    val modifier =
                        Modifier.offset {
                            if (isPortrait) {
                                IntOffset(
                                    0,
                                    yOffset.value.roundToInt(),
                                )
                            } else {
                                IntOffset(yOffset.value.roundToInt(), 0)
                            }
                        }

                    val alphaModifier =
                        Modifier.graphicsLayer {
                            alpha = if (dialControlState.isDragging) 0.38f else 1f
                        }
                    MainTimerView(
                        modifier = alphaModifier.then(modifier),
                        state = dialControlState,
                        gestureModifier = gestureModifier.then(tutorialModifier),
                        timerUiState = timerUiState,
                        displayTime = { displayTimeState.value },
                        timerStyle = timerStyle,
                        domainLabel = label,
                        onStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            coroutineScope.launch {
                                if (!requestAlarmPermission()) {
                                    viewModel.startTimer()
                                }
                            }
                        },
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            coroutineScope.launch {
                                if (!requestAlarmPermission()) {
                                    viewModel.toggleTimer()
                                }
                            }
                        },
                        onLongClick = { navController.navigate(SettingsDest) },
                        onBreakBudgetClick = { showResetBreakBudgetDialog = true },
                    )
                    DialControl(
                        modifier = modifier,
                        state = dialControlState,
                        dialContent = { region ->
                            DialControlButton(
                                enabled = !dialControlState.isDisabled(region),
                                selected = region == dialControlState.selectedOption,
                                region = region,
                            )
                        },
                    )
                    ManualSyncButton(
                        modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp),
                        syncManager = syncManager,
                        visible = syncStatus.serverRunning,
                    )
                    if (showTutorial) {
                        TutorialScreen(
                            onClose = { viewModel.setShowTutorial(false) },
                        )
                    } else {
                        BottomAppBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            hide = hideBottomBar(),
                            onShowSheet = { showNavigationSheet = true },
                            onLabelClick = { showSelectLabelDialog = true },
                            labelData = label.getLabelData(),
                            sessionCountToday = uiState.sessionCountToday,
                            badgeItemCount = actionBadgeItemCount,
                            navController = navController,
                        )
                    }
                }
            }
        }
    }

    if (showNavigationSheet) {
        MainNavigationSheet(
            onHideSheet = { showNavigationSheet = false },
            navController = navController,
            onUpdateClicked = onUpdateClicked,
            actionBadgeCount = actionBadgeItemCount,
            showPro = isFDroid() || !uiState.isPro,
            isUpdateAvailable = updateAvailable,
            wasNotificationPermissionDenied = wasNotificationPermissionDenied,
            onNotificationPermissionGranted = { granted: Boolean ->
                mainViewModel.setNotificationPermissionGranted(granted)
            },
        )
    }

    var showFinishedSessionSheet by remember(timerUiState.isFinished, timerUiState.endTime) {
        mutableStateOf(timerUiState.isFinished && viewModel.isWithinInactivityTimeout())
    }

    // Auto-reset once the inactivity timeout elapses (immediately if already past it, e.g. after
    // foregrounding). A one-shot delay avoids a per-second ticker running while finished.
    LaunchedEffect(timerUiState.isFinished, timerUiState.endTime) {
        if (timerUiState.isFinished) {
            val remaining = TimerManager.AUTOSTART_TIMEOUT - viewModel.currentIdleTime()
            if (remaining > 0) delay(remaining.milliseconds)
            showFinishedSessionSheet = false
            viewModel.resetTimer(actionType = FinishActionType.MANUAL_DO_NOTHING)
        }
    }

    if (showFinishedSessionSheet) {
        FinishedSessionSheet(
            timerUiState = timerUiState,
            idleTime = { idleTimeState.value },
            onHideSheet = { showFinishedSessionSheet = false },
            onNext = {
                viewModel.next()
                // ask for in-app review if the user just completed a focus session
                if (!timerUiState.isBreak) {
                    viewModel.askForReviewIfEligible()
                }
            },
            onReset = {
                viewModel.resetTimer(actionType = FinishActionType.MANUAL_DO_NOTHING)
            },
            onUpdateFinishedSession = { updateDuration, notes ->
                viewModel.updateFinishedSession(updateDuration, notes)
            },
        )
    }

    if (showResetBreakBudgetDialog) {
        ConfirmationDialog(
            title = stringResource(Res.string.main_reset_break_budget),
            onConfirm = {
                viewModel.resetBreakBudget()
                showResetBreakBudgetDialog = false
            },
            onDismiss = { showResetBreakBudgetDialog = false },
        )
    }

    if (showSelectLabelDialog) {
        SelectActiveLabelDialog(
            initialSelectedLabel = timerUiState.label.label.name,
            onDismiss = { showSelectLabelDialog = false },
            onConfirm = { selectedLabels ->
                if (selectedLabels.isNotEmpty()) {
                    val first = selectedLabels.first()
                    if (first != label.label.name) {
                        viewModel.setActiveLabel(first)
                    }
                }
                showSelectLabelDialog = false
            },
            onNavigateToLabels = {
                navController.navigate(LabelsDest)
                showSelectLabelDialog = false
            },
            onNavigateToActiveLabel = {
                navController.navigate(AddEditLabelDest(name = timerUiState.label.getLabelName()))
                showSelectLabelDialog = false
            },
            onNavigateToTimerDurations = {
                navController.navigate(TimerDurationsDest)
                showSelectLabelDialog = false
            },
            onClearLabel = {
                viewModel.setActiveLabel(Label.DEFAULT_LABEL_NAME)
                showSelectLabelDialog = false
            },
        )
    }
}

private enum class SyncAction {
    SYNC,
    OVERWRITE,
    PULL,
}

private enum class SyncFeedback {
    SYNCED,
    OVERWRITTEN,
    PULLED,
    NO_CONNECTIONS,
}

@Composable
private fun ManualSyncButton(
    modifier: Modifier,
    syncManager: SyncManager,
    visible: Boolean,
) {
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<SyncFeedback?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    var pendingAction by remember { mutableStateOf<SyncAction?>(null) }

    fun runAction(action: SyncAction) {
        feedbackJob?.cancel()
        feedbackJob =
            scope.launch {
                val done =
                    when (action) {
                        SyncAction.SYNC -> syncManager.syncNow()
                        SyncAction.OVERWRITE -> syncManager.overwritePeers()
                        SyncAction.PULL -> syncManager.pullFromPeers()
                    }
                feedback =
                    if (done) {
                        when (action) {
                            SyncAction.SYNC -> SyncFeedback.SYNCED
                            SyncAction.OVERWRITE -> SyncFeedback.OVERWRITTEN
                            SyncAction.PULL -> SyncFeedback.PULLED
                        }
                    } else {
                        SyncFeedback.NO_CONNECTIONS
                    }
                delay(SYNC_FEEDBACK_DURATION_MS)
                feedback = null
            }
    }

    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    modifier = Modifier.alpha(0.5f),
                    onClick = { pendingAction = SyncAction.OVERWRITE },
                ) {
                    Icon(
                        imageVector = EvaIcons.Outline.CloudUpload,
                        contentDescription = stringResource(Res.string.main_sync_overwrite),
                    )
                }
                IconButton(
                    modifier = Modifier.alpha(0.5f),
                    onClick = { runAction(SyncAction.SYNC) },
                ) {
                    Icon(
                        imageVector = EvaIcons.Outline.Sync,
                        contentDescription = stringResource(Res.string.main_sync_now),
                    )
                }
                IconButton(
                    modifier = Modifier.alpha(0.5f),
                    onClick = { pendingAction = SyncAction.PULL },
                ) {
                    Icon(
                        imageVector = EvaIcons.Outline.CloudDownload,
                        contentDescription = stringResource(Res.string.main_sync_pull),
                    )
                }
            }
            AnimatedVisibility(visible = feedback != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text =
                    when (feedback) {
                        SyncFeedback.SYNCED -> stringResource(Res.string.main_sync_done)
                        SyncFeedback.OVERWRITTEN -> stringResource(Res.string.main_sync_overwrite_done)
                        SyncFeedback.PULLED -> stringResource(Res.string.main_sync_pull_done)
                        SyncFeedback.NO_CONNECTIONS ->
                            stringResource(Res.string.main_sync_no_connections)
                        null -> ""
                    },
                    modifier = Modifier.padding(end = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }

    pendingAction?.let { action ->
        val isOverwrite = action == SyncAction.OVERWRITE
        ConfirmationDialog(
            title = stringResource(if (isOverwrite) Res.string.main_sync_overwrite else Res.string.main_sync_pull),
            subtitle =
            stringResource(
                if (isOverwrite) Res.string.main_sync_overwrite_desc else Res.string.main_sync_pull_desc,
            ),
            onConfirm = {
                pendingAction = null
                runAction(action)
            },
            onDismiss = { pendingAction = null },
        )
    }
}

private const val SYNC_FEEDBACK_DURATION_MS = 1500L
