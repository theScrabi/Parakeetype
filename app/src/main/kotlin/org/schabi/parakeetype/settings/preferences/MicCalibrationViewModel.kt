package org.schabi.parakeetype.settings.preferences

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.MicCalibrationManager
import org.schabi.parakeetype.audio.MicInfo
import org.schabi.parakeetype.audio.MicScore
import org.schabi.parakeetype.audio.MicScorer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One microphone's calibration result: the device plus its [MicScore] for the reference
 * utterance.
 */
data class MicResult(
    val mic: MicInfo,
    val score: MicScore,
)

/**
 * The lifecycle of a microphone calibration run.
 *
 *  - [Idle]: the mic list is shown with a "Start" button.
 *  - [GetReady]: a brief pause before mic [index] is captured; the UI tells the user which
 *    mic is next and to prepare to speak.
 *  - [Recording]: a reference clip is being captured on mic [index] of [total]; the UI shows
 *    a live "say 'thanks' now" indicator.
 *  - [Done]: every mic has been scored; [results] are ranked and [bestMicId] is persisted.
 *  - [Error]: the calibration could not run (no mics, missing permission, capture failure).
 */
sealed class CalibrationState {
    data object Idle : CalibrationState()
    data class GetReady(val index: Int, val total: Int) : CalibrationState()
    data class Recording(val index: Int, val total: Int) : CalibrationState()
    data class Done(val results: List<MicResult>, val bestMicId: Int) : CalibrationState()
    data class Error(@StringRes val messageRes: Int) : CalibrationState()
}

/**
 * Drives the microphone calibration: enumerates the input mics, records a short reference
 * clip ("thanks") on each, scores them by high-frequency energy + SNR ([MicScorer]), and
 * persists the winner as the preferred mic ([AppPreferences.setPreferredMicId]).
 */
class MicCalibrationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val manager = MicCalibrationManager(application)

    private val _mics = MutableStateFlow<List<MicInfo>>(emptyList())
    val mics: StateFlow<List<MicInfo>> = _mics.asStateFlow()

    private val _state = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /** The currently persisted preferred mic id (0 = none). */
    val preferredMicId: StateFlow<Int> = prefs.preferredMicId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    init {
        viewModelScope.launch {
            _mics.value = withContext(Dispatchers.IO) {
                runCatching { manager.listInputMics() }.getOrDefault(emptyList())
            }
        }
    }

    /**
     * Records a [RECORD_MS]-millisecond reference clip on every mic, scores each, and persists
     * the highest-scoring one. No-op if a calibration is already running.
     */
    fun startCalibration() {
        if (_state.value !is CalibrationState.Idle) return
        val micList = _mics.value
        if (micList.isEmpty()) {
            _state.value = CalibrationState.Error(R.string.calib_no_mics)
            return
        }
        viewModelScope.launch {
            val results = ArrayList<MicResult>(micList.size)
            for ((index, mic) in micList.withIndex()) {
                // Brief "get ready" pause so the user knows which mic is next and can prepare
                // to speak before the capture window opens.
                _state.value = CalibrationState.GetReady(index, micList.size)
                delay(READY_MS.toLong())
                _state.value = CalibrationState.Recording(index, micList.size)
                val score = try {
                    val samples = manager.recordOnMic(mic, RECORD_MS)
                    MicScorer.score(samples)
                } catch (e: Exception) {
                    Log.w(TAG, "Calibration capture failed for mic ${mic.id}", e)
                    MicScore(0f, 0f, 0f)
                }
                results.add(MicResult(mic, score))
            }
            val best = results.filter { it.score.score > 0f }.maxByOrNull { it.score.score }
            val bestId = best?.mic?.id ?: 0
            if (best != null) {
                prefs.setPreferredMicId(bestId)
                Log.d(TAG, "Calibration complete — preferred mic id=$bestId (score=${best.score.score})")
            }
            _state.value = CalibrationState.Done(results, bestId)
        }
    }

    /** Returns to the idle state so the user can re-run the calibration. */
    fun reset() {
        _state.value = CalibrationState.Idle
    }

    companion object {
        private const val TAG = "MicCalibrationVM"

        /** Reference-clip length per mic. Long enough to say "thanks" clearly. Exposed so the
         *  capture UI can sync its progress bar to the actual recording window. */
        const val RECORD_MS = 3000

        /** "Get ready" pause before each mic's capture window opens. */
        private const val READY_MS = 1500
    }
}
