package org.schabi.parakeetype.inference

/** Represents the outcome of a single transcription attempt. */
sealed class TranscriptResult {

    /**
     * In-progress transcript from streaming inference.
     * The keyboard should show [text] as composing (underlined) text.
     *
     * [confidence] is the engine's per-token geometric-mean probability in [0.0, 1.0]
     * (1.0 when the engine does not surface per-token log-probs). Used by
     * [org.schabi.parakeetype.inference.InferenceRepository] to suppress low-confidence
     * first-stride outputs that would otherwise displace real speech (cold-stride
     * hallucinations). Defaults to 1.0 so engines/tests that don't compute it are
     * treated as fully confident.
     */
    data class Partial(val text: String, val confidence: Float = 1.0f) : TranscriptResult()

    /**
     * Final, confirmed transcript for a completed utterance.
     * The keyboard should commit [text] to the active input field.
     *
     * [isUtteranceBoundary] is true when this Final was emitted mid-session by the VAD
     * silence-boundary handler. The recording session is still active and the keyboard
     * must NOT tear down capture state — only commit the text and continue listening.
     * When false (the default), this is a true session-ending Final.
     *
     * [confidence] is the engine's per-token geometric-mean probability in [0.0, 1.0]
     * (1.0 when not computed). See [Partial.confidence].
     */
    data class Final(
        val text: String,
        val isUtteranceBoundary: Boolean = false,
        val confidence: Float = 1.0f,
    ) : TranscriptResult()

    /** Inference failed. [cause] carries the underlying exception for logging/display. */
    data class Failure(val cause: Throwable) : TranscriptResult()

    /**
     * The rolling audio window was trimmed to prevent attention drift.
     *
     * [TextInjector] must call [resetAfterTrim][org.schabi.parakeetype.ime.TextInjector.resetAfterTrim]
     * when it receives this so that its committed-word tracking is shrunk to the words most
     * likely to still be inside the retained tail audio.  Without this, the suffix-overlap
     * alignment in [setPartial][org.schabi.parakeetype.ime.TextInjector.setPartial] fails for
     * every stride after the trim and middle sentences are silently dropped.
     *
     * [stableWords] carries the confirmed-stable leading words from the partial that triggered
     * the trim (up to [safeStableCount] words from InferenceRepository).  TextInjector uses
     * them as the new [committedWords] anchor directly, bypassing the field re-read which may
     * be stale when the composing span was stuck (RC-3 / P4 fix).  Defaults to an empty list
     * for force-trims and silence-trims where no stable word list exists.
     */
    data class WindowTrimmed(val stableWords: List<String> = emptyList()) : TranscriptResult()

    /**
     * The model saw audio but could not resolve a word — typically a short utterance
     * (a single word, or one buried in room noise) that fails the decode confidence gate.
     * No text is committed; the keyboard should surface a brief "didn't catch that" cue
     * instead of staying silent.
     */
    object NoSpeech : TranscriptResult()
}
