package com.rainfold.app.domain

import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class SessionMode { CUSTOM, SLEEP, FOCUS }

/** Wall time is consulted once at Play. Running deadlines always use the monotonic clock. */
object LocalSessionTime {
    fun next(minuteOfDay: Int, now: ZonedDateTime): ZonedDateTime {
        require(minuteOfDay in 0..1439)
        val time = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }

    fun until(minuteOfDay: Int, now: ZonedDateTime): Duration =
        java.time.Duration.between(now, next(minuteOfDay, now)).toMillis().milliseconds

    fun completedDay(deadline: Duration, now: Duration, wallNow: ZonedDateTime): String =
        wallNow.minus(java.time.Duration.ofMillis((now - deadline).coerceAtLeast(Duration.ZERO).inWholeMilliseconds))
            .toLocalDate().toString()
}

fun SessionMode.defaults(): SessionSettings = when (this) {
    SessionMode.CUSTOM -> SessionSettings(null, 1.seconds, 10.seconds)
    SessionMode.SLEEP -> SessionSettings(30.minutes, 5.seconds, 30.seconds, mode = this)
    SessionMode.FOCUS -> SessionSettings(25.minutes, 1.seconds, 5.seconds, mode = this)
}

/** Shared keys preserve legacy session preferences and keep partial media commands lossless. */
object SessionPreferences {
    fun encode(settings: SessionSettings): Map<String, Any> = mapOf(
        "duration" to (settings.duration?.inWholeMilliseconds ?: 0L),
        "fadeIn" to settings.fadeIn.inWholeMilliseconds,
        "fadeOut" to settings.fadeOut.inWholeMilliseconds,
        "mode" to settings.mode.name,
        "endAtMinute" to (settings.endAtMinute ?: -1),
        "completionCue" to settings.completionCue,
        "breakDuration" to settings.breakDuration.inWholeMilliseconds,
    )

    fun decode(values: Map<String, *>, fallback: SessionSettings = SessionMode.CUSTOM.defaults()): SessionSettings {
        fun long(key: String, default: Long) = values[key] as? Long ?: default
        val mode = (values["mode"] as? String)?.let { name -> SessionMode.entries.find { it.name == name } } ?: fallback.mode
        val end = (values["endAtMinute"] as? Int ?: fallback.endAtMinute ?: -1)
            .takeIf { it in 0..1439 && mode == SessionMode.SLEEP }
        return SessionSettings(
            if (end != null) null else long("duration", fallback.duration?.inWholeMilliseconds ?: 0)
                .takeIf { it > 0 }?.milliseconds,
            long("fadeIn", fallback.fadeIn.inWholeMilliseconds).coerceIn(0, 60_000).milliseconds,
            long("fadeOut", fallback.fadeOut.inWholeMilliseconds).coerceIn(0, 60_000).milliseconds,
            mode, end, values["completionCue"] as? Boolean ?: fallback.completionCue,
            long("breakDuration", fallback.breakDuration.inWholeMilliseconds).coerceIn(0, 15 * 60_000).milliseconds,
        )
    }
}

/** No streaks or history. A break is silent and never automatically starts another audio session. */
data class FocusProgress(
    val day: String = "",
    val count: Int = 0,
    val breakDeadlineMs: Long? = null,
    val bootCount: Int = -1,
) {
    fun countToday(today: String): Int = if (day == today) count else 0
    fun complete(today: String, nowMs: Long, breakDuration: Duration, boot: Int): FocusProgress =
        FocusProgress(today, (countToday(today).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            breakDuration.takeIf { it > Duration.ZERO }?.let { nowMs + it.inWholeMilliseconds }, boot)

    fun breakRemaining(nowMs: Long, boot: Int): Duration? =
        breakDeadlineMs?.takeIf { boot >= 0 && boot == bootCount }?.let { (it - nowMs).coerceAtLeast(0).milliseconds }

    fun clearBreak(): FocusProgress = copy(breakDeadlineMs = null)
}