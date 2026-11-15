package com.rainfold.app.dsp

import kotlin.math.PI
import kotlin.math.sin

/** A quiet 600 ms sine with a smooth zero-to-zero envelope, mixed through the existing output. */
object CompletionCue {
    const val DURATION_MS = 600L
    const val PEAK = 0.035f

    fun sample(elapsedMs: Double): Float {
        if (elapsedMs <= 0 || elapsedMs >= DURATION_MS) return 0f
        val envelope = sin(PI * elapsedMs / DURATION_MS)
        return (PEAK * envelope * envelope * sin(2 * PI * 660 * elapsedMs / 1000)).toFloat()
    }
}