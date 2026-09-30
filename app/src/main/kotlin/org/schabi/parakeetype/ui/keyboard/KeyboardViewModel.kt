package org.schabi.parakeetype.ui.keyboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.schabi.parakeetype.audio.AudioCaptureManager
import org.schabi.parakeetype.audio.SpeechEndpointer
import org.schabi.parakeetype.ime.EnterAction
import org.schabi.parakeetype.ime.TextInjector
import org.schabi.parakeetype.inference.EngineState
import org.schabi.parakeetype.inference.InferenceRepository
import org.schabi.parakeetype.inference.PipelineDiagnostics
import org.schabi.parakeetype.inference.TranscriptResult
import org.schabi.parakeetype.recognition.RecognitionSession
import org.schabi.parakeetype.settings.preferences.AppPreferences
import org.schabi.parakeetype.ui.keyboard.components.WHISPER_LANGUAGE_OPTIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "KeyboardViewModel"

/** Silence after speech that ends an instant-mode session. */
private const val INSTANT_END_OF_SPEECH_SILENCE_MS = 2_000L

/** Without any speech for this long an instant-mode session ends on its own. */
private const val INSTANT_NO_SPEECH_TIMEOUT_MS = 8_000L

/** Report the microphone level every 3rd 30 ms chunk (~11 updates per second). */
private const val LEVEL_REPORT_INTERVAL_CHUNKS = 3

/**
 * Bridges the IME lifecycle, audio capture, and inference pipeline into a stream of
 * [KeyboardUiState] values consumed by [KeyboardScreen].
 */
class KeyboardViewModel(
    private val audioCaptureManager: AudioCaptureManager,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<KeyboardUiState>(
        KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting)
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    /**
     * `"HOLD"` (default) or `"TAP_TOGGLE"`.
     * Collected eagerly so the TalkButton always has the latest value without
     * needing a suspend context.
     */
    val triggerMode: StateFlow<String> = appPreferences.triggerMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "HOLD")

    /**
     * What the delete (trash) button does: `"DELETE_ALL"` (default) or
     * `"DELETE_LAST_SENTENCE"`. Collected eagerly so [deleteAll] can read a
     * synchronous snapshot without a suspend context.
     */
    val deleteButtonMode: StateFlow<String> = appPreferences.deleteButtonMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "DELETE_ALL")

    /** Keyboard UI position in portrait: `"CENTER"` (default), `"LEFT"` or `"RIGHT"`. */
    val keyboardPositionPortrait: StateFlow<String> = appPreferences.keyboardPositionPortrait
        .stateIn(viewModelScope, SharingStarted.Eagerly, "CENTER")

    /** Edge the keyboard UI is docked to in landscape: `"RIGHT"` (default) or `"LEFT"`. */
    val keyboardPositionLandscape: StateFlow<String> = appPreferences.keyboardPositionLandscape
        .stateIn(viewModelScope, SharingStarted.Eagerly, "RIGHT")

    /**
     * `true` when the user opted into raw (unprocessed) microphone capture,
     * which bypasses the platform's echo cancellation - needed when transcribing
     * audio played from the device's own speaker.
     * Collected eagerly so [onRecordStart] can read a synchronous snapshot.
     */
    val rawMicCapture: StateFlow<Boolean> = appPreferences.rawMicCapture
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether VAD (voice activity detection) is enabled. Collected eagerly so the value is
     * always available as a snapshot when recording starts - no suspend context required.
     */
    val vadSensitivity: StateFlow<Boolean> = appPreferences.vadSensitivity
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * `true` when the currently selected model is a Whisper variant.
     * Used to conditionally show the language selector in the keyboard UI.
     */
    val isWhisperEngine: StateFlow<Boolean> = appPreferences.selectedModelId
        .map { it.name.startsWith("WHISPER") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Currently selected Whisper language tag: `"auto"`, `"en"`, `"de"`, or `"nl"`.
     * Collected eagerly so the selector always reflects the saved value on first draw.
     */
    val whisperLanguage: StateFlow<String> = appPreferences.whisperLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    /**
     * The post-processing language used by the cleaning pipeline (filler removal, number
     * normalisation, spurious-period handling). Mirrors [SpeechEngine.currentLanguage] for
     * the active engine: the user's forced language, or `"en"` when set to `"auto"` / unset.
     *
     * Collected eagerly so [org.schabi.parakeetype.ime.TextInjector]'s display-cleaning lambda can
     * read a synchronous snapshot when injecting text — this keeps the display path's
     * language consistent with the engine's (e.g. so German noun capitalisation is
     * preserved and German fillers are removed in the displayed text, not just in the
     * stable-chunk tracking path).
     */
    val currentLanguage: StateFlow<String> = appPreferences.forcedLanguage
        .map { it ?: "en" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "en")

    /**
     * `true` when the transcript post-processing pipeline (filler removal, stutter collapse,
     * repetition deduplication, capitalisation) is active.  Defaults to `true`.
     * Collected eagerly so the live value is always available when recording starts.
     */
    val postprocessingEnabled: StateFlow<Boolean> = appPreferences.postprocessingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Pipeline diagnostic counters for the current or most recent recording session.
     *
     * Updated live during recording and kept visible after the session ends so the user
     * can glance at the keyboard status bar to see if anything unusual happened (trims,
     * alignment recoveries, blanks discarded) without needing logcat.
     *
     * Reset at the start of each new recording in [onRecordStart].
     */
    private val _diagnostics = MutableStateFlow(PipelineDiagnostics())
    val diagnostics: StateFlow<PipelineDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Whether the pipeline diagnostics badge is visible on the keyboard.
     * Collected eagerly so the UI always reflects the saved preference on first draw.
     */
    val showPipelineDiagnostics: StateFlow<Boolean> = appPreferences.showPipelineDiagnostics
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * When `true` (default), number-word sequences in the transcript are converted to
     * digit form by [org.schabi.parakeetype.inference.NumberNormaliser] as part of the
     * post-processing pipeline.
     */
    val formatNumbersAsDigits: StateFlow<Boolean> = appPreferences.formatNumbersAsDigits
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Persists [tag] to preferences and immediately forwards it to the loaded engine.
     * Safe to call at any time - the engine's [setLanguage] is thread-safe.
     */
    fun setWhisperLanguage(tag: String) {
        inferenceRepository?.setLanguage(tag)
        viewModelScope.launch { appPreferences.setWhisperLanguage(tag) }
    }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)

    /** Called by [ParakeetypeInputMethodService] whenever [InferenceService.engineState] changes. */
    fun setEngineState(state: EngineState) {
        _engineState.value = state
        when (state) {
            EngineState.Unloaded -> _uiState.value = KeyboardUiState.EngineLoading(
                KeyboardUiState.LoadingReason.ModelNotDownloaded
            )

            EngineState.Loading -> _uiState.value = KeyboardUiState.EngineLoading(
                KeyboardUiState.LoadingReason.EngineStarting
            )

            EngineState.Ready -> {
                // Clear any engine-driven blocking state so the user can start recording.
                if (_uiState.value is KeyboardUiState.EngineLoading ||
                    (_uiState.value is KeyboardUiState.Error &&
                            (_uiState.value as KeyboardUiState.Error).reason == KeyboardUiState.ErrorReason.EngineLoadFailed)
                ) {
                    _uiState.value = KeyboardUiState.Idle
                }
                startPendingInstantSession()
            }

            is EngineState.Error -> _uiState.value = KeyboardUiState.Error(
                reason = KeyboardUiState.ErrorReason.EngineLoadFailed,
                detail = state.message,
            )
        }
    }

    private var inferenceRepository: InferenceRepository? = null

    /** Set by [ParakeetypeInputMethodService] when the service binding is established. */
    fun setInferenceRepository(repo: InferenceRepository?) {
        inferenceRepository = repo
        // Apply the persisted language tag immediately.
        repo?.setLanguage(whisperLanguage.value)
        // Constrain auto-detection to only the languages shown in the selector.
        // "Auto" then means "detect from EN/DE/ES" instead of "any of ~100 languages",
        // which eliminates false picks like ES (50262) beating EN (50259) by a tiny margin.
        repo?.setLanguageConstraints(
            WHISPER_LANGUAGE_OPTIONS.filter { (tag, _) -> tag != "auto" }.map { (tag, _) -> tag }
        )
        startPendingInstantSession()
    }

    private var textInjector: TextInjector? = null

    private val _enterAction = MutableStateFlow(EnterAction.DONE)

    /**
     * The context-aware action the Enter key should perform for the currently focused editor.
     * Updated each time [setTextInjector] is called (i.e. on every [onStartInput]).
     */
    val enterAction: StateFlow<EnterAction> = _enterAction.asStateFlow()

    fun setTextInjector(injector: TextInjector?) {
        textInjector = injector
        _enterAction.value = injector?.enterAction ?: EnterAction.DONE
    }

    private val _isContinuousMode = MutableStateFlow(false)

    /**
     * `true` while the keyboard is in locked continuous-recording mode (engaged by
     * dragging the talk button to the left).  Resets to `false` whenever recording stops.
     */
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)

    /**
     * Microphone level of the running recording in [0, 1] (the voice-input sheet's scale),
     * updated ~11 times per second from the capture thread; drives the talk button's halo.
     */
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    /**
     * Called by the UI when the user drags the talk button past the lock threshold.
     * Recording continues uninterrupted - only the visual state changes.
     */
    fun onContinuousModeEnabled() {
        _isContinuousMode.value = true
    }

    /**
     * Called when the user taps "Retry" after a transient error (e.g. low-confidence failure).
     *
     * Clears the error and returns to [KeyboardUiState.Idle] so the user starts a fresh
     * recording with a normal press (HOLD) or a drag-left-to-lock — exactly like the initial
     * recording.
     *
     * We intentionally do NOT auto-start recording and do NOT engage continuous mode. The
     * previous behaviour forced continuous mode and immediately started capturing, which
     * made the button jump straight into locked recording with no finger on it — confusing
     * and not what "retry" implies. "Retry" means "let me try again", i.e. dismiss the
     * error and give me back the idle button; the user then chooses how to record.
     */
    fun onRetry() {
        // Cancel any lingering capture / inference from the failed session so it cannot
        // bleed into the next one, then return to Idle.
        captureJob?.cancel()
        captureJob = null
        audioCaptureManager.stopCapture()
        cancelInstantSession()
        _isContinuousMode.value = false
        _uiState.value = KeyboardUiState.Idle
    }

    /** Delete backward to the previous word boundary. */
    fun deleteWord() {
        textInjector?.deleteWord()
    }

    /**
     * Performs the configured delete action: clears the whole editor field
     * ([deleteButtonMode] == `"DELETE_ALL"`, the default) or removes only the
     * last sentence before the cursor (`"DELETE_LAST_SENTENCE"`).
     */
    fun deleteAll() {
        if (deleteButtonMode.value == "DELETE_LAST_SENTENCE") {
            textInjector?.deleteLastSentence()
        } else {
            textInjector?.deleteAll()
        }
    }

    /**
     * Perform the context-aware Enter action for the currently focused editor.
     * Inserts a newline for multi-line fields; otherwise triggers the editor's IME action
     * (search, send, go, done, next) via [InputConnection.performEditorAction].
     */
    fun performEnterAction() {
        textInjector?.performEnterAction()
    }

    private var captureJob: Job? = null

    /**
     * Instant mode: the [requestInstantStart] coroutine that reads the preference, whether a
     * session is waiting for the engine ([instantPending]) or recording without a finger on
     * the talk button ([instantSession]), and whether its end switches back to the previous
     * keyboard ([instantSwitchBack]; cleared as soon as the user touches the talk button).
     */
    private var instantRequest: Job? = null
    private var instantPending = false
    private var instantSession = false
    private var instantSwitchBack = false

    /**
     * Set by [org.schabi.parakeetype.ime.ParakeetypeInputMethodService]: an instant-mode
     * session ended on its own and its text is typed — switch back to the previous keyboard;
     * returns whether it did. Not called when the session ended with an error, so the user
     * sees it, nor when the user took the session over with the talk button.
     */
    var onInstantSessionFinished: (() -> Boolean)? = null

    /**
     * Called by the IME when the user just switched to it. With instant mode enabled, starts
     * recording right away (or as soon as the engine is ready) without a press of the talk
     * button; the session ends on its own after the user stops speaking ([SpeechEndpointer])
     * and then [onInstantSessionFinished] fires.
     */
    fun requestInstantStart() {
        instantRequest?.cancel()
        instantRequest = viewModelScope.launch {
            if (!appPreferences.instantMode.first()) return@launch
            Log.d(TAG, "Instant mode - starting to listen once the engine is ready")
            instantPending = true
            startPendingInstantSession()
        }
    }

    private fun startPendingInstantSession() {
        if (!instantPending) return
        if (_engineState.value !is EngineState.Ready || inferenceRepository == null) return
        instantPending = false
        instantSession = true
        instantSwitchBack = true
        onRecordStart()
    }

    /** Drops a pending or running instant-mode session without switching keyboards. */
    private fun cancelInstantSession() {
        instantRequest?.cancel()
        instantRequest = null
        instantPending = false
        instantSession = false
        instantSwitchBack = false
    }

    /**
     * The instant-mode session's transcription finished and its text is typed: switch back
     * to the previous keyboard right away (or return to Idle when there is none). The
     * Transcribing state is kept until then, so the keyboard never flashes its idle state.
     */
    private fun finishInstantSession() {
        instantSwitchBack = false
        _isContinuousMode.value = false
        captureJob = null
        if (_uiState.value is KeyboardUiState.Error) {
            Log.d(TAG, "Instant-mode session ended with an error - staying on the keyboard")
            return
        }
        if (onInstantSessionFinished?.invoke() != true) _uiState.value = KeyboardUiState.Idle
    }

    /**
     * Set by [org.schabi.parakeetype.ime.ParakeetypeInputMethodService] to a lambda that requests
     * the inference service to reload its (still-present) model and re-establish the
     * repository binding.
     *
     * Invoked from [onRecordStart] when the engine reports [EngineState.Ready] but no
     * [InferenceRepository] is currently bound — a binder desync that would otherwise let
     * the mic open with no transcription. The callback lets the IME react (rebind / reload)
     * instead of the VM silently doing nothing.
     */
    var onMissingRepo: (() -> Unit)? = null

    /**
     * The talk button asks to start recording. A press while an instant-mode session records
     * takes that session over instead of starting a new one: recording goes on as if the
     * button had been held from the start (release stops it, drag left locks it), the
     * endpointer no longer stops it and the keyboard stays open afterwards.
     */
    fun onTalkPress() {
        if (instantSession && captureJob != null &&
            (_uiState.value is KeyboardUiState.Listening || _uiState.value is KeyboardUiState.Processing)
        ) {
            Log.d(TAG, "Instant mode - talk button pressed, handing the session over")
            instantSession = false
            instantSwitchBack = false
            return
        }
        onRecordStart()
    }

    /**
     * Start microphone capture and pipe audio through the inference engine.
     * Ignored if the engine is not yet [EngineState.Ready] or if no
     * [InferenceRepository] is currently bound (a binder desync).
     */
    fun onRecordStart() {
        if (_engineState.value !is EngineState.Ready) {
            Log.w(TAG, "onRecordStart() ignored - engine not ready (${_engineState.value})")
            return
        }

        // EngineState.Ready without a bound InferenceRepository means the UI shows
        // a ready talk button but transcription would silently no-op (the old code fell
        // into a "capture audio without transcription" branch). Surface the desync as a
        // loading state and ask the IME to reload/rebind instead of opening the mic.
        if (inferenceRepository == null) {
            Log.w(
                TAG,
                "onRecordStart() ignored - engine Ready but no InferenceRepository bound (desync); requesting reload"
            )
            _uiState.value = KeyboardUiState.EngineLoading(
                KeyboardUiState.LoadingReason.EngineStarting
            )
            onMissingRepo?.invoke()
            return
        }

        captureJob?.cancel()
        _uiState.value = KeyboardUiState.Listening
        _diagnostics.value = PipelineDiagnostics()

        // An instant-mode session runs without a finger on the talk button: it looks like a
        // held button and the endpointer stops it. The endpointer needs the VAD's speech
        // probability, so VAD is on for this session whatever the setting says.
        val instant = instantSession

        captureJob = viewModelScope.launch {
            // Capture this coroutine's Job reference so the collect lambda can detect
            // whether it belongs to the currently active session.  If onRecordStart() is
            // called again (e.g. from onFieldCleared after a "Send"), captureJob is
            // replaced with the new job; any collect callbacks still queued from the OLD
            // job see captureJob != myJob and return immediately - preventing stale
            // partial results from bleeding into the fresh TextInjector state.
            val myJob = coroutineContext[Job]

            try {
                // Repo was verified non-null above; re-read defensively in case the binder
                // detached between the guard and this launch. If it did become null, abort
                // cleanly rather than capturing audio with nowhere to send it.
                val repo = inferenceRepository
                if (repo == null) {
                    Log.w(TAG, "InferenceRepository detached before capture started - aborting session")
                    _isContinuousMode.value = false
                    _uiState.value = KeyboardUiState.EngineLoading(
                        KeyboardUiState.LoadingReason.EngineStarting
                    )
                    onMissingRepo?.invoke()
                    return@launch
                }

                // Pipe audio through the inference engine on Dispatchers.Default.
                // TranscriptResult emissions drive both the UI and text injection.
                // Fed on the capture thread; its terminal event stops this session like a tap
                // on the talk button (unless the session was replaced meanwhile).
                val endpointer = if (instant) {
                    SpeechEndpointer(
                        silenceMs = INSTANT_END_OF_SPEECH_SILENCE_MS,
                        noSpeechTimeoutMs = INSTANT_NO_SPEECH_TIMEOUT_MS,
                    )
                } else null
                _micLevel.value = 0f
                var levelChunks = 0
                repo.transcribe(
                    audio = audioCaptureManager.startCapture(
                        vadEnabled = vadSensitivity.value || instant,
                        rawSource = rawMicCapture.value,
                        onLevel = { level ->
                            if (levelChunks++ % LEVEL_REPORT_INTERVAL_CHUNKS == 0) {
                                _micLevel.value =
                                    ((RecognitionSession.rmsToDb(level) + 2f) / 12f).coerceIn(0f, 1f)
                            }
                        },
                        onSpeechProbability = endpointer?.let { e ->
                            { probability ->
                                val event = e.onFrame(probability)
                                if (event == SpeechEndpointer.Event.EndOfSpeech ||
                                    event == SpeechEndpointer.Event.NoSpeech
                                ) viewModelScope.launch {
                                    if (captureJob != myJob || !instantSession) return@launch
                                    Log.d(TAG, "Instant mode - $event, stopping")
                                    stopRecording()
                                }
                            }
                        },
                    ),
                    postprocessingEnabled = postprocessingEnabled.value,
                    formatNumbersAsDigits = formatNumbersAsDigits.value,
                ).collect { result ->
                    // Stale-session guard: if this job has been superseded (e.g. the field
                    // was cleared and a new session started), discard this result entirely
                    // so no old-session text is injected into the fresh TextInjector.
                    if (captureJob != null && captureJob != myJob) return@collect

                    when (result) {
                        is TranscriptResult.Partial -> {
                            _uiState.value = KeyboardUiState.Processing(result.text)
                            textInjector?.setPartial(result.text)
                            // Update alignment recovery counter from the injector.
                            val injector = textInjector
                            if (injector != null) {
                                val d = _diagnostics.value
                                if (injector.alignmentRecoveryCount > d.alignmentRecoveries) {
                                    _diagnostics.value = d.copy(alignmentRecoveries = injector.alignmentRecoveryCount)
                                }
                            }
                        }

                        is TranscriptResult.Final -> {
                            Log.d(
                                TAG, "Final transcript: \"${result.text}\"" +
                                        if (result.isUtteranceBoundary) " [utterance boundary]" else ""
                            )
                            textInjector?.commitFinal(result.text)
                            if (!result.isUtteranceBoundary) {
                                _isContinuousMode.value = false
                                // An instant-mode session stays in its stopped state until
                                // the keyboard switches back (finishInstantSession).
                                _uiState.value = if (instant && instantSwitchBack) KeyboardUiState.Transcribing
                                else KeyboardUiState.Idle
                                captureJob = null
                            }
                        }

                        is TranscriptResult.Failure -> {
                            Log.e(TAG, "Transcription failure", result.cause)
                            _isContinuousMode.value = false
                            _uiState.value = KeyboardUiState.Error(
                                reason = KeyboardUiState.ErrorReason.TranscriptionFailed,
                                detail = result.cause.message,
                            )
                        }

                        // The audio window was trimmed: shrink committedWords so the
                        // next partial can re-anchor without losing middle sentences.
                        is TranscriptResult.WindowTrimmed -> {
                            Log.d(
                                TAG, "WindowTrimmed - resetting TextInjector alignment" +
                                        if (result.stableWords.isNotEmpty()) " (stableWords=${result.stableWords.size}w)" else ""
                            )
                            textInjector?.resetAfterTrim(result.stableWords)
                            _diagnostics.value = _diagnostics.value.copy(
                                windowTrims = _diagnostics.value.windowTrims + 1
                            )
                        }

                        // The model saw audio but couldn't resolve a word (weak
                        // high-frequency fricatives). Surface a brief "didn't catch that"
                        // cue, then return to the listening/idle state so the user can
                        // retry without tapping to clear it.
                        is TranscriptResult.NoSpeech -> {
                            Log.d(TAG, "NoSpeech — surfacing 'didn't catch that'")
                            _uiState.value = KeyboardUiState.NoSpeech
                            viewModelScope.launch {
                                delay(2000)
                                if (_uiState.value is KeyboardUiState.NoSpeech) {
                                    _uiState.value =
                                        if (_isContinuousMode.value) KeyboardUiState.Listening
                                        else KeyboardUiState.Idle
                                }
                            }
                        }
                    }
                }

                // The flow completed normally. If still in Transcribing (or Listening), it
                // means VAD filtered out all audio (nothing was said) so InferenceRepository
                // emitted no Final result. Reset to Idle so the button becomes usable again.
                if (instant && (captureJob == null || captureJob == myJob)) {
                    instantSession = false
                    if (instantSwitchBack) {
                        finishInstantSession()
                        return@launch
                    }
                }
                if (_uiState.value == KeyboardUiState.Transcribing ||
                    _uiState.value == KeyboardUiState.Listening
                ) {
                    Log.d(TAG, "Transcription flow ended with no Final result - resetting to Idle")
                    _isContinuousMode.value = false
                    _uiState.value = KeyboardUiState.Idle
                    captureJob = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission denied", e)
                _isContinuousMode.value = false
                if (instant) cancelInstantSession()
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.MicPermissionDenied,
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord failed to initialise", e)
                _isContinuousMode.value = false
                if (instant) cancelInstantSession()
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.MicInitFailed,
                    detail = e.message,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected audio capture error", e)
                _isContinuousMode.value = false
                if (instant) cancelInstantSession()
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.AudioCaptureFailed,
                    detail = e.message,
                )
            }
        }
    }

    /**
     * The user stops recording with the talk button. During an instant-mode session this
     * also means "stay on the keyboard": no switch back afterwards.
     */
    fun onRecordStop() {
        instantSwitchBack = false
        stopRecording()
    }

    private fun stopRecording() {
        // Always reset continuous mode when recording stops from any code path.
        _isContinuousMode.value = false
        instantSession = false

        // Stop the microphone - this terminates the upstream audio flow, which causes
        // InferenceRepository to finish collecting audio and run its definitive final
        // inference pass.  We intentionally do NOT cancel captureJob here: slow engines
        // like Whisper can take 10-20 s to produce the Final result after audio ends,
        // and cancelling the job before it is collected means commitFinal() is never
        // called and no text is injected.  The job self-terminates once it collects
        // TranscriptResult.Final (or Failure).
        audioCaptureManager.stopCapture()

        // Switch to Transcribing so the UI clearly signals "mic off, engine still working".
        // The collect loop in onRecordStart() will transition back to Idle (and commit
        // the text) once Final arrives.
        if (_uiState.value is KeyboardUiState.Listening ||
            _uiState.value is KeyboardUiState.Processing
        ) {
            _uiState.value = KeyboardUiState.Transcribing
        }
    }

    /**
     * Called when the focused text field is cleared externally - typically because the user
     * pressed "Send" in a messaging app and the app cleared the [EditText] content.
     *
     * Resets [TextInjector] session state so the next recording does not try to align
     * against stale committed-word tracking that no longer matches the now-empty field.
     *
     * If a recording is actively in progress ([KeyboardUiState.Listening] /
     * [KeyboardUiState.Processing]), the capture job is cancelled and immediately restarted
     * with a fresh audio window - so the user continues dictating from a clean slate without
     * needing to toggle the talk button.  [isContinuousMode] is intentionally preserved so
     * the button stays active in continuous mode across the field-clear event.
     *
     * If the engine is still running its final inference pass ([KeyboardUiState.Transcribing]),
     * the job is cancelled (the result is no longer useful for the emptied field) and the
     * UI resets to [KeyboardUiState.Idle].
     */
    fun onFieldCleared() {
        Log.d(TAG, "onFieldCleared - resetting TextInjector and restarting audio if recording")

        // Always clear TextInjector state - stale committedWords would cause alignment
        // failures and potential duplication on the very next partial injection.
        textInjector?.clear()

        val isActivelyRecording = _uiState.value is KeyboardUiState.Listening ||
                _uiState.value is KeyboardUiState.Processing
        val hasInferenceRunning = _uiState.value is KeyboardUiState.Transcribing

        when {
            isActivelyRecording -> {
                // Explicitly stop the current audio capture so the old AudioRecord
                // stops feeding chunks into the channel buffer immediately - without
                // this, the old capture can keep producing audio for up to one read
                // cycle (~30 ms) after captureJob.cancel(), and those samples would
                // end up in the new session's rolling window via the Channel.UNLIMITED
                // buffer if the old flow hadn't been cancelled yet.
                audioCaptureManager.stopCapture()
                // onRecordStart() cancels the existing captureJob internally, which
                // discards the rolling audio window, and starts a fresh one.
                // _isContinuousMode is NOT touched so the button stays active.
                onRecordStart()
            }

            hasInferenceRunning -> {
                // Mic is already off but the final inference is still running.
                // The result is no longer needed (field was just cleared), so cancel it.
                captureJob?.cancel()
                captureJob = null
                cancelInstantSession()
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Idle
            }
            // Idle / Error / Loading - TextInjector clear above is sufficient.
        }
    }

    /**
     * Commits any in-progress composing (partial) text as final, then cancels the capture job
     * immediately. Must be called **before** [setTextInjector] is set to null so that the
     * [android.view.inputmethod.InputConnection] is still valid when we write the final text.
     */
    fun commitPartialAndStop() {
        val currentState = _uiState.value
        if (currentState is KeyboardUiState.Processing && currentState.partial.isNotEmpty()) {
            // Commit the last partial transcript so no text is lost on app-switch.
            textInjector?.commitFinal(currentState.partial)
        } else {
            // Transcribing (no partial yet) or any other state - remove any composing span
            // without committing. If the engine was still running its final pass it will be
            // cancelled; that partial audio is lost, which is acceptable on input-field change.
            textInjector?.clear()
        }
        // Cancel the capture coroutine immediately - don't wait for the audio loop to drain.
        captureJob?.cancel()
        captureJob = null
        cancelInstantSession()
        _isContinuousMode.value = false
        audioCaptureManager.stopCapture()
        _uiState.value = KeyboardUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()
        cancelInstantSession()
        _isContinuousMode.value = false
        textInjector = null
        inferenceRepository = null
    }

    class Factory(
        private val audioCaptureManager: AudioCaptureManager,
        private val appPreferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KeyboardViewModel(audioCaptureManager, appPreferences) as T
    }
}
