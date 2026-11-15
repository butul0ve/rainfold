package com.rainfold.app.dsp

/** Remaining time comes from the monotonic session deadline, never a tick counter. */
object SessionFade {
    fun gain(remainingMs: Double?, fadeOutMs: Long): Float {
        if (remainingMs == null) return 1f
        return (remainingMs / fadeOutMs.coerceAtLeast(30)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Rain leaves in the first half; the noise bed leaves in the second half. */
    fun noiseGain(remainingMs: Double?, fadeOutMs: Long): Float =
        gain(remainingMs, fadeOutMs.coerceAtLeast(60) / 2)

    fun rainGain(remainingMs: Double?, fadeOutMs: Long): Float =
        gain(remainingMs?.minus(fadeOutMs.coerceAtLeast(60) / 2.0), fadeOutMs.coerceAtLeast(60) / 2)
}