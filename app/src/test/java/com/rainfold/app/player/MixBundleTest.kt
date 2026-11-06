package com.rainfold.app.player

import android.os.Bundle
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixBundleTest {
    @Test
    fun `session extras round trip every mix field`() {
        val mix = NoiseMix(NoiseType.BROWN, 0.23f, true, 0.67f, true, true)
        assertEquals(mix, Bundle().apply { putMix(mix) }.readMix())
    }

    @Test
    fun `slider commands update only the addressed layer`() {
        val mix = NoiseMix(NoiseType.PINK, 0.8f, true, 0.6f, true, true)
        assertEquals(mix.copy(noiseVolume = 0.1f),
            Bundle().apply { putFloat("volume", 0.1f) }.readMix(mix))
        assertEquals(mix.copy(rainVolume = 0.2f),
            Bundle().apply { putFloat("rainVolume", 0.2f) }.readMix(mix))
    }

    @Test
    fun `mute and unmute commands retain both saved volumes`() {
        var mix = NoiseMix(noiseVolume = 0.7f, rainEnabled = true, rainVolume = 0.3f)
        mix = Bundle().apply { putBoolean("noiseMuted", true) }.readMix(mix)
        assertEquals(NoiseMix(noiseVolume = 0.7f, rainEnabled = true, rainVolume = 0.3f,
            noiseMuted = true), mix)
        mix = Bundle().apply { putBoolean("rainMuted", true) }.readMix(mix)
        assertEquals(true, mix.rainMuted)
        mix = Bundle().apply { putBoolean("noiseMuted", false) }.readMix(mix)
        assertEquals(false, mix.noiseMuted)
        mix = Bundle().apply { putBoolean("rainMuted", false) }.readMix(mix)
        assertEquals(NoiseMix(noiseVolume = 0.7f, rainEnabled = true, rainVolume = 0.3f), mix)
    }

    @Test
    fun `noise selection and rain removal preserve unrelated settings`() {
        val mix = NoiseMix(rainEnabled = true, rainVolume = 0.8f, rainMuted = true)
        assertEquals(mix.copy(noiseType = NoiseType.WHITE),
            Bundle().apply { putString("noise", "WHITE") }.readMix(mix))
        assertEquals(mix.copy(rainEnabled = false),
            Bundle().apply { putBoolean("rainEnabled", false) }.readMix(mix))
        assertEquals(mix, Bundle().readMix(mix))
    }
}