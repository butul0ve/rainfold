package com.rainfold.app.player

import android.app.Application
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackState
import com.rainfold.app.domain.SessionMode
import com.rainfold.app.domain.defaults
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration as SessionDuration
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
@Config(sdk = [35])
class SessionModesServiceTest {
    @Test
    fun `explicit stop at deadline cancels the cue even when expiry is observed by that command`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        var now = SessionDuration.ZERO
        ReflectionHelpers.setField(service, "clockSession", PlaybackSession { now })
        service.handleSettingsCommand(Bundle().apply {
            putSettings(SessionMode.FOCUS.defaults().copy(duration = 1.seconds, completionCue = true))
        })
        val output = BlockingOutput()
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(NoisePlayer(
            { output }, { now.inWholeMilliseconds }, {}, {}, { FloatArray(48_000) })))
        try {
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            now = 1.seconds
            service.session().player.stop()
            assertFalse(ReflectionHelpers.getField(service, "cuePlaying"))
            assertEquals(1, service.session().sessionExtras.getInt("focusToday"))
            assertEquals(-1L, service.session().sessionExtras.getLong("breakRemaining"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `natural focus completion counts once releases audio and restores a silent break after recreation`() {
        val context: Application = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 4)
        var controller = Robolectric.buildService(PlaybackService::class.java).create()
        var service = controller.get()
        service.handleSettingsCommand(Bundle().apply {
            putSettings(SessionMode.FOCUS.defaults().copy(duration = 1.seconds, breakDuration = 3.minutes))
        })
        val output = BlockingOutput()
        val creations = AtomicInteger()
        val engine = NoisePlayer({ creations.incrementAndGet(); output }, SystemClock::elapsedRealtime, {}, {}, { FloatArray(48_000) })
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(engine))
        try {
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1300))
            assertEquals(1, service.session().sessionExtras.getInt("focusToday"))
            assertTrue(service.session().sessionExtras.getLong("breakRemaining") in 170_000..180_000)
            assertEquals(PlaybackState.Idle, service.clock().state())
            assertEquals(1, output.releases.get())
            assertFalse(service.wakeLock().isHeld)
            assertFalse(ReflectionHelpers.getField(service, "cuePlaying"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, service.session().sessionExtras.getInt("focusToday"))
            assertEquals(1, creations.get())
        } finally {
            controller.destroy()
        }
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        service = controller.get()
        try {
            assertEquals(SessionMode.FOCUS, service.clock().settings.mode)
            assertEquals(1, service.session().sessionExtras.getInt("focusToday"))
            assertTrue(service.session().sessionExtras.getLong("breakRemaining") > 0)
            assertEquals(PlaybackState.Idle, service.clock().state())
            assertFalse(service.wakeLock().isHeld)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
            assertEquals(0L, service.session().sessionExtras.getLong("breakRemaining"))
            assertEquals(1, service.session().sessionExtras.getInt("focusToday"))
            assertEquals(PlaybackState.Idle, service.clock().state())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `enabled cue reuses output and transient focus loss cancels it without automatic restart`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        service.handleSettingsCommand(Bundle().apply {
            putSettings(SessionMode.FOCUS.defaults().copy(duration = 1.seconds, completionCue = true))
        })
        val output = BlockingOutput()
        val creations = AtomicInteger()
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(NoisePlayer(
            { creations.incrementAndGet(); output }, SystemClock::elapsedRealtime, {}, {}, { FloatArray(48_000) })))
        try {
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
            assertTrue(ReflectionHelpers.getField(service, "cuePlaying"))
            assertTrue(service.wakeLock().isHeld)
            assertEquals(0, output.releases.get())
            val focus = ReflectionHelpers.getField<AudioFocusRequest>(service, "focusRequest")
            val listener = ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(focus, "mFocusListener")
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertFalse(ReflectionHelpers.getField(service, "cuePlaying"))
            assertFalse(service.wakeLock().isHeld)
            assertEquals(1, output.releases.get())
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(PlaybackState.Idle, service.clock().state())
            assertEquals(1, creations.get())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `manual stop and sleep expiry never increment focus count or start a break`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        service.handleSettingsCommand(Bundle().apply { putSettings(SessionMode.FOCUS.defaults().copy(duration = 25.minutes)) })
        val output = BlockingOutput()
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(NoisePlayer(
            { output }, SystemClock::elapsedRealtime, {}, {}, { FloatArray(48_000) })))
        try {
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            service.session().player.stop()
            assertEquals(0, service.session().sessionExtras.getInt("focusToday"))
            assertEquals(-1L, service.session().sessionExtras.getLong("breakRemaining"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            service.handleSettingsCommand(Bundle().apply { putSettings(SessionMode.SLEEP.defaults().copy(duration = 1.seconds)) })
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(0, service.session().sessionExtras.getInt("focusToday"))
            assertEquals(-1L, service.session().sessionExtras.getLong("breakRemaining"))
            assertEquals(PlaybackState.Idle, service.clock().state())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `mode settings preserve scene data and cannot replace a playing or paused timer`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        service.handleSceneCommand(Bundle().apply { putString("operation", "select"); putString("id", "builtin:cozy-storm") })
        val originalLibrary = service.session().sessionExtras.getString(SceneLibraryJson.KEY)
        val originalMix = service.clock().mix
        service.handleSettingsCommand(Bundle().apply { putSettings(SessionMode.FOCUS.defaults()) })
        val output = BlockingOutput()
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(NoisePlayer(
            { output }, SystemClock::elapsedRealtime, {}, {}, { FloatArray(48_000) })))
        try {
            service.session().player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            val deadline = service.clock().deadline
            service.handleSettingsCommand(Bundle().apply { putSettings(SessionMode.SLEEP.defaults()) })
            assertEquals(SessionMode.FOCUS, service.clock().settings.mode)
            assertEquals(deadline, service.clock().deadline)
            service.session().player.pause()
            service.handleSettingsCommand(Bundle().apply { putSettings(SessionMode.SLEEP.defaults()) })
            assertEquals(SessionMode.FOCUS, service.clock().settings.mode)
            assertEquals(originalMix, service.clock().mix)
            assertEquals(originalLibrary, service.session().sessionExtras.getString(SceneLibraryJson.KEY))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `settings bundle round trip and partial updates preserve all unaddressed fields`() {
        val settings = SessionMode.SLEEP.defaults().copy(duration = null, endAtMinute = 430)
        assertEquals(settings, Bundle().apply { putSettings(settings) }.readSettings(SessionMode.CUSTOM.defaults()))
        assertEquals(settings.copy(fadeOut = 5.seconds), Bundle().apply { putLong("fadeOut", 5000) }.readSettings(settings))
        assertEquals(settings, Bundle().readSettings(settings))
    }

    private fun PlaybackService.session(): MediaSession = ReflectionHelpers.getField(this, "mediaSession")
    private fun PlaybackService.clock(): PlaybackSession = ReflectionHelpers.getField(this, "clockSession")
    private fun PlaybackService.wakeLock(): PowerManager.WakeLock = ReflectionHelpers.getField(this, "wakeLock")

    private class BlockingOutput : AudioOutput {
        override val bufferSizeInFrames = 1024
        override val underrunCount = 0
        val writes = LinkedBlockingQueue<Unit>()
        private val results = LinkedBlockingQueue<Int>()
        val releases = AtomicInteger()
        override fun play() {}
        override fun write(buffer: FloatArray, offset: Int, size: Int): Int {
            writes.offer(Unit)
            return checkNotNull(results.poll(5, TimeUnit.SECONDS))
        }
        override fun pause() { results.offer(0) }
        override fun release() { releases.incrementAndGet() }
    }
}