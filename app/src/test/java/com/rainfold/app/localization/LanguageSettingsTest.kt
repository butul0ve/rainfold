package com.rainfold.app.localization

import android.app.Application
import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.rainfold.app.MainActivity
import com.rainfold.app.R
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.player.PlaybackService
import com.rainfold.app.player.sceneTitle
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "en")
class LanguageSettingsTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `new installs and upgrades without a completed selection are prompted`() {
        assertTrue(LanguageSettings(context).selectionRequired)
        val playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        playback.edit().putString("noise", "BROWN").putFloat("volume", 0.23f).apply()
        val original = playback.all.toMap()
        assertTrue(LanguageSettings(context).selectionRequired)
        LanguageSettings(context).select(AppLanguage.ENGLISH)
        assertFalse(LanguageSettings(context).selectionRequired)
        assertEquals(original, playback.all)
    }

    @Test
    fun `all choices survive settings recreation and a later change replaces the language`() {
        AppLanguage.entries.forEach { language ->
            LanguageSettings(context).select(language)
            val restored = LanguageSettings(context)
            assertEquals(language, restored.selected)
            assertFalse(restored.selectionRequired)
        }
    }

    @Test
    fun `unfinished or unsupported selection is offered again`() {
        val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)
        preferences.edit().putString("selected_language", "de").apply()
        assertTrue(LanguageSettings(context).selectionRequired)
        preferences.edit().putBoolean("selection_completed", true).putString("selected_language", "xx").apply()
        assertTrue(LanguageSettings(context).selectionRequired)
    }

    @Test
    fun `older Android localizes service resources without changing the base context`() {
        if (Build.VERSION.SDK_INT >= 33) return
        val settings = LanguageSettings(context)
        settings.select(AppLanguage.GERMAN)
        assertEquals("Abspielen", settings.localizedContext().getString(R.string.play))
        settings.select(AppLanguage.CHINESE)
        assertEquals("播放", settings.localizedContext().getString(R.string.play))
        assertEquals("Play", context.getString(R.string.play))
    }

    @Test
    @Config(sdk = [26])
    fun `saved language applies when the activity is created and recreated`() {
        // This lifecycle check needs no media service; Robolectric otherwise supplies a null binder.
        shadowOf(context).declareComponentUnbindable(ComponentName(context, PlaybackService::class.java))
        LanguageSettings(context).select(AppLanguage.RUSSIAN)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertEquals("Играть", controller.get().getString(R.string.play))
            controller.recreate()
            assertEquals("Играть", controller.get().getString(R.string.play))
            assertFalse(LanguageSettings(controller.get()).selectionRequired)
        } finally {
            controller.pause().stop().destroy()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    @Config(sdk = [35])
    fun `OS upgrade migrates legacy selection once and respects later system choices`() {
        val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)
        preferences.edit().putString("selected_language", "de").putBoolean("selection_completed", true).apply()
        val settings = LanguageSettings(context)
        val manager = context.getSystemService(LocaleManager::class.java)
        settings.migrateToPlatformLocales()
        assertEquals("de", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("ru")
        settings.migrateToPlatformLocales()
        assertEquals("ru", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        settings.migrateToPlatformLocales()
        assertTrue(manager.applicationLocales.isEmpty)
        assertFalse(settings.selectionRequired)
    }

    @Test
    @Config(sdk = [35])
    fun `migration preserves a language already selected in Android settings`() {
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("selected_language", "de").apply()
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("ru")
        LanguageSettings(context).migrateToPlatformLocales()
        assertEquals("ru", manager.applicationLocales.toLanguageTags())
        assertTrue(LanguageSettings(context).selectionRequired)
    }

    @Test
    fun `all languages translate scenes dialogs and formatted labels but preserve user names`() {
        val expected = mapOf(
            AppLanguage.ENGLISH to listOf("Play", "Scenes", "Choose your language", "Deep Sleep"),
            AppLanguage.RUSSIAN to listOf("Играть", "Сцены", "Выберите язык", "Глубокий сон"),
            AppLanguage.GERMAN to listOf("Abspielen", "Szenen", "Sprache auswählen", "Tiefer Schlaf"),
            AppLanguage.CHINESE to listOf("播放", "场景", "选择语言", "深度睡眠"),
        )
        expected.forEach { (language, strings) ->
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
            })
            assertEquals(strings, listOf(localized.getString(R.string.play), localized.getString(R.string.scenes),
                localized.getString(R.string.choose_language), localized.sceneTitle(SceneLibrary.builtIns.first())))
            assertTrue(localized.getString(R.string.remaining_time, 2, 5).endsWith("2:05"))
            assertEquals("42%", localized.getString(R.string.volume_percent, 42))
            assertEquals("Моя ночь 夜", localized.sceneTitle(SceneLibrary.builtIns.first().copy(id = "user:one", name = "Моя ночь 夜")))
        }
    }

    @Test
    @Config(sdk = [26])
    fun `language change refreshes paused media metadata and survives service restart`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val session = ReflectionHelpers.getField<MediaSession>(service, "mediaSession")
        val clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        try {
            service.handleSceneCommand(Bundle().apply {
                putString("operation", "select")
                putString("id", "builtin:deep-sleep")
            })
            // Exercise a paused domain session without opening an audio output.
            clock.configure(SessionSettings(duration = 30.minutes))
            clock.dispatch(PlaybackCommand.Play)
            clock.dispatch(PlaybackCommand.Pause)
            val before = clock.state()
            assertTrue(before is PlaybackState.Paused)
            LanguageSettings(context).select(AppLanguage.GERMAN)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Tiefer Schlaf", session.player.mediaMetadata.title.toString())
            assertEquals(before, clock.state())
            assertEquals(30.minutes, clock.settings.duration)
        } finally {
            controller.destroy()
        }
        val restarted = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val restartedSession = ReflectionHelpers.getField<MediaSession>(restarted.get(), "mediaSession")
            assertEquals("Tiefer Schlaf", restartedSession.player.mediaMetadata.title.toString())
            assertFalse(LanguageSettings(context).selectionRequired)
        } finally {
            restarted.destroy()
        }
    }
}