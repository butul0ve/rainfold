package com.rainfold.app.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTest {
    private var now = 10.seconds
    private val session = PlaybackSession { now }

    @Test
    fun `play pause resume and stop preserve selected mix`() {
        val mix = NoiseMix(NoiseType.BROWN, 0.7f)
        session.dispatch(PlaybackCommand.UpdateMix(mix))
        assertEquals(PlaybackState.Idle, session.state())
        assertEquals(PlaybackState.Playing(mix, null), session.dispatch(PlaybackCommand.Play))
        assertEquals(PlaybackState.Paused(mix, null), session.dispatch(PlaybackCommand.Pause))
        assertEquals(PlaybackState.Playing(mix, null), session.dispatch(PlaybackCommand.Resume))
        assertEquals(PlaybackState.Idle, session.dispatch(PlaybackCommand.Stop))
        assertEquals(mix, session.mix)
        assertEquals(PlaybackState.Playing(mix, null), session.dispatch(PlaybackCommand.Play))
    }

    @Test
    fun `remaining time excludes pause and catches up without ticks`() {
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Play)
        now += 17.seconds
        assertEquals(
            43.seconds,
            (session.dispatch(PlaybackCommand.Pause) as PlaybackState.Paused).remaining
        )
        now += 2.hours
        assertEquals(43.seconds, (session.state() as PlaybackState.Paused).remaining)
        session.dispatch(PlaybackCommand.Resume)
        now += 42.seconds
        assertEquals(1.seconds, (session.state() as PlaybackState.Playing).remaining)
        now += 1.seconds
        assertEquals(PlaybackState.Idle, session.state())
    }

    @Test
    fun `duplicate commands do not restart or extend a session`() {
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Resume)
        session.dispatch(PlaybackCommand.Pause)
        assertEquals(PlaybackState.Idle, session.state())
        session.dispatch(PlaybackCommand.Play)
        now += 10.seconds
        assertEquals(
            50.seconds,
            (session.dispatch(PlaybackCommand.Play) as PlaybackState.Playing).remaining
        )
        session.dispatch(PlaybackCommand.Pause)
        now += 10.seconds
        session.dispatch(PlaybackCommand.Pause)
        session.dispatch(PlaybackCommand.Play)
        assertEquals(50.seconds, (session.state() as PlaybackState.Paused).remaining)
        session.dispatch(PlaybackCommand.Resume)
        now += 10.seconds
        assertEquals(
            40.seconds,
            (session.dispatch(PlaybackCommand.Resume) as PlaybackState.Playing).remaining
        )
    }

    @Test
    fun `mix updates do not reset deadline and stop starts a fresh session`() {
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Play)
        now += 20.seconds
        val mix = NoiseMix(NoiseType.WHITE, 0.1f)
        assertEquals(
            PlaybackState.Playing(mix, 40.seconds),
            session.dispatch(PlaybackCommand.UpdateMix(mix))
        )
        session.dispatch(PlaybackCommand.Stop)
        assertEquals(PlaybackState.Playing(mix, 60.seconds), session.dispatch(PlaybackCommand.Play))
    }

    @Test
    fun `endless session never expires`() {
        session.dispatch(PlaybackCommand.Play)
        now += 100.hours
        assertNull((session.state() as PlaybackState.Playing).remaining)
    }

    @Test
    fun `overshot deadline expires and next play resets elapsed time`() {
        session.configure(SessionSettings(duration = 1.seconds))
        session.dispatch(PlaybackCommand.Play)
        now += 2.hours
        assertEquals(PlaybackState.Idle, session.dispatch(PlaybackCommand.Pause))
        assertEquals(
            1.seconds,
            (session.dispatch(PlaybackCommand.Play) as PlaybackState.Playing).remaining
        )
    }

    @Test
    fun `settings reject invalid durations and fades`() {
        listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE).forEach { duration ->
            assertTrue(runCatching { SessionSettings(duration = duration) }.exceptionOrNull() is IllegalArgumentException)
        }
        listOf((-1).seconds, Duration.INFINITE).forEach { fade ->
            assertTrue(runCatching { SessionSettings(fadeIn = fade) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { SessionSettings(fadeOut = fade) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `settings can only change while idle`() {
        session.dispatch(PlaybackCommand.Play)
        assertTrue(runCatching { session.configure(SessionSettings()) }.exceptionOrNull() is IllegalStateException)
        session.dispatch(PlaybackCommand.Pause)
        assertTrue(runCatching { session.configure(SessionSettings()) }.exceptionOrNull() is IllegalStateException)
        session.dispatch(PlaybackCommand.Stop)
        session.configure(SessionSettings(duration = 30.seconds))
        assertEquals(
            30.seconds,
            (session.dispatch(PlaybackCommand.Play) as PlaybackState.Playing).remaining
        )
    }

    @Test
    fun `configuration commands cannot change an active or paused deadline`() {
        val settings = SessionSettings(duration = 60.seconds)
        session.dispatch(PlaybackCommand.Configure(settings))
        session.dispatch(PlaybackCommand.Play)
        now += 10.seconds
        session.dispatch(PlaybackCommand.Configure(SessionSettings(duration = 90.seconds)))
        assertEquals(settings, session.settings)
        assertEquals(50.seconds, (session.state() as PlaybackState.Playing).remaining)
        session.dispatch(PlaybackCommand.Pause)
        session.dispatch(PlaybackCommand.Configure(SessionSettings()))
        assertEquals(settings, session.settings)
        session.dispatch(PlaybackCommand.Stop)
        session.dispatch(PlaybackCommand.Configure(SessionSettings()))
        assertNull(session.settings.duration)
    }

    @Test
    fun `play at expiry reports a fresh start even when the previous snapshot was playing`() {
        session.configure(SessionSettings(duration = 1.seconds))
        session.dispatch(PlaybackCommand.Play)
        assertTrue(session.state() is PlaybackState.Playing)
        now += 1.seconds
        val transition = session.transition(PlaybackCommand.Play)
        assertEquals(PlaybackState.Idle, transition.previous)
        assertEquals(PlaybackState.Playing(session.mix, 1.seconds), transition.next)
    }

    @Test
    fun `commands at the deadline cannot leave a zero duration paused session`() {
        listOf(
            PlaybackCommand.Pause, PlaybackCommand.Resume, PlaybackCommand.Stop,
            PlaybackCommand.UpdateMix(NoiseMix(NoiseType.BROWN)),
        ).forEach { command ->
            session.configure(SessionSettings(duration = 1.seconds))
            session.dispatch(PlaybackCommand.Play)
            now += 1.seconds
            assertEquals(
                PlaybackTransition(PlaybackState.Idle, PlaybackState.Idle),
                session.transition(command),
            )
        }
    }

    @Test
    fun `one command reads the monotonic clock once even if time advances on each reading`() {
        var readings = 0
        val advancing = PlaybackSession { readings++.seconds }
        advancing.configure(SessionSettings(duration = 1.seconds))
        assertEquals(
            PlaybackTransition(PlaybackState.Idle, PlaybackState.Playing(advancing.mix, 1.seconds)),
            advancing.transition(PlaybackCommand.Play),
        )
        assertEquals(1, readings)
        assertEquals(
            PlaybackTransition(PlaybackState.Idle, PlaybackState.Playing(advancing.mix, 1.seconds)),
            advancing.transition(PlaybackCommand.Play),
        )
        assertEquals(2, readings)
    }

    @Test
    fun `rapid pause resume stop and resume after stop never resurrect elapsed playback`() {
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Play)
        now += 20.seconds
        repeat(20) {
            session.dispatch(PlaybackCommand.Pause)
            session.dispatch(PlaybackCommand.Resume)
        }
        assertEquals(PlaybackState.Playing(session.mix, 40.seconds), session.state())
        session.dispatch(PlaybackCommand.Stop)
        assertEquals(PlaybackState.Idle, session.dispatch(PlaybackCommand.Resume))
        assertEquals(PlaybackState.Playing(session.mix, 60.seconds), session.dispatch(PlaybackCommand.Play))
    }

    @Test
    fun `absolute renderer deadline excludes pauses and does not slide with observation time`() {
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Play)
        assertEquals(70.seconds, session.deadline)
        now += 20.seconds
        assertEquals(70.seconds, session.deadline)
        session.dispatch(PlaybackCommand.Pause)
        assertNull(session.deadline)
        now += 2.hours
        session.dispatch(PlaybackCommand.Resume)
        assertEquals(now + 40.seconds, session.deadline)
        session.dispatch(PlaybackCommand.Stop)
        assertNull(session.deadline)
    }

}