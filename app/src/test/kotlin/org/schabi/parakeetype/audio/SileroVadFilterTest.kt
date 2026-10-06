package org.schabi.parakeetype.audio

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.parakeetype.inference.WavReader
import org.schabi.parakeetype.inference.openWavFixture
import org.schabi.parakeetype.inference.resolveSileroModel
import kotlin.random.Random

/**
 * [SileroVadFilter] against the real Silero VAD model from the model archive (skipped
 * without it): the model's input / state / output tensors are wired correctly, and [flush]
 * resets the RNN state and the context samples.
 */
class SileroVadFilterTest {

    private val frame = SileroVadFilter.REQUIRED_SAMPLES

    private fun quietNoise(n: Int): ShortArray {
        val rnd = Random(3)
        return ShortArray(n) { rnd.nextInt(-30, 31).toShort() }
    }

    /** Feeds [samples] frame by frame and returns the speech probability of every frame. */
    private fun SileroVadFilter.probabilities(samples: ShortArray): List<Float> =
        (0 until samples.size / frame).map { i ->
            process(AudioChunk(samples.copyOfRange(i * frame, (i + 1) * frame)), 0f)
            lastSpeechProbability
        }

    @Test
    fun `silence stays below the threshold`() {
        val vad = SileroVadFilter(resolveSileroModel().readBytes())
        val probs = vad.probabilities(quietNoise(16_000 * 2))
        vad.close()

        assertThat(probs.max()).isLessThan(0.1f)
    }

    @Test
    fun `speech is detected`() {
        val speech = WavReader().loadAsSingleChunk(openWavFixture("hello-world.wav")).samples
        val vad = SileroVadFilter(resolveSileroModel().readBytes())
        val probs = vad.probabilities(speech)
        vad.close()

        assertThat(probs.max()).isGreaterThan(0.9f)
        assertThat(probs.count { it >= 0.5f }).isGreaterThanOrEqualTo(7)
    }

    @Test
    fun `flush resets the state and the context`() {
        val speech = WavReader().loadAsSingleChunk(openWavFixture("hello-world.wav")).samples
        val silence = quietNoise(16_000)
        val model = resolveSileroModel().readBytes()

        val fresh = SileroVadFilter(model)
        val expected = fresh.probabilities(silence)
        fresh.close()

        val used = SileroVadFilter(model)
        used.probabilities(speech)
        used.flush()
        val actual = used.probabilities(silence)
        used.close()

        assertThat(actual).isEqualTo(expected)
    }
}
