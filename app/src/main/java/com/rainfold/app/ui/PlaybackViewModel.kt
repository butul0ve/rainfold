package com.rainfold.app.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

data class PlaybackScreenState(
    val mix: NoiseMix = NoiseMix(),
    val playback: PlaybackState = PlaybackState.Idle,
    val connected: Boolean = false,
    val audioFailed: Boolean = false,
    val settings: SessionSettings = SessionSettings(),
)

@UnstableApi
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PlaybackScreenState())
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private val future = MediaController.Builder(application, SessionToken(application,
        ComponentName(application, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) = update(extras)
            override fun onDisconnected(controller: MediaController) {
                this@PlaybackViewModel.controller = null
                mutableState.value = mutableState.value.copy(connected = false, playback = PlaybackState.Idle)
            }
        }).buildAsync()

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                update(it.sessionExtras)
            }.onFailure { mutableState.value = mutableState.value.copy(audioFailed = true) }
        }, { command -> Handler(Looper.getMainLooper()).post(command) })
    }

    private fun update(extras: Bundle) {
        val mix = NoiseMix(NoiseType.valueOf(extras.getString("noise") ?: "PINK"), extras.getFloat("volume", 0.5f))
        val remaining = extras.getLong("remaining", -1).takeIf { it >= 0 }?.milliseconds
        mutableState.value = PlaybackScreenState(mix, when (extras.getString("status")) {
            "playing" -> PlaybackState.Playing(mix, remaining)
            "paused" -> PlaybackState.Paused(mix, remaining)
            else -> PlaybackState.Idle
        }, true, extras.getBoolean("failed"), SessionSettings(
            extras.getLong("duration").takeIf { it > 0 }?.milliseconds,
            extras.getLong("fadeIn").milliseconds, extras.getLong("fadeOut").milliseconds))
    }

    fun togglePlayback() {
        controller?.let { if (state.value.playback is PlaybackState.Playing) it.pause() else it.play() }
    }
    fun stop() { controller?.stop() }
    fun selectNoise(type: NoiseType) = updateMix(state.value.mix.copy(noiseType = type))
    fun setVolume(volume: Float) = updateMix(state.value.mix.copy(noiseVolume = volume.coerceIn(0f, 1f)))
    private fun updateMix(mix: NoiseMix) = send(Bundle().apply {
        putString("noise", mix.noiseType.name)
        putFloat("volume", mix.noiseVolume)
    })
    fun configure(settings: SessionSettings) = send(Bundle().apply {
        putLong("duration", settings.duration?.inWholeMilliseconds ?: 0)
        putLong("fadeIn", settings.fadeIn.inWholeMilliseconds)
        putLong("fadeOut", settings.fadeOut.inWholeMilliseconds)
    })
    private fun send(args: Bundle) { controller?.sendCustomCommand(SessionCommand(PlaybackService.UPDATE, Bundle.EMPTY), args) }
    override fun onCleared() { MediaController.releaseFuture(future) }
}