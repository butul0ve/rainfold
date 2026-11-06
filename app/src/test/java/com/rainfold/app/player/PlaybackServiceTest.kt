package com.rainfold.app.player

import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.rainfold.app.domain.PlaybackSession
import com.rainfold.app.domain.PlaybackState
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceTest {
    @Test
    fun `removing playing task stops playback and releases foreground and audio resources`() {
        checkTaskRemoval(pauseFirst = false)
    }

    @Test
    fun `removing paused task cancels pending release and stops service`() {
        checkTaskRemoval(pauseFirst = true)
    }

    @Test
    fun `posted error from old renderer cannot stop a new service session`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val first = BlockingOutput()
        val second = BlockingOutput()
        val outputs = LinkedBlockingQueue(listOf(first, second))
        val engine = NoisePlayer({ outputs.remove() }, { 0 }, {}, {}, { FloatArray(48_000) })
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(engine))
        val session = ReflectionHelpers.getField<MediaSession>(service, "mediaSession")
        val clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        try {
            session.player.play()
            assertTrue(first.writes.poll(5, TimeUnit.SECONDS) != null)
            val failedThread = ReflectionHelpers.getField<Thread>(engine, "renderThread")
            first.results.offer(-6)
            failedThread.join(5000)
            assertFalse(failedThread.isAlive)
            assertEquals(1, first.releases.get())
            // The error is posted but the service's main looper has not consumed it yet.
            session.player.stop()
            session.player.play()
            assertTrue(second.writes.poll(5, TimeUnit.SECONDS) != null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertTrue(clock.state() is PlaybackState.Playing)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "failed"))
            assertEquals(0, second.releases.get())
        } finally {
            controller.destroy()
        }
        assertEquals(1, second.releases.get())
    }

    private fun checkTaskRemoval(pauseFirst: Boolean) {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val output = BlockingOutput()
        val engine = NoisePlayer({ output }, { 0 }, {}, {}, { FloatArray(48_000) })
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(engine))
        val session = ReflectionHelpers.getField<MediaSession>(service, "mediaSession")
        val clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        val wakeLock = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock")
        try {
            session.player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            assertTrue(clock.state() is PlaybackState.Playing)
            assertTrue(wakeLock.isHeld)
            if (pauseFirst) session.player.pause()
            service.onTaskRemoved(Intent())
            assertEquals(PlaybackState.Idle, clock.state())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(1, output.releases.get())
            assertFalse(wakeLock.isHeld)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally {
            controller.destroy()
        }
        assertEquals(1, output.releases.get())
    }

    private class BlockingOutput : AudioOutput {
        override val bufferSizeInFrames = 1024
        override val underrunCount = 0
        val writes = LinkedBlockingQueue<Unit>()
        val results = LinkedBlockingQueue<Int>()
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