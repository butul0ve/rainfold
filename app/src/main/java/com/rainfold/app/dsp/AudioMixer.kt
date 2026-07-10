package com.rainfold.app.dsp

object AudioMixer {
    fun mix(output: FloatArray, source: FloatArray, volume: Float) {
        require(output.size == source.size) { "All sources must use the same frame count" }
        source.indices.forEach { index -> output[index] += source[index] * volume.coerceIn(0f, 1f) }
    }
}

object AudioLimiter {
    /** Preserves normal PCM levels and smoothly limits only samples above the safe threshold. */
    fun limit(sample: Float): Float {
        val magnitude = kotlin.math.abs(sample)
        if (magnitude <= THRESHOLD) return sample

        val normalizedExcess = (magnitude - THRESHOLD) / (1f - THRESHOLD)
        val limitedMagnitude = THRESHOLD + (1f - THRESHOLD) * kotlin.math.tanh(normalizedExcess.toDouble()).toFloat()
        return kotlin.math.sign(sample) * limitedMagnitude
    }

    fun limitInPlace(samples: FloatArray) {
        samples.indices.forEach { index -> samples[index] = limit(samples[index]) }
    }

    private const val THRESHOLD = 0.8f
}