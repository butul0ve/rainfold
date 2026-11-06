package com.rainfold.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rainfold.app.R
import com.rainfold.app.domain.SceneLibrary
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.player.sceneTitle
import com.rainfold.app.player.descriptionResource
import androidx.compose.ui.platform.LocalContext
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.domain.SessionMode
import com.rainfold.app.reminders.ReminderSettings
import com.rainfold.app.localization.AppLanguage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RainfoldScreen(
    state: PlaybackScreenState,
    onSelect: (NoiseType) -> Unit,
    onPlaybackToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onStop: () -> Unit,
    onConfigure: (SessionSettings) -> Unit,
    onRainVolumeChange: (Float) -> Unit,
    onNoiseMute: () -> Unit,
    onRainMute: () -> Unit,
    onAddRain: () -> Unit,
    onRemoveRain: () -> Unit,
    sceneActions: SceneActions,
    onAmoledToggle: () -> Unit,
    currentLanguage: AppLanguage,
    languageSelectionRequired: Boolean,
    onLanguageSelected: (AppLanguage) -> Unit,
    onModeSelected: (SessionMode) -> Unit,
    onReminderChanged: (ReminderSettings) -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var addSound by rememberSaveable { mutableStateOf(false) }
    var scenes by rememberSaveable { mutableStateOf(false) }
    var saveScene by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    val selectedScene = state.library.selected
    val idle = state.playback is PlaybackState.Idle
    RainfoldTheme(darkTheme = isSystemInDarkTheme(), amoled = state.amoled) {
        val sleeping = (state.settings.mode == SessionMode.SLEEP ||
            (state.settings.mode == SessionMode.CUSTOM && selectedScene?.purpose == ScenePurpose.SLEEP)) && state.playback is PlaybackState.Playing
        val tint = if (state.settings.mode == SessionMode.FOCUS ||
            (state.settings.mode == SessionMode.CUSTOM && selectedScene?.purpose == ScenePurpose.FOCUS)) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        val backdrop = if (state.amoled) Modifier else Modifier.background(Brush.verticalGradient(listOf(
            tint.copy(alpha = if (sleeping) 0.12f else 0.35f), MaterialTheme.colorScheme.surface,
        )))
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().then(backdrop).safeDrawingPadding()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(RainfoldTokens.size24),
                    verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size16),
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    val sceneTitle = selectedScene?.let { LocalContext.current.sceneTitle(it) } ?: stringResource(R.string.custom_mix)
                    Text(if (selectedScene != null && selectedScene.mix != state.mix) stringResource(R.string.scene_modified, sceneTitle) else sceneTitle,
                        style = MaterialTheme.typography.headlineMedium)
                    selectedScene?.let { Text(purposeLabel(it.purpose), style = MaterialTheme.typography.labelLarge) }
                    selectedScene?.descriptionResource()?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { scenes = true }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scenes))
                    }
                    Text(stringResource(when (state.playback) {
                        is PlaybackState.Playing -> R.string.playing
                        is PlaybackState.Paused -> R.string.paused
                        else -> R.string.ready
                    }))
                    Text(sessionTimeLabel(state))
                    SessionModeControls(state, onModeSelected, onPlaybackToggle, onStop)
                    LayerCard(noiseLabel(state.mix.noiseType), state.mix.noiseVolume, state.mix.noiseMuted,
                        state.connected, onVolumeChange, onNoiseMute) {
                        SettingPicker(stringResource(R.string.sound), noiseLabel(state.mix.noiseType),
                            state.connected, NoiseType.entries, { noiseLabel(it) }, onSelect)
                        Text(stringResource(when (state.mix.noiseType) {
                            NoiseType.WHITE -> R.string.white_noise_description
                            NoiseType.PINK -> R.string.pink_noise_description
                            NoiseType.BROWN -> R.string.brown_noise_description
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.mix.rainEnabled) {
                        LayerCard(stringResource(R.string.rain), state.mix.rainVolume, state.mix.rainMuted,
                            state.connected, onRainVolumeChange, onRainMute, onRemoveRain)
                    }
                    OutlinedButton(onClick = { addSound = true }, enabled = state.connected && !state.mix.rainEnabled,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_sound))
                    }
                    Box {
                        TextButton(onClick = { menu = true }, enabled = state.connected && !idle) {
                            Text(stringResource(R.string.session_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.stop_session)) },
                                onClick = { menu = false; onStop() })
                        }
                    }
                    OutlinedButton(onClick = { timer = true }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.timer))
                    }
                    OutlinedButton(onClick = { saveScene = true },
                        enabled = state.connected && state.library.custom.size < SceneLibrary.MAX_CUSTOM_SCENES,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_scene)) }
                    FilterChip(selected = state.amoled, onClick = onAmoledToggle,
                        label = { Text(stringResource(R.string.amoled_theme)) })
                    OutlinedButton(onClick = { languageDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.language_setting, currentLanguage.nativeName))
                    }
                    if (state.sceneError) Text(stringResource(R.string.scene_library_error), color = MaterialTheme.colorScheme.error)
                    if (state.audioFailed) Text(stringResource(R.string.audio_error), color = MaterialTheme.colorScheme.error)
                }
                PlaybackButton(state, onPlaybackToggle, Modifier.padding(RainfoldTokens.size16))
            }
            if (languageSelectionRequired || languageDialog) {
                LanguageDialog(currentLanguage, languageSelectionRequired,
                    onDismiss = { languageDialog = false },
                    onSelect = { language -> languageDialog = false; onLanguageSelected(language) })
            }
            if (scenes) SceneBrowser(state, sceneActions, { scenes = false }, onPlaybackToggle)
            if (saveScene) SceneNameDialog(stringResource(R.string.save_scene),
                selectedScene?.let { LocalContext.current.sceneTitle(it) }.orEmpty(), selectedScene?.purpose ?: ScenePurpose.SLEEP,
                onDismiss = { saveScene = false }, onSave = { name, purpose -> sceneActions.save(name, purpose); saveScene = false })
            if (timer) ModalBottomSheet(onDismissRequest = { timer = false }) {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(RainfoldTokens.size24),
                        verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size16)) {
                        Text(stringResource(R.string.timer), style = MaterialTheme.typography.titleMedium)
                        if (!idle) Text(stringResource(R.string.settings_after_stop), style = MaterialTheme.typography.bodySmall)
                        ModeTimerSettings(state, onConfigure, onReminderChanged)
                        SettingPicker(stringResource(R.string.timer),
                            state.settings.endAtMinute?.let { stringResource(R.string.end_at_value, it / 60, it % 60) }
                                ?: state.settings.duration?.let { stringResource(R.string.minutes_value, it.inWholeMinutes) } ?: stringResource(R.string.no_timer),
                            state.connected && idle, listOf<Duration?>(null, 15.minutes, 25.minutes, 30.minutes, 50.minutes, 60.minutes, 90.minutes),
                            { value -> if (value == null) stringResource(R.string.no_timer) else stringResource(R.string.minutes_value, value.inWholeMinutes) },
                            { onConfigure(state.settings.copy(duration = it, endAtMinute = null)) })
                        SettingPicker(stringResource(R.string.fade_in), stringResource(R.string.seconds_value, state.settings.fadeIn.inWholeSeconds),
                            state.connected && idle, listOf(0.seconds, 1.seconds, 5.seconds, 10.seconds, 30.seconds),
                            { stringResource(R.string.seconds_value, it.inWholeSeconds) },
                            { onConfigure(state.settings.copy(fadeIn = it)) })
                        SettingPicker(stringResource(R.string.fade_out), stringResource(R.string.seconds_value, state.settings.fadeOut.inWholeSeconds),
                            state.connected && idle, listOf(0.seconds, 5.seconds, 10.seconds, 30.seconds, 60.seconds),
                            { stringResource(R.string.seconds_value, it.inWholeSeconds) },
                            { onConfigure(state.settings.copy(fadeOut = it)) })
                    }
                    PlaybackButton(state, onPlaybackToggle, Modifier.padding(RainfoldTokens.size16))
                }
            }
            if (addSound) {
                ModalBottomSheet(onDismissRequest = { addSound = false }) {
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(RainfoldTokens.size24),
                            verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size16)) {
                            Text(stringResource(R.string.environment), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.rain), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.rain_description))
                            Text(stringResource(R.string.rain_credit), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.add_sound_hint), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onAddRain(); addSound = false }, enabled = state.connected,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.add_rain))
                            }
                        }
                        PlaybackButton(state, onPlaybackToggle, Modifier.padding(RainfoldTokens.size16))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybackButton(state: PlaybackScreenState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onToggle, enabled = state.connected,
        modifier = modifier.fillMaxWidth().heightIn(min = RainfoldTokens.size56)) {
        Text(stringResource(when (state.playback) {
            is PlaybackState.Playing -> R.string.pause
            is PlaybackState.Paused -> R.string.resume
            else -> R.string.play
        }))
    }
}

