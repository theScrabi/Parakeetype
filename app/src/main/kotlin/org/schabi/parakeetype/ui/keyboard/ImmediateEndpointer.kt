package org.schabi.parakeetype.ui.keyboard

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.parakeetype.audio.AudioChunk

private const val TAG = "ImmediateEndpointer"

/** Without any speech for this long an immediate-mode session ends on its own. */
internal const val IMMEDIATE_NO_SPEECH_TIMEOUT_MS = 8_000L

/** Silence after the end of speech that ends an immediate-mode session. */
internal const val IMMEDIATE_END_OF_SPEECH_SILENCE_MS = 2_000L

/**
 * Silence between the end of speech and the VAD's utterance boundary marker: the Silero VAD's
 * 450 ms hangover plus its 600 ms boundary window (see `SileroVadFilter`).
 */
private const val VAD_BOUNDARY_DELAY_MS = 1_050L

/**
 * End-of-speech detection for the keyboard's immediate mode: fed the VAD-filtered audio of
 * the session, it calls [onEndOfSpeech] once the user has stopped speaking for
 * [IMMEDIATE_END_OF_SPEECH_SILENCE_MS], or when nothing was said within
 * [IMMEDIATE_NO_SPEECH_TIMEOUT_MS].
 *
 * All methods must be called on [scope]'s (main) thread.
 */
class ImmediateEndpointer(
    private val scope: CoroutineScope,
    private val onEndOfSpeech: () -> Unit,
) {
    private var active = false
    private var speechStarted = false
    private var timer: Job? = null

    /** Starts watching a new session; the no-speech timeout starts now. */
    fun start() {
        active = true
        speechStarted = false
        schedule(IMMEDIATE_NO_SPEECH_TIMEOUT_MS, "No speech")
    }

    /** One chunk of the session's VAD-filtered audio. */
    fun onChunk(chunk: AudioChunk) {
        if (!active) return
        if (chunk.isSilenceBoundary) {
            if (speechStarted) {
                schedule(
                    (IMMEDIATE_END_OF_SPEECH_SILENCE_MS - VAD_BOUNDARY_DELAY_MS).coerceAtLeast(0L),
                    "End of speech",
                )
            }
        } else if (chunk.samples.isNotEmpty()) {
            // Speech (resumed): a pending end of speech or the no-speech timeout no longer applies.
            speechStarted = true
            timer?.cancel()
            timer = null
        }
    }

    /** Stops watching; no [onEndOfSpeech] call follows. */
    fun cancel() {
        active = false
        timer?.cancel()
        timer = null
    }

    private fun schedule(delayMs: Long, reason: String) {
        timer?.cancel()
        timer = scope.launch {
            delay(delayMs)
            if (!active) return@launch
            Log.d(TAG, "$reason - ending the immediate-mode session")
            cancel()
            onEndOfSpeech()
        }
    }
}
