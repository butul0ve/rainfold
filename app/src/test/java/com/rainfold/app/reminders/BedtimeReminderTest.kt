package com.rainfold.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class BedtimeReminderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `reminders start disabled and enabling schedules one local alarm which disabling cancels`() {
        val reminder = BedtimeReminder(context)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertFalse(reminder.settings.enabled)
        reminder.schedule()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        reminder.configure(ReminderSettings(true, 23 * 60 + 15))
        assertEquals(ReminderSettings(true, 23 * 60 + 15), BedtimeReminder(context).settings)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms.scheduledAlarms.single().type)
        reminder.schedule()
        assertEquals(1, alarms.scheduledAlarms.size)
        reminder.configure(reminder.settings.copy(enabled = false))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `delivery is silent and optional and reschedules without launching playback`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val reminder = BedtimeReminder(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val receiver = BedtimeReminderReceiver()
        receiver.onReceive(context, Intent(BedtimeReminder.ACTION))
        assertEquals(0, shadowOf(notifications).allNotifications.size)
        reminder.configure(ReminderSettings(true))
        receiver.onReceive(context, Intent(BedtimeReminder.ACTION))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        val channel = notifications.getNotificationChannel(BedtimeReminder.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertNull(shadowOf(context).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        reminder.configure(ReminderSettings(false))
        assertEquals(0, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun `reboot and clock changes only reschedule an opted in reminder`() {
        BedtimeReminder(context).configure(ReminderSettings(true, 420))
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            BedtimeReminderReceiver().onReceive(context, Intent(action))
            assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
            assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        }
    }

    @Test
    @Config(sdk = [35])
    fun `denied notification permission never posts or starts playback`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BedtimeReminder(context).configure(ReminderSettings(true))
        BedtimeReminderReceiver().onReceive(context, Intent(BedtimeReminder.ACTION))
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertNull(shadowOf(context).nextStartedService)
    }
}