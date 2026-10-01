package org.schabi.parakeetype.recognition

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.schabi.parakeetype.audio.AudioCaptureManager
import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.audio.MicrophoneBusyException
import org.schabi.parakeetype.audio.SpeechEndpointer
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.settings.preferences.AppPreferences
import kotlin.math.log10

private const val TAG = "RecognitionSession"

/** Without any speech for this long the session ends with [SpeechRecognizer.ERROR_SPEECH_TIMEOUT]. */
private const val NO_SPEECH_TIMEOUT_MS = 8_000L

/** Silence after speech that ends the session when the client asks for no length. */
private const val DEFAULT_COMPLETE_SILENCE_MS = 1_000L

/** Report the microphone level every 3rd 30 ms chunk (~11 updates per second). */
private const val LEVEL_REPORT_INTERVAL_CHUNKS = 3

/** The [RecognizerIntent] extras a [RecognitionSession] honours. */
data class RecognitionOptions(
    /** [RecognizerIntent.EXTRA_PARTIAL_RESULTS]: report partial results while listening. */
    val partialResults: Boolean = false,
    /**
     * [RecognizerIntent.EXTRA_SEGMENTED_SESSION]: deliver every utterance as a segment and
     * keep listening until stopped, instead of ending after the first utterance.
     */
    val segmented: Boolean = false,
    /** [RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS]; 0 = VAD default. */
    val completeSilenceMs: Long = 0,
    /** [RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS]; 0 = no minimum. */
    val minimumLengthMs: Long = 0,
) {
    companion object {
        fun from(intent: Intent) = RecognitionOptions(
            partialResults = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
            // The segment callbacks exist since API 33.
            segmented = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
            completeSilenceMs = intent.millisExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS),
            minimumLengthMs = intent.millisExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS),
        )

        // Documented as long, but clients commonly put an Int.
        @Suppress("DEPRECATION")
        private fun Intent.millisExtra(key: String): Long =
            ((extras?.get(key) as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
    }
}

/**
 * One speech-recognition session: captures the microphone, runs the audio through the shared
 * [org.schabi.parakeetype.inference.InferenceRepository] and reports the
 * [android.speech.RecognitionListener]-style events to [listener].
 *
 * Used by both speech-recognizer entry points — [ParakeetypeRecognitionService]
 * (`SpeechRecognizer`) and [VoiceInputActivity] (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`).
 *
 * Capture starts immediately and is buffered while the model is still loading, so nothing
 * said right after [start] is lost. The session ends on its own once the VAD detects the end
 * of the utterance (unless [RecognitionOptions.segmented]), on [stop], or with an error.
 *
 * @param audioContext Context the [android.media.AudioRecord] is built on — for the
 *   recognition service an attribution context carrying the calling app's identity.
 * @param scope Scope on the main dispatcher; every [listener] callback runs on it.
 */
