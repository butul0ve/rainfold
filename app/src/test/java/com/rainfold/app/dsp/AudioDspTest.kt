package com.rainfold.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {
    @Test
    fun `limiter always returns a valid pcm sample`() {
        listOf(-10f, -1f, 0f, 1f, 10f).forEach { sample ->
            assertTrue(AudioLimiter.limit(sample) in -1f..1f)
        }
    }

    @Test
    fun `limiter preserves safe pcm levels`() {
        assertEquals(0.5f, AudioLimiter.limit(0.5f), 0.0001f)
        assertEquals(-0.8f, AudioLimiter.limit(-0.8f), 0.0001f)
    }

    @Test
    fun `mixer applies volume to every source sample`() {
        val output = floatArrayOf(0.1f, 0.2f)
        AudioMixer.mix(output, floatArrayOf(0.4f, -0.2f), 0.5f)
        assertEquals(0.3f, output[0], 0.0001f)
        assertEquals(0.1f, output[1], 0.0001f)
    }

    @Test
    fun `generators produce finite pcm samples`() {
        listOf(WhiteNoiseGenerator(), PinkNoiseGenerator(), BrownNoiseGenerator()).forEach { generator ->
            repeat(512) {
                assertTrue(generator.nextSample().isFinite())
            }
        }
    }

    @Test
    fun `brown noise has an audible normalized level after warmup`() {
        val generator = BrownNoiseGenerator()
        repeat(4_800) { generator.nextSample() }
        val rms = rms(FloatArray(48_000) { generator.nextSample() })

        assertTrue(rms in 0.1f..0.6f)
    }

    private fun rms(samples: FloatArray): Float {
        val meanSquare = samples.sumOf { sample -> (sample * sample).toDouble() } / samples.size
        return kotlin.math.sqrt(meanSquare).toFloat()
    }
}