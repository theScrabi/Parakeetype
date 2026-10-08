package org.schabi.parakeetype.audio

import android.media.AudioFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmConverterTest {

    private fun bytes(capacity: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun pcm16(vararg samples: Short) = bytes(samples.size * 2) { samples.forEach { putShort(it) } }

    @Test
    fun `given 16 kHz mono 16-bit PCM, when converted, then the samples are unchanged`() {
        val samples = shortArrayOf(0, 1, -1, 1000, Short.MAX_VALUE, Short.MIN_VALUE)

        val output = PcmConverter(PcmFormat()).convert(pcm16(*samples))

        assertThat(output).containsExactly(*samples)
    }

    @Test
    fun `given frames split across calls, when converted, then nothing is lost`() {
        val input = pcm16(100, 200, 300)
        val converter = PcmConverter(PcmFormat())

        val output = converter.convert(input.copyOfRange(0, 3)) +
                converter.convert(input.copyOfRange(3, 5)) +
                converter.convert(input.copyOfRange(5, 6))

        assertThat(output).containsExactly(100, 200, 300)
    }

    @Test
    fun `given stereo input, when converted, then the channels are averaged`() {
        val output = PcmConverter(PcmFormat(channelCount = 2)).convert(pcm16(1000, 3000, -200, 200))

        assertThat(output).containsExactly(2000, 0)
    }

    @Test
    fun `given 8-bit, 24-bit, 32-bit and float input, when converted, then they map to 16-bit`() {
        val half: Short = 16_384
        val pcm8 = byteArrayOf(128.toByte(), 192.toByte(), 64)
        val pcm24 = bytes(6) { put(0); put(0); put(0x40); put(0); put(0); put(0xC0.toByte()) }
        val pcm32 = bytes(4) { putInt(1 shl 30) }
        val float = bytes(8) { putFloat(0.5f); putFloat(-2f) }

        assertThat(PcmConverter(PcmFormat(encoding = AudioFormat.ENCODING_PCM_8BIT)).convert(pcm8))
            .containsExactly(0, half, (-half).toShort())
        assertThat(PcmConverter(PcmFormat(encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED)).convert(pcm24))
            .containsExactly(half, (-half).toShort())
        assertThat(PcmConverter(PcmFormat(encoding = AudioFormat.ENCODING_PCM_32BIT)).convert(pcm32))
            .containsExactly(half)
        // Out-of-range float samples are clipped.
        assertThat(PcmConverter(PcmFormat(encoding = AudioFormat.ENCODING_PCM_FLOAT)).convert(float))
            .containsExactly(half, Short.MIN_VALUE)
    }

    @Test
    fun `given 48 kHz input, when converted, then every 3 samples are averaged into one`() {
        val converter = PcmConverter(PcmFormat(sampleRate = 48_000))

        val output = converter.convert(pcm16(3, 6, 9, 30, 60, 90, 300)) + converter.finish()

        assertThat(output).containsExactly(6, 60, 300)
    }

    @Test
    fun `given 8 kHz input, when converted, then it is upsampled linearly`() {
        val output = PcmConverter(PcmFormat(sampleRate = 8_000)).convert(pcm16(0, 100, 300))

        assertThat(output).containsExactly(0, 50, 100, 200, 300)
    }

    @Test
    fun `given 44_1 kHz speech-band tone, when converted, then length and shape are kept`() {
        val rate = 44_100
        val seconds = 2
        val input = ShortArray(rate * seconds) { (sin(2 * PI * 440 * it / rate) * 10_000).toInt().toShort() }
        val converter = PcmConverter(PcmFormat(sampleRate = rate))

        val output = converter.convert(pcm16(*input)) + converter.finish()

        assertThat(output.size).isBetween(PIPELINE_SAMPLE_RATE * seconds - 1, PIPELINE_SAMPLE_RATE * seconds + 1)
        // Compare against the tone at 16 kHz. The averaged spans start on whole input samples,
        // so their centres jitter by up to half a 44.1 kHz sample: a few percent of error.
        val maxError = output.indices.drop(1).maxOf { i ->
            abs(output[i] - sin(2 * PI * 440 * (i + 0.5) / PIPELINE_SAMPLE_RATE - PI * 440 / rate) * 10_000)
        }
        assertThat(maxError).isLessThan(700.0)
    }

    @Test
    fun `given formats, when checked, then only uncompressed PCM in range is supported`() {
        assertThat(PcmFormat().isSupported).isTrue()
        assertThat(PcmFormat(encoding = AudioFormat.ENCODING_OPUS).isSupported).isFalse()
        assertThat(PcmFormat(channelCount = 0).isSupported).isFalse()
        assertThat(PcmFormat(sampleRate = 4_000).isSupported).isFalse()
    }
}
