package org.schabi.parakeetype.inference

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.schabi.parakeetype.audio.AudioChunk

/**
 * Word timings on the chunked-TDT streaming path: each word starts at the encoder frame of
 * its first token, mapped back to the capture position of the audio — the VAD drops silence,
 * so the repository's sample positions alone do not tell when a word was said.
 */
class StreamingWordTimingsTest {

    /** [seconds] of audio in 512-sample frames; frames from [gapAtMs] on were captured [gapMs] later. */
    private fun audio(seconds: Int, gapAtMs: Long, gapMs: Long) = flow {
        val frames = seconds * 16_000 / 512
        repeat(frames) { i ->
            val positionMs = i * 32L
            val timestampMs = if (positionMs >= gapAtMs) positionMs + gapMs else positionMs
            emit(AudioChunk(ShortArray(512), timestampMs = timestampMs))
        }
    }

    @Test
    fun `given word timings requested, when transcribing, then the final carries each word's capture time`() = runTest {
        // A word every second; the VAD dropped 2 s of silence (its lead-in kept 200 ms) before the 4th word.
        val repo = InferenceRepository(FakeStreamingParakeet(buildWordScript(words = 5, intervalMs = 1_000)))

        val final = repo.transcribe(audio(seconds = 6, gapAtMs = 2_800, gapMs = 2_000), wordTimings = true)
            .toList().filterIsInstance<TranscriptResult.Final>().single()

        assertThat(final.words.map { it.text.lowercase().trim('.') })
            .containsExactly("w0001", "w0002", "w0003", "w0004", "w0005")
        val expectedMs = listOf(0L, 1_000L, 2_000L, 5_000L, 6_000L)
        final.words.zip(expectedMs).forEach { (word, ms) -> assertThat(word.startMs).isCloseTo(ms, within(100L)) }
        assertThat(final.words.map { it.confidence }).allSatisfy { assertThat(it).isCloseTo(0.9f, within(0.01f)) }
    }

    @Test
    fun `given no word timings requested, when transcribing, then the final carries no words`() = runTest {
        val repo = InferenceRepository(FakeStreamingParakeet(buildWordScript(words = 5, intervalMs = 1_000)))

        val final = repo.transcribe(audio(seconds = 6, gapAtMs = 3_000, gapMs = 2_000))
            .toList().filterIsInstance<TranscriptResult.Final>().single()

        assertThat(final.words).isEmpty()
    }
}
