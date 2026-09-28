package org.schabi.parakeetype.recognition

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * [TranscriptAccumulator] rebuilds the recognizer's session text from the repository's
 * result stream: the chunked-TDT shape (whole-utterance partials, finals at boundaries) and
 * the legacy sliding-window shape (partials restart after a window trim).
 */
class TranscriptAccumulatorTest {

    @Test
    fun `empty session has empty text and full confidence`() {
        val acc = TranscriptAccumulator()
        assertThat(acc.text).isEmpty()
        assertThat(acc.confidence).isEqualTo(1.0f)
    }

    @Test
    fun `partials replace the current utterance`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("Hello")
        acc.onPartial("Hello there")
        assertThat(acc.text).isEqualTo("Hello there")
    }

    @Test
    fun `final commits the utterance`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("Hello there")
        acc.onFinal("Hello there.", confidence = 0.9f)
        assertThat(acc.text).isEqualTo("Hello there.")
        assertThat(acc.confidence).isEqualTo(0.9f)
    }

    @Test
    fun `utterances after a boundary are appended`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("First sentence")
        acc.onFinal("First sentence.", confidence = 0.8f)
        acc.onPartial("Second")
        assertThat(acc.text).isEqualTo("First sentence. Second")
        acc.onFinal("Second one.", confidence = 0.95f)
        assertThat(acc.text).isEqualTo("First sentence. Second one.")
        // The session confidence is the weakest utterance.
        assertThat(acc.confidence).isEqualTo(0.8f)
    }

    @Test
    fun `partial after a window trim is merged without duplicating the overlap`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("now I lifted the record button")
        acc.onWindowTrimmed()
        // The retained tail audio re-transcribes the last words before the new ones.
        acc.onPartial("the record button and I'm pressing")
        assertThat(acc.text).isEqualTo("now I lifted the record button and I'm pressing")
        acc.onFinal("the record button and I'm pressing it.", confidence = 1f)
        assertThat(acc.text).isEqualTo("now I lifted the record button and I'm pressing it.")
    }

    @Test
    fun `utterance after a boundary is not aligned against the trimmed prefix`() {
        val acc = TranscriptAccumulator()
        acc.onPartial("one two three four")
        acc.onWindowTrimmed()
        acc.onFinal("three four five", confidence = 1f)
        acc.onPartial("three four again")
        assertThat(acc.text).isEqualTo("one two three four five three four again")
    }

    @Test
    fun `rms level maps onto the recognizer dB scale`() {
        assertThat(RecognitionSession.rmsToDb(0f)).isEqualTo(-2f)
        assertThat(RecognitionSession.rmsToDb(1f)).isEqualTo(10f)
        // -30 dBFS, typical speech
        assertThat(RecognitionSession.rmsToDb(0.0316f)).isBetween(4.9f, 5.1f)
    }
}
