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
import androidx.media3.session.SessionResult
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.domain.SessionMode
import com.rainfold.app.domain.defaults
import com.rainfold.app.player.putSettings
import com.rainfold.app.player.readSettings
import com.rainfold.app.reminders.BedtimeReminder
import com.rainfold.app.reminders.ReminderSettings
import kotlin.time.Duration
import com.rainfold.app.player.SceneLibraryJson
import com.rainfold.app.player.readMix
import com.rainfold.app.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

data class PlaybackScreenState(
    val mix: NoiseMix = NoiseMix(),
    val playback: PlaybackState = PlaybackState.Idle,
    val connected: Boolean = false,
    val audioFailed: Boolean = false,
    val library: SceneLibrary = SceneLibrary(),
    val settings: SessionSettings = SessionSettings(),
    val sceneError: Boolean = false,
    val amoled: Boolean = false,
    val focusToday: Int = 0,
    val breakRemaining: Duration? = null,
    val reminder: ReminderSettings = ReminderSettings(),
)

@UnstableApi
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val appearance = application.getSharedPreferences("appearance", Application.MODE_PRIVATE)
    private val bedtime = BedtimeReminder(application)
    private val mutableState = MutableStateFlow(PlaybackScreenState(amoled = appearance.getBoolean("amoled", false), reminder = bedtime.settings))
    val state = mutableState.asStateFlow()
    private var encodedLibrary: String? = null
    private var library = SceneLibrary()
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
        bedtime.schedule()
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                update(it.sessionExtras)
            }.onFailure { mutableState.value = mutableState.value.copy(audioFailed = true) }
        }, { command -> Handler(Looper.getMainLooper()).post(command) })
    }

    private fun update(extras: Bundle) {
        val encoded = extras.getString(SceneLibraryJson.KEY)
        if (encoded != encodedLibrary) {
            library = SceneLibraryJson.decode(encoded)
            encodedLibrary = encoded
        }
        val mix = extras.readMix()
        val remaining = extras.getLong("remaining", -1).takeIf { it >= 0 }?.milliseconds
        mutableState.value = PlaybackScreenState(mix, when (extras.getString("status")) {
            "playing" -> PlaybackState.Playing(mix, remaining)
            "paused" -> PlaybackState.Paused(mix, remaining)
            else -> PlaybackState.Idle
        }, true, extras.getBoolean("failed"), library, extras.readSettings(SessionMode.CUSTOM.defaults()),
            sceneError = mutableState.value.sceneError, amoled = mutableState.value.amoled,
            focusToday = extras.getInt("focusToday"),
            breakRemaining = extras.getLong("breakRemaining", -1).takeIf { it >= 0 }?.milliseconds,
            reminder = bedtime.settings)
    }

    fun toggleAmoled() {
        val enabled = !state.value.amoled
        appearance.edit().putBoolean("amoled", enabled).apply()
        mutableState.value = mutableState.value.copy(amoled = enabled)
    }

    fun togglePlayback() {
        controller?.let { if (state.value.playback is PlaybackState.Playing) it.pause() else it.play() }
    }
    fun stop() { controller?.stop() }
    fun selectNoise(type: NoiseType) = send(Bundle().apply { putString("noise", type.name) })
    fun setVolume(volume: Float) = send(Bundle().apply { putFloat("volume", volume.coerceIn(0f, 1f)) })
    fun setRainVolume(volume: Float) = send(Bundle().apply { putFloat("rainVolume", volume.coerceIn(0f, 1f)) })
    fun toggleNoiseMute() = send(Bundle().apply { putBoolean("noiseMuted", !state.value.mix.noiseMuted) })
    fun toggleRainMute() = send(Bundle().apply { putBoolean("rainMuted", !state.value.mix.rainMuted) })
    fun addRain() = send(Bundle().apply { putBoolean("rainEnabled", true) })
    fun removeRain() = send(Bundle().apply { putBoolean("rainEnabled", false) })
    fun configure(settings: SessionSettings) = send(Bundle().apply { putSettings(settings) })
    fun selectMode(mode: SessionMode) = configure(mode.defaults())
    fun configureReminder(settings: ReminderSettings) {
        bedtime.configure(settings)
        mutableState.value = state.value.copy(reminder = bedtime.settings)
    }
    fun selectScene(id: String, play: Boolean) = sceneCommand(if (play) "play" else "select", id)
    fun saveScene(name: String, purpose: ScenePurpose) = sceneCommand("save", name = name, purpose = purpose)
    fun renameScene(id: String, name: String) = sceneCommand("rename", id, name)
    fun copyScene(id: String, name: String) = sceneCommand("copy", id, name)
    fun deleteScene(id: String) = sceneCommand("delete", id)
    fun favoriteScene(id: String) = sceneCommand("favorite", id)
    private fun sceneCommand(operation: String, id: String? = null, name: String? = null, purpose: ScenePurpose? = null) {
        val result = controller?.sendCustomCommand(SessionCommand(PlaybackService.SCENE, Bundle.EMPTY), Bundle().apply {
            putString("operation", operation)
            putString("id", id)
            putString("name", name)
            putString("purpose", purpose?.name)
        }) ?: return
        result.addListener({
            val failed = runCatching { result.get().resultCode != SessionResult.RESULT_SUCCESS }.getOrDefault(true)
            mutableState.value = mutableState.value.copy(sceneError = failed)
        }, { command -> Handler(Looper.getMainLooper()).post(command) })
    }
    private fun send(args: Bundle) { controller?.sendCustomCommand(SessionCommand(PlaybackService.UPDATE, Bundle.EMPTY), args) }
    override fun onCleared() { MediaController.releaseFuture(future) }
}