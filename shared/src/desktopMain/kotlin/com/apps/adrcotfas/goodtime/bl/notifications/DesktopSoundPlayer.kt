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
package com.apps.adrcotfas.goodtime.bl.notifications

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.bl.TimerType
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.data.settings.SoundData
import com.apps.adrcotfas.goodtime.settings.notifications.toSoundData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.concurrent.Volatile
import goodtime_productivity.shared.generated.resources.Res

/**
 * Plays bundled WAV sounds via javax.sound.sampled.
 * Sound files are packaged under composeResources/files/sounds/.
 */
class DesktopSoundPlayer(
    private val settingsRepo: SettingsRepository,
    private val logger: Logger,
    private val ioScope: CoroutineScope,
    private val playerScope: CoroutineScope,
) : SoundPlayer {
    private var job: Job? = null
    private val playbackMutex = Mutex()
    private var clip: Clip? = null

    @Volatile
    private var state = SoundPlayerState()

    @Volatile
    private var alarmBreak = false

    init {
        ioScope.launch {
            settingsRepo.settings.collect { settings ->
                state =
                    state.copy(
                        workRingTone = toSoundData(settings.workFinishedSound),
                        breakRingTone = toSoundData(settings.breakFinishedSound),
                        loop = settings.insistentNotification,
                    )
                alarmBreak = settings.breakEndAlarm
            }
        }
    }

    override fun close() {
        playerScope.launch {
            job?.cancelAndJoin()
            stopInternal()
        }
    }

    override fun play(timerType: TimerType) {
        val soundData =
            when (timerType) {
                TimerType.FOCUS -> state.workRingTone
                TimerType.BREAK, TimerType.LONG_BREAK -> state.breakRingTone
            }
        val loop =
            when (timerType) {
                TimerType.FOCUS -> state.loop
                TimerType.BREAK, TimerType.LONG_BREAK -> state.loop || alarmBreak
            }
        play(soundData, loop)
    }

    override fun play(
        soundData: SoundData,
        loop: Boolean,
    ) {
        logger.i { "play() called with soundData=${soundData.name}, uri=${soundData.uriString}, loop=$loop" }
        playerScope.launch {
            job?.cancelAndJoin()
            job =
                playerScope.launch {
                    playbackMutex.withLock {
                        stopInternal()
                        playInternal(soundData, loop)
                    }
                }
        }
    }

    override fun stop() {
        playerScope.launch {
            job?.cancelAndJoin()
            job =
                playerScope.launch {
                    playbackMutex.withLock {
                        stopInternal()
                    }
                }
        }
    }

    private fun stopInternal() {
        clip?.stop()
        clip?.close()
        clip = null
    }

    private suspend fun playInternal(
        soundData: SoundData,
        loop: Boolean,
    ) {
        if (soundData.isSilent) {
            logger.i { "Sound is silent, skipping playback" }
            return
        }

        val fileName = soundData.uriString.takeUnless { it.isBlank() } ?: DEFAULT_SOUND
        val bytes =
            readSound(fileName)
                ?: readSound(DEFAULT_SOUND)
                ?: run {
                    logger.e(Exception("Sound not found")) { "Could not load sound: $fileName" }
                    return
                }

        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val format = stream.format
            val info = DataLine.Info(Clip::class.java, format)
            val newClip = AudioSystem.getLine(info) as Clip
            newClip.open(stream)
            newClip.loop(if (loop) Clip.LOOP_CONTINUOUSLY else 0)
            newClip.start()
            clip = newClip
            logger.i { "Playing sound: $fileName" }
        } catch (e: Exception) {
            logger.e(e) { "Failed to play sound: $fileName" }
        }
    }

    private suspend fun readSound(fileName: String): ByteArray? =
        runCatching { Res.readBytes("files/sounds/$fileName") }
            .getOrNull()
            ?: runCatching { Res.readBytes("files/$fileName") }
                .getOrNull()

    private companion object {
        const val DEFAULT_SOUND = "positive_chime.wav"
    }
}
