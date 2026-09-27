package dev.brgr.outspoke.settings.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    val triggerMode: StateFlow<String> = prefs.triggerMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "HOLD",
    )

    fun setTriggerMode(mode: String) {
        viewModelScope.launch { prefs.setTriggerMode(mode) }
    }

    val deleteButtonMode: StateFlow<String> = prefs.deleteButtonMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "DELETE_ALL",
    )

    fun setDeleteButtonMode(mode: String) {
        viewModelScope.launch { prefs.setDeleteButtonMode(mode) }
    }

    val rawMicCapture: StateFlow<Boolean> = prefs.rawMicCapture.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setRawMicCapture(enabled: Boolean) {
        viewModelScope.launch { prefs.setRawMicCapture(enabled) }
    }

    val vadSensitivity: StateFlow<Boolean> = prefs.vadSensitivity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setVadSensitivity(enabled: Boolean) {
        viewModelScope.launch { prefs.setVadSensitivity(enabled) }
    }

    val postprocessingEnabled: StateFlow<Boolean> = prefs.postprocessingEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setPostprocessingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setPostprocessingEnabled(enabled) }
    }

    val showPipelineDiagnostics: StateFlow<Boolean> = prefs.showPipelineDiagnostics.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setShowPipelineDiagnostics(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowPipelineDiagnostics(enabled) }
    }

    /** Resets the tutorial-shown flag so it plays again the next time the keyboard opens. */
    fun resetTutorial() {
        viewModelScope.launch { prefs.setKeyboardTutorialShown(false) }
    }
}
