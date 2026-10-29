package org.schabi.parakeetype.settings.preferences

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.schabi.parakeetype.inference.InferenceService
import org.schabi.parakeetype.inference.TextReplacement
import org.schabi.parakeetype.inference.TextReplacements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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

    val textReplacements: StateFlow<List<TextReplacement>> = prefs.textReplacements.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Appends [rule] to the replacement table, or replaces the rule at [index] when given. */
    fun saveTextReplacement(rule: TextReplacement, index: Int? = null) {
        viewModelScope.launch {
            prefs.updateTextReplacements { rules ->
                if (index == null || index !in rules.indices) rules + rule
                else rules.toMutableList().apply { set(index, rule) }
            }
        }
    }

    fun removeTextReplacement(index: Int) {
        viewModelScope.launch {
            prefs.updateTextReplacements { rules -> rules.filterIndexed { i, _ -> i != index } }
        }
    }

    /**
     * Writes the replacement table to [uri] as CSV ([TextReplacements.toCsv]).
     * Returns the number of rules written, or `null` when the file could not be written.
     */
    suspend fun exportTextReplacements(uri: Uri): Int? = withContext(Dispatchers.IO) {
        val rules = prefs.textReplacements.first()
        try {
            val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("No output stream for $uri")
            output.bufferedWriter().use { it.write(TextReplacements.toCsv(rules)) }
            rules.size
        } catch (e: Exception) {
            Log.w(TAG, "Export of the replacement table failed", e)
            null
        }
    }

    /**
     * Reads a replacement table from the CSV file at [uri] ([TextReplacements.fromCsv]) without
     * storing it. Returns `null` when the file could not be read or is too large to be a table.
     */
    suspend fun readTextReplacements(uri: Uri): List<TextReplacement>? = withContext(Dispatchers.IO) {
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IOException("No input stream for $uri")
            val bytes = input.use { it.readAtMost(MAX_IMPORT_BYTES) }
                ?: throw IOException("$uri is larger than $MAX_IMPORT_BYTES bytes")
            TextReplacements.fromCsv(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Import of the replacement table failed", e)
            null
        }
    }

    /** Stores [imported] rules: as the whole table when [replace], else merged into it. */
    fun importTextReplacements(imported: List<TextReplacement>, replace: Boolean) {
        viewModelScope.launch {
            prefs.updateTextReplacements { rules ->
                if (replace) imported else TextReplacements.merge(rules, imported)
            }
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > limit) return null
        }
    }

    private companion object {
        const val TAG = "PreferencesViewModel"

        /** Far above any hand-made table; guards against picking e.g. a video by mistake. */
        const val MAX_IMPORT_BYTES = 1024 * 1024
    }

}
