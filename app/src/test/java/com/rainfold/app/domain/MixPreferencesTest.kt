package com.rainfold.app.domain

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class MixPreferencesTest {
    @Test
    fun `all layers and mute volumes survive persistence and restore stopped`() {
        val original = PlaybackSession { 0.seconds }
        val mix = NoiseMix(NoiseType.BROWN, 0.73f, true, 0.64f, true, true)
        original.dispatch(PlaybackCommand.UpdateMix(mix))
        original.dispatch(PlaybackCommand.Play)
        val persisted = MixPreferences.encode(original.mix)
        val restored = PlaybackSession { 200.seconds }
        restored.dispatch(PlaybackCommand.UpdateMix(MixPreferences.decode(persisted)))
        assertEquals(mix, restored.mix)
        assertEquals(PlaybackState.Idle, restored.state())
    }

    @Test
    fun `legacy settings migrate with rain absent and independent default volumes`() {
        assertEquals(NoiseMix(NoiseType.WHITE, 0.9f),
            MixPreferences.decode(mapOf("noise" to "WHITE", "volume" to 0.9f)))
        assertEquals(NoiseMix(), MixPreferences.decode(emptyMap<String, Any>()))
    }

    @Test
    fun `invalid saved values are bounded or use safe defaults`() {
        val restored = MixPreferences.decode(mapOf(
            "noise" to "REMOVED", "volume" to Float.NaN, "rainVolume" to 7f,
            "rainEnabled" to "true", "rainMuted" to 1,
        ))
        assertEquals(NoiseMix(rainVolume = 1f), restored)
        assertEquals(0f, MixPreferences.decode(mapOf("volume" to -3f)).noiseVolume, 0f)
    }

    @Test
    fun `partial layer updates retain the rest of the authoritative mix`() {
        val mix = NoiseMix(NoiseType.WHITE, 0.7f, true, 0.9f, true, false)
        assertEquals(mix.copy(rainMuted = true), MixPreferences.decode(mapOf("rainMuted" to true), mix))
    }

    @Test
    fun `layer edits preserve the playing deadline and paused remaining duration`() {
        var now = 0.seconds
        val session = PlaybackSession { now }
        session.configure(SessionSettings(duration = 60.seconds))
        session.dispatch(PlaybackCommand.Play)
        now = 10.seconds
        val mix = NoiseMix(rainEnabled = true, rainVolume = 0.8f, noiseMuted = true)
        session.dispatch(PlaybackCommand.UpdateMix(mix))
        assertEquals(60.seconds, session.deadline)
        assertEquals(PlaybackState.Playing(mix, 50.seconds), session.state())
        session.dispatch(PlaybackCommand.Pause)
        now = 100.seconds
        session.dispatch(PlaybackCommand.UpdateMix(mix.copy(rainEnabled = false)))
        session.dispatch(PlaybackCommand.Resume)
        assertEquals(150.seconds, session.deadline)
        assertEquals(PlaybackState.Playing(session.mix, 50.seconds), session.state())
    }
}