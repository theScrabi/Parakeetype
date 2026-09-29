package org.schabi.parakeetype.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.parakeetype.inference.EngineState
import org.schabi.parakeetype.inference.InferenceRepository
import org.schabi.parakeetype.inference.InferenceService
import org.schabi.parakeetype.settings.model.ModelStorageManager
import org.schabi.parakeetype.settings.preferences.AppPreferences

private const val TAG = "InferenceConnection"

/**
 * Upper bound for waiting until the engine is ready. Loading the ~700 MB Parakeet model takes
 * a few seconds; the generous limit covers slow devices. Audio captured meanwhile is buffered.
 */
private const val ENGINE_READY_TIMEOUT_MS = 60_000L

/**
 * A binding to [InferenceService] for the speech-recognizer entry points
 * ([ParakeetypeRecognitionService], [VoiceInputActivity]) — the same service the IME binds, so
 * the recognizer and the keyboard share one loaded model.
 *
 * Binding starts loading the model; [awaitRepository] then waits until it is usable.
 */
class InferenceConnection(private val context: Context) {

    /** Outcome of [awaitRepository]. */
    sealed class Result {
        data class Ready(val repository: InferenceRepository) : Result()

        /** [error] is a `SpeechRecognizer.ERROR_*` code. */
        data class Unavailable(val error: Int, val reason: String) : Result()
    }

    private val binder = MutableStateFlow<InferenceService.InferenceBinder?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder.value = service as InferenceService.InferenceBinder
        }

        // BIND_AUTO_CREATE re-creates the service and calls onServiceConnected again.
        override fun onServiceDisconnected(name: ComponentName) {
            binder.value = null
        }
    }

    fun bind() {
        if (isBound) return
        isBound = context.bindService(
            Intent(context, InferenceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!isBound) Log.e(TAG, "bindService(InferenceService) failed")
    }

    fun unbind() {
        if (!isBound) return
        context.unbindService(connection)
        isBound = false
        binder.value = null
    }

    /**
     * Suspends until the engine is [EngineState.Ready], it failed, or no model is installed.
     *
     * [onLoading] is called (at most once) when an installed model is not loaded yet, i.e.
     * the caller has to wait for it to load.
     */
    suspend fun awaitRepository(onLoading: () -> Unit = {}): Result {
        bind()
        return withTimeoutOrNull(ENGINE_READY_TIMEOUT_MS) {
            val b = binder.filterNotNull().first()
            // Reload a model that was closed under memory pressure (no-op otherwise).
            b.reloadIfNeeded()
            var loadingReported = false
            val state = b.getEngineState().first { state ->
                val done = when (state) {
                    EngineState.Ready, is EngineState.Error -> true
                    // Unloaded with the model files present means a memory-pressure unload
                    // that reloadIfNeeded() is reversing — keep waiting.
                    EngineState.Unloaded -> !isSelectedModelInstalled()
                    EngineState.Loading -> false
                }
                // A freshly created service starts in Loading before it notices that no model
                // is installed, so only an installed model counts as loading.
                if (!done && !loadingReported && isSelectedModelInstalled()) {
                    loadingReported = true
                    onLoading()
                }
                done
            }
            when (state) {
                EngineState.Ready -> b.getRepository()?.let { Result.Ready(it) }
                    ?: Result.Unavailable(SpeechRecognizer.ERROR_SERVER, "engine ready but no repository bound")
                is EngineState.Error -> Result.Unavailable(SpeechRecognizer.ERROR_SERVER, state.message)
                else -> Result.Unavailable(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, "no speech model installed")
            }
        } ?: Result.Unavailable(SpeechRecognizer.ERROR_SERVER, "timed out waiting for the engine to load")
    }

    private suspend fun isSelectedModelInstalled(): Boolean {
        val modelId = AppPreferences(context.applicationContext).selectedModelId.first()
        return ModelStorageManager.isModelReady(context, modelId)
    }
}
