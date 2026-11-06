package com.rainfold.app.player

import android.os.Bundle
import com.rainfold.app.domain.SessionPreferences
import com.rainfold.app.domain.SessionSettings

internal fun Bundle.putSettings(settings: SessionSettings) {
    SessionPreferences.encode(settings).forEach { (key, value) ->
        when (value) {
            is String -> putString(key, value)
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }
}

internal fun Bundle.readSettings(fallback: SessionSettings): SessionSettings {
    val source = this
    return SessionPreferences.decode(buildMap<String, Any> {
        SessionPreferences.encode(fallback).forEach { (key, value) ->
            if (source.containsKey(key)) when (value) {
                is String -> source.getString(key)?.let { put(key, it) }
                is Long -> put(key, source.getLong(key))
                is Int -> put(key, source.getInt(key))
                is Boolean -> put(key, source.getBoolean(key))
            }
        }
    }, fallback)
}