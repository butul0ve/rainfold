package com.rainfold.app.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.rainfold.app.domain.NoiseMix
import com.rainfold.app.domain.NoiseType
import com.rainfold.app.dsp.AudioLimiter
import com.rainfold.app.dsp.BrownNoiseGenerator
import com.rainfold.app.dsp.NoiseGenerator
import com.rainfold.app.dsp.PinkNoiseGenerator
import com.rainfold.app.dsp.WhiteNoiseGenerator
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NoisePlayer {
    private val isPlaying = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    @Synchronized
    fun play(mix: NoiseMix) {
        stop()
        val track = createTrack()
        audioTrack = track
        isPlaying.set(true)
        renderThread = thread(name = "RainfoldAudio", isDaemon = true) {
            val generator = generatorFor(mix.noiseType)
            val buffer = FloatArray(BUFFER_FRAMES)
            track.play()
            while (isPlaying.get()) {
                buffer.indices.forEach { index ->
                    buffer[index] = AudioLimiter.limit(generator.nextSample() * mix.noiseVolume)
                }
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    @Synchronized
    fun stop() {
        isPlaying.set(false)
        renderThread?.join(200)
        renderThread = null
        audioTrack?.runCatching {
            pause()
            flush()
            release()
        }
        audioTrack = null
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, BUFFER_FRAMES * Float.SIZE_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun generatorFor(type: NoiseType): NoiseGenerator = when (type) {
        NoiseType.WHITE -> WhiteNoiseGenerator()
        NoiseType.PINK -> PinkNoiseGenerator()
        NoiseType.BROWN -> BrownNoiseGenerator()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BUFFER_FRAMES = 2_048
    }
}