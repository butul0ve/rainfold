package com.rainfold.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.rainfold.app.MainActivity
import com.rainfold.app.R
import com.rainfold.app.domain.LocalSessionTime
import com.rainfold.app.localization.LanguageSettings
import java.time.ZonedDateTime

data class ReminderSettings(val enabled: Boolean = false, val minuteOfDay: Int = 22 * 60 + 30)

/** Inexact, local and silent. No exact-alarm access or network is needed. */
class BedtimeReminder(private val context: Context) {
    private val preferences = context.getSharedPreferences("bedtime", Context.MODE_PRIVATE)
    val settings: ReminderSettings get() = ReminderSettings(preferences.getBoolean("enabled", false),
        preferences.getInt("minute", 22 * 60 + 30).coerceIn(0, 1439))

    fun configure(settings: ReminderSettings) {
        require(settings.minuteOfDay in 0..1439)
        preferences.edit {
            putBoolean("enabled", settings.enabled)
            putInt("minute", settings.minuteOfDay)
        }
        schedule()
        if (!settings.enabled) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun schedule() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, BedtimeReminderReceiver::class.java)
            .setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (settings.enabled) {
            val at = LocalSessionTime.next(settings.minuteOfDay, ZonedDateTime.now()).toInstant().toEpochMilli()
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun notifyIfEnabled() {
        if (!settings.enabled) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        val localized = LanguageSettings(context).localizedContext()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.bedtime_reminder),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(localized, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(localized.getString(R.string.bedtime_reminder))
            .setContentText(localized.getString(R.string.bedtime_message)).setContentIntent(launch)
            .setAutoCancel(true).build())
    }

    companion object {
        const val ACTION = "com.rainfold.app.BEDTIME"
        const val CHANNEL = "bedtime"
        const val NOTIFICATION_ID = 2001
    }
}

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = BedtimeReminder(context)
        if (intent.action == BedtimeReminder.ACTION) reminder.notifyIfEnabled()
        reminder.schedule()
    }
}