package org.schabi.parakeetype.inference

import org.schabi.parakeetype.audio.AudioChunk
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a WAV file and converts it to [AudioChunk]s at 16 kHz / 16-bit / mono.
 *
 * - Handles 16-bit PCM WAV files natively.
 * - Resamples from other sample rates using linear interpolation (sufficient for tests).
 * - Resamples from stereo to mono by averaging channels.
 * - Splits into configurable chunks (default 40 ms = 640 samples at 16 kHz).
 */
class WavReader {

    /** Load a WAV resource and return chunks ready for InferenceRepository or ParakeetEngine. */
    fun loadAsChunks(
        inputStream: InputStream,
        chunkDurationMs: Long = 40,
    ): List<AudioChunk> {
        val (samples, sampleRate, channels) = readPcm16(inputStream)
        val mono = if (channels > 1) downmixToMono(samples, channels) else samples
        val resampled = if (sampleRate != 16_000) {
            resampleLinear(mono, sampleRate, 16_000)
        } else mono
        return splitIntoChunks(resampled, chunkDurationMs)
    }

    /** Load a WAV and return a single merged AudioChunk (for direct engine tests). */
    fun loadAsSingleChunk(inputStream: InputStream): AudioChunk {
        val (samples, sampleRate, channels) = readPcm16(inputStream)
        val mono = if (channels > 1) downmixToMono(samples, channels) else samples
        val resampled = if (sampleRate != 16_000) {
            resampleLinear(mono, sampleRate, 16_000)
        } else mono
        return AudioChunk(resampled, sampleRate = 16_000)
    }

    /**
     * Parse a WAV file and return raw 16-bit PCM samples plus metadata.
     *
     * Validates format: only 16-bit PCM (WAVE format code 1) is supported.
     * Throws [IllegalArgumentException] with a clear message on unsupported formats.
     */
    internal fun readPcm16(inputStream: InputStream): PcmData {
        val bytes = inputStream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // WAV tags are 4 raw ASCII bytes; numeric fields are little-endian.
        fun readTag(): String {
            val tag = ByteArray(4)
            buf.get(tag)
            return tag.toString(Charsets.US_ASCII)
        }

        // --- RIFF header ---
        if (readTag() != "RIFF") {
            throw IllegalArgumentException("WAV file missing RIFF header")
        }
        buf.int // file size - 8
        if (readTag() != "WAVE") {
            throw IllegalArgumentException("WAV file missing WAVE header")
        }

        // --- Parse chunks until we find 'fmt ' and 'data' ---
        var fmtSampleRate = -1
        var fmtChannels = -1
        var fmtBitsPerSample = -1
        var fmtBlockAlign = -1
        var dataOffset = -1
        var dataLength = -1

        while (buf.remaining() >= 8) {
            val chunkTag = readTag()
            val chunkSize = buf.int

            when (chunkTag) {
                "fmt " -> {
                    if (chunkSize < 16) {
                        throw IllegalArgumentException("fmt chunk too small ($chunkSize bytes)")
                    }
                    val audioFormat = buf.short.toInt()
                    if (audioFormat != 1) {
                        throw IllegalArgumentException(
                            "WAV format code $audioFormat is not supported (only 16-bit PCM, code=1)"
                        )
                    }
                    fmtChannels = buf.short.toInt()
                    fmtSampleRate = buf.int
                    buf.int // byte rate (skip)
                    fmtBlockAlign = buf.short.toInt()
                    fmtBitsPerSample = buf.short.toInt()
                    if (fmtBitsPerSample != 16) {
                        throw IllegalArgumentException(
                            "WAV bits-per-sample is $fmtBitsPerSample (only 16-bit supported)"
                        )
                    }
                    // Skip any extra fmt bytes (e.g. CBSize + extension for extended fmt)
                    if (chunkSize > 16) buf.position(buf.position() + (chunkSize - 16))
                }

                "data" -> {
                    dataOffset = buf.position()
                    dataLength = chunkSize
                    // Skip the entire data chunk in the header pass
                    buf.position(buf.position() + dataLength)
                }

                else -> {
                    // Skip unknown chunk (e.g. "JUNK", "bext", etc.)
                    buf.position(buf.position() + chunkSize)
                }
            }
        }

        if (fmtSampleRate <= 0) {
            throw IllegalArgumentException("WAV file missing 'fmt ' chunk")
        }
        if (dataLength <= 0) {
            throw IllegalArgumentException("WAV file missing 'data' chunk")
        }

        // --- Read PCM samples ---
        buf.position(dataOffset)
        val sampleCount = dataLength / 2 // 2 bytes per 16-bit sample
        val samples = ShortArray(sampleCount)
        for (i in samples.indices) {
            samples[i] = buf.short
        }

        return PcmData(samples, fmtSampleRate, fmtChannels)
    }

    /** Downmix multi-channel PCM to mono by averaging channels. */
    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        val monoCount = samples.size / channels
        val mono = ShortArray(monoCount)
        for (i in 0 until monoCount) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    /**
     * Resample [samples] from [fromRate] to [toRate] using linear interpolation.
     *
     * Sufficient accuracy for test fixtures; not intended for production audio processing.
     */
    internal fun resampleLinear(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return samples.copyOf()
        val ratio = fromRate.toDouble() / toRate
        // Long arithmetic: samples.size * toRate overflows Int for fixtures > ~6 s at 22 kHz.
        val outCount = (samples.size.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outCount)
        for (i in 0 until outCount) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(samples.size - 1)
            val frac = srcPos - idx0
            val v0 = samples[idx0].toDouble()
            val v1 = samples[idx1].toDouble()
            out[i] = (v0 + frac * (v1 - v0)).toInt().toShort()
        }
        return out
    }

    /**
     * Split a flat ShortArray into chunks of [chunkDurationMs] milliseconds at 16 kHz.
     */
    private fun splitIntoChunks(samples: ShortArray, chunkDurationMs: Long): List<AudioChunk> {
        val chunkSize = (16_000 * chunkDurationMs / 1000).toInt()
        val chunks = mutableListOf<AudioChunk>()
        for (i in samples.indices step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(samples.size)
            val chunk = samples.copyOfRange(i, end)
            chunks.add(
                AudioChunk(
                    chunk,
                    sampleRate = 16_000,
                    timestampMs = i * 1000L / 16_000,
                )
            )
        }
        return chunks
    }
}

/** Result of [WavReader.readPcm16]: raw samples, sample rate, and channel count. */
data class PcmData(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
)
