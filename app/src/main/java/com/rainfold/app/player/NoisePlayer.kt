package com.rainfold.app.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.rainfold.app.BuildConfig
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.dsp.AudioLimiter
import com.rainfold.app.dsp.GainRamp
import com.rainfold.app.dsp.SessionFade
import com.rainfold.app.dsp.SeamlessLoop
import com.rainfold.app.dsp.SoundLayerMixer
import com.rainfold.app.dsp.CompletionCue
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
    private val loadRain: () -> FloatArray,
) {
    constructor(loadRain: () -> FloatArray) : this(
        ::createAudioOutput,
        SystemClock::elapsedRealtime,
        { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
        { error -> if (BuildConfig.DEBUG) Log.e("RainfoldAudio", "Renderer failed", error) },
        loadRain,
    )

    private data class Target(
        val mix: NoiseMix,
        val gain: Float,
        val rampMs: Long,
        val deadlineMs: Long? = null,
        val fadeOutMs: Long = 30,
        val playId: Long = 0,
        val fadeFromSilence: Boolean = true,
        val sequentialFade: Boolean = false,
        val cueStartedMs: Long? = null,
    )

    private val target = AtomicReference(Target(NoiseMix(), 0f, 30))
    private val running = AtomicBoolean(false)
    private var errorCallback: ((Long) -> Unit)? = null
    @Volatile
    private var rendererId = 0L
    private var renderThread: Thread? = null
    @Volatile
    private var audioTrack: AudioOutput? = null

    @Synchronized
    fun play(
        mix: NoiseMix,
        fadeMs: Long,
        deadline: Long? = null,
        fadeOutMs: Long = 0,
        fadeFromSilence: Boolean = true,
        sequentialFade: Boolean = false,
        onError: (Long) -> Unit,
    ) {
        errorCallback = onError
        // Publish the deadline and playback target together, including when reusing a live output.
        target.set(
            Target(
                mix, 1f, fadeMs.coerceAtLeast(30), deadline,
                fadeOutMs.coerceAtLeast(30), target.get().playId + 1, fadeFromSilence, sequentialFade,
            )
        )
        if (running.getAndSet(true)) return
        val id = ++rendererId
        renderThread = thread(name = "RainfoldAudio", isDaemon = true) {
            var track: AudioOutput? = null
            var failure: RuntimeException? = null
            try {
                prepareThread()
                val mixer = SoundLayerMixer(SeamlessLoop(loadRain(), SAMPLE_RATE / 2))
                track = createOutput()
                audioTrack = track
                var lastDiagnostic = elapsedRealtime()
                var maxRenderNs = 0L
                val envelope = GainRamp()
                var previous: Target? = null
                val buffer = FloatArray(BUFFER_FRAMES)
                track.play()
                while (running.get()) {
                    val renderStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    val next = target.get()
                    val now = elapsedRealtime()
                    if (next !== previous) {
                        val newPlay = previous != null && next.playId != previous.playId
                        // A Stop/Play transition can be published between two render blocks.
                        if (newPlay && next.fadeFromSilence) envelope.moveTo(0f, 0)
                        if (previous == null || next.gain != previous.gain || newPlay) {
                            envelope.moveTo(next.gain, (next.rampMs * SAMPLE_RATE / 1000).toInt())
                        }
                        mixer.update(next.mix)
                        previous = next
                    }
                    val remaining = next.deadlineMs?.minus(now)
                    buffer.indices.forEach { index ->
                        val sampleRemaining = remaining?.let { it - index * 1000.0 / SAMPLE_RATE }
                        val mixed = if (next.sequentialFade) mixer.nextSample(
                            SessionFade.noiseGain(sampleRemaining, next.fadeOutMs),
                            SessionFade.rainGain(sampleRemaining, next.fadeOutMs),
                        ) else mixer.nextSample() * SessionFade.gain(sampleRemaining, next.fadeOutMs)
                        val cue = next.cueStartedMs?.let {
                            CompletionCue.sample(now - it + index * 1000.0 / SAMPLE_RATE)
                        }
                        buffer[index] = AudioLimiter.limit(
                            (cue ?: mixed) * envelope.next()
                        )
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
            } catch (error: Exception) {
                failure = error as? RuntimeException ?: IllegalStateException("Audio initialization failed", error)
            } finally {
                try {
                    track?.release()
                } catch (error: RuntimeException) {
                    if (failure == null) failure = error
                }
                // Capture the callback before allowing play() to create another renderer.
                // Cleanup must not overwrite the next renderer's output or callback.
                val callback = synchronized(this@NoisePlayer) {
                    audioTrack = null
                    if (running.getAndSet(false)) errorCallback else null
                }
                failure?.let { error ->
                    if (callback != null) {
                        logFailure(error)
                        callback(id)
                    }
                }
            }
        }
    }

    /** Also checked after posting a failure to the service's main thread. */
    fun isCurrentRenderer(id: Long): Boolean = rendererId == id

    fun update(mix: NoiseMix) {
        target.set(target.get().copy(mix = mix))
    }

    /** Called only by the service after a naturally completed Focus session with audio focus. */
    fun playCompletionCue() {
        target.set(target.get().copy(gain = 1f, rampMs = 30, deadlineMs = null,
            cueStartedMs = elapsedRealtime(), sequentialFade = false))
    }

    fun fadeToSilence(): Long {
        target.set(target.get().copy(gain = 0f, rampMs = 50))
        val queuedFrames =
            audioTrack?.runCatching { bufferSizeInFrames }?.getOrNull() ?: BUFFER_FRAMES * 2
        return 50L + (queuedFrames + BUFFER_FRAMES * 2) * 1000L / SAMPLE_RATE
    }

    /** Only called after the transition has reached silence, or during destruction. */
    fun stop() {
        rendererId++
        running.set(false)
        audioTrack?.runCatching { pause() }
        renderThread?.join()
        renderThread = null
        audioTrack = null
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