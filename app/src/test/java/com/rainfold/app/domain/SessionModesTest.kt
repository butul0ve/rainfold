package com.rainfold.app.domain

import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModesTest {
    @Test
    fun `mode defaults are silent opt in and legacy preferences retain their timer`() {
        assertEquals(30.minutes, SessionMode.SLEEP.defaults().duration)
        assertEquals(25.minutes, SessionMode.FOCUS.defaults().duration)
        SessionMode.entries.forEach {
            assertFalse(it.defaults().completionCue)
            assertEquals(Duration.ZERO, it.defaults().breakDuration)
        }
        assertEquals(SessionSettings(60.minutes, 5.seconds, 10.seconds),
            SessionPreferences.decode(mapOf("duration" to 3_600_000L, "fadeIn" to 5000L, "fadeOut" to 10_000L)))
    }

    @Test
    fun `all session settings survive persistence and partial updates retain mode`() {
        listOf(
            SessionMode.FOCUS.defaults().copy(completionCue = true, breakDuration = 5.minutes),
            SessionMode.SLEEP.defaults().copy(duration = null, endAtMinute = 7 * 60),
        ).forEach { settings ->
            assertEquals(settings, SessionPreferences.decode(SessionPreferences.encode(settings)))
            assertEquals(settings.copy(fadeIn = 10.seconds), SessionPreferences.decode(mapOf("fadeIn" to 10_000L), settings))
        }
    }

    @Test
    fun `invalid stored mode and values are safe and bounded`() {
        val settings = SessionPreferences.decode(mapOf("mode" to "NEW_MODE", "endAtMinute" to 9999,
            "breakDuration" to Long.MAX_VALUE, "fadeOut" to -1L, "completionCue" to "true"))
        assertEquals(SessionMode.CUSTOM, settings.mode)
        assertNull(settings.endAtMinute)
        assertEquals(15.minutes, settings.breakDuration)
        assertEquals(Duration.ZERO, settings.fadeOut)
        assertFalse(settings.completionCue)
    }

    @Test
    fun `end time rolls across midnight and resolves daylight saving using calendar time`() {
        assertEquals(90.minutes, LocalSessionTime.until(60, ZonedDateTime.parse("2026-11-07T23:30:00+03:00[Europe/Moscow]")))
        assertEquals(24.hours, LocalSessionTime.until(60, ZonedDateTime.parse("2026-11-07T01:00:00+03:00[Europe/Moscow]")))
        // 02:30 does not exist on the spring transition; java time moves it to 03:30.
        assertEquals(1.hours, LocalSessionTime.until(150, ZonedDateTime.parse("2026-03-29T01:30:00+01:00[Europe/Berlin]")))
    }

    @Test
    fun `end time is captured once and pause and wall clock changes never extend it`() {
        var wall = ZonedDateTime.parse("2026-11-07T23:00:00+03:00[Europe/Moscow]")
        var now = 10.seconds
        var wallReads = 0
        val session = PlaybackSession(wallNow = { wallReads++; wall }) { now }
        session.configure(SessionMode.SLEEP.defaults().copy(duration = null, endAtMinute = 23 * 60 + 30))
        session.dispatch(PlaybackCommand.Play)
        val deadline = now + 30.minutes
        now += 10.minutes
        session.dispatch(PlaybackCommand.Pause)
        wall = wall.minusDays(3)
        now += 5.minutes
        assertEquals(15.minutes, (session.state() as PlaybackState.Paused).remaining)
        session.dispatch(PlaybackCommand.Resume)
        assertEquals(deadline, session.deadline)
        assertEquals(1, wallReads)
        now = deadline
        assertEquals(PlaybackState.Idle, session.state())
        assertEquals(SessionMode.SLEEP, session.takeCompletion()!!.settings.mode)
        assertNull(session.takeCompletion())
    }

    @Test
    fun `end time may expire while paused and resume cannot resurrect it`() {
        var now = Duration.ZERO
        val session = PlaybackSession(wallNow = { ZonedDateTime.parse("2026-11-07T23:00:00Z") }) { now }
        session.configure(SessionMode.SLEEP.defaults().copy(duration = null, endAtMinute = 23 * 60 + 1))
        session.dispatch(PlaybackCommand.Play)
        session.dispatch(PlaybackCommand.Pause)
        now = 2.minutes
        assertEquals(PlaybackState.Idle, session.dispatch(PlaybackCommand.Resume))
        assertTrue(session.hasCompletion)
    }

    @Test
    fun `focus completion is latched once even if metadata observes expiry before stop`() {
        var now = Duration.ZERO
        val session = PlaybackSession { now }
        session.configure(SessionMode.FOCUS.defaults().copy(duration = 25.minutes))
        session.dispatch(PlaybackCommand.Play)
        now = 10.minutes
        session.dispatch(PlaybackCommand.Pause)
        now += 3.hours
        assertNull(session.takeCompletion())
        session.dispatch(PlaybackCommand.Resume)
        now += 15.minutes
        repeat(3) { assertEquals(PlaybackState.Idle, session.state()) }
        session.dispatch(PlaybackCommand.Stop)
        val completion = session.takeCompletion()!!
        assertEquals(SessionMode.FOCUS, completion.settings.mode)
        assertEquals(now, completion.deadline)
        assertNull(session.takeCompletion())
        session.dispatch(PlaybackCommand.Play)
        now += 1.minutes
        session.dispatch(PlaybackCommand.Stop)
        assertNull(session.takeCompletion())
    }

    @Test
    fun `restart restores preferences stopped without claiming an unfinished focus completion`() {
        val original = PlaybackSession { 0.seconds }
        original.configure(SessionMode.FOCUS.defaults().copy(completionCue = true, breakDuration = 3.minutes))
        original.dispatch(PlaybackCommand.Play)
        val restored = PlaybackSession { 10.hours }
        restored.configure(SessionPreferences.decode(SessionPreferences.encode(original.settings)))
        assertEquals(original.settings, restored.settings)
        assertEquals(PlaybackState.Idle, restored.state())
        assertNull(restored.takeCompletion())
        assertNull(restored.deadline)
    }

    @Test
    fun `focus count resets by local date and breaks use monotonic time within the same boot`() {
        val first = FocusProgress().complete("2026-11-07", 1000L, 5.minutes, 12)
        assertEquals(1, first.countToday("2026-11-07"))
        assertEquals(0, first.countToday("2026-11-08"))
        assertEquals(4.minutes, first.breakRemaining(61_000, 12))
        assertEquals(Duration.ZERO, first.breakRemaining(400_000, 12))
        assertNull(first.breakRemaining(1000, 13))
        assertNull(first.breakRemaining(1000, -1))
        assertNull(first.clearBreak().breakRemaining(1000, 12))
        assertEquals(2, first.complete("2026-11-07", 10_000, Duration.ZERO, 12).count)
        assertEquals(1, first.complete("2026-11-08", 10_000, Duration.ZERO, 12).count)
    }

    @Test
    fun `delayed completion keeps its actual deadline for daily count and break`() {
        var now = Duration.ZERO
        val session = PlaybackSession { now }
        session.configure(SessionMode.FOCUS.defaults().copy(duration = 1.minutes))
        session.dispatch(PlaybackCommand.Play)
        now = 3.minutes
        session.state()
        val completion = session.takeCompletion()!!
        assertEquals(1.minutes, completion.deadline)
        assertEquals("2026-11-07", LocalSessionTime.completedDay(completion.deadline, now,
            ZonedDateTime.parse("2026-11-08T00:01:00+03:00[Europe/Moscow]")))
        val progress = FocusProgress().complete("2026-11-07", completion.deadline.inWholeMilliseconds, 1.minutes, 4)
        assertEquals(Duration.ZERO, progress.breakRemaining(now.inWholeMilliseconds, 4))
    }
}