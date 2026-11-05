package com.rainfold.app.player

import android.os.Bundle
import com.rainfold.app.domain.MixPreferences
import com.rainfold.app.domain.NoiseMix

internal fun Bundle.putMix(mix: NoiseMix) {
    MixPreferences.encode(mix).forEach { (key, value) ->
        when (value) {
            is String -> putString(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }
}

internal fun Bundle.readMix(fallback: NoiseMix = NoiseMix()): NoiseMix = MixPreferences.decode(
    buildMap<String, Any> {
        if (containsKey("noise")) getString("noise")?.let { put("noise", it) }
        listOf("volume", "rainVolume").forEach { key ->
            if (containsKey(key)) put(key, getFloat(key))
        }
        listOf("rainEnabled", "noiseMuted", "rainMuted").forEach { key ->
            if (containsKey(key)) put(key, getBoolean(key))
        }
    }, fallback,
)