class RecognitionSession(
    private val audioContext: Context,
    private val inference: InferenceConnection,
    private val options: RecognitionOptions,
    private val listener: Listener,
    private val scope: CoroutineScope,
) {

    /**
     * Mirrors [android.speech.RecognitionListener], plus [onModelLoading]. Called on the main
     * thread.
     */
    interface Listener {
        /**
         * The installed model is not loaded yet ([loading] = `true`) or has finished loading
         * (`false`, only after a `true`). Audio is captured and buffered meanwhile.
         */
        fun onModelLoading(loading: Boolean)

        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onRmsChanged(rmsdB: Float)

        /** The session text so far (only with [RecognitionOptions.partialResults]). */
        fun onPartialResult(text: String)

        /** One finished utterance of a [RecognitionOptions.segmented] session. */
        fun onSegmentResult(text: String, confidence: Float)
        fun onEndOfSpeech()

        /** Terminal: the recognised text of a non-segmented session. */
        fun onResult(text: String, confidence: Float)

        /** Terminal: a segmented session ended. */
        fun onEndOfSegmentedSession()

        /**
         * Terminal: [error] is a `SpeechRecognizer.ERROR_*` code. [microphoneBusy] marks an
         * [SpeechRecognizer.ERROR_AUDIO] because another app (e.g. a phone call) has the
         * microphone.
         */
        fun onError(error: Int, microphoneBusy: Boolean = false)
    }

    private val capture = AudioCaptureManager(audioContext)

    private var job: Job? = null
    private var speechStarted = false

    /** Read on the capture thread (see [onLevel]). */
    @Volatile
    private var stopRequested = false

    /** `true` once a terminal callback was delivered or the session was cancelled. */
    private var finished = false

    fun start() {
        check(job == null) { "session already started" }
        Log.d(TAG, "Starting session: $options")
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                fail(SpeechRecognizer.ERROR_SERVER)
            }
        }
    }

    /** Stops listening. The audio captured so far is still transcribed and delivered. */
    fun stop() {
        if (stopRequested || finished) return
        stopRequested = true
        capture.stopCapture()
        if (speechStarted) listener.onEndOfSpeech()
    }

    /** Aborts the session. No further [listener] callbacks are made. */
    fun cancel() {
        finished = true
        job?.cancel()
    }

    private suspend fun run() = coroutineScope {
        val prefs = AppPreferences(audioContext.applicationContext)
        val rawSource = prefs.rawMicCapture.first()
        val postprocessing = prefs.postprocessingEnabled.first()
        val numbersAsDigits = prefs.formatNumbersAsDigits.first()

        // Capture → channel → repository. The unlimited channel buffers the audio while
        // awaitRepository() is still waiting for the model to load.
        val audio = Channel<AudioChunk>(Channel.UNLIMITED)
        var levelChunks = 0
        var readyReported = false
        val endpointer = endpointer()
        launch {
            try {
                // VAD is always on: its speech probability drives the end-of-speech detection.
                capture.startCapture(vadEnabled = true, rawSource = rawSource, onSpeechProbability = { probability ->
                    val event = endpointer?.onFrame(probability)
                    if (event != null) launch { onEndpointerEvent(event) }
                }, onLevel = { level ->
                    // A stop() that raced the start of capture was lost: startCapture resets
                    // the manager's stop flag when the AudioRecord starts.
                    if (stopRequested) capture.stopCapture()
                    if (levelChunks++ % LEVEL_REPORT_INTERVAL_CHUNKS == 0) launch {
                        if (finished) return@launch
                        if (!readyReported) {
                            readyReported = true
                            listener.onReadyForSpeech()
                        }
                        listener.onRmsChanged(rmsToDb(level))
                    }
                }).collect { chunk -> audio.send(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrophoneBusyException) {
                Log.w(TAG, "Microphone in use by another app", e)
                fail(SpeechRecognizer.ERROR_AUDIO, microphoneBusy = true)
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission missing", e)
                fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture failed", e)
                fail(SpeechRecognizer.ERROR_AUDIO)
            } finally {
                audio.close()
            }
        }

        var modelLoading = false
        val result = inference.awaitRepository(onLoading = {
            if (!finished) {
                modelLoading = true
                listener.onModelLoading(true)
            }
        })
        val repository = when (result) {
            is InferenceConnection.Result.Ready -> {
                if (modelLoading && !finished) listener.onModelLoading(false)
                result.repository
            }
            is InferenceConnection.Result.Unavailable -> {
                Log.w(TAG, "Engine unavailable: ${result.reason}")
                fail(result.error)
                return@coroutineScope
            }
        }

        val transcript = TranscriptAccumulator()
        repository.transcribe(
            audio = audio.consumeAsFlow(),
            postprocessingEnabled = postprocessing,
            formatNumbersAsDigits = numbersAsDigits,
        ).collect { result ->
            if (finished) return@collect
            when (result) {
                is TranscriptResult.Partial -> {
                    transcript.onPartial(result.text)
                    if (options.partialResults) {
                        listener.onPartialResult(if (options.segmented) result.text else transcript.text)
                    }
                }

                is TranscriptResult.Final -> {
                    transcript.onFinal(result.text, result.confidence)
                    if (options.segmented) {
                        if (result.text.isNotBlank()) listener.onSegmentResult(result.text, result.confidence)
                    } else if (options.partialResults) {
                        listener.onPartialResult(transcript.text)
                    }
                }

                is TranscriptResult.WindowTrimmed -> transcript.onWindowTrimmed()
                // Low confidence / hallucination guard: nothing is added to the transcript.
                is TranscriptResult.Failure -> Log.w(TAG, "Utterance rejected: ${result.cause.message}")
                TranscriptResult.NoSpeech -> Log.d(TAG, "Utterance not understood")
            }
        }

        // The transcription flow ends once capture has ended and the final flush is done.
        if (finished) return@coroutineScope
        finished = true
        if (options.segmented) {
            listener.onEndOfSegmentedSession()
        } else {
            val text = transcript.text
            if (text.isBlank()) listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
            else listener.onResult(text, transcript.confidence)
        }
    }

    /**
     * The end-of-speech detection for [options], or `null` when the session only ends on
     * [stop]: a segmented session runs until stopped unless the client asked for a silence /
     * length limit.
     */
    private fun endpointer(): SpeechEndpointer? {
        if (options.segmented && options.completeSilenceMs <= 0 && options.minimumLengthMs <= 0) return null
        return SpeechEndpointer(
            silenceMs = options.completeSilenceMs.takeIf { it > 0 } ?: DEFAULT_COMPLETE_SILENCE_MS,
            noSpeechTimeoutMs = NO_SPEECH_TIMEOUT_MS.takeUnless { options.segmented },
            minimumLengthMs = options.minimumLengthMs,
        )
    }

    /** Runs on the main thread. */
    private fun onEndpointerEvent(event: SpeechEndpointer.Event) {
        if (finished || stopRequested) return
        when (event) {
            SpeechEndpointer.Event.SpeechStart -> {
                speechStarted = true
                listener.onBeginningOfSpeech()
            }

            SpeechEndpointer.Event.EndOfSpeech -> {
                Log.d(TAG, "End of speech detected")
                stop()
            }

            SpeechEndpointer.Event.NoSpeech -> {
                Log.d(TAG, "No speech within ${NO_SPEECH_TIMEOUT_MS}ms")
                fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            }
        }
    }

    private fun fail(error: Int, microphoneBusy: Boolean = false) {
        if (finished) return
        finished = true
        listener.onError(error, microphoneBusy)
        job?.cancel()
    }

    internal companion object {
        /**
         * Maps a normalised RMS level in [0.0, 1.0] to the `rmsdB` scale recognizer clients
         * expect: roughly -2 (silence) to 10 (loud speech), as Google's recognizer reports.
         */
        fun rmsToDb(level: Float): Float {
            val dbfs = 20f * log10(level.coerceAtLeast(1e-5f))
            return ((dbfs + 50f) / 4f).coerceIn(-2f, 10f)
        }
    }
}
