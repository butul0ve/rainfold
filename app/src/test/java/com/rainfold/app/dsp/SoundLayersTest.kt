package com.rainfold.app.dsp

import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundLayersTest {
    private fun constant(value: Float) = object : NoiseGenerator {
        override fun nextSample() = value
    }

    private fun mixer(rain: NoiseGenerator = constant(1f), base: Float = 1f) = SoundLayerMixer(
        rain, 1000, Array(3) { constant(base) },
    )

    private fun SoundLayerMixer.settle(mix: NoiseMix): Float {
        update(mix)
        repeat(50) { nextSample() }
        return nextSample()
    }

    @Test
    fun `layers have independent gains and fixed headroom`() {
        val mixer = mixer(base = 0.5f)
        val mix = NoiseMix(noiseVolume = 0.8f, rainEnabled = true, rainVolume = 0.3f)
        assertEquals(0.28f, mixer.settle(mix), 0.00001f)
        assertEquals(0.16f, mixer.settle(mix.copy(rainMuted = true)), 0.00001f)
        assertEquals(0.12f, mixer.settle(mix.copy(noiseMuted = true)), 0.00001f)
        assertEquals(0f, mixer.settle(mix.copy(noiseMuted = true, rainMuted = true)), 0f)
    }

    @Test
    fun `adding removing and rapidly readding rain ramps from the current level`() {
        val mixer = mixer(base = 0f)
        val mix = NoiseMix(rainEnabled = true, rainVolume = 1f)
        mixer.settle(mix.copy(rainEnabled = false))
        mixer.update(mix)
        var previous = 0f
        repeat(20) {
            val next = mixer.nextSample()
            assertEquals(0.4f / 50, next - previous, 0.000001f)
            previous = next
        }
        mixer.update(mix.copy(rainEnabled = false))
        repeat(10) {
            val next = mixer.nextSample()
            assertTrue(next < previous && previous - next < 0.4f / 50)
            previous = next
        }
        mixer.update(mix)
        assertTrue(mixer.nextSample() > previous)
        repeat(49) { mixer.nextSample() }
        assertEquals(0.4f, mixer.nextSample(), 0f)
        assertEquals(0f, mixer.settle(mix.copy(rainEnabled = false)), 0f)
    }

    @Test
    fun `volume and mute preserve the stored level and smoothly reach silence`() {
        val mixer = mixer(base = 0f)
        val mix = NoiseMix(rainEnabled = true, rainVolume = 1f)
        mixer.settle(mix)
        mixer.update(mix.copy(rainVolume = 0.25f))
        val ramp = FloatArray(50) { mixer.nextSample() }
        assertEquals(0.1f, ramp.last(), 0f)
        assertTrue(ramp.asList().zipWithNext().all { (a, b) -> a >= b && a - b < 0.007f })
        assertEquals(0f, mixer.settle(mix.copy(rainVolume = 0.25f, rainMuted = true)), 0f)
        assertEquals(0.1f, mixer.settle(mix.copy(rainVolume = 0.25f)), 0f)
    }

    @Test
    fun `unrelated layer edits do not prolong an in progress ramp`() {
        val mixer = mixer(base = 0f)
        val mix = NoiseMix(rainEnabled = true, rainVolume = 1f)
        mixer.update(mix)
        repeat(25) { mixer.nextSample() }
        mixer.update(mix.copy(noiseVolume = 0.1f, noiseType = NoiseType.WHITE))
        repeat(25) { mixer.nextSample() }
        assertEquals(0.4f, mixer.nextSample(), 0f)
    }

    @Test
    fun `noise crossfade holds level with rain present even when retargeted rapidly`() {
        val mixer = mixer()
        var mix = NoiseMix(noiseVolume = 1f, rainEnabled = true, rainVolume = 1f)
        mixer.settle(mix)
        repeat(500) { index ->
            mix = mix.copy(noiseType = NoiseType.entries[index % 3])
            mixer.update(mix)
            repeat(7) { assertEquals(0.8f, mixer.nextSample(), 0.00001f) }
        }
    }

    @Test
    fun `maximum layers during transitions stay within headroom and final limiter is bounded`() {
        val mixer = mixer()
        mixer.update(NoiseMix(noiseVolume = 1f, rainEnabled = true, rainVolume = 1f))
        repeat(5000) {
            assertTrue(abs(mixer.nextSample()) <= 0.80001f)
        }
        // Deliberately overloaded upstream signal also exercises the final safety limiter.
        val overloaded = mixer(constant(10f), 10f)
        overloaded.update(NoiseMix(noiseVolume = 1f, rainEnabled = true, rainVolume = 1f))
        repeat(200) { assertTrue(AudioLimiter.limit(overloaded.nextSample()) in -1f..1f) }
        for (step in -10000..10000) {
            val input = step / 100f
            val limited = AudioLimiter.limit(input)
            assertTrue(limited.isFinite() && limited in -1f..1f)
            assertEquals(-limited, AudioLimiter.limit(-input), 0f)
            if (step < 10000) assertTrue(AudioLimiter.limit(input + 0.01f) >= limited)
        }
    }

    @Test
    fun `muted and removed rain continues at its current loop phase`() {
        var calls = 0
        val rain = object : NoiseGenerator {
            override fun nextSample(): Float { calls++; return calls / 1000f }
        }
        val mixer = mixer(rain, 0f)
        val mix = NoiseMix(noiseMuted = true, rainEnabled = true, rainVolume = 1f)
        mixer.settle(mix.copy(rainEnabled = false))
        mixer.settle(mix.copy(rainMuted = true))
        assertEquals(0.4f * 153 / 1000, mixer.settle(mix), 0.000001f)
        assertEquals(153, calls)
    }

    @Test
    fun `loop overlap blends both endpoints and repeats the overlap adjusted period`() {
        val loop = SeamlessLoop(floatArrayOf(-1f, -1f, -1f, -0.5f, 0f, 0.5f, 1f, 1f), 3)
        val expected = floatArrayOf(-0.5f, 0f, 0.5f, 0f, -1f)
        repeat(4) { assertArrayEquals(expected, FloatArray(5) { loop.nextSample() }, 0f) }
    }

    @Test
    fun `overlap removes a hard wrap and never boosts a constant input`() {
        val loop = SeamlessLoop(FloatArray(100) { -1f + it * 2f / 99 }, 20)
        val output = FloatArray(800) { loop.nextSample() }
        assertTrue(output.asList().zipWithNext().all { (a, b) -> abs(a - b) < 0.1f })
        val constant = SeamlessLoop(FloatArray(100) { 0.7f }, 20)
        repeat(800) { assertEquals(0.7f, constant.nextSample(), 0.000001f) }
    }

    @Test
    fun `loop rejects invalid pcm and unusable overlaps`() {
        listOf(
            { SeamlessLoop(FloatArray(4), 1) },
            { SeamlessLoop(FloatArray(4), 3) },
            { SeamlessLoop(floatArrayOf(0f, Float.NaN, 0f, 0f), 2) },
            { SeamlessLoop(FloatArray(4) { 2f }, 2) },
        ).forEach { invalid ->
            try { invalid(); throw AssertionError("Expected invalid loop to fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}