package org.schabi.parakeetype.recognition

import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.settings.model.ModelStorageManager

private const val TAG = "ParakeetypeRecognition"

/**
 * Languages of the Parakeet TDT 0.6B v3 model (BCP-47), reported by
 * [ParakeetypeRecognitionService.onCheckRecognitionSupport].
 */
internal val PARAKEET_LANGUAGES = listOf(
    "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it",
    "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk",
)

/**
 * On-device [RecognitionService]: lets other apps use Parakeetype through
 * [SpeechRecognizer] (and lets the user pick it as the system's voice-input service).
 *
 * The service binds [org.schabi.parakeetype.inference.InferenceService] for as long as a client
 * is bound, so the model starts loading as soon as a client creates its `SpeechRecognizer` and
 * is shared with the keyboard. Each `startListening` runs one [RecognitionSession]; the
 * platform allows one session at a time (the default [getMaxConcurrentSessionsCount]).
 *
 * The platform already checked that the caller holds `RECORD_AUDIO`. Audio is recorded on an
 * attribution context built from the caller's [Callback.getCallingAttributionSource], so the
 * microphone use is attributed to (and shown for) the calling app as well.
 */
class ParakeetypeRecognitionService : RecognitionService() {

    private val scope = MainScope()
    private lateinit var inference: InferenceConnection

    private var session: RecognitionSession? = null

    override fun onCreate() {
        super.onCreate()
        inference = InferenceConnection(this).also { it.bind() }
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        scope.cancel()
        inference.unbind()
        super.onDestroy()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (session != null) {
            listener.send { error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (recognizerIntent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)) {
            // Only live microphone input is supported.
            Log.w(TAG, "EXTRA_AUDIO_SOURCE is not supported")
            listener.send { error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        if (!PermissionHelper.hasRecordPermission(this)) {
            // Parakeetype itself also needs the permission; it is granted in the app.
            Log.w(TAG, "RECORD_AUDIO not granted to Parakeetype")
            listener.send { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }

        // Must be created before returning: the platform only skips its own data-delivery
        // bookkeeping when the attribution context exists by then.
        val audioContext = createContext(
            ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build()
        )
        val options = RecognitionOptions.from(recognizerIntent)
        session = RecognitionSession(
            audioContext = audioContext,
            inference = inference,
            options = options,
            listener = CallbackListener(listener),
            scope = scope,
        ).also { it.start() }
    }

    override fun onStopListening(listener: Callback) {
        session?.stop()
    }

    override fun onCancel(listener: Callback) {
        session?.cancel()
        session = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        val support = RecognitionSupport.Builder().apply {
            if (ModelStorageManager.isModelReady(this@ParakeetypeRecognitionService)) {
                setInstalledOnDeviceLanguages(PARAKEET_LANGUAGES)
            } else {
                // Installed by importing the model archive in the app; there is no download.
                setSupportedOnDeviceLanguages(PARAKEET_LANGUAGES)
            }
        }.build()
        supportCallback.onSupportResult(support)
    }

    /** Forwards the session events to the client; a terminal event ends the session. */
    private inner class CallbackListener(private val callback: Callback) : RecognitionSession.Listener {
        override fun onReadyForSpeech() = callback.send { readyForSpeech(Bundle()) }
        override fun onBeginningOfSpeech() = callback.send { beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = callback.send { rmsChanged(rmsdB) }
        override fun onPartialResult(text: String) = callback.send { partialResults(resultsBundle(text, null)) }
        // Segmented sessions only exist on API 33+ (see RecognitionOptions.segmented).
        override fun onSegmentResult(text: String, confidence: Float) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            callback.send { segmentResults(resultsBundle(text, confidence)) }
        }

        override fun onEndOfSpeech() = callback.send { endOfSpeech() }

        override fun onResult(text: String, confidence: Float) {
            endSession()
            callback.send { results(resultsBundle(text, confidence)) }
        }

        override fun onEndOfSegmentedSession() {
            endSession()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            callback.send { endOfSegmentedSession() }
        }

        override fun onError(error: Int) {
            endSession()
            callback.send { error(error) }
        }

        private fun endSession() {
            session = null
        }
    }
}

/** Builds the `results` / `partialResults` bundle: one hypothesis, optionally scored. */
private fun resultsBundle(text: String, confidence: Float?) = Bundle().apply {
    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    if (confidence != null) putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(confidence))
}

/** Client callbacks are binder calls; a client that died is simply no longer notified. */
private inline fun RecognitionService.Callback.send(block: RecognitionService.Callback.() -> Unit) {
    try {
        block()
    } catch (e: RemoteException) {
        Log.w(TAG, "Recognition client is gone", e)
    }
}
