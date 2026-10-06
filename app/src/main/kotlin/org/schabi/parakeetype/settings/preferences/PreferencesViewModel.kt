package org.schabi.parakeetype.settings.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.schabi.parakeetype.inference.InferenceService
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

    val instantMode: StateFlow<Boolean> = prefs.instantMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setInstantMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setInstantMode(enabled) }
    }

    val deleteButtonMode: StateFlow<String> = prefs.deleteButtonMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "DELETE_ALL",
    )

    fun setDeleteButtonMode(mode: String) {
        viewModelScope.launch { prefs.setDeleteButtonMode(mode) }
    }

    val keyboardPositionPortrait: StateFlow<String> = prefs.keyboardPositionPortrait.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "CENTER",
    )

    fun setKeyboardPositionPortrait(position: String) {
        viewModelScope.launch { prefs.setKeyboardPositionPortrait(position) }
    }

    val keyboardPositionLandscape: StateFlow<String> = prefs.keyboardPositionLandscape.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "RIGHT",
    )

    fun setKeyboardPositionLandscape(position: String) {
        viewModelScope.launch { prefs.setKeyboardPositionLandscape(position) }
    }

    val leftHandedMode: StateFlow<Boolean> = prefs.leftHandedMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setLeftHandedMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setLeftHandedMode(enabled) }
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

    val keepModelLoaded: StateFlow<Boolean> = prefs.keepModelLoaded.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    /**
     * Persists the keep-model-loaded choice. Enabling it also starts [InferenceService] in
     * its foreground mode right away (allowed: the settings Activity is visible), which
     * preloads the model. Disabling is handled by the service itself, which observes the
     * preference and leaves the foreground state.
     */
    fun setKeepModelLoaded(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setKeepModelLoaded(enabled)
            if (enabled) InferenceService.startKeepLoaded(getApplication())
        }
    }

}
