package org.schabi.parakeetype.settings.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import org.schabi.parakeetype.settings.model.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance per process - the delegate ensures this.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "parakeetype_prefs")

/**
 * Thin wrapper around [DataStore<Preferences>] that exposes typed [Flow]s and suspend setters
 * for every user-configurable preference.
 *
 * Instantiate with application context to avoid memory leaks.
 */
class AppPreferences(private val context: Context) {

    private val keyTriggerMode = stringPreferencesKey("trigger_mode")

    /** `"HOLD"` or `"TAP_TOGGLE"`. Defaults to `"HOLD"`. */
    val triggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyTriggerMode] ?: "HOLD"
    }

    suspend fun setTriggerMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyTriggerMode] = mode }
    }

    private val keyImmediateMode = booleanPreferencesKey("immediate_mode")

    /**
     * When `true`, the keyboard starts listening as soon as the user switches to it (e.g. with
     * another keyboard's microphone key), and switches back to the previous keyboard once the
     * user stops speaking and the text is typed. Opt-in; defaults to `false`.
     */
    val immediateMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyImmediateMode] ?: false
    }

    suspend fun setImmediateMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyImmediateMode] = enabled }
    }

    private val keyDeleteButtonMode = stringPreferencesKey("delete_button_mode")

    /**
     * What the keyboard's delete (trash) button does:
     *   `"DELETE_ALL"` (default) — clears the entire editor field.
     *   `"DELETE_LAST_SENTENCE"` — removes only the last sentence before the cursor,
     *   so a long document is never wiped by a single tap.
     */
    val deleteButtonMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyDeleteButtonMode] ?: "DELETE_ALL"
    }

    suspend fun setDeleteButtonMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyDeleteButtonMode] = mode }
    }

    private val keyKeyboardPositionPortrait = stringPreferencesKey("keyboard_position_portrait")

    /**
     * Horizontal position of the keyboard UI in portrait: `"CENTER"` (default, full width),
     * `"LEFT"` or `"RIGHT"` (docked to that edge at a phone-like width — for tablets and
     * one-handed use).
     */
    val keyboardPositionPortrait: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyKeyboardPositionPortrait] ?: "CENTER"
    }

    suspend fun setKeyboardPositionPortrait(position: String) {
        context.dataStore.edit { prefs -> prefs[keyKeyboardPositionPortrait] = position }
    }

    private val keyKeyboardPositionLandscape = stringPreferencesKey("keyboard_position_landscape")

    /**
     * Edge the keyboard UI is docked to in landscape: `"RIGHT"` (default) or `"LEFT"`
     * (left-handed use). In landscape the UI is always docked so it stays within thumb reach.
     */
    val keyboardPositionLandscape: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyKeyboardPositionLandscape] ?: "RIGHT"
    }

    suspend fun setKeyboardPositionLandscape(position: String) {
        context.dataStore.edit { prefs -> prefs[keyKeyboardPositionLandscape] = position }
    }

    private val keyRawMicCapture = booleanPreferencesKey("raw_mic_capture")

    /**
     * When `true`, [org.schabi.parakeetype.audio.AudioCaptureManager] captures from
     * [android.media.MediaRecorder.AudioSource.UNPROCESSED] instead of
     * [android.media.MediaRecorder.AudioSource.DEFAULT], bypassing the platform's
     * voice processing (AEC / NS / AGC).
     *
     * Required for the speakerphone use case: with the default (voice-processed)
     * source, audio played from the device's own speaker is cancelled out of the
     * mic stream by AEC, so e.g. a voicemail playing on speaker transcribes as
     * silence. Off by default to keep the standard capture behaviour.
     */
    val rawMicCapture: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyRawMicCapture] ?: false
    }

    suspend fun setRawMicCapture(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyRawMicCapture] = enabled }
    }

    private val keyVadEnabled = booleanPreferencesKey("vad_enabled")

    /** Whether VAD (voice activity detection) is active. Defaults to `true`. */
    val vadSensitivity: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyVadEnabled] ?: true
    }

    suspend fun setVadSensitivity(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyVadEnabled] = enabled }
    }

    private val keySelectedModelId = stringPreferencesKey("selected_model_id")

    /**
     * The [ModelId] of the model that [InferenceService] should load.
     * Defaults to [ModelId.DEFAULT] when no preference has been saved.
     */
    val selectedModelId: Flow<ModelId> = context.dataStore.data.map { prefs ->
        val stored = prefs[keySelectedModelId]
        if (stored != null) runCatching { ModelId.valueOf(stored) }.getOrDefault(ModelId.DEFAULT)
        else ModelId.DEFAULT
    }

    suspend fun setSelectedModelId(modelId: ModelId) {
        context.dataStore.edit { prefs -> prefs[keySelectedModelId] = modelId.name }
    }

    private val keyWhisperLanguage = stringPreferencesKey("whisper_language")

    /**
     * BCP-47 language tag for Whisper decoding, or `"auto"` for automatic detection.
     * Supported values: `"auto"`, `"en"`, `"de"`, `"nl"`.
     * Defaults to `"auto"`.
     */
    val whisperLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyWhisperLanguage] ?: "auto"
    }

    suspend fun setWhisperLanguage(tag: String) {
        context.dataStore.edit { prefs -> prefs[keyWhisperLanguage] = tag }
    }

    private val keyPostprocessingEnabled = booleanPreferencesKey("postprocessing_enabled")

    /**
     * When `true` (default) the transcript post-processing pipeline is active:
     * filler words are removed, stutters collapsed, repeated phrases deduplicated,
     * and sentence capitalisation enforced.
     *
     * Set to `false` to receive the raw model output unchanged - useful for debugging
     * whether cleaning is responsible for dropped or altered words.
     */
    val postprocessingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyPostprocessingEnabled] ?: true
    }

    suspend fun setPostprocessingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyPostprocessingEnabled] = enabled }
    }

    private val keyShowPipelineDiagnostics = booleanPreferencesKey("show_pipeline_diagnostics")

    /**
     * When `true`, the keyboard UI displays a live [PipelineDiagnostics] summary badge.
     * Defaults to `false` - hidden by default to keep the UI clean.
     */
    val showPipelineDiagnostics: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyShowPipelineDiagnostics] ?: false
    }

    suspend fun setShowPipelineDiagnostics(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyShowPipelineDiagnostics] = enabled }
    }

    private val keyForcedLanguage = stringPreferencesKey("forced_language")

    /**
     * BCP-47 language tag to force on the active speech engine, or `null` for automatic detection.
     *
     * Supported values for Parakeet TDT 0.6B-v3:
     *   `null` (auto), `"en"`, `"de"`, `"fr"`, `"es"`, `"it"`, `"pt"`, `"nl"`, `"pl"`,
     *   `"zh"`, `"ja"`, `"ko"`.
     *
     * When non-null, `InferenceService` passes this tag to `SpeechEngine.setLanguage()` after
     * loading the engine.  The language is also used by the post-processing pipeline
     * (filler-word removal, number normalisation) to select the correct locale rules.
     *
     * Defaults to `null` (auto-detect) so existing installs are unaffected.
     */
    val forcedLanguage: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyForcedLanguage]   // null when key absent → auto-detect
    }

    suspend fun setForcedLanguage(tag: String?) {
        context.dataStore.edit { prefs ->
            if (tag == null) prefs.remove(keyForcedLanguage)
            else prefs[keyForcedLanguage] = tag
        }
    }

    private val keyFormatNumbersAsDigits = booleanPreferencesKey("format_numbers_as_digits")

    /**
     * When `true` (default), the post-processing pipeline converts number-word sequences
     * such as "twelve", "two thousand and twenty five", or "zwölf" into their digit
     * equivalents ("12", "2025", "12").
     *
     * Set to `false` to keep number words as spoken — useful when the user dictates
     * text where the written form of numbers is preferred (e.g. legal or literary writing).
     */
    val formatNumbersAsDigits: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyFormatNumbersAsDigits] ?: true
    }

    suspend fun setFormatNumbersAsDigits(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyFormatNumbersAsDigits] = enabled }
    }

    private val keyKeepModelLoaded = booleanPreferencesKey("keep_model_loaded")

    /**
     * When `true`, [org.schabi.parakeetype.inference.InferenceService] runs as a *started*
     * foreground service (persistent notification) so the ~700 MB speech model stays in
     * RAM while the user switches to another keyboard. Without it the service is only
     * bound by the IME and is destroyed — model unloaded — as soon as another IME is
     * picked. Opt-out; defaults to `true`.
     */
    val keepModelLoaded: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyKeepModelLoaded] ?: true
    }

    suspend fun setKeepModelLoaded(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyKeepModelLoaded] = enabled }
    }

    private val keyPreferredMicId = intPreferencesKey("preferred_mic_id")

    /**
     * The [android.media.AudioDeviceInfo.id] the microphone calibration selected as the
     * highest-fidelity input, or `0` when no calibration has been run (use the system
     * default). [org.schabi.parakeetype.audio.AudioCaptureManager] applies it via
     * `AudioRecord.setPreferredDevice`.
     */
    val preferredMicId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[keyPreferredMicId] ?: 0
    }

    suspend fun setPreferredMicId(id: Int) {
        context.dataStore.edit { prefs -> prefs[keyPreferredMicId] = id }
    }

}
