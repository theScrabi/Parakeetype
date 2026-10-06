package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [WavReader] — WAV parsing, resampling, channel downmix.
 *
 * These tests construct WAV files programmatically (no external resources needed)
 * and verify the reader produces correct [AudioChunk] output.
 */
class WavReaderTest {

    private val reader = WavReader()

    // ─── Basic WAV parsing ──────────────────────────────────────────────────

    @Test
    fun `readPcm16 parses a simple mono 16-bit WAV`() {
        val wav = buildWav(
            samples = shortArrayOf(100, -200, 300, -400, 500),
            sampleRate = 16_000,
            channels = 1,
        )
        val pcm = reader.readPcm16(ByteArrayInputStream(wav))

        assertThat(pcm.sampleRate).isEqualTo(16_000)
        assertThat(pcm.channels).isEqualTo(1)
        assertThat(pcm.samples).isEqualTo(shortArrayOf(100, -200, 300, -400, 500))
    }

    @Test
    fun `readPcm16 rejects non-PCM format`() {
        val wav = buildWav(
            samples = shortArrayOf(1, 2, 3),
            sampleRate = 16_000,
            channels = 1,
            audioFormat = 6, // IEEE float, not PCM
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            reader.readPcm16(ByteArrayInputStream(wav))
        }
        // JUnit 4 fallback
        try {
            reader.readPcm16(ByteArrayInputStream(wav))
            org.junit.Assert.fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("format code 6")
        }
    }

