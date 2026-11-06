package com.rainfold.app.player

import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoisePlayerTest {
    @Test
    fun `rapid stop play restarts fade before renderer observes silence`() {
        val output = FakeOutput()
        val player = player { output }
        try {
            player.play(NoiseMix(NoiseType.WHITE, 1f), 30) {}
            repeat(6) {
                output.writes.await()
                output.buffers.await()
                output.results.add(512)
            }
            output.writes.await()
            output.buffers.await()
            player.fadeToSilence()
            player.play(NoiseMix(NoiseType.WHITE, 1f), 1000) {}
            output.results.add(512)
            output.writes.await()
            val restarted = output.buffers.await()
            assertTrue(restarted.any { it != 0f })
            assertTrue(restarted.all { kotlin.math.abs(it) <= 0.8f * 512 / 48_000 })
        } finally {
            player.stop()
        }
        assertEquals(1, output.releases.get())
    }

    @Test
    fun `old renderer failure keeps its callback when new playback starts during reporting`() {
        val first = FakeOutput()
        val second = FakeOutput()
        val outputs = LinkedBlockingQueue(listOf(first, second))
        val reporting = LinkedBlockingQueue<Unit>()
        val continueReporting = LinkedBlockingQueue<Unit>()
        val failures = LinkedBlockingQueue<String>()
        val player = NoisePlayer({ outputs.remove() }, { 0 }, {}, {
            reporting.add(Unit)
            continueReporting.await()
        }, { FloatArray(48_000) })
        try {
            player.play(NoiseMix(), 30) { id ->
                failures.add(if (player.isCurrentRenderer(id)) "old-current" else "old")
            }
            first.writes.await()
            first.results.add(-6)
            reporting.await()
            player.play(NoiseMix(), 30) { failures.add("new") }
            second.writes.await()
            continueReporting.add(Unit)
            assertEquals("old", failures.await())
            assertTrue(failures.isEmpty())
        } finally {
            continueReporting.offer(Unit)
            player.stop()
        }
        assertEquals(1, first.releases.get())
        assertEquals(1, second.releases.get())
    }

    @Test
    fun `queued failure becomes stale only when another renderer starts`() {
        val first = FakeOutput()
        val second = FakeOutput()
        val outputs = LinkedBlockingQueue(listOf(first, second))
        val failures = LinkedBlockingQueue<Long>()
        val player = player { outputs.remove() }
        try {
            player.play(NoiseMix(), 30) { failures.add(it) }
            first.writes.await()
            first.results.add(-6)
            val failedRenderer = failures.await()
            assertTrue(player.isCurrentRenderer(failedRenderer))
            player.play(NoiseMix(), 30) { failures.add(it) }
            second.writes.await()
            // The service performs this check when its posted error reaches the main thread.
            assertTrue(!player.isCurrentRenderer(failedRenderer))
        } finally {
            player.stop()
        }
    }

    @Test
    fun `quick resume continues current gain instead of jumping to silence`() {
        val output = FakeOutput()
        val player = NoisePlayer({ output }, { 0 }, {}, {}, { FloatArray(48_000) { 0.5f } })
        val mix = NoiseMix(noiseMuted = true, rainEnabled = true, rainVolume = 1f)
        try {
            player.play(mix, 30) {}
            repeat(6) {
                output.writes.await()
                output.buffers.await()
                output.results.add(512)
            }
            output.writes.await()
            assertEquals(0.2f, output.buffers.await().first(), 0.00001f)
            player.fadeToSilence()
            player.play(mix, 50, fadeFromSilence = false) {}
            output.results.add(512)
            output.writes.await()
            assertTrue(output.buffers.await().all { kotlin.math.abs(it - 0.2f) < 0.00001f })
        } finally {
            player.stop()
        }
    }

    @Test
    fun `new play after deadline fades in while reusing the output`() {
        val output = FakeOutput()
        val now = AtomicLong()
        val player = NoisePlayer({ output }, now::get, {}, {}, { FloatArray(48_000) })
        try {
            player.play(NoiseMix(NoiseType.WHITE, 1f), 30, deadline = 1000, fadeOutMs = 50) {}
            repeat(5) {
                output.writes.await()
                output.buffers.await()
                output.results.add(512)
            }
            output.writes.await()
            output.buffers.await()
            now.set(1000)
            output.results.add(512)
            output.writes.await()
            assertTrue(output.buffers.await().all { it == 0f })
            player.play(NoiseMix(NoiseType.WHITE, 1f), 1000, deadline = 2000, fadeOutMs = 50) {}
            output.results.add(512)
            output.writes.await()
            val resumed = output.buffers.await()
            // At most 512 frames into a one-second ramp, with 0.8 output headroom.
            assertTrue(resumed.all { kotlin.math.abs(it) <= 0.8f * 512 / 48_000 })
            assertTrue(resumed.any { it != 0f })
        } finally {
            player.stop()
        }
        assertEquals(1, output.releases.get())
    }

    @Test
    fun `deadline silences PCM without a service tick and resume retargets the same output`() {
        val output = FakeOutput()
        val now = AtomicLong()
        val failures = LinkedBlockingQueue<Unit>()
        val player = NoisePlayer({ output }, now::get, {}, {}, { FloatArray(48_000) })
        try {
            player.play(NoiseMix(), 30, deadline = 1000, fadeOutMs = 50) { failures.add(Unit) }
            output.writes.await()
            assertTrue(output.buffers.await().any { it != 0f })
            now.set(1000)
            output.results.add(512)
            output.writes.await()
            assertTrue(output.buffers.await().all { it == 0f })
            player.fadeToSilence()
            player.play(NoiseMix(), 50, deadline = 2000, fadeOutMs = 50) { failures.add(Unit) }
            output.results.add(512)
            output.writes.await()
            assertTrue(output.buffers.await().any { it != 0f })
        } finally {
            player.stop()
        }
        assertEquals(1, output.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `partial writes finish the current block and stop unblocks and releases once`() {
        val output = FakeOutput()
        val failures = LinkedBlockingQueue<Unit>()
        val player = player { output }
        try {
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            assertEquals(Write(0, 512), output.writes.await())
            output.results.add(100)
            assertEquals(Write(100, 412), output.writes.await())
            output.results.add(412)
            assertEquals(Write(0, 512), output.writes.await())
        } finally {
            player.stop()
        }
        player.stop()
        assertEquals(1, output.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `resume during fade reuses output and renderer errors reach the current callback after release`() {
        val output = FakeOutput()
        val creations = AtomicInteger()
        val failures = LinkedBlockingQueue<Int>()
        val player = player { creations.incrementAndGet(); output }
        try {
            player.play(NoiseMix(), 30) { failures.add(-1) }
            output.writes.await()
            assertTrue(player.fadeToSilence() >= 50)
            player.play(NoiseMix(NoiseType.BROWN, 0.2f), 50, fadeFromSilence = false) { id ->
                failures.add(if (player.isCurrentRenderer(id)) output.releases.get() else -2)
            }
            output.results.add(-6)
            assertEquals(1, failures.await())
            assertEquals(1, creations.get())
            assertTrue(failures.isEmpty())
        } finally {
            player.stop()
        }
        assertEquals(1, output.releases.get())
    }

    @Test
    fun `failed output is released before a new renderer starts`() {
        val first = FakeOutput()
        val second = FakeOutput()
        val outputs = LinkedBlockingQueue(listOf(first, second))
        val failures = LinkedBlockingQueue<Unit>()
        val player = player { outputs.remove() }
        try {
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            first.writes.await()
            first.results.add(-6)
            failures.await()
            assertEquals(1, first.releases.get())
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            second.writes.await()
        } finally {
            player.stop()
        }
        assertEquals(1, first.releases.get())
        assertEquals(1, second.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `play failure releases an allocated output and notifies once`() {
        val output = FakeOutput(playError = IllegalStateException("play failed"))
        val failures = LinkedBlockingQueue<Int>()
        val player = player { output }
        try {
            player.play(NoiseMix(), 30) { failures.add(output.releases.get()) }
            assertEquals(1, failures.await())
            assertTrue(failures.isEmpty())
        } finally {
            player.stop()
        }
        assertEquals(1, output.releases.get())
    }

    @Test
    fun `thread initialization failure notifies and does not prevent another play`() {
        val output = FakeOutput()
        val attempts = AtomicInteger()
        val failures = LinkedBlockingQueue<Unit>()
        val player = NoisePlayer(
            createOutput = { output },
            elapsedRealtime = { 0 },
            prepareThread = { check(attempts.getAndIncrement() > 0) },
            logFailure = {},
            loadRain = { FloatArray(48_000) },
        )
        try {
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            failures.await()
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            output.writes.await()
        } finally {
            player.stop()
        }
        assertEquals(2, attempts.get())
        assertEquals(1, output.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `release failure still clears the renderer and reports the error`() {
        val first = FakeOutput(releaseError = IllegalStateException("release failed"))
        val second = FakeOutput()
        val outputs = LinkedBlockingQueue(listOf(first, second))
        val failures = LinkedBlockingQueue<Unit>()
        val player = player { outputs.remove() }
        try {
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            first.writes.await()
            first.results.add(-6)
            failures.await()
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            second.writes.await()
        } finally {
            player.stop()
        }
        assertEquals(1, first.releases.get())
        assertEquals(1, second.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `rain transitions keep one output and the original deadline`() {
        val output = FakeOutput()
        val now = AtomicLong(100)
        val creations = AtomicInteger()
        val loads = AtomicInteger()
        val failures = LinkedBlockingQueue<Unit>()
        val player = NoisePlayer(
            { creations.incrementAndGet(); output }, now::get, {}, {},
            { loads.incrementAndGet(); FloatArray(48_000) { 0.5f } },
        )
        var mix = NoiseMix(noiseMuted = true, rainVolume = 1f)
        fun block(): FloatArray {
            output.writes.await()
            return output.buffers.await()
        }
        fun change(next: NoiseMix, expected: Float) {
            player.update(next)
            var pcm = FloatArray(0)
            repeat(6) { output.results.add(512); pcm = block() }
            assertTrue(pcm.all { kotlin.math.abs(it - expected) < 0.00001f })
        }
        try {
            player.play(mix, 30, deadline = 1000, fadeOutMs = 50) { failures.add(Unit) }
            assertTrue(block().all { it == 0f })
            mix = mix.copy(rainEnabled = true)
            change(mix, 0.2f)
            change(mix.copy(rainMuted = true), 0f)
            change(mix.copy(rainVolume = 0.25f), 0.05f)
            change(mix.copy(rainEnabled = false), 0f)
            change(mix, 0.2f)
            now.set(1000)
            output.results.add(512)
            assertTrue(block().all { it == 0f })
            player.update(mix.copy(rainVolume = 0.9f))
            output.results.add(512)
            assertTrue(block().all { it == 0f })
        } finally {
            player.stop()
        }
        assertEquals(1, creations.get())
        assertEquals(1, loads.get())
        assertEquals(1, output.releases.get())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `unreadable rain asset reports an error without allocating an output`() {
        val creations = AtomicInteger()
        val failures = LinkedBlockingQueue<Unit>()
        val player = NoisePlayer(
            { creations.incrementAndGet(); FakeOutput() }, { 0 }, {}, {},
            { throw java.io.IOException("Unreadable asset") },
        )
        try {
            player.play(NoiseMix(), 30) { failures.add(Unit) }
            failures.await()
            assertEquals(0, creations.get())
            assertTrue(failures.isEmpty())
        } finally {
            player.stop()
        }
    }

    private fun player(factory: () -> AudioOutput) = NoisePlayer(factory, { 0 }, {}, {}, { FloatArray(48_000) })

    private data class Write(val offset: Int, val size: Int)

    private class FakeOutput(
        private val playError: RuntimeException? = null,
        private val releaseError: RuntimeException? = null,
    ) : AudioOutput {
        override val bufferSizeInFrames = 1024
        override val underrunCount = 0
        val writes = LinkedBlockingQueue<Write>()
        val buffers = LinkedBlockingQueue<FloatArray>()
        val results = LinkedBlockingQueue<Int>()
        val releases = AtomicInteger()

        override fun play() {
            playError?.let { throw it }
        }

        override fun write(buffer: FloatArray, offset: Int, size: Int): Int {
            buffers.add(buffer.copyOfRange(offset, offset + size))
            writes.add(Write(offset, size))
            return results.await()
        }

        override fun pause() {
            results.add(0)
        }

        override fun release() {
            releases.incrementAndGet()
            releaseError?.let { throw it }
        }
    }
}

private fun <T> LinkedBlockingQueue<T>.await(): T =
    checkNotNull(poll(5, TimeUnit.SECONDS)) { "Timed out waiting for renderer" }