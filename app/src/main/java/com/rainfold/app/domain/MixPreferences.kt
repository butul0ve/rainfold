package com.rainfold.app.domain

/** Stable local keys, shared by persistence and the media-controller transport. */
object MixPreferences {
    fun encode(mix: NoiseMix): Map<String, Any> = mapOf(
        "noise" to mix.noiseType.name,
        "volume" to mix.noiseVolume,
        "rainEnabled" to mix.rainEnabled,
        "rainVolume" to mix.rainVolume,
        "noiseMuted" to mix.noiseMuted,
        "rainMuted" to mix.rainMuted,
    )

    fun decode(values: Map<String, *>, fallback: NoiseMix = NoiseMix()): NoiseMix {
        fun volume(key: String, default: Float) =
            (values[key] as? Float)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default
        return NoiseMix(
            noiseType = (values["noise"] as? String)?.let { name ->
                NoiseType.entries.find { it.name == name }
            } ?: fallback.noiseType,
            noiseVolume = volume("volume", fallback.noiseVolume),
            rainEnabled = values["rainEnabled"] as? Boolean ?: fallback.rainEnabled,
            rainVolume = volume("rainVolume", fallback.rainVolume),
            noiseMuted = values["noiseMuted"] as? Boolean ?: fallback.noiseMuted,
            rainMuted = values["rainMuted"] as? Boolean ?: fallback.rainMuted,
        )
    }
}