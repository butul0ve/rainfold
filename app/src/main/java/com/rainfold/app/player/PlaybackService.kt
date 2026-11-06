package com.rainfold.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
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
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionSettings
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val engine = NoisePlayer()
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
    private var failed = false
    private var releaseGeneration = 0
    // Pause/Resume cancels a pending release, but must not discard renderer failures.
    private var rendererGeneration = 0
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) dispatch(PlaybackCommand.Pause)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (clockSession.state() is PlaybackState.Idle) dispatch(PlaybackCommand.Stop)
            else {
                publish()
                handler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val type = runCatching {
            NoiseType.valueOf(
                preferences.getString(
                    "noise",
                    "PINK"
                )!!
            )
        }.getOrDefault(NoiseType.PINK)
        val volume =
            preferences.getFloat("volume", 0.5f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        clockSession.dispatch(PlaybackCommand.UpdateMix(NoiseMix(type, volume)))
        clockSession.configure(
            SessionSettings(
                preferences.getLong("duration", 0).takeIf { it > 0 }?.milliseconds,
                preferences.getLong("fadeIn", 1000).coerceIn(0, 60_000).milliseconds,
                preferences.getLong("fadeOut", 10_000).coerceIn(0, 60_000).milliseconds,
            )
        )
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
                                .add(SessionCommand(UPDATE, Bundle.EMPTY)).build()
                        ).build()
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != UPDATE) return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                    )
                    return try {
                        if (args.containsKey("noise")) dispatch(
                            PlaybackCommand.UpdateMix(
                                clockSession.mix.copy(
                                    noiseType = NoiseType.valueOf(requireNotNull(args.getString("noise"))),
                                    noiseVolume = args.getFloat(
                                        "volume",
                                        clockSession.mix.noiseVolume
                                    )
                                )
                            )
                        )
                        if (args.containsKey("duration")) dispatch(
                            PlaybackCommand.Configure(
                                SessionSettings(
                                    args.getLong("duration").takeIf { it > 0 }?.milliseconds,
                                    args.getLong("fadeIn").coerceIn(0, 60_000).milliseconds,
                                    args.getLong("fadeOut").coerceIn(0, 60_000).milliseconds
                                )
                            )
                        )
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (_: IllegalArgumentException) {
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }
            }).build()
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun dispatch(command: PlaybackCommand, abandonFocus: Boolean = true) {
        if (abandonFocus) focusPolicy.onUserCommand(command)
        failed = false
        try {
            val (previous, next) = clockSession.transition(command)
            val starting = next is PlaybackState.Playing && previous !is PlaybackState.Playing
            if (starting) {
                // Android 15+ requires foreground eligibility before requesting audio focus.
                startService(Intent(this, PlaybackService::class.java))
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        "playback",
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
                startForeground(
                    1001, Notification.Builder(this, "playback")
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.play)).build()
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
                    releaseGeneration++
                    focusPolicy.cancelResume()
                    val id = rendererGeneration
                    playbackActive = true
                    if (!wakeLock.isHeld) wakeLock.acquire()
                    engine.play(
                        next.mix,
                        if (previous is PlaybackState.Idle) clockSession.settings.fadeIn.inWholeMilliseconds else 50,
                        deadline = clockSession.deadline?.inWholeMilliseconds,
                        fadeOutMs = clockSession.settings.fadeOut.inWholeMilliseconds,
                    ) {
                        handler.post { if (id == rendererGeneration) audioFailure() }
                    }
                } else engine.update(next.mix)
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 250)
            } else {
                if (next is PlaybackState.Idle) focusPolicy.cancelResume()
                if (playbackActive || command == PlaybackCommand.Stop ||
                    (command == PlaybackCommand.Pause && abandonFocus)
                ) {
                    silenceAndRelease(abandonFocus || next is PlaybackState.Idle)
                }
            }
            if (command is PlaybackCommand.UpdateMix || command is PlaybackCommand.Configure) saveSettings()
            publish()
        } catch (_: RuntimeException) {
            audioFailure()
        }
    }

    private fun silenceAndRelease(abandonFocus: Boolean) {
        playbackActive = false
        handler.removeCallbacks(tick)
        val drainMs = engine.fadeToSilence()
        val id = ++releaseGeneration
        handler.postDelayed({
            if (releaseGeneration == id) {
                engine.stop()
                rendererGeneration++
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

    private fun audioFailure() {
        focusPolicy.cancelResume()
        clockSession.dispatch(PlaybackCommand.Stop)
        silenceAndRelease(true)
        failed = true
        publish()
    }

    private fun saveSettings() {
        preferences.edit().putString("noise", clockSession.mix.noiseType.name)
            .putFloat("volume", clockSession.mix.noiseVolume)
            .putLong("duration", clockSession.settings.duration?.inWholeMilliseconds ?: 0)
            .putLong("fadeIn", clockSession.settings.fadeIn.inWholeMilliseconds)
            .putLong("fadeOut", clockSession.settings.fadeOut.inWholeMilliseconds).apply()
    }

    private fun publish() {
        adapter.refresh()
        val state = clockSession.state()
        mediaSession?.setSessionExtras(Bundle().apply {
            putString("noise", clockSession.mix.noiseType.name)
            putFloat("volume", clockSession.mix.noiseVolume)
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
        if (clockSession.state() !is PlaybackState.Playing) stopSelf()
    }

    override fun onDestroy() {
        releaseGeneration++
        rendererGeneration++
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
            val playback = clockSession.state()
            val title = getString(
                when (clockSession.mix.noiseType) {
                    NoiseType.WHITE -> R.string.white_noise
                    NoiseType.PINK -> R.string.pink_noise
                    NoiseType.BROWN -> R.string.brown_noise
                }
            )
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
                                        .setArtist(getString(R.string.app_name)).build()
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

        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
        override fun handleStop(): ListenableFuture<*> {
            dispatch(PlaybackCommand.Stop)
            return Futures.immediateVoidFuture()
        }
    }

    companion object {
        const val UPDATE = "com.rainfold.app.UPDATE"
    }
}