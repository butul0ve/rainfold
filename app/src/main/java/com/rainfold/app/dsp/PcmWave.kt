package com.rainfold.app.dsp

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline asset contract: RIFF/WAVE, uncompressed signed PCM16, mono, renderer sample rate. */
object PcmWave {
    fun read(input: InputStream, sampleRate: Int = 48_000): FloatArray {
        val bytes = input.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE")
        val end = buffer.getInt(4).toLong() + 8
        require(end in 12..bytes.size.toLong())
        var position = 12
        var validFormat = false
        var data: FloatArray? = null
        while (position.toLong() + 8 <= end) {
            val size = buffer.getInt(position + 4)
            val start = position + 8
            require(size >= 0 && start.toLong() + size <= end)
            when (tag(position)) {
                "fmt " -> {
                    require(size >= 16)
                    require(buffer.getShort(start).toInt() == 1)
                    require(buffer.getShort(start + 2).toInt() == 1)
                    require(buffer.getInt(start + 4) == sampleRate)
                    require(buffer.getInt(start + 8) == sampleRate * 2)
                    require(buffer.getShort(start + 12).toInt() == 2)
                    require(buffer.getShort(start + 14).toInt() == 16)
                    validFormat = true
                }
                "data" -> {
                    require(size > 0 && size % 2 == 0)
                    data = FloatArray(size / 2) { buffer.getShort(start + it * 2) / 32768f }
                }
            }
            val next = start.toLong() + size + size % 2
            require(next <= end)
            position = next.toInt()
        }
        require(validFormat && position.toLong() == end)
        return requireNotNull(data)
    }
}