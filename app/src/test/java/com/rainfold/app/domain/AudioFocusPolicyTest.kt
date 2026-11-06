package com.rainfold.app.domain

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioFocusPolicyTest {
    private var now = 0.seconds
    private val session = PlaybackSession { now }
    private val focus = AudioFocusPolicy()

    @Test
    fun `repeated temporary losses resume once and preserve paused time`() {
        session.configure(SessionSettings(duration = 60.seconds))
        user(PlaybackCommand.Play)
        now += 15.seconds
        transientLoss()
        now += 2.hours
        transientLoss()
        transientLoss()
        gain()
        assertEquals(PlaybackState.Playing(session.mix, 45.seconds), session.state())
        assertFalse(focus.onGain())
    }

    @Test
    fun `volume noise and configuration commands during interruption retain resume intent`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.UpdateMix(session.mix.copy(noiseVolume = 0.2f)))
        transientLoss()
        val mix = session.mix.copy(noiseType = NoiseType.BROWN)
        user(PlaybackCommand.UpdateMix(mix))
        user(PlaybackCommand.Configure(SessionSettings(duration = 30.seconds)))
        gain()
        assertEquals(PlaybackState.Playing(mix, null), session.state())
    }

    @Test
    fun `explicit pause during interruption cancels resumption despite later transient losses`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.Pause)
        transientLoss()
        gain()
        assertEquals(PlaybackState.Paused(session.mix, null), session.state())
    }

    @Test
    fun `stop during interruption prevents gain from starting a new session`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.Stop)
        transientLoss()
        gain()
        assertEquals(PlaybackState.Idle, session.state())
        user(PlaybackCommand.Play)
        assertEquals(PlaybackState.Playing(session.mix, null), session.state())
    }

    @Test
    fun `permanent loss cancels an earlier temporary interruption`() {
        user(PlaybackCommand.Play)
        transientLoss()
        focus.cancelResume()
        session.dispatch(PlaybackCommand.Pause)
        transientLoss()
        gain()
        assertEquals(PlaybackState.Paused(session.mix, null), session.state())
    }

    @Test
    fun `headset pause follows the explicit pause cancellation path`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.Pause)
        user(PlaybackCommand.UpdateMix(session.mix.copy(noiseVolume = 0.8f)))
        gain()
        assertEquals(PlaybackState.Paused(session.mix, null), session.state())
    }

    @Test
    fun `temporary loss while already user paused does not arm resumption`() {
        user(PlaybackCommand.Play)
        user(PlaybackCommand.Pause)
        transientLoss()
        gain()
        assertEquals(PlaybackState.Paused(session.mix, null), session.state())
    }

    @Test
    fun `explicit resume and a later interruption form a new focus cycle`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.Resume)
        assertFalse(focus.onGain())
        transientLoss()
        gain()
        assertEquals(PlaybackState.Playing(session.mix, null), session.state())
    }

    @Test
    fun `ignored duplicate play while interrupted does not discard resume intent`() {
        user(PlaybackCommand.Play)
        transientLoss()
        user(PlaybackCommand.Play)
        gain()
        assertEquals(PlaybackState.Playing(session.mix, null), session.state())
    }

    @Test
    fun `idle or failed playback cannot resume from a pending gain`() {
        user(PlaybackCommand.Play)
        transientLoss()
        session.dispatch(PlaybackCommand.Stop)
        focus.cancelResume()
        gain()
        assertEquals(PlaybackState.Idle, session.state())
    }

    private fun user(command: PlaybackCommand) {
        focus.onUserCommand(command)
        if (session.dispatch(command) is PlaybackState.Playing) focus.cancelResume()
    }

    private fun transientLoss() {
        focus.onTransientLoss(session.state() is PlaybackState.Playing)
        session.dispatch(PlaybackCommand.Pause)
    }

    private fun gain() {
        if (focus.onGain()) session.dispatch(PlaybackCommand.Resume)
    }
}