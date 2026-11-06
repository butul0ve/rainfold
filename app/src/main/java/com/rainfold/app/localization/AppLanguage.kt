package com.rainfold.app.localization

import java.util.Locale

enum class AppLanguage(val tag: String, val nativeName: String, val flag: String) {
    ENGLISH("en", "English", "🇬🇧"),
    RUSSIAN("ru", "Русский", "🇷🇺"),
    GERMAN("de", "Deutsch", "🇩🇪"),
    CHINESE("zh-Hans", "简体中文", "🇨🇳");

    companion object {
        fun fromTag(tag: String): AppLanguage? {
            val language = Locale.forLanguageTag(tag).language
            return entries.find { Locale.forLanguageTag(it.tag).language == language }
        }
    }
}