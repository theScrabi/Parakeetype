package org.schabi.parakeetype.recognition

import org.schabi.parakeetype.ime.TranscriptAligner
import org.schabi.parakeetype.ime.TranscriptAligner.splitToWords
import org.schabi.parakeetype.inference.TranscriptResult

/**
 * Rebuilds the full text of a recognition session from the [TranscriptResult] stream of
 * [org.schabi.parakeetype.inference.InferenceRepository.transcribe] — the plain-string
 * counterpart of [org.schabi.parakeetype.ime.TextInjector], which writes the same stream
 * into an input field.
 *
 * On the Parakeet chunked-TDT path every [TranscriptResult.Partial] carries the whole
 * utterance since the last utterance boundary and every [TranscriptResult.Final] closes that
 * utterance, so the session text is simply the committed finals plus the current partial.
 * The legacy sliding-window path (non-streaming engines) additionally emits
 * [TranscriptResult.WindowTrimmed]: after a trim the next partial only covers the retained
 * tail audio, so it is merged into the frozen words with [TranscriptAligner.findNewContent].
 */
class TranscriptAccumulator {

    /** Words that can no longer change: closed utterances and window-trimmed prefixes. */
    private val committed = mutableListOf<String>()

    /** Words of the current utterance, already de-overlapped against [committed]. */
    private var pending: List<String> = emptyList()

    /**
     * `true` after a [TranscriptResult.WindowTrimmed]: results until the next utterance
     * boundary overlap the tail of [committed] and must be aligned against it.
     */
    private var alignAfterTrim = false

    /** Lowest confidence of all finals so far (1.0 when no final was received). */
    var confidence: Float = 1.0f
        private set

    /** The full session text: every committed word followed by the current utterance. */
    val text: String get() = (committed + pending).joinToString(" ")

    fun onPartial(text: String) {
        pending = newWords(text)
    }

    /** Commits [text] as the end of the current utterance. */
    fun onFinal(text: String, confidence: Float) {
        committed += newWords(text)
        pending = emptyList()
        alignAfterTrim = false
        this.confidence = minOf(this.confidence, confidence)
    }

    fun onWindowTrimmed() {
        committed += pending
        pending = emptyList()
        alignAfterTrim = true
    }

    private fun newWords(text: String): List<String> {
        val words = text.splitToWords()
        return if (alignAfterTrim) TranscriptAligner.findNewContent(committed, words) else words
    }
}
