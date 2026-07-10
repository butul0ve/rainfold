package com.rainfold.app.dsp

interface NoiseGenerator {
    fun nextSample(): Float
}

class WhiteNoiseGenerator(
    private var state: Long = 0x6A09E667F3BCC909uL.toLong(),
) : NoiseGenerator {
    override fun nextSample(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 40).toFloat() / (1 shl 23) - 1f).coerceIn(-1f, 1f)
    }
}

/** Paul Kellet's filtered white-noise approximation. */
class PinkNoiseGenerator(
    private val white: NoiseGenerator = WhiteNoiseGenerator(),
) : NoiseGenerator {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f

    override fun nextSample(): Float {
        val input = white.nextSample()
        b0 = 0.99886f * b0 + input * 0.0555179f
        b1 = 0.99332f * b1 + input * 0.0750759f
        b2 = 0.96900f * b2 + input * 0.1538520f
        b3 = 0.86650f * b3 + input * 0.3104856f
        b4 = 0.55000f * b4 + input * 0.5329522f
        b5 = -0.7616f * b5 - input * 0.0168980f
        return ((b0 + b1 + b2 + b3 + b4 + b5 + input * 0.5362f) * 0.11f).coerceIn(-1f, 1f)
    }
}

class BrownNoiseGenerator(
    private val white: NoiseGenerator = WhiteNoiseGenerator(),
) : NoiseGenerator {
    private var previous = 0f

    override fun nextSample(): Float {
        previous = (previous + white.nextSample() * 0.02f).coerceIn(-1f, 1f)
        return previous
    }
}