package com.rainfold.app.domain

import kotlin.time.Duration

/** Null duration means endless. Fades are applied by the audio renderer. */
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
    data class Configure(val settings: SessionSettings) : PlaybackCommand
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Stop : PlaybackCommand
    data class UpdateMix(val mix: NoiseMix) : PlaybackCommand
}

data class PlaybackTransition(val previous: PlaybackState, val next: PlaybackState)

/** Clock must be monotonic; paused time does not count toward the session. */
class PlaybackSession(private val now: () -> Duration) {
    var mix: NoiseMix = NoiseMix()
        private set
    var settings: SessionSettings = SessionSettings()
        private set
    private var status = Status.IDLE
    private var elapsed = Duration.ZERO
    private var startedAt = Duration.ZERO

    /** Absolute monotonic deadline, shared with the renderer without adding command setup time. */
    val deadline: Duration?
        get() = if (status == Status.PLAYING) settings.duration?.let { startedAt + it - elapsed } else null

    fun configure(settings: SessionSettings) {
        check(status == Status.IDLE)
        this.settings = settings
    }

    fun state(): PlaybackState = state(now())

    private fun state(at: Duration): PlaybackState {
        val played = elapsed + if (status == Status.PLAYING) at - startedAt else Duration.ZERO
        val remaining = settings.duration?.let { (it - played).coerceAtLeast(Duration.ZERO) }
        if (status == Status.PLAYING && remaining == Duration.ZERO) status = Status.IDLE
        return when (status) {
            Status.IDLE -> PlaybackState.Idle
            Status.PLAYING -> PlaybackState.Playing(mix, remaining)
            Status.PAUSED -> PlaybackState.Paused(mix, remaining)
        }
    }

    fun dispatch(command: PlaybackCommand): PlaybackState = transition(command).next

    /** Expiry and the command share one clock reading, including at the exact deadline. */
    fun transition(command: PlaybackCommand): PlaybackTransition {
        val at = now()
        val previous = state(at)
        when (command) {
            is PlaybackCommand.Configure -> if (status == Status.IDLE) configure(command.settings)

            PlaybackCommand.Play -> if (status == Status.IDLE) {
                elapsed = Duration.ZERO
                startedAt = at
                status = Status.PLAYING
            }

            PlaybackCommand.Pause -> if (status == Status.PLAYING) {
                elapsed += at - startedAt
                status = Status.PAUSED
            }

            PlaybackCommand.Resume -> if (status == Status.PAUSED) {
                startedAt = at
                status = Status.PLAYING
            }

            PlaybackCommand.Stop -> {
                status = Status.IDLE
                elapsed = Duration.ZERO
            }

            is PlaybackCommand.UpdateMix -> mix = command.mix
        }
        return PlaybackTransition(previous, state(at))
    }

    private enum class Status { IDLE, PLAYING, PAUSED }
}