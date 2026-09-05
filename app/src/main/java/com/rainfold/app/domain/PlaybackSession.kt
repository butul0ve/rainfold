package com.rainfold.app.domain

import kotlin.time.Duration

/** Null duration means endless. Timing values are contracts; audio envelopes arrive in stage 1. */
data class SessionSettings(
    val duration: Duration? = null,
    val fadeIn: Duration = Duration.ZERO,
    val fadeOut: Duration = Duration.ZERO,
) {
    init {
        require(duration == null || (duration.isFinite() && duration > Duration.ZERO))
        require(fadeIn.isFinite() && fadeIn >= Duration.ZERO)
        require(fadeOut.isFinite() && fadeOut >= Duration.ZERO)
    }
}

sealed interface PlaybackCommand {
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Stop : PlaybackCommand
    data class UpdateMix(val mix: NoiseMix) : PlaybackCommand
}

/** Clock must be monotonic; paused time does not count toward the session. */
class PlaybackSession(private val now: () -> Duration) {
    var mix: NoiseMix = NoiseMix()
        private set
    var settings: SessionSettings = SessionSettings()
        private set
    private var status = Status.IDLE
    private var elapsed = Duration.ZERO
    private var startedAt = Duration.ZERO

    fun configure(settings: SessionSettings) {
        check(status == Status.IDLE)
        this.settings = settings
    }

    fun state(): PlaybackState {
        val played = elapsed + if (status == Status.PLAYING) now() - startedAt else Duration.ZERO
        val remaining = settings.duration?.let { (it - played).coerceAtLeast(Duration.ZERO) }
        if (status == Status.PLAYING && remaining == Duration.ZERO) status = Status.IDLE
        return when (status) {
            Status.IDLE -> PlaybackState.Idle
            Status.PLAYING -> PlaybackState.Playing(mix, remaining)
            Status.PAUSED -> PlaybackState.Paused(mix, remaining)
        }
    }

    fun dispatch(command: PlaybackCommand): PlaybackState {
        state()
        when (command) {
            PlaybackCommand.Play -> if (status == Status.IDLE) {
                elapsed = Duration.ZERO
                startedAt = now()
                status = Status.PLAYING
            }

            PlaybackCommand.Pause -> if (status == Status.PLAYING) {
                elapsed += now() - startedAt
                status = Status.PAUSED
            }

            PlaybackCommand.Resume -> if (status == Status.PAUSED) {
                startedAt = now()
                status = Status.PLAYING
            }

            PlaybackCommand.Stop -> {
                status = Status.IDLE
                elapsed = Duration.ZERO
            }

            is PlaybackCommand.UpdateMix -> mix = command.mix
        }
        return state()
    }

    private enum class Status { IDLE, PLAYING, PAUSED }
}