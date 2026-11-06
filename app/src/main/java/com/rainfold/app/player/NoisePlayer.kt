package com.rainfold.app.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.rainfold.app.BuildConfig
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.dsp.AudioLimiter
import com.rainfold.app.dsp.BrownNoiseGenerator
import com.rainfold.app.dsp.GainRamp
import com.rainfold.app.dsp.NoiseGenerator
import com.rainfold.app.dsp.PinkNoiseGenerator
import com.rainfold.app.dsp.SessionFade
import com.rainfold.app.dsp.WhiteNoiseGenerator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val SAMPLE_RATE = 48_000
private const val BUFFER_FRAMES = 512

/** One render thread owns and releases AudioTrack. Playback transitions are scheduled by the service. */
class NoisePlayer internal constructor(
    private val createOutput: () -> AudioOutput,
    private val elapsedRealtime: () -> Long,
    private val prepareThread: () -> Unit,
    private val logFailure: (RuntimeException) -> Unit,
) {
    constructor() : this(
        ::createAudioOutput,
        SystemClock::elapsedRealtime,
        { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
        { error -> if (BuildConfig.DEBUG) Log.e("RainfoldAudio", "Renderer failed", error) },
    )

    private data class Target(
        val mix: NoiseMix,
        val gain: Float,
        val rampMs: Long,
        val deadlineMs: Long? = null,
        val fadeOutMs: Long = 30,
        val playId: Long = 0,
    )

    private val target = AtomicReference(Target(NoiseMix(), 0f, 30))
    private val running = AtomicBoolean(false)
    private val errorCallback = AtomicReference<(() -> Unit)?>(null)
    private var renderThread: Thread? = null
    @Volatile
    private var audioTrack: AudioOutput? = null

    fun play(
        mix: NoiseMix,
        fadeMs: Long,
        deadline: Long? = null,
        fadeOutMs: Long = 0,
        onError: () -> Unit,
    ) {
        errorCallback.set(onError)
        // Publish the deadline and playback target together, including when reusing a live output.
        target.set(
            Target(
                mix, 1f, fadeMs.coerceAtLeast(30), deadline,
                fadeOutMs.coerceAtLeast(30), target.get().playId + 1,
            )
        )
        if (running.getAndSet(true)) return
        renderThread = thread(name = "RainfoldAudio", isDaemon = true) {
            var track: AudioOutput? = null
            var failure: RuntimeException? = null
            try {
                prepareThread()
                track = createOutput()
                audioTrack = track
                val generators = NoiseType.entries.map(::generatorFor).toTypedArray()
                val weights = Array(generators.size) { GainRamp() }
                var lastDiagnostic = elapsedRealtime()
                var maxRenderNs = 0L
                val envelope = GainRamp()
                val volume = GainRamp(mix.noiseVolume)
                var previous: Target? = null
                val buffer = FloatArray(BUFFER_FRAMES)
                track.play()
                while (running.get()) {
                    val renderStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    val next = target.get()
                    val now = elapsedRealtime()
                    if (next !== previous) {
                        val restartAfterExpiry = previous != null && next.playId != previous.playId &&
                            previous.deadlineMs?.let { it <= now } == true
                        if (restartAfterExpiry) envelope.moveTo(0f, 0)
                        if (previous == null || next.gain != previous.gain || restartAfterExpiry) {
                            envelope.moveTo(next.gain, (next.rampMs * SAMPLE_RATE / 1000).toInt())
                        }
                        volume.moveTo(next.mix.noiseVolume, SAMPLE_RATE / 30)
                        if (previous?.mix?.noiseType != next.mix.noiseType) {
                            weights.forEachIndexed { index, ramp ->
                                ramp.moveTo(
                                    if (index == next.mix.noiseType.ordinal) 1f else 0f,
                                    SAMPLE_RATE / 20
                                )
                            }
                        }
                        previous = next
                    }
                    val remaining = next.deadlineMs?.minus(now)
                    buffer.indices.forEach { index ->
                        val timedGain = SessionFade.gain(
                            remaining?.let { it - index * 1000.0 / SAMPLE_RATE }, next.fadeOutMs,
                        )
                        var sample = 0f
                        for (source in generators.indices) {
                            sample += generators[source].nextSample() * weights[source].next()
                        }
                        buffer[index] =
                            AudioLimiter.limit(sample * volume.next() * envelope.next() * timedGain * 0.8f)
                    }
                    if (BuildConfig.DEBUG) {
                        maxRenderNs = maxOf(maxRenderNs, System.nanoTime() - renderStart)
                        if (elapsedRealtime() - lastDiagnostic >= 10_000) {
                            Log.d(
                                "RainfoldAudio",
                                "underruns=${track.underrunCount}, bufferFrames=${track.bufferSizeInFrames}, maxRenderUs=${maxRenderNs / 1000}"
                            )
                            lastDiagnostic = elapsedRealtime()
                            maxRenderNs = 0
                        }
                    }
                    var offset = 0
                    while (running.get() && offset < buffer.size) {
                        val written = track.write(
                            buffer,
                            offset,
                            buffer.size - offset,
                        )
                        check(written > 0) { "AudioTrack write failed: $written" }
                        offset += written
                    }
                }
            } catch (error: RuntimeException) {
                failure = error
            } finally {
                try {
                    track?.release()
                } catch (error: RuntimeException) {
                    if (failure == null) failure = error
                }
                audioTrack = null
                val reportFailure = running.getAndSet(false)
                failure?.let { error ->
                    if (reportFailure) {
                        logFailure(error)
                        errorCallback.get()?.invoke()
                    }
                }
            }
        }
    }

    fun update(mix: NoiseMix) {
        target.set(target.get().copy(mix = mix))
    }

    fun fadeToSilence(): Long {
        target.set(target.get().copy(gain = 0f, rampMs = 50))
        val queuedFrames =
            audioTrack?.runCatching { bufferSizeInFrames }?.getOrNull() ?: BUFFER_FRAMES * 2
        return 50L + (queuedFrames + BUFFER_FRAMES * 2) * 1000L / SAMPLE_RATE
    }

    /** Only called after the transition has reached silence, or during destruction. */
    fun stop() {
        running.set(false)
        audioTrack?.runCatching { pause() }
        renderThread?.join()
        renderThread = null
        audioTrack = null
    }

    private fun generatorFor(type: NoiseType): NoiseGenerator = when (type) {
        NoiseType.WHITE -> WhiteNoiseGenerator()
        NoiseType.PINK -> PinkNoiseGenerator()
        NoiseType.BROWN -> BrownNoiseGenerator()
    }
}

/** The renderer can exercise writes and cleanup in JVM tests without Android resources. */
internal interface AudioOutput {
    val bufferSizeInFrames: Int
    val underrunCount: Int
    fun play()
    fun write(buffer: FloatArray, offset: Int, size: Int): Int
    fun pause()
    fun release()
}

private fun createAudioOutput(): AudioOutput = AndroidAudioOutput()

private class AndroidAudioOutput : AudioOutput {
    private val track: AudioTrack

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, BUFFER_FRAMES * Float.SIZE_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    override val bufferSizeInFrames: Int get() = track.bufferSizeInFrames
    override val underrunCount: Int get() = track.underrunCount
    override fun play() = track.play()
    override fun write(buffer: FloatArray, offset: Int, size: Int): Int =
        track.write(buffer, offset, size, AudioTrack.WRITE_BLOCKING)
    override fun pause() = track.pause()
    override fun release() = track.release()
}