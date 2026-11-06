package com.rainfold.app.localization

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/** Kept separate from playback preferences so choosing a language never changes the mix. */
class LanguageSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    val selected: AppLanguage?
        get() = preferences.getString("selected_language", null)?.let(AppLanguage::fromTag)

    // Existing installs have no selection marker and receive the same dialog as new installs.
    val selectionRequired: Boolean
        get() = !preferences.getBoolean("selection_completed", false) || selected == null

    fun select(language: AppLanguage) {
        preferences.edit {
            putString("selected_language", language.tag)
            putBoolean("selection_completed", true)
            putBoolean("platform_locale_initialized", Build.VERSION.SDK_INT >= 33)
        }
    }

    /** One-time transfer when a device with a saved pre-13 choice upgrades to Android 13+. */
    fun migrateToPlatformLocales() {
        if (Build.VERSION.SDK_INT < 33 || preferences.getBoolean("platform_locale_initialized", false)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales.isEmpty) selected?.let {
            manager.applicationLocales = LocaleList.forLanguageTags(it.tag)
        }
        preferences.edit { putBoolean("platform_locale_initialized", true) }
    }

    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    /** AppCompat updates Activities below API 33; services need the same saved locale explicitly. */
    fun localizedContext(): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = selected ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
        }
        return context.createConfigurationContext(configuration)
    }
}