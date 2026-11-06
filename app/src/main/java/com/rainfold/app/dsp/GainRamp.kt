package com.rainfold.app.dsp

/** Sample-counted linear ramp. Retargeting always starts at the current gain. */
class GainRamp(initial: Float = 0f) {
    var value: Float = initial
        private set
    private var target = initial
    private var remaining = 0
    private var step = 0f

    fun moveTo(target: Float, frames: Int) {
        require(target.isFinite() && frames >= 0)
        this.target = target
        remaining = frames
        step = if (frames == 0) 0f else (target - value) / frames
        if (frames == 0) value = target
    }

    fun next(): Float {
        if (remaining > 0) {
            remaining--
            value = if (remaining == 0) target else value + step
        }
        return value
    }
}