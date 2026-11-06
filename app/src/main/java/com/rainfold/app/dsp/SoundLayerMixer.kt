package com.rainfold.app.dsp

import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType

/** Stateful sources and gain ramps live for the renderer's lifetime, never per block. */
class SoundLayerMixer(
    private val rain: NoiseGenerator,
    sampleRate: Int = 48_000,
    private val noise: Array<NoiseGenerator> = arrayOf(
        WhiteNoiseGenerator(), PinkNoiseGenerator(), BrownNoiseGenerator(),
    ),
) {
    private val transitionFrames = (sampleRate / 20).coerceAtLeast(1)
    private val weights = Array(noise.size) { GainRamp() }
    private val noiseGain = GainRamp()
    private val rainGain = GainRamp()
    private var previous: NoiseMix? = null

    init {
        require(sampleRate > 0 && noise.size == NoiseType.entries.size)
    }

    fun update(mix: NoiseMix) {
        val before = previous
        if (before?.noiseType != mix.noiseType) {
            weights.forEachIndexed { index, ramp ->
                ramp.moveTo(if (index == mix.noiseType.ordinal) 1f else 0f, transitionFrames)
            }
        }
        if (before == null || before.noiseVolume != mix.noiseVolume || before.noiseMuted != mix.noiseMuted) {
            noiseGain.moveTo(if (mix.noiseMuted) 0f else mix.noiseVolume, transitionFrames)
        }
        if (before == null || before.rainVolume != mix.rainVolume ||
            before.rainEnabled != mix.rainEnabled || before.rainMuted != mix.rainMuted
        ) {
            rainGain.moveTo(if (mix.rainEnabled && !mix.rainMuted) mix.rainVolume else 0f, transitionFrames)
        }
        previous = mix
    }

    /** Fixed per-layer headroom avoids changing the noise level when rain is added or muted. */
    fun nextSample(noiseEnvelope: Float = 1f, rainEnvelope: Float = 1f): Float {
        var base = 0f
        for (index in noise.indices) base += noise[index].nextSample() * weights[index].next()
        // Advance even inaudible sources, so adding/unmuting never restarts a loop.
        return (base * noiseGain.next() * noiseEnvelope + rain.nextSample() * rainGain.next() * rainEnvelope) * LAYER_HEADROOM
    }

    companion object {
        const val LAYER_HEADROOM = 0.4f
    }
}