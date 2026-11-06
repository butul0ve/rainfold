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

/** One render thread owns and releases AudioTrack. Playback transitions are scheduled by the service. */
class NoisePlayer {
    private data class Target(val mix: NoiseMix, val gain: Float, val rampMs: Long)

    private val target = AtomicReference(Target(NoiseMix(), 0f, 30))
    private val running = AtomicBoolean(false)
    private val errorCallback = AtomicReference<(() -> Unit)?>(null)
    private var renderThread: Thread? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var deadlineMs: Long? = null
    @Volatile
    private var fadeOutMs = 0L

    fun play(mix: NoiseMix, fadeMs: Long, onError: () -> Unit) {
        errorCallback.set(onError)
        target.set(Target(mix, 1f, fadeMs.coerceAtLeast(30)))
        if (running.getAndSet(true)) return
        renderThread = thread(name = "RainfoldAudio", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var track: AudioTrack? = null
            try {
                track = createTrack()
                audioTrack = track
                val generators = NoiseType.entries.map(::generatorFor).toTypedArray()
                val weights = Array(generators.size) { GainRamp() }
                var lastDiagnostic = SystemClock.elapsedRealtime()
                var maxRenderNs = 0L
                val envelope = GainRamp()
                val volume = GainRamp(mix.noiseVolume)
                var previous: Target? = null
                val buffer = FloatArray(BUFFER_FRAMES)
                track.play()
                while (running.get()) {
                    val renderStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    val next = target.get()
                    if (next !== previous) {
                        if (previous == null || next.gain != previous.gain) {
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
                    val remaining = deadlineMs?.minus(SystemClock.elapsedRealtime())
                    buffer.indices.forEach { index ->
                        val timedGain = SessionFade.gain(
                            remaining?.let { it - index * 1000.0 / SAMPLE_RATE }, fadeOutMs,
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
                        if (SystemClock.elapsedRealtime() - lastDiagnostic >= 10_000) {
                            Log.d(
                                "RainfoldAudio",
                                "underruns=${track.underrunCount}, bufferFrames=${track.bufferSizeInFrames}, maxRenderUs=${maxRenderNs / 1000}"
                            )
                            lastDiagnostic = SystemClock.elapsedRealtime()
                            maxRenderNs = 0
                        }
                    }
                    var offset = 0
                    while (running.get() && offset < buffer.size) {
                        val written = track.write(
                            buffer,
                            offset,
                            buffer.size - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        check(written > 0) { "AudioTrack write failed: $written" }
                        offset += written
                    }
                }
            } catch (error: RuntimeException) {
                if (BuildConfig.DEBUG) Log.e("RainfoldAudio", "Renderer failed", error)
                if (running.get()) errorCallback.get()?.invoke()
            } finally {
                track?.release()
                audioTrack = null
                running.set(false)
            }
        }
    }

    fun update(mix: NoiseMix) {
        target.set(target.get().copy(mix = mix, rampMs = 30))
    }

    fun setDeadline(deadline: Long?, fadeMs: Long) {
        fadeOutMs = fadeMs.coerceAtLeast(30)
        deadlineMs = deadline
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

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
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

    private fun generatorFor(type: NoiseType): NoiseGenerator = when (type) {
        NoiseType.WHITE -> WhiteNoiseGenerator()
        NoiseType.PINK -> PinkNoiseGenerator()
        NoiseType.BROWN -> BrownNoiseGenerator()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BUFFER_FRAMES = 512
    }
}