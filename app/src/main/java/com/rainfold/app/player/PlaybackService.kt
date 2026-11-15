package com.rainfold.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.core.content.edit
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rainfold.app.MainActivity
import com.rainfold.app.R
import com.rainfold.app.domain.AudioFocusPolicy
import com.rainfold.app.domain.MixPreferences
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.domain.SessionMode
import com.rainfold.app.domain.SessionPreferences
import com.rainfold.app.domain.FocusProgress
import com.rainfold.app.domain.LocalSessionTime
import com.rainfold.app.dsp.CompletionCue
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import com.rainfold.app.dsp.PcmWave
import com.rainfold.app.localization.LanguageSettings
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val engine by lazy { NoisePlayer { assets.open("rain.wav").use { PcmWave.read(it) } } }
    private val clockSession = PlaybackSession { SystemClock.elapsedRealtime().milliseconds }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: SessionPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var wakeLock: PowerManager.WakeLock
    private val focusPolicy = AudioFocusPolicy()
    private var hasFocus = false
    private var focusRequested = false
    // A domain snapshot can expire before the renderer has been told to stop.
    private var playbackActive = false
    private var cuePlaying = false
    private var failed = false
    private var focusProgress = FocusProgress()
    private val bootCount by lazy { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }
    private var encodedLibrary = SceneLibraryJson.encode(SceneLibrary())
    private var library = SceneLibrary()
        set(value) {
            field = value
            encodedLibrary = SceneLibraryJson.encode(value)
        }
    private var releaseGeneration = 0
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val languageSettings by lazy { LanguageSettings(this) }
    private val languageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { adapter.refresh() }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) dispatch(PlaybackCommand.Pause)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (clockSession.state() is PlaybackState.Idle && (playbackActive || clockSession.hasCompletion)) {
                dispatch(PlaybackCommand.Stop, naturalEnd = true)
            } else {
                publish()
                observeSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        clockSession.dispatch(PlaybackCommand.UpdateMix(MixPreferences.decode(preferences.all)))
        clockSession.configure(SessionPreferences.decode(preferences.all))
        focusProgress = FocusProgress(preferences.getString("focusDay", "").orEmpty(),
            preferences.getInt("focusCount", 0).coerceAtLeast(0),
            preferences.getLong("breakDeadline", -1).takeIf { it >= 0 }, preferences.getInt("breakBoot", -1))
        library = SceneLibraryJson.decode(preferences.getString(SceneLibraryJson.KEY, null))
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Rainfold:Playback"
        )
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (!focusRequested) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        if (focusPolicy.onGain()) dispatch(PlaybackCommand.Resume)
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        focusPolicy.onTransientLoss(clockSession.state() is PlaybackState.Playing)
                        hasFocus = false
                        dispatch(PlaybackCommand.Pause, abandonFocus = false)
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        dispatch(PlaybackCommand.Pause)
                    }
                }
            }, handler).build()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        adapter = SessionPlayer()
        mediaSession = MediaSession.Builder(this, adapter)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(SessionCommand(UPDATE, Bundle.EMPTY))
                                .add(SessionCommand(SCENE, Bundle.EMPTY)).build()
                        ).build()
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == SCENE) {
                        return try {
                            handleSceneCommand(args)
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        } catch (_: IllegalArgumentException) {
                            Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                    }
                    if (customCommand.customAction != UPDATE) return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                    )
                    return try {
                        if (MixPreferences.encode(clockSession.mix).keys.any(args::containsKey)) {
                            dispatch(PlaybackCommand.UpdateMix(args.readMix(clockSession.mix)))
                        }
                        if (SessionPreferences.encode(clockSession.settings).keys.any(args::containsKey)) {
                            handleSettingsCommand(args)
                        }
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (_: IllegalArgumentException) {
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }
            }).build()
        languageSettings.observe(languageListener)
        publish()
        observeSession()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    internal fun handleSettingsCommand(args: Bundle) {
        dispatch(PlaybackCommand.Configure(args.readSettings(clockSession.settings)))
    }

    private fun dispatch(command: PlaybackCommand, abandonFocus: Boolean = true, naturalEnd: Boolean = false) {
        if (abandonFocus) focusPolicy.onUserCommand(command)
        failed = false
        try {
            val (previous, next) = clockSession.transition(command)
            val completion = clockSession.takeCompletion()
            val completed = completion?.settings
            val focusCompleted = completed?.mode == SessionMode.FOCUS && completed.duration != null
            if (focusCompleted) {
                val now = SystemClock.elapsedRealtime().milliseconds
                val deadline = checkNotNull(completion).deadline
                focusProgress = focusProgress.complete(LocalSessionTime.completedDay(deadline, now, ZonedDateTime.now()),
                    deadline.inWholeMilliseconds, checkNotNull(completed).breakDuration, bootCount)
                saveProgress()
            }
            if ((command == PlaybackCommand.Stop && !naturalEnd) ||
                (command is PlaybackCommand.Configure && clockSession.settings.mode != SessionMode.FOCUS)) {
                focusProgress = focusProgress.clearBreak()
                saveProgress()
            }
            val starting = next is PlaybackState.Playing && previous !is PlaybackState.Playing
            if (starting) {
                // Android 15+ requires foreground eligibility before requesting audio focus.
                startService(Intent(this, PlaybackService::class.java))
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        "playback",
                        languageSettings.localizedContext().getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
                startForeground(
                    1001, Notification.Builder(this, "playback")
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(languageSettings.localizedContext().getString(R.string.app_name))
                        .setContentText(languageSettings.localizedContext().getString(R.string.play)).build()
                )
                if (!hasFocus) {
                    hasFocus =
                        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    focusRequested = hasFocus
                    check(hasFocus) { "Audio focus denied" }
                }
            }
            if (next is PlaybackState.Playing) {
                if (starting) {
                    cuePlaying = false
                    focusProgress = focusProgress.clearBreak()
                    saveProgress()
                    releaseGeneration++
                    focusPolicy.cancelResume()
                    playbackActive = true
                    if (!wakeLock.isHeld) wakeLock.acquire()
                    engine.play(
                        next.mix,
                        if (previous is PlaybackState.Idle) clockSession.settings.fadeIn.inWholeMilliseconds else 50,
                        deadline = clockSession.deadline?.inWholeMilliseconds,
                        fadeOutMs = clockSession.settings.fadeOut.inWholeMilliseconds,
                        fadeFromSilence = previous is PlaybackState.Idle,
                        sequentialFade = clockSession.settings.mode == SessionMode.SLEEP,
                    ) { id ->
                        handler.post { if (engine.isCurrentRenderer(id)) audioFailure() }
                    }
                } else engine.update(next.mix)
            } else {
                if (next is PlaybackState.Idle) focusPolicy.cancelResume()
                if (playbackActive || cuePlaying || command == PlaybackCommand.Stop ||
                    (command == PlaybackCommand.Pause && abandonFocus)
                ) {
                    val cue = focusCompleted && completed.completionCue && hasFocus && playbackActive &&
                        command != PlaybackCommand.Pause && (command != PlaybackCommand.Stop || naturalEnd) &&
                        SystemClock.elapsedRealtime() - checkNotNull(completion).deadline.inWholeMilliseconds <= 1000
                    if (cue) engine.playCompletionCue()
                    silenceAndRelease(abandonFocus || next is PlaybackState.Idle, if (cue) CompletionCue.DURATION_MS else 0)
                }
            }
            if (command is PlaybackCommand.UpdateMix || command is PlaybackCommand.Configure) saveSettings()
            publish()
            observeSession()
        } catch (_: RuntimeException) {
            audioFailure()
        }
    }

    private fun observeSession() {
        handler.removeCallbacks(tick)
        val playback = clockSession.state()
        val active = playback is PlaybackState.Playing || clockSession.hasCompletion ||
            (playback is PlaybackState.Paused && clockSession.settings.endAtMinute != null) ||
            (focusProgress.breakRemaining(SystemClock.elapsedRealtime(), bootCount)?.isPositive() == true)
        handler.postDelayed(tick, if (active) 250 else 60_000)
    }

    private fun saveProgress() {
        preferences.edit {
            putString("focusDay", focusProgress.day)
            putInt("focusCount", focusProgress.count)
            putLong("breakDeadline", focusProgress.breakDeadlineMs ?: -1)
            putInt("breakBoot", focusProgress.bootCount)
        }
    }

    private fun silenceAndRelease(abandonFocus: Boolean, cueDelayMs: Long = 0) {
        playbackActive = false
        cuePlaying = cueDelayMs > 0
        handler.removeCallbacks(tick)
        val id = ++releaseGeneration
        fun drain() {
            if (releaseGeneration != id) return
            val drainMs = engine.fadeToSilence()
            handler.postDelayed({
                if (releaseGeneration == id) {
                    engine.stop()
                    cuePlaying = false
                    if (wakeLock.isHeld) wakeLock.release()
                    if (abandonFocus) {
                        audioManager.abandonAudioFocusRequest(focusRequest)
                        hasFocus = false
                        focusRequested = false
                    }
                    if (clockSession.state() is PlaybackState.Idle) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
            }, drainMs)
        }
        if (cueDelayMs > 0) handler.postDelayed({ drain() }, cueDelayMs) else drain()
    }

    private fun audioFailure() {
        focusPolicy.cancelResume()
        clockSession.dispatch(PlaybackCommand.Stop)
        clockSession.takeCompletion()
        silenceAndRelease(true)
        failed = true
        publish()
    }

    /** All scene edits run on the session looper and capture the service's authoritative mix. */
    internal fun handleSceneCommand(args: Bundle) {
        val id = args.getString("id").orEmpty()
        when (args.getString("operation")) {
            "select", "play" -> {
                library = library.select(id)
                dispatch(PlaybackCommand.UpdateMix(checkNotNull(library.selected).mix))
                if (args.getString("operation") == "play") {
                    when (clockSession.state()) {
                        is PlaybackState.Paused -> dispatch(PlaybackCommand.Resume)
                        PlaybackState.Idle -> dispatch(PlaybackCommand.Play)
                        is PlaybackState.Playing -> Unit
                    }
                }
            }
            "save" -> library = library.save("user:${UUID.randomUUID()}",
                args.getString("name").orEmpty(), clockSession.mix,
                ScenePurpose.valueOf(args.getString("purpose").orEmpty()))
            "rename" -> library = library.rename(id, args.getString("name").orEmpty())
            "copy" -> {
                val source = requireNotNull(library.scenes.find { it.id == id })
                library = library.save("user:${UUID.randomUUID()}", args.getString("name").orEmpty(),
                    source.mix, source.purpose).copy(selectedId = library.selectedId)
            }
            "delete" -> library = library.delete(id)
            "favorite" -> library = library.toggleFavorite(id)
            else -> throw IllegalArgumentException("Unknown scene operation")
        }
        saveSettings()
        publish()
    }

    private fun saveSettings() {
        val edit = preferences.edit()
        MixPreferences.encode(clockSession.mix).forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
            }
        }
        SessionPreferences.encode(clockSession.settings).forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
            }
        }
        edit.putString(SceneLibraryJson.KEY, encodedLibrary).apply()
    }

    private fun publish() {
        adapter.refresh()
        val state = clockSession.state()
        mediaSession?.setSessionExtras(Bundle().apply {
            putSettings(clockSession.settings)
            putInt("focusToday", focusProgress.countToday(LocalDate.now().toString()))
            putLong("breakRemaining", focusProgress.breakRemaining(SystemClock.elapsedRealtime(), bootCount)?.inWholeMilliseconds ?: -1)
            putMix(clockSession.mix)
            putString(SceneLibraryJson.KEY, encodedLibrary)
            putString(
                "status", when (state) {
                    is PlaybackState.Playing -> "playing"; is PlaybackState.Paused -> "paused"; else -> "idle"
                }
            )
            putLong(
                "remaining", when (state) {
                    is PlaybackState.Playing -> state.remaining; is PlaybackState.Paused -> state.remaining; else -> null
                }?.inWholeMilliseconds ?: -1
            )
            putLong("duration", clockSession.settings.duration?.inWholeMilliseconds ?: 0)
            putLong("fadeIn", clockSession.settings.fadeIn.inWholeMilliseconds)
            putLong("fadeOut", clockSession.settings.fadeOut.inWholeMilliseconds)
            putBoolean("failed", failed)
        })
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        dispatch(PlaybackCommand.Stop)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adapter.refresh()
    }

    override fun onDestroy() {
        languageSettings.stopObserving(languageListener)
        releaseGeneration++
        handler.removeCallbacksAndMessages(null)
        engine.stop()
        if (wakeLock.isHeld) wakeLock.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
        unregisterReceiver(noisyReceiver)
        mediaSession?.release()
        adapter.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private inner class SessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        fun refresh() = invalidateState()
        override fun getState(): State {
            val localized = languageSettings.localizedContext()
            val playback = clockSession.state()
            val noiseTitle = localized.getString(
                when (clockSession.mix.noiseType) {
                    NoiseType.WHITE -> R.string.white_noise
                    NoiseType.PINK -> R.string.pink_noise
                    NoiseType.BROWN -> R.string.brown_noise
                }
            )
            val mixTitle = if (clockSession.mix.rainEnabled) {
                localized.getString(R.string.noise_and_rain, noiseTitle)
            } else noiseTitle
            val title = library.selected?.let { scene ->
                val name = localized.sceneTitle(scene)
                if (scene.mix == clockSession.mix) name else localized.getString(R.string.scene_modified, name)
            } ?: mixTitle
            return State.Builder().setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE
                ).build()
            )
                .setPlayWhenReady(
                    playback is PlaybackState.Playing,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                )
                .setPlaybackState(if (playback is PlaybackState.Idle) Player.STATE_IDLE else Player.STATE_READY)
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder("noise").setMediaItem(
                            MediaItem.Builder()
                                .setMediaId("noise").setMediaMetadata(
                                    MediaMetadata.Builder().setTitle(title)
                                        .setArtist(localized.getString(R.string.app_name)).build()
                                ).build()
                        ).build()
                    )
                )
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            dispatch(if (!playWhenReady) PlaybackCommand.Pause else if (clockSession.state() is PlaybackState.Paused) PlaybackCommand.Resume else PlaybackCommand.Play)
            return Futures.immediateVoidFuture()
        }

        // PlaybackService releases the engine and session before releasing this adapter.
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
        override fun handleStop(): ListenableFuture<*> {
            dispatch(PlaybackCommand.Stop)
            return Futures.immediateVoidFuture()
        }
    }

    companion object {
        const val SCENE = "com.rainfold.app.SCENE"
        const val UPDATE = "com.rainfold.app.UPDATE"
    }
}