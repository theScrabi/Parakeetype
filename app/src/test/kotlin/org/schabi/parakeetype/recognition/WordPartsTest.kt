package org.schabi.parakeetype.recognition

import android.speech.RecognitionPart
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.parakeetype.inference.WordTiming

/**
 * `RECOGNITION_PARTS` carry only what the client asked for: a word's timestamp with
 * `EXTRA_REQUEST_WORD_TIMING`, its confidence level with `EXTRA_REQUEST_WORD_CONFIDENCE`;
 * the other field keeps the documented default (0 / `CONFIDENCE_LEVEL_UNKNOWN`).
 */
class WordPartsTest {

    private val words = listOf(WordTiming("Hello", 1_200, 0.95f), WordTiming("world.", 1_700, 0.3f))

    @Test
    fun `given word timing requested, when building parts, then they carry the timestamps only`() {
        assertThat(wordParts(words, timing = true, confidence = false)).containsExactly(
            WordPart("Hello", 1_200, RecognitionPart.CONFIDENCE_LEVEL_UNKNOWN),
            WordPart("world.", 1_700, RecognitionPart.CONFIDENCE_LEVEL_UNKNOWN),
        )
    }

    @Test
    fun `given word confidence requested, when building parts, then they carry the confidence levels only`() {
        assertThat(wordParts(words, timing = false, confidence = true)).containsExactly(
            WordPart("Hello", 0, RecognitionPart.CONFIDENCE_LEVEL_HIGH),
            WordPart("world.", 0, RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_LOW),
        )
    }

    @Test
    fun `given a word probability, when mapping it to a confidence level, then higher probabilities rank higher`() {
        assertThat(listOf(0.1f, 0.3f, 0.6f, 0.8f, 0.95f).map { wordConfidenceLevel(it) }).containsExactly(
            RecognitionPart.CONFIDENCE_LEVEL_LOW,
            RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_LOW,
            RecognitionPart.CONFIDENCE_LEVEL_MEDIUM,
            RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_HIGH,
            RecognitionPart.CONFIDENCE_LEVEL_HIGH,
        )
    }
}
