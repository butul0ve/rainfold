package com.rainfold.app.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmWaveTest {
    private fun wave(): ByteArray = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(42); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(48_000); putInt(96_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(6); putShort(-32768); putShort(0); putShort(32767)
    }.array()

    @Test
    fun `pcm16 keeps sign and scale at both full scale limits`() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768), PcmWave.read(wave().inputStream()), 0f)
    }

    @Test
    fun `unknown odd sized metadata chunk is skipped with padding`() {
        val original = wave()
        val withMetadata = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(original, 0, 36)
            put("JUNK".toByteArray()); putInt(1); put(42); put(0)
            put(original, 36, 14)
            putInt(4, 52)
        }.array()
        assertArrayEquals(PcmWave.read(original.inputStream()), PcmWave.read(withMetadata.inputStream()), 0f)
    }

    @Test
    fun `shipped rain recording matches the documented checksum and renderer contract`() {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("rain.wav")).use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        org.junit.Assert.assertEquals(
            "107e487f4442654f291be398b1a280cf2d9ad2a5d86d678718585a04822141a0", digest,
        )
        val pcm = PcmWave.read(bytes.inputStream())
        org.junit.Assert.assertEquals(1_440_000, pcm.size)
        org.junit.Assert.assertTrue(pcm.all { it.isFinite() && it in -1f..1f })
        val rms = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        org.junit.Assert.assertTrue(rms in 0.15..0.16)
        val loop = SeamlessLoop(pcm, 24_000)
        val period = pcm.size - 24_000
        val beginning = FloatArray(512) { loop.nextSample() }
        repeat(period - beginning.size) { loop.nextSample() }
        assertArrayEquals(beginning, FloatArray(512) { loop.nextSample() }, 0f)
    }

    @Test
    fun `wrong rate stereo compressed and truncated files are rejected`() {
        val invalid = listOf(
            wave().copyOf(49),
            wave().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 44_100) },
            wave().also { it[22] = 2 },
            wave().also { it[20] = 3 },
            wave().also { it[40] = 127 },
            ByteArray(4),
        )
        invalid.forEach { bytes ->
            try { PcmWave.read(bytes.inputStream()); throw AssertionError("Expected unsupported WAV to fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}