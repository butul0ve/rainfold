package com.rainfold.app.dsp

import com.rainfold.app.domain.NoiseMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SessionModesDspTest {
    @Test
    fun `sleep fades rain in first half and noise in second half with exact endpoints`() {
        assertEquals(1f, SessionFade.rainGain(10_000.0, 10_000), 0f)
        assertEquals(0.5f, SessionFade.rainGain(7500.0, 10_000), 0f)
        assertEquals(0f, SessionFade.rainGain(5000.0, 10_000), 0f)
        assertEquals(1f, SessionFade.noiseGain(5000.0, 10_000), 0f)
        assertEquals(0.5f, SessionFade.noiseGain(2500.0, 10_000), 0f)
        assertEquals(0f, SessionFade.noiseGain(0.0, 10_000), 0f)
        assertEquals(1f, SessionFade.rainGain(null, 0), 0f)
        assertEquals(0f, SessionFade.rainGain(-1.0, 0), 0f)
    }

    @Test
    fun `layer envelopes preserve bounded PCM and do not mutate saved mix`() {
        val constant = object : NoiseGenerator { override fun nextSample() = 1f }
        val mixer = SoundLayerMixer(constant, sampleRate = 100, noise = Array(3) { constant })
        mixer.update(NoiseMix(noiseVolume = 1f, rainEnabled = true, rainVolume = 1f))
        repeat(5) { mixer.nextSample() }
        assertEquals(0.8f, mixer.nextSample(), 0.00001f)
        assertEquals(0.4f, mixer.nextSample(1f, 0f), 0.00001f)
        assertEquals(0f, mixer.nextSample(0f, 0f), 0f)
        assertEquals(0.8f, mixer.nextSample(), 0.00001f)
    }

    @Test
    fun `completion cue has a quiet peak smooth boundaries finite duration and no discontinuities`() {
        assertEquals(0f, CompletionCue.sample(-1.0), 0f)
        assertEquals(0f, CompletionCue.sample(0.0), 0f)
        assertEquals(0f, CompletionCue.sample(600.0), 0f)
        var previous = 0f
        var peak = 0f
        repeat(28_801) { sample ->
            val value = CompletionCue.sample(sample * 1000.0 / 48_000)
            assertTrue(value.isFinite() && abs(value) <= CompletionCue.PEAK)
            assertTrue(abs(value - previous) < 0.004f)
            peak = maxOf(peak, abs(value))
            previous = value
        }
        assertTrue(peak > 0.03f)
    }
}