    @Test
    fun `readPcm16 rejects non-16-bit samples`() {
        val wav = buildWav24bit(
            samples = byteArrayOf(0, 0, 1, 0, 0, 2),
            sampleRate = 44_100,
            channels = 1,
        )
        try {
            reader.readPcm16(ByteArrayInputStream(wav))
            org.junit.Assert.fail("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("24")
        }
    }

    @Test
    fun `readPcm16 rejects missing RIFF header`() {
        val bad = ByteArray(100) { 0 }
        try {
            reader.readPcm16(ByteArrayInputStream(bad))
            org.junit.Assert.fail("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("RIFF")
        }
    }

    // ─── Stereo to mono downmix ─────────────────────────────────────────────

    @Test
    fun `loadAsSingleChunk downmixes stereo to mono by averaging`() {
        // Stereo: L=200, R=400 → mono average = 300
        val samples = shortArrayOf(200, 400, 600, 800)
        val wav = buildWav(samples, 16_000, channels = 2)
        val chunk = reader.loadAsSingleChunk(ByteArrayInputStream(wav))

        assertThat(chunk.samples).containsExactly(300.toShort(), 700.toShort())
        assertThat(chunk.sampleRate).isEqualTo(16_000)
    }

    // ─── Resampling ─────────────────────────────────────────────────────────

    @Test
    fun `loadAsSingleChunk resamples 8 kHz to 16 kHz`() {
        // 8 kHz: 4 samples → at 16 kHz should be ~8 samples (each original doubled)
        val samples = shortArrayOf(100, 200, 300, 400)
        val wav = buildWav(samples, sampleRate = 8_000, channels = 1)
        val chunk = reader.loadAsSingleChunk(ByteArrayInputStream(wav))

        assertThat(chunk.sampleRate).isEqualTo(16_000)
        assertThat(chunk.samples.size).isEqualTo(8)
        // After interpolation, values should be near original at even indices
        assertThat(chunk.samples[0]).isEqualTo(100.toShort())
        assertThat(chunk.samples[4]).isEqualTo(300.toShort())
    }

    @Test
    fun `resampleLinear handles identity (same rate) correctly`() {
        val input = shortArrayOf(100, 200, 300)
        val output = reader.resampleLinear(input, 16_000, 16_000)
        assertThat(output).containsExactly(100.toShort(), 200.toShort(), 300.toShort())
    }

    // ─── Chunk splitting ────────────────────────────────────────────────────

    @Test
    fun `loadAsChunks splits into 40 ms chunks`() {
        // 160 samples = 10 ms at 16 kHz → should produce 4 chunks of 40 samples each
        val samples = ShortArray(160) { it.toShort() }
        val wav = buildWav(samples, 16_000, 1)
        val chunks = reader.loadAsChunks(ByteArrayInputStream(wav), chunkDurationMs = 40)

        // 160 samples / 640 per chunk = 1 chunk (last chunk < 640 samples)
        assertThat(chunks.size).isEqualTo(1)
        assertThat(chunks[0].samples.size).isEqualTo(160)
    }

    @Test
    fun `loadAsChunks produces multiple chunks for long audio`() {
        // 2000 samples at 16 kHz → at 40 ms (640 samples/chunk) = 4 chunks (640+640+640+80)
        val samples = ShortArray(2000) { 100 }
        val wav = buildWav(samples, 16_000, 1)
        val chunks = reader.loadAsChunks(ByteArrayInputStream(wav), chunkDurationMs = 40)

        assertThat(chunks.size).isEqualTo(4)
        assertThat(chunks[0].samples.size).isEqualTo(640)
        assertThat(chunks[1].samples.size).isEqualTo(640)
        assertThat(chunks[2].samples.size).isEqualTo(640)
        assertThat(chunks[3].samples.size).isEqualTo(80)
    }

    // ─── Integration with generated WAV files ───────────────────────────────
    @Test
    fun `loadAsSingleChunk reads generated silence-only wav from classpath`() {
        val stream = openWavFixture("silence-only.wav")
        val chunk = reader.loadAsSingleChunk(stream)

        assertThat(chunk.sampleRate).isEqualTo(16_000)
        // 3 seconds at 16 kHz = 48 000 samples
        assertThat(chunk.samples.size).isEqualTo(48_000)
        // All samples should be zero (silence)
        assertThat(chunk.samples.toList().all { it == 0.toShort() }).isTrue()
    }
    @Test
    fun `loadAsSingleChunk reads generated noise-only wav from classpath`() {
        val stream = openWavFixture("noise-only.wav")
        val chunk = reader.loadAsSingleChunk(stream)

        assertThat(chunk.sampleRate).isEqualTo(16_000)
        assertThat(chunk.samples.size).isEqualTo(48_000)
        // Noise should have non-zero samples
        assertThat(chunk.samples.any { it != 0.toShort() }).isTrue()
    }

    // ─── WAV builder helpers ────────────────────────────────────────────────

    /** Build a minimal valid 16-bit PCM WAV file byte array. */
    private fun buildWav(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int = 1,
        audioFormat: Int = 1, // 1 = PCM
    ): ByteArray {
        val dataLen = samples.size * 2
        val fileLen = 36 + dataLen
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header — tag ints are the little-endian encoding of the 4 ASCII bytes
        bb.putInt(0x46464952) // "RIFF"
        bb.putInt(fileLen)
        bb.putInt(0x45564157) // "WAVE"
        // fmt chunk
        bb.putInt(0x20746D66) // "fmt "
        bb.putInt(16)
        bb.putShort(audioFormat.toShort())
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 2)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16.toShort())
        // data chunk
        bb.putInt(0x61746164) // "data"
        bb.putInt(dataLen)
        for (s in samples) bb.putShort(s)

        return bb.array()
    }

    /** Build a 24-bit WAV (for rejecting non-16-bit tests). */
    private fun buildWav24bit(
        samples: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
    ): ByteArray {
        val dataLen = samples.size
        val fileLen = 36 + dataLen
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)

        bb.putInt(0x46464952) // "RIFF"
        bb.putInt(fileLen)
        bb.putInt(0x45564157) // "WAVE"
        bb.putInt(0x20746D66) // "fmt "
        bb.putInt(16)
        bb.putShort(1.toShort()) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 3)
        bb.putShort((channels * 3).toShort())
        bb.putShort(24.toShort()) // 24-bit
        bb.putInt(0x61746164) // "data"
        bb.putInt(dataLen)
        bb.put(samples)

        return bb.array()
    }
}
