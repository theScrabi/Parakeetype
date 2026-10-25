package org.schabi.parakeetype.audio

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [toPcm16Bytes] turns captured samples into the raw bytes a recognizer client receives
 * through `RecognitionListener.onBufferReceived`: 16-bit signed little-endian PCM, the
 * byte order [android.media.AudioFormat.ENCODING_PCM_16BIT] uses.
 */
class Pcm16BytesTest {

    @Test
    fun `given samples, when converted, then each becomes two little-endian bytes`() {
        val bytes = shortArrayOf(0x0102, -2, Short.MAX_VALUE, Short.MIN_VALUE).toPcm16Bytes()

        assertThat(bytes).isEqualTo(
            byteArrayOf(
                0x02, 0x01,
                0xFE.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0x7F,
                0x00, 0x80.toByte(),
            )
        )
    }

    @Test
    fun `given converted bytes, when read back as little-endian shorts, then the samples round-trip`() {
        val samples = ShortArray(512) { (it * 97 - 20_000).toShort() }

        val buffer = ByteBuffer.wrap(samples.toPcm16Bytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val decoded = ShortArray(buffer.remaining()).also { buffer.get(it) }

        assertThat(decoded).isEqualTo(samples)
    }

    @Test
    fun `given no samples, when converted, then no bytes`() {
        assertThat(ShortArray(0).toPcm16Bytes()).isEmpty()
    }
}
