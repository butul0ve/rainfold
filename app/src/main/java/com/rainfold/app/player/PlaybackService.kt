package com.rainfold.app.player

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.PlaybackController
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackSnapshot
import com.rainfold.app.domain.PlaybackState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bound ownership for stage 0. MediaController and a foreground session arrive in stage 1. */
class PlaybackService : MediaSessionService(), PlaybackController {
    private val player = NoisePlayer()
    private val session = PlaybackSession { SystemClock.elapsedRealtime().milliseconds }
    private val handler = Handler(Looper.getMainLooper())
    private var playbackId = 0
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state = mutableState.asStateFlow()

    inner class LocalBinder : Binder() {
        val controller: PlaybackController get() = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        val mediaBinder = super.onBind(intent)
        return if (intent?.action == ACTION_LOCAL) LocalBinder() else mediaBinder
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null

    override fun dispatch(command: PlaybackCommand) {
        val previous = mutableState.value.playback
        val next = session.dispatch(command)
        try {
            if (next is PlaybackState.Playing) {
                if (previous !is PlaybackState.Playing || previous.mix.noiseType != next.mix.noiseType) {
                    val id = ++playbackId
                    player.play(next.mix) {
                        handler.post { if (id == playbackId) reportAudioFailure() }
                    }
                } else {
                    player.setVolume(next.mix.noiseVolume)
                }
            } else {
                playbackId++
                player.stop()
            }
            mutableState.value = PlaybackSnapshot(session.mix, next)
        } catch (_: RuntimeException) {
            reportAudioFailure()
        }
    }

    private fun reportAudioFailure() {
        playbackId++
        player.stop()
        mutableState.value = PlaybackSnapshot(
            session.mix,
            session.dispatch(PlaybackCommand.Stop),
            audioFailed = true
        )
    }

    override fun onDestroy() {
        playbackId++
        handler.removeCallbacksAndMessages(null)
        player.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL = "com.rainfold.app.BIND_PLAYBACK"
    }
}