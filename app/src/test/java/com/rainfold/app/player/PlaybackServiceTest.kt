package com.rainfold.app.player

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.domain.PlaybackCommand
import com.rainfold.app.domain.ScenePurpose
import com.rainfold.app.domain.SessionSettings
import com.rainfold.app.domain.PlaybackSession
import kotlin.time.Duration.Companion.minutes
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

    @Test
    fun `saved scene captures service mix and recreation restores edits in idle without changing recipe`() {
        var controller = Robolectric.buildService(PlaybackService::class.java).create()
        var service = controller.get()
        val mix = NoiseMix(NoiseType.BROWN, 0.23f, true, 0.67f, true, true)
        var clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        clock.dispatch(PlaybackCommand.UpdateMix(mix))
        service.scene("save", name = "Сон", purpose = ScenePurpose.SLEEP)
        val saved = SceneLibraryJson.decode(service.onGetSessionForTest().sessionExtras.getString(SceneLibraryJson.KEY))
        val id = checkNotNull(saved.selectedId)
        service.scene("rename", id, "Ночь")
        assertEquals("Ночь", service.onGetSessionForTest().player.mediaMetadata.title.toString())
        service.scene("favorite", id)
        // A later unsaved layer edit must not mutate the saved recipe.
        clock.dispatch(PlaybackCommand.UpdateMix(mix.copy(rainVolume = 0.1f)))
        service.scene("favorite", "builtin:quiet-focus") // persists authoritative current mix too
        assertEquals(service.getString(com.rainfold.app.R.string.scene_modified, "Ночь"),
            service.onGetSessionForTest().player.mediaMetadata.title.toString())
        controller.destroy()
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        service = controller.get()
        try {
            clock = ReflectionHelpers.getField(service, "clockSession")
            val restored = SceneLibraryJson.decode(service.onGetSessionForTest().sessionExtras.getString(SceneLibraryJson.KEY))
            assertEquals(id, restored.selectedId)
            assertEquals("Ночь", restored.selected!!.name)
            assertEquals(mix, restored.selected!!.mix)
            assertEquals(setOf(id, "builtin:quiet-focus"), restored.favorites)
            assertEquals(mix.copy(rainVolume = 0.1f), clock.mix)
            assertEquals(PlaybackState.Idle, clock.state())
            assertFalse(ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock").isHeld)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `scene selection copy and deletion during playback keep output deadline and timer settings`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val output = BlockingOutput()
        val creations = AtomicInteger()
        val engine = NoisePlayer({ creations.incrementAndGet(); output }, { 0 }, {}, {}, { FloatArray(48_000) })
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(engine))
        val session = service.onGetSessionForTest()
        val clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        clock.configure(SessionSettings(duration = 30.minutes))
        try {
            session.player.play()
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            val deadline = clock.deadline
            service.scene("select", "builtin:quiet-focus")
            assertEquals(deadline, clock.deadline)
            service.scene("save", name = "Work", purpose = ScenePurpose.FOCUS)
            val saved = SceneLibraryJson.decode(session.sessionExtras.getString(SceneLibraryJson.KEY))
            service.scene("copy", "builtin:rainy-window", "Rain copy")
            val copied = SceneLibraryJson.decode(session.sessionExtras.getString(SceneLibraryJson.KEY))
            assertEquals(saved.selectedId, copied.selectedId)
            assertEquals(saved.selected!!.mix, clock.mix)
            assertEquals(2, copied.custom.size)
            service.scene("delete", checkNotNull(saved.selectedId))
            assertTrue(clock.state() is PlaybackState.Playing)
            assertEquals(deadline, clock.deadline)
            assertEquals(30.minutes, clock.settings.duration)
            assertEquals(1, creations.get())
            assertEquals(0, output.releases.get())
            assertEquals(saved.selected!!.mix, clock.mix)
        } finally {
            controller.destroy()
        }
        assertEquals(1, output.releases.get())
    }

    @Test
    fun `select while paused stays silent and explicit scene play resumes remaining time`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val output = BlockingOutput()
        val engine = NoisePlayer({ output }, { 0 }, {}, {}, { FloatArray(48_000) })
        ReflectionHelpers.setField(service, "engine\$delegate", lazyOf(engine))
        val session = service.onGetSessionForTest()
        val clock = ReflectionHelpers.getField<PlaybackSession>(service, "clockSession")
        clock.configure(SessionSettings(duration = 15.minutes))
        try {
            service.scene("select", "builtin:rainy-window")
            assertEquals(PlaybackState.Idle, clock.state())
            assertFalse(ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock").isHeld)
            service.scene("play", "builtin:deep-sleep")
            assertTrue(output.writes.poll(5, TimeUnit.SECONDS) != null)
            session.player.pause()
            val remaining = (clock.state() as PlaybackState.Paused).remaining
            service.scene("select", "builtin:rainy-window")
            assertTrue(clock.state() is PlaybackState.Paused)
            assertEquals(remaining, (clock.state() as PlaybackState.Paused).remaining)
            service.scene("play", "builtin:quiet-focus")
            assertTrue(clock.state() is PlaybackState.Playing)
            assertEquals(remaining, (clock.state() as PlaybackState.Playing).remaining)
        } finally {
            controller.destroy()
        }
    }

    private fun PlaybackService.onGetSessionForTest(): MediaSession = ReflectionHelpers.getField(this, "mediaSession")

    private fun PlaybackService.scene(operation: String, id: String? = null, name: String? = null, purpose: ScenePurpose? = null) {
        handleSceneCommand(Bundle().apply {
            putString("operation", operation)
            putString("id", id)
            putString("name", name)
            putString("purpose", purpose?.name)
        })
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