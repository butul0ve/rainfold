package com.rainfold.app.ui

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rainfold.app.R
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionMode
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.reminders.ReminderSettings
import com.rainfold.app.player.sceneTitle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Composable
internal fun SessionModeControls(state: PlaybackScreenState, onMode: (SessionMode) -> Unit,
    onPlayback: () -> Unit, onStop: () -> Unit) {
    var night by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.session_mode), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RainfoldTokens.size8)) {
        SessionMode.entries.forEach { mode ->
            FilterChip(selected = state.settings.mode == mode, onClick = { if (state.settings.mode != mode) onMode(mode) },
                enabled = state.connected && state.playback is PlaybackState.Idle,
                modifier = Modifier.heightIn(min = RainfoldTokens.size52), label = { Text(modeLabel(mode)) })
        }
    }
    Text(stringResource(R.string.session_mode_hint), style = MaterialTheme.typography.bodySmall)
    if (state.settings.mode == SessionMode.FOCUS) Text(stringResource(R.string.focus_today, state.focusToday))
    if (state.settings.mode == SessionMode.SLEEP) {
        OutlinedButton(onClick = { night = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.night_screen))
        }
    }
    if (night) NightScreen(state, onPlayback, onStop, { night = false })
}

@Composable
internal fun ModeTimerSettings(state: PlaybackScreenState, onConfigure: (SessionSettings) -> Unit,
    onReminder: (ReminderSettings) -> Unit) {
    val context = LocalContext.current
    val idle = state.connected && state.playback is PlaybackState.Idle
    if (state.settings.mode == SessionMode.SLEEP) {
        Text(stringResource(R.string.sleep_timer_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = idle, onClick = {
            val minute = state.settings.endAtMinute ?: 7 * 60
            TimePickerDialog(context, { _, hour, min ->
                onConfigure(state.settings.copy(duration = null, endAtMinute = hour * 60 + min))
            }, minute / 60, minute % 60, true).show()
        }, modifier = Modifier.fillMaxWidth()) {
            Text(state.settings.endAtMinute?.let { stringResource(R.string.end_at_value, it / 60, it % 60) }
                ?: stringResource(R.string.end_at))
        }
        Text(stringResource(R.string.end_at_hint), style = MaterialTheme.typography.bodySmall)
        ReminderControls(state.reminder, onReminder)
    }
    if (state.settings.mode == SessionMode.FOCUS) {
        Text(stringResource(R.string.focus_timer_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RainfoldTokens.size8)) {
            listOf(25, 50, 90).forEach { minutes ->
                FilterChip(selected = state.settings.duration == minutes.minutes,
                    onClick = { onConfigure(state.settings.copy(duration = minutes.minutes, endAtMinute = null)) }, enabled = idle,
                    modifier = Modifier.heightIn(min = RainfoldTokens.size52),
                    label = { Text(stringResource(R.string.minutes_value, minutes.toLong())) })
            }
        }
        FilterChip(selected = state.settings.completionCue,
            onClick = { onConfigure(state.settings.copy(completionCue = !state.settings.completionCue)) }, enabled = idle,
            modifier = Modifier.heightIn(min = RainfoldTokens.size52), label = { Text(stringResource(R.string.completion_cue)) })
        SettingPicker(stringResource(R.string.focus_break),
            if (state.settings.breakDuration == Duration.ZERO) stringResource(R.string.no_break)
            else stringResource(R.string.minutes_value, state.settings.breakDuration.inWholeMinutes),
            idle, listOf(0, 1, 3, 5, 10, 15),
            { if (it == 0) stringResource(R.string.no_break) else stringResource(R.string.minutes_value, it.toLong()) },
            { onConfigure(state.settings.copy(breakDuration = it.minutes)) })
        Text(stringResource(R.string.focus_break_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReminderControls(settings: ReminderSettings, onReminder: (ReminderSettings) -> Unit) {
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) onReminder(settings.copy(enabled = true))
    }
    Text(stringResource(R.string.bedtime_reminder), style = MaterialTheme.typography.titleMedium)
    FilterChip(selected = settings.enabled, onClick = {
        if (!settings.enabled && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else onReminder(settings.copy(enabled = !settings.enabled))
    }, modifier = Modifier.heightIn(min = RainfoldTokens.size52),
        label = { Text(stringResource(if (settings.enabled) R.string.reminder_on else R.string.reminder_off)) })
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, hour, minute -> onReminder(settings.copy(minuteOfDay = hour * 60 + minute)) },
            settings.minuteOfDay / 60, settings.minuteOfDay % 60, true).show()
    }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.reminder_time, settings.minuteOfDay / 60, settings.minuteOfDay % 60))
    }
    Text(stringResource(R.string.reminder_hint), style = MaterialTheme.typography.bodySmall)
    if (permissionDenied || (settings.enabled && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())) {
        Text(stringResource(R.string.reminder_permission), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun sessionTimeLabel(state: PlaybackScreenState): String {
    if (state.playback is PlaybackState.Idle && state.settings.mode == SessionMode.FOCUS && state.breakRemaining != null) {
        val seconds = (state.breakRemaining.inWholeMilliseconds + 999) / 1000
        return if (seconds == 0L) stringResource(R.string.break_finished)
        else stringResource(R.string.break_remaining, seconds / 60, seconds % 60)
    }
    val remaining = when (val playback = state.playback) {
        is PlaybackState.Playing -> playback.remaining
        is PlaybackState.Paused -> playback.remaining
        else -> state.settings.duration
    }
    if (state.playback is PlaybackState.Idle && state.settings.endAtMinute != null) {
        return stringResource(R.string.end_at_value, state.settings.endAtMinute / 60, state.settings.endAtMinute % 60)
    }
    if (remaining == null) return stringResource(R.string.no_timer)
    val seconds = (remaining.inWholeMilliseconds + 999) / 1000
    return stringResource(R.string.remaining_time, seconds / 60, seconds % 60)
}

@Composable
private fun modeLabel(mode: SessionMode): String = stringResource(when (mode) {
    SessionMode.CUSTOM -> R.string.custom_mode
    SessionMode.SLEEP -> R.string.purpose_sleep
    SessionMode.FOCUS -> R.string.purpose_focus
})

@Composable
private fun NightScreen(state: PlaybackScreenState, onPlayback: () -> Unit, onStop: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color(0xFFBDBDBD)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(RainfoldTokens.size24)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(RainfoldTokens.size24)) {
                    Text(stringResource(R.string.night_screen), style = MaterialTheme.typography.headlineMedium)
                    Text(state.library.selected?.let { LocalContext.current.sceneTitle(it) } ?: stringResource(R.string.custom_mix))
                    Text(sessionTimeLabel(state), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close_night_screen), color = Color(0xFFBDBDBD)) }
                    TextButton(onClick = onStop, enabled = state.connected && state.playback !is PlaybackState.Idle) {
                        Text(stringResource(R.string.stop_session), color = Color(0xFFBDBDBD))
                    }
                    if (state.audioFailed) Text(stringResource(R.string.audio_error))
                }
                Button(onClick = onPlayback, enabled = state.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242424), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = RainfoldTokens.size56)) {
                    Text(stringResource(when (state.playback) {
                        is PlaybackState.Playing -> R.string.pause
                        is PlaybackState.Paused -> R.string.resume
                        else -> R.string.play
                    }))
                }
            }
        }
    }
}