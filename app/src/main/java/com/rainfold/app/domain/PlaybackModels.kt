package com.rainfold.app.domain

import kotlin.time.Duration

enum class NoiseType {
    WHITE,
    PINK,
    BROWN,
}

data class NoiseMix(
    val noiseType: NoiseType = NoiseType.PINK,
    val noiseVolume: Float = 0.5f,
    val rainEnabled: Boolean = false,
    val rainVolume: Float = 0.3f,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Playing(
        val mix: NoiseMix,
        val remaining: Duration?,
    ) : PlaybackState

    data class Paused(
        val mix: NoiseMix,
        val remaining: Duration?,
    ) : PlaybackState
}