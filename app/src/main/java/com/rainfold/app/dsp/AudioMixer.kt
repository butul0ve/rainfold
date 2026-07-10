package com.rainfold.app.dsp

object AudioMixer {
    fun mix(output: FloatArray, source: FloatArray, volume: Float) {
        require(output.size == source.size) { "All sources must use the same frame count" }
        source.indices.forEach { index -> output[index] += source[index] * volume.coerceIn(0f, 1f) }
    }
}

object AudioLimiter {
    /** Smoothly limits a float PCM sample without hard clipping. */
    fun limit(sample: Float): Float = sample / (1f + kotlin.math.abs(sample))

    fun limitInPlace(samples: FloatArray) {
        samples.indices.forEach { index -> samples[index] = limit(samples[index]) }
    }
}