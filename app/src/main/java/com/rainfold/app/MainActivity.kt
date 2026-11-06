package com.rainfold.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rainfold.app.localization.AppLanguage
import com.rainfold.app.localization.LanguageSettings
import com.rainfold.app.ui.PlaybackViewModel
import com.rainfold.app.ui.RainfoldScreen
import com.rainfold.app.ui.SceneActions

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        LanguageSettings(newBase).migrateToPlatformLocales()
        // Android 13+ persists app locales itself. Restore our choice on older releases
        // before AppCompat wraps the Activity context, including after process death.
        if (Build.VERSION.SDK_INT < 33) {
            LanguageSettings(newBase).selected?.let {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it.tag))
            }
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val languageSettings = LanguageSettings(this)
        setContent {
            var languageRequired by remember { mutableStateOf(languageSettings.selectionRequired) }
            var currentLanguage by remember {
                mutableStateOf(AppLanguage.fromTag(resources.configuration.locales[0].toLanguageTag()) ?: AppLanguage.ENGLISH)
            }
            val viewModel: PlaybackViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            RainfoldScreen(
                state,
                viewModel::selectNoise,
                viewModel::togglePlayback,
                viewModel::setVolume,
                viewModel::stop,
                viewModel::configure,
                viewModel::setRainVolume,
                viewModel::toggleNoiseMute,
                viewModel::toggleRainMute,
                viewModel::addRain,
                viewModel::removeRain,
                SceneActions(viewModel::selectScene, viewModel::saveScene, viewModel::renameScene,
                    viewModel::copyScene, viewModel::deleteScene, viewModel::favoriteScene),
                viewModel::toggleAmoled,
                currentLanguage,
                languageRequired,
                { language ->
                    languageSettings.select(language)
                    currentLanguage = language
                    languageRequired = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
                },
                viewModel::selectMode,
                viewModel::configureReminder,
            )
        }
    }
}