@Composable
private fun LayerCard(
    title: String,
    volume: Float,
    muted: Boolean,
    enabled: Boolean,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    onRemove: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val volumeLabel = stringResource(R.string.layer_volume, title)
    val muteLabel = stringResource(if (muted) R.string.unmute_layer else R.string.mute_layer, title)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(RainfoldTokens.size16),
            verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size8)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
            Text("${stringResource(R.string.volume)}: ${stringResource(R.string.volume_percent, (volume * 100).toInt())}")
            Slider(value = volume, onValueChange = onVolume, enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = volumeLabel })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RainfoldTokens.size8)) {
                FilterChip(selected = muted, onClick = onMute, enabled = enabled,
                    label = { Text(stringResource(if (muted) R.string.unmute else R.string.mute)) },
                    modifier = Modifier.semantics { contentDescription = muteLabel })
                if (onRemove != null) {
                    TextButton(onClick = onRemove, enabled = enabled) {
                        Text(stringResource(R.string.remove_rain))
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> SettingPicker(
    label: String,
    value: String,
    enabled: Boolean,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

@Composable
private fun noiseLabel(type: NoiseType): String = stringResource(when (type) {
    NoiseType.WHITE -> R.string.white_noise
    NoiseType.PINK -> R.string.pink_noise
    NoiseType.BROWN -> R.string.brown_noise
})