package org.schabi.parakeetype.audio

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClientAudioReaderTest {

    /** Passes every frame through; its speech probability is 1 for non-silent frames. */
    private class PassThroughVad : VadFilter {
        var closed = false
        override val isSpeechActive = false
        override var lastSpeechProbability = 0f
        override fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
            lastSpeechProbability = if (rms > 0f) 1f else 0f
            return listOf(chunk)
        }

        override fun flush() = false
        override fun close() {
            closed = true
        }
    }

    private fun pcm16(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it) } }.array()

    @Test
    fun `given 1100 samples, when read, then 512-sample frames are emitted and the last is padded`() = runTest {
        val samples = ShortArray(1_100) { (it % 100 + 1).toShort() }
        val vad = PassThroughVad()
        val levels = mutableListOf<Float>()

        val chunks = ClientAudioReader(ByteArrayInputStream(pcm16(samples)), PcmFormat()) { vad }
            .read(onLevel = { levels += it }).toList()

        assertThat(chunks.map { it.samples.size }).containsExactly(512, 512, 512)
        assertThat(chunks.flatMap { it.samples.toList() }.take(1_100)).isEqualTo(samples.toList())
        assertThat(chunks.last().samples.drop(1_100 - 1_024)).allMatch { it == 0.toShort() }
        assertThat(levels).hasSize(3)
        assertThat(vad.closed).isTrue()
    }

    @Test
    fun `given stop from the speech callback, when read, then later audio is dropped`() = runTest {
        val samples = ShortArray(512 * 10) { 1_000 }
        lateinit var reader: ClientAudioReader
        var frames = 0
        reader = ClientAudioReader(ByteArrayInputStream(pcm16(samples)), PcmFormat()) { PassThroughVad() }

        val chunks = reader.read(onSpeechProbability = { if (++frames == 3) reader.stop() }).toList()

        assertThat(chunks).hasSize(3)
    }
}
