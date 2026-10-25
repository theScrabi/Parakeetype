package org.schabi.parakeetype.recognition

import android.speech.RecognitionPart
import org.schabi.parakeetype.inference.WordTiming

/**
 * One `RecognitionPart` of a result: a word with its adjacent punctuation.
 *
 * @param timestampMillis Offset of the word from the start of the session (0 unless word
 *   timing was requested).
 * @param confidenceLevel A `RecognitionPart.CONFIDENCE_LEVEL_*` value (unknown unless word
 *   confidence was requested).
 */
data class WordPart(val text: String, val timestampMillis: Long, val confidenceLevel: Int)

/**
 * The `RECOGNITION_PARTS` for [words], carrying their timestamps only with [timing]
 * (`EXTRA_REQUEST_WORD_TIMING`) and their confidence levels only with [confidence]
 * (`EXTRA_REQUEST_WORD_CONFIDENCE`).
 */
fun wordParts(words: List<WordTiming>, timing: Boolean, confidence: Boolean): List<WordPart> = words.map { word ->
    WordPart(
        text = word.text,
        timestampMillis = if (timing) word.startMs.coerceAtLeast(0) else 0,
        confidenceLevel = if (confidence) wordConfidenceLevel(word.confidence) else RecognitionPart.CONFIDENCE_LEVEL_UNKNOWN,
    )
}

/** Maps a word's probability in [0.0, 1.0] to a `RecognitionPart.CONFIDENCE_LEVEL_*`. */
fun wordConfidenceLevel(probability: Float): Int = when {
    probability >= 0.9f -> RecognitionPart.CONFIDENCE_LEVEL_HIGH
    probability >= 0.75f -> RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_HIGH
    probability >= 0.5f -> RecognitionPart.CONFIDENCE_LEVEL_MEDIUM
    probability >= 0.25f -> RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_LOW
    else -> RecognitionPart.CONFIDENCE_LEVEL_LOW
}
