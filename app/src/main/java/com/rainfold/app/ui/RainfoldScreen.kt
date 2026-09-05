package com.rainfold.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rainfold.app.R
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun RainfoldScreen(
    state: PlaybackScreenState,
    onSelect: (NoiseType) -> Unit,
    onPlaybackToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onStop: () -> Unit,
    onConfigure: (SessionSettings) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val idle = state.playback is PlaybackState.Idle
    val remaining = when (val playback = state.playback) {
        is PlaybackState.Playing -> playback.remaining
        is PlaybackState.Paused -> playback.remaining
        else -> state.settings.duration
    }
    RainfoldTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(RainfoldTokens.size24),
                verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size16),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium)
                SettingPicker(stringResource(R.string.sound), noiseLabel(state.mix.noiseType),
                    state.connected, NoiseType.entries, { noiseLabel(it) }, onSelect)
                Button(onClick = onPlaybackToggle, enabled = state.connected,
                    modifier = Modifier.fillMaxWidth().heightIn(min = RainfoldTokens.size56)) {
                    Text(stringResource(when (state.playback) {
                        is PlaybackState.Playing -> R.string.pause
                        is PlaybackState.Paused -> R.string.resume
                        else -> R.string.play
                    }))
                }
                Text(stringResource(when (state.playback) {
                    is PlaybackState.Playing -> R.string.playing
                    is PlaybackState.Paused -> R.string.paused
                    else -> R.string.ready
                }))
                Text(if (remaining == null) stringResource(R.string.no_timer) else {
                    val total = (remaining.inWholeMilliseconds + 999) / 1000
                    stringResource(R.string.remaining_time, total / 60, total % 60)
                })
                val volumeLabel = stringResource(R.string.volume)
                Text(stringResource(R.string.volume_percent, (state.mix.noiseVolume * 100).toInt()))
                Slider(value = state.mix.noiseVolume, onValueChange = onVolumeChange,
                    enabled = state.connected, modifier = Modifier.fillMaxWidth().semantics { contentDescription = volumeLabel })
                Box {
                    TextButton(onClick = { menu = true }, enabled = state.connected && !idle) {
                        Text(stringResource(R.string.session_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.stop_session)) },
                            onClick = { menu = false; onStop() })
                    }
                }
                Text(stringResource(R.string.timer), style = MaterialTheme.typography.titleMedium)
                if (!idle) Text(stringResource(R.string.settings_after_stop), style = MaterialTheme.typography.bodySmall)
                SettingPicker(stringResource(R.string.timer),
                    state.settings.duration?.let { stringResource(R.string.minutes_value, it.inWholeMinutes) } ?: stringResource(R.string.no_timer),
                    state.connected && idle, listOf<Duration?>(null, 15.minutes, 30.minutes, 60.minutes, 90.minutes),
                    { value -> if (value == null) stringResource(R.string.no_timer) else stringResource(R.string.minutes_value, value.inWholeMinutes) },
                    { onConfigure(state.settings.copy(duration = it)) })
                SettingPicker(stringResource(R.string.fade_in), stringResource(R.string.seconds_value, state.settings.fadeIn.inWholeSeconds),
                    state.connected && idle, listOf(0.seconds, 1.seconds, 5.seconds, 10.seconds, 30.seconds),
                    { stringResource(R.string.seconds_value, it.inWholeSeconds) },
                    { onConfigure(state.settings.copy(fadeIn = it)) })
                SettingPicker(stringResource(R.string.fade_out), stringResource(R.string.seconds_value, state.settings.fadeOut.inWholeSeconds),
                    state.connected && idle, listOf(0.seconds, 5.seconds, 10.seconds, 30.seconds, 60.seconds),
                    { stringResource(R.string.seconds_value, it.inWholeSeconds) },
                    { onConfigure(state.settings.copy(fadeOut = it)) })
                if (state.audioFailed) Text(stringResource(R.string.audio_error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun <T> SettingPicker(
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