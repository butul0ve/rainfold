package com.rainfold.app.dsp

/** Linear overlap of tail and head; the repeated period is size - crossfadeFrames. */
class SeamlessLoop(samples: FloatArray, private val crossfadeFrames: Int) : NoiseGenerator {
    private val samples = samples.copyOf()
    private var position = crossfadeFrames

    init {
        require(crossfadeFrames >= 2 && crossfadeFrames <= samples.size / 2)
        require(samples.all { it.isFinite() && it in -1f..1f })
    }

    override fun nextSample(): Float {
        val overlap = position - (samples.size - crossfadeFrames)
        val sample = if (overlap >= 0) {
            val incoming = overlap.toFloat() / (crossfadeFrames - 1)
            samples[position] * (1f - incoming) + samples[overlap] * incoming
        } else samples[position]
        position++
        if (position == samples.size) position = crossfadeFrames
        return sample
    }
}