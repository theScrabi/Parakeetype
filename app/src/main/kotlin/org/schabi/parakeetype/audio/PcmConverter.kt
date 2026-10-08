package org.schabi.parakeetype.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** 16 kHz - the sample rate every [AudioChunk] the inference pipeline receives has. */
const val PIPELINE_SAMPLE_RATE = 16_000

/**
 * Format of raw PCM audio a recognizer client supplies (see
 * [android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE]): interleaved, little-endian samples.
 *
 * @param encoding One of [SUPPORTED_ENCODINGS].
 */
data class PcmFormat(
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val channelCount: Int = 1,
    val sampleRate: Int = PIPELINE_SAMPLE_RATE,
) {
    /** `true` when [PcmConverter] can convert this format. */
    val isSupported: Boolean
        get() = encoding in SUPPORTED_ENCODINGS &&
                channelCount in 1..MAX_CHANNELS &&
                sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE

    internal val bytesPerSample: Int
        get() = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 4 // ENCODING_PCM_32BIT, ENCODING_PCM_FLOAT
        }

    companion object {
        val SUPPORTED_ENCODINGS = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        const val MAX_CHANNELS = 8
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000
    }
}

/**
 * Converts a stream of raw PCM bytes in [format] into the pipeline's format: 16-bit mono at
 * [PIPELINE_SAMPLE_RATE]. Channels are averaged; higher rates are downsampled by averaging
 * each output sample's span of input samples (a box low-pass that keeps the aliasing of
 * 44.1 / 48 kHz input out of the speech band), lower rates are upsampled linearly.
 *
 * Stateful: bytes of a frame split across two [convert] calls and the resampler's position
 * carry over, so the input can be fed in arbitrary pieces.
 */
class PcmConverter(private val format: PcmFormat) {

    init {
        require(format.isSupported) { "unsupported PCM format: $format" }
    }

    private val frameBytes = format.bytesPerSample * format.channelCount

    /** Input frames per output sample. */
    private val step = format.sampleRate.toDouble() / PIPELINE_SAMPLE_RATE

    /** The tail of the previous [convert] input that did not fill a whole frame. */
    private val pending = ByteArray(frameBytes)
    private var pendingCount = 0

    /** Index of the next input frame. */
    private var inputIndex = 0L

    // Downsampling: the output sample being averaged ends at input position [nextEdge].
    private var nextEdge = step
    private var sum = 0.0
    private var sumCount = 0

    // Upsampling: the previous input sample and the input position of the next output sample.
    private var previous = 0f
    private var nextOutputPosition = 0.0

    /** Converts the first [length] bytes of [bytes] and returns the 16 kHz mono samples. */
    fun convert(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val output = ShortArrayBuilder(((length / frameBytes + 1) / step).toInt() + 2)
        var offset = 0
        if (pendingCount > 0) {
            val needed = minOf(frameBytes - pendingCount, length)
            System.arraycopy(bytes, 0, pending, pendingCount, needed)
            pendingCount += needed
            offset = needed
            if (pendingCount < frameBytes) return output.build()
            onFrame(mono(ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN), 0), output)
            pendingCount = 0
        }
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        while (offset + frameBytes <= length) {
            onFrame(mono(buffer, offset), output)
            offset += frameBytes
        }
        System.arraycopy(bytes, offset, pending, 0, length - offset)
        pendingCount = length - offset
        return output.build()
    }

    /** Ends the input: returns the last, partially averaged output sample (if any). */
    fun finish(): ShortArray {
        pendingCount = 0
        if (step <= 1.0 || sumCount == 0) return ShortArray(0)
        val last = toShort(sum / sumCount)
        sum = 0.0
        sumCount = 0
        return shortArrayOf(last)
    }

    private fun onFrame(sample: Float, output: ShortArrayBuilder) {
        val index = inputIndex++
        when {
            step == 1.0 -> output.add(toShort(sample.toDouble()))

            step > 1.0 -> {
                if (index >= nextEdge && sumCount > 0) {
                    output.add(toShort(sum / sumCount))
                    sum = 0.0
                    sumCount = 0
                    nextEdge += step
                }
                sum += sample
                sumCount++
            }

            else -> {
                if (index == 0L) previous = sample
                // Every output position in (index - 1, index] lies between previous and sample.
                while (nextOutputPosition <= index) {
                    val fraction = (nextOutputPosition - (index - 1)).toFloat().coerceIn(0f, 1f)
                    output.add(toShort((previous + (sample - previous) * fraction).toDouble()))
                    nextOutputPosition += step
                }
                previous = sample
            }
        }
    }

    /** The average of the channels of the frame at [offset], in [-1, 1]. */
    private fun mono(buffer: ByteBuffer, offset: Int): Float {
        var total = 0f
        for (channel in 0 until format.channelCount) {
            total += sampleAt(buffer, offset + channel * format.bytesPerSample)
        }
        return total / format.channelCount
    }

    private fun sampleAt(buffer: ByteBuffer, offset: Int): Float = when (format.encoding) {
        // 8-bit PCM is unsigned, centred on 128.
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32_768f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val value = (buffer.get(offset).toInt() and 0xFF) or
                    ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
                    (buffer.get(offset + 2).toInt() shl 16)
            value / 8_388_608f
        }
        AudioFormat.ENCODING_PCM_32BIT -> (buffer.getInt(offset) / 2_147_483_648.0).toFloat()
        else -> buffer.getFloat(offset) // ENCODING_PCM_FLOAT
    }

    private fun toShort(sample: Double): Short =
        (sample * 32_768.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private class ShortArrayBuilder(capacity: Int) {
        private var array = ShortArray(maxOf(capacity, 16))
        private var size = 0

        fun add(value: Short) {
            if (size == array.size) array = array.copyOf(size * 2)
            array[size++] = value
        }

        fun build(): ShortArray = array.copyOf(size)
    }
}
