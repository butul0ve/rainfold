package com.rainfold.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.PlaybackController
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.player.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackScreenState(
    val mix: NoiseMix = NoiseMix(),
    val playback: PlaybackState = PlaybackState.Idle,
    val connected: Boolean = false,
    val audioFailed: Boolean = false,
)

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PlaybackScreenState())
    val state = mutableState.asStateFlow()
    private var controller: PlaybackController? = null
    private var observation: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val playbackController = (binder as PlaybackService.LocalBinder).controller
            controller = playbackController
            observation?.cancel()
            observation = viewModelScope.launch {
                playbackController.state.collect { snapshot ->
                    mutableState.value = PlaybackScreenState(
                        mix = snapshot.mix,
                        playback = snapshot.playback,
                        connected = true,
                        audioFailed = snapshot.audioFailed,
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            controller = null
            observation?.cancel()
            mutableState.value =
                mutableState.value.copy(playback = PlaybackState.Idle, connected = false)
        }
    }
    private val bound = application.bindService(
        Intent(application, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
        connection,
        Context.BIND_AUTO_CREATE,
    )

    fun togglePlayback() = dispatch(
        if (state.value.playback is PlaybackState.Playing) PlaybackCommand.Stop else PlaybackCommand.Play,
    )

    fun selectNoise(type: NoiseType) = updateMix(state.value.mix.copy(noiseType = type))

    fun setVolume(volume: Float) =
        updateMix(state.value.mix.copy(noiseVolume = volume.coerceIn(0f, 1f)))

    private fun updateMix(mix: NoiseMix) {
        dispatch(PlaybackCommand.UpdateMix(mix))
    }

    private fun dispatch(command: PlaybackCommand) {
        controller?.let { playbackController ->
            playbackController.dispatch(command)
            val snapshot = playbackController.state.value
            mutableState.value =
                PlaybackScreenState(snapshot.mix, snapshot.playback, true, snapshot.audioFailed)
        }
    }

    override fun onCleared() {
        observation?.cancel()
        if (bound) getApplication<Application>().unbindService(connection)
        controller = null
    }
}