package com.rainfold.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEnvelopeTest {
    @Test
    fun `start and stop ramps are bounded and reach exact endpoints`() {
        val ramp = GainRamp()
        ramp.moveTo(1f, 480)
        var previous = 0f
        repeat(480) {
            val value = ramp.next()
            assertTrue(value in previous..1f)
            assertTrue(value - previous <= 1f / 480 + 0.00001f)
            previous = value
        }
        assertEquals(1f, ramp.value, 0f)
        ramp.moveTo(0f, 240)
        repeat(240) {
            val value = ramp.next()
            assertTrue(value in 0f..previous)
            previous = value
        }
        assertEquals(0f, ramp.next(), 0f)
    }

    @Test
    fun `rapid pause resume retargets from the current level`() {
        val ramp = GainRamp()
        ramp.moveTo(1f, 100)
        repeat(40) { ramp.next() }
        val before = ramp.value
        ramp.moveTo(0f, 100)
        assertEquals(before, ramp.value, 0f)
        repeat(20) { ramp.next() }
        val paused = ramp.value
        ramp.moveTo(1f, 100)
        assertEquals(paused, ramp.value, 0f)
        assertTrue(ramp.next() > paused)
        repeat(99) { ramp.next() }
        assertEquals(1f, ramp.value, 0f)
    }

    @Test
    fun `crossfade weights preserve headroom`() {
        val outgoing = GainRamp(1f)
        val incoming = GainRamp()
        outgoing.moveTo(0f, 2400)
        incoming.moveTo(1f, 2400)
        repeat(2400) {
            assertEquals(1f, outgoing.next() + incoming.next(), 0.0001f)
        }
    }

    @Test
    fun `deadline envelope catches up after delayed ticks and stays silent after expiry`() {
        assertEquals(1f, SessionFade.gain(null, 1000), 0f)
        assertEquals(1f, SessionFade.gain(2000.0, 1000), 0f)
        assertEquals(0.5f, SessionFade.gain(500.0, 1000), 0f)
        assertEquals(0f, SessionFade.gain(0.0, 1000), 0f)
        assertEquals(0f, SessionFade.gain(-300.0, 1000), 0f)
        assertEquals(0.5f, SessionFade.gain(15.0, 0), 0f)
    }
}