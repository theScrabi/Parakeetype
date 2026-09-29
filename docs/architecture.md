# Parakeetype - Architecture Reference

> **Scope**: Authoritative technical reference for the Parakeetype Android IME codebase.
> Intended for developers adding features, new engines, or debugging the inference pipeline.
> For user-facing information see README.md; for potential future improvements see analysis.md.

---

## Table of Contents

1. High-Level Overview
2. Module and Package Map
3. Layer-by-Layer Architecture
4. Data Flow End to End
5. Key Interfaces and Sealed Classes
6. Sliding-Window Inference Details
7. Post-Processing Pipeline
8. Text Injection and Alignment
9. Model Registry and Import
10. Engine Implementations
11. Service and Lifecycle Management
12. State Machines
13. Build Configuration and ABI Splits
14. Testing Strategy
15. Extension Guide: Adding a New Engine
16. Key Conventions and Invariants

---

## 1. High-Level Overview

Parakeetype is a privacy-first Android Input Method Editor (IME). All speech recognition runs
on-device via ONNX Runtime; no audio ever leaves the device, and the app has no network access at all (no INTERNET permission).

    Active App (Text Field)
         | InputConnection API
    ParakeetypeInputMethodService    <- Android IME entry-point (LifecycleOwner + Compose UI)
         | KeyboardViewModel      <- UI state + capture lifecycle
         | binds to
    InferenceService              <- Foreground LifecycleService
         | InferenceRepository    <- Sliding-window buffer (<=30 s)
         |   SpeechEngine         <- Interface; swap models here (currently ParakeetEngine)
         | Flow<AudioChunk>
    AudioCaptureManager           <- 16 kHz / 16-bit / mono PCM
    SileroVadFilter               <- Neural VAD (Silero v4, ONNX) - primary
    RMSVadFilter                  <- Energy-threshold VAD - fallback

**Core design principles:**

- SpeechEngine is the *only* seam for adding a new ASR model. Nothing in the IME or service layer changes.
- InferenceService keeps the engine alive across keyboard hide/show cycles. With the *Keep model loaded* setting (`keep_model_loaded`, on by default) it also starts itself as a `specialUse` foreground service, which survives the unbind and keeps the model warm across keyboard switches; when the user opts out it is bound-only (destroyed — model unloaded — when the user switches to another IME and the IME unbinds).
- Constructor injection only throughout; no field injection.
- No external SDKs that phone home (no analytics, no crash-reporting services). Crashes are logged locally only and shared by the user on request (`crash` package).

---

## 2. Module and Package Map

Single Gradle module (app). All Kotlin source lives under app/src/main/kotlin/ (package root org.schabi.parakeetype).

| Package | Key files | Responsibility |
|---|---|---|
| audio | AudioCaptureManager, MicCalibrationManager, SileroVadFilter, RMSVadFilter, VadFilter, SpeechEndpointer, AudioChunk, PermissionHelper | Mic capture, PCM chunking, Voice Activity Detection, end-of-speech detection, optional mic calibration |
| inference | SpeechEngine, ParakeetEngine, ChunkStreamingEngine, WhisperEngine, VoxtralEngine, SpeechEngineFactory, InferenceRepository, InferenceService, TranscriptResult, EngineState, PipelineDiagnostics, NumberNormaliser, GrammarCorrector | ASR pipeline, sliding window, post-processing, foreground service |
| ime | ParakeetypeInputMethodService, TextInjector, TranscriptAligner, EnterAction | Keyboard service, composing text management, alignment |
| recognition | ParakeetypeRecognitionService, VoiceInputActivity, RecognitionSession, InferenceConnection, TranscriptAccumulator | Speech recognizer for other apps: android.speech RecognitionService and RecognizerIntent.ACTION_RECOGNIZE_SPEECH |
| settings/model | ModelId, ModelRegistry, ModelImporter, ModelStorageManager, ModelState, ModelViewModel | Model enumeration, single-archive import, SHA-256 verification, on-disk paths |
| settings/preferences | AppPreferences, PreferencesViewModel | DataStore-backed user preferences |
| settings/screens | HomeScreen, ModelScreen, PreferencesScreen, MicCalibrationScreen | Settings Compose UI |
| ui/keyboard | KeyboardViewModel, KeyboardUiState, KeyboardScreen, ImeComposeView | IME Compose hosting, UI state |
| ui/keyboard/components | TalkButton, StatusIndicator, KeyboardActionButton, LanguageSelector | Keyboard UI sub-components |
| ui/theme | ParakeetypeKeyboardTheme | Compose theming |
| crash | ParakeetypeApplication, CrashReporter, CrashReportDialog, CrashReportFormatter, LogcatReader | Local crash log: JVM uncaught-exception handler + ApplicationExitInfo (native crashes, ANRs), notification, Share dialog |

---

## 3. Layer-by-Layer Architecture

### 3.1 Audio Capture and VAD

**AudioCaptureManager**

- Opens AudioRecord with source DEFAULT (the vendor-recommended source), 16 kHz mono PCM-16. No application-side gain: DEFAULT arrives at a usable level.
- Emits 480-sample (30 ms) chunks as a cold Flow<AudioChunk>.
- When the user has run mic calibration, applies the selected microphone via AudioRecord.setPreferredDevice (no-op when unset or the device is gone).
- On stop: drains the hardware buffer then waits HANGOVER_DRAIN_SAFETY_FRAMES = 20 frames (600 ms) to flush VAD hangover before the flow completes.

**VadFilter interface**

    interface VadFilter {
        fun process(chunk: AudioChunk, rms: Float): AudioChunk?  // null = silence, drop
        fun flush(): List<AudioChunk>                             // drain tail on stop
        fun isSpeechActive(): Boolean
        fun close()
    }

**SileroVadFilter** (primary)

- Silero VAD v4 ONNX. RNN state tensors h/c shape [2,1,64] carried across chunks.
- Speech probability threshold: 0.3.
- Three layers: onset gate (2 frames/60 ms), pre-roll buffer (15 frames/450 ms lead-in), hangover.
- Falls back to RMSVadFilter if the ONNX model fails to load.

**RMSVadFilter** (fallback)

- Energy threshold with the same 3-layer onset/pre-roll/hangover structure.

**MicCalibrationManager / MicScorer** (optional, user-triggered)

- MicCalibrationScreen (settings) lists the input microphones, records a short reference clip on each, and ranks them with MicScorer (HF energy fraction + percentile SNR).
- The winning mic id is persisted (AppPreferences.preferredMicId) and applied to production capture via AudioRecord.setPreferredDevice; falls back to the system default when the device is no longer present.
- Strict opt-in: nothing runs unless the user opens the calibration screen.

---

### 3.2 Inference Pipeline

**SpeechEngine interface** - the single extension point for new models:

    interface SpeechEngine {
        val isLoaded: Boolean
        fun load(modelDir: File)
        fun transcribe(chunk: AudioChunk): TranscriptResult
        fun setLanguage(tag: String)
        fun setLanguageFilter(tags: List<String>)
        fun close()
    }

**SpeechEngineFactory** routes ModelId to a concrete engine implementation.

**InferenceRepository**

- Receives Flow<AudioChunk> from AudioCaptureManager.
- Maintains a rolling audio window (see Section 6).
- Emits Flow<TranscriptResult> consumed by KeyboardViewModel -> TextInjector.
- Applies an 8-step post-processing pipeline to every raw transcript (see Section 7).

**InferenceService**

- LifecycleService, bound by the IME; optionally a started `specialUse` foreground service (keep-loaded mode).
- Notification channel: parakeetype_inference, notification ID 1001.
- Owns the SpeechEngine instance; reloads on selectedModelId preference change.
- Uses a Mutex to protect engine swaps.
- Exposes StateFlow<EngineState> to bound clients.
- FileObserver on models/ directory detects newly imported models.
- Default: destroyed on the IME's final unbind (keyboard switch). Keep-loaded mode: started + foreground, survives the unbind; only critical memory pressure unloads the engine.

---

### 3.3 IME and Text Injection

**ParakeetypeInputMethodService**

- Extends InputMethodService; implements LifecycleOwner and SavedStateRegistryOwner to host Compose.
- Hosts the keyboard UI via ImeComposeView.
- Binds to InferenceService in onCreate and stays bound for the IME's lifetime (unbinds in onDestroy, i.e. when another keyboard is selected).
- Forwards InputConnection changes to TextInjector and KeyboardViewModel.

**TextInjector**

- Manages all writes to the active InputConnection.
- Keeps the last 6 words (MUTABLE_WORD_COUNT = 6) as a composing (underlined) span via setComposingText; all earlier words are permanently committed.
- On WindowTrimmed: commits composing text minus the last TRIM_TAIL_WORD_COUNT uncertain tail words, clears lastPartial, re-anchors committedWords from actual field content.
- Two-layer alignment recovery (field-scan -> composing-commit fallback) handles complete divergence.
- Delegates new-content discovery to TranscriptAligner.

**TranscriptAligner** (pure / stateless)

- normalizeWord(), splitToWords(), wordsMatch().
- findNewContent(committed, partial) performs a 3-layer overlap search (see Section 8).
- Tolerates Parakeet attention drift and post-trim leading garbage tokens.

---

### 3.4 Settings and Model Lifecycle

**ModelId enum:**

| Value | storageDirName | Status |
|---|---|---|
| PARAKEET_V3 | parakeet-v3 | Active |
| VOXTRAL_MINI | voxtral-mini-4b | Disabled (resource limits) |
| WHISPER_SMALL | whisper-small-int8 | Disabled (resource limits) |

> Warning: storageDirName must never change after release - it is the on-disk key for existing installations.

**ModelRegistry** maps each ModelId to a ModelInfo: display name, archiveUrl (the single-file ZIP the user downloads in the browser), ModelFile list with SHA-256 hashes, size estimate. ModelRegistry.all contains only models confirmed to run within acceptable resource limits on real devices.

**ModelStorageManager** stores all files in <filesDir>/models/<storageDirName>/. Checks the requiredFiles list (all ModelFile names) for readiness.

**ModelImporter** installs a model from the ZIP archive the user picked via the system file picker (SAF OpenDocument, no storage permission), verifies SHA-256 of every file and emits ModelState.Importing(progress). Parakeetype itself never downloads anything.

**AppPreferences** (DataStore, store name parakeetype_prefs): trigger_mode (String, default HOLD), delete_button_mode (String, DELETE_ALL | DELETE_LAST_SENTENCE, default DELETE_ALL), vad_sensitivity (Float, default 0.0), selected_model_id (String), whisper_language (String, default "auto"), postprocessing_enabled (Boolean, default true), show_pipeline_diagnostics (Boolean, default false), forced_language (String?, default null), format_numbers_as_digits (Boolean, default true), keep_model_loaded (Boolean, default true — runs InferenceService as a started foreground service so the model survives keyboard switches), keyboard_position_portrait (String, CENTER | LEFT | RIGHT, default CENTER), keyboard_position_landscape (String, LEFT | RIGHT, default RIGHT), raw_mic_capture (Boolean, default false — true captures from AudioSource.UNPROCESSED to bypass AEC, needed for the speakerphone use case), preferredMicId (Int, default 0).

---

### 3.5 UI Layer

**KeyboardViewModel** bridges IME lifecycle, AudioCaptureManager, and InferenceRepository results into KeyboardUiState. Owns captureJob - the coroutine driving audio capture for a recording session.

**KeyboardUiState** (sealed class):

| State | Meaning |
|---|---|
| Idle | Keyboard visible, not recording |
| Listening | Mic open, VAD active |
| Processing(partial) | Partial transcript available |
| Transcribing | Final inference running |
| EngineLoading(reason) | Engine is being loaded |
| Error(reason, detail) | Unrecoverable error |

---

## 4. Data Flow End to End

    Microphone (PCM-16, 16 kHz, 480-sample chunks)
        |
    AudioCaptureManager  [peak-envelope normalisation]
        |  AudioChunk (float32)
    SileroVadFilter / RMSVadFilter  [silence suppression]
        |  AudioChunk (speech only)
    InferenceRepository  [rolling window, fires every 1 s once >=2 s buffered]
        |
    SpeechEngine.transcribe()  [ParakeetEngine: nemo128 -> encoder -> decoder/joint]
        |  raw transcript string
    cleanTranscript()  [8-step post-processing]
        |  TranscriptResult (Partial | Final | WindowTrimmed | Failure)
    KeyboardViewModel
        |-- KeyboardUiState --> KeyboardScreen (Compose)
        +-- TextInjector
                |  TranscriptAligner.findNewContent()
            InputConnection (setComposingText / commitText)
                |
            Active App Text Field

---

## 5. Key Interfaces and Sealed Classes

### TranscriptResult (sealed class)

| Variant | Fields | TextInjector action |
|---|---|---|
| Partial(text) | text: String | setComposingText - keep last 6 words mutable |
| Final(text, isUtteranceBoundary) | isUtteranceBoundary: Boolean | commitText; when isUtteranceBoundary=true do NOT stop capture |
| WindowTrimmed(stableWords) | stableWords: List<String> | Call resetAfterTrim(stableWords) immediately - skipping causes silent word drops |
| Failure(exception) | exception: Throwable | Log; surface to user via KeyboardUiState.Error |

### EngineState (sealed class)

Unloaded | Loading | Ready | Error(message: String)

### ModelState (sealed class)

NotDownloaded | Importing(progress: Float) | Ready

### PipelineDiagnostics

Counters: windowTrims, alignmentRecoveries, blanksDiscarded.
summary() returns e.g. "2T . 1R . 3B" or "OK" when all zero.

---

## 6. Sliding-Window Inference Details

Key constants in InferenceRepository:

| Constant | Value | Purpose |
|---|---|---|
| MIN_SAMPLES | 2 s | Minimum window before first partial fires |
| STRIDE_SAMPLES | 1 s | New inference every second of new audio |
| MAX_WINDOW_SAMPLES | 30 s | Hard ceiling |
| STABLE_STRIDES | 3 | Consecutive strides that must agree before audio trim |
| TRIGGER_WINDOW_SAMPLES | 6 s | Trim logic activates above this size |
| MIN_CONTEXT_SAMPLES | 4 s | Tail context kept after a stable-chunk trim |
| FORCE_TRIM_WINDOW_SAMPLES | 12 s | Aggressive trim when strides diverge |
| SILENCE_TRIM_STRIDES | 2 | Consecutive blank strides before proactive trim |
| SHORT_UTTERANCE_THRESHOLD_SAMPLES | 2.5 s | Below this the final flush uses the short-utterance path |
| MIN_PADDING_SAMPLES | 1.25 s | Tail zero-pad floor for short-utterance decodes |
| SHORT_UTT_LEAD_SILENCE_SAMPLES | 600 ms | Lead silence prepended to short-utterance decodes |
| CONFIDENCE_THRESHOLD | 0.55 | Legacy (non-Parakeet) final-confidence gate; the Parakeet short-utterance path uses a plausibility floor (≥2 word chars) instead |

**Trim triggers** (all emit TranscriptResult.WindowTrimmed):

1. Stable-prefix trim: last 3 partials share a common leading-word prefix -> trim audio corresponding to stable words, retain MIN_CONTEXT_SAMPLES tail.
2. Silence trim: 2 consecutive blank strides -> proactive trim regardless of prefix agreement.
3. Force trim: window > FORCE_TRIM_WINDOW_SAMPLES with no stable prefix -> unconditional trim.

**End of speech** (ending a recognizer session) is not taken from these boundaries but from SpeechEndpointer, fed with the VAD's raw per-frame speech probability (VadFilter.lastSpeechProbability via AudioCaptureManager.startCapture(onSpeechProbability)). The VAD's filtered output opens on one frame ≥ 0.3 and replays its 600 ms lead-in, so a breath or a tap on the phone looked like resumed speech and postponed an endpoint that waited past the boundary forever. SpeechEndpointer counts only 4 consecutive frames (120 ms) at ≥ 0.5 as speech (the shortest words reach 7+, breaths and taps 0–2) and measures the silence in frames from the last such run.

**Mid-session final**: when isSilenceBoundary is set on an AudioChunk, the repository emits Final(isUtteranceBoundary = true) without stopping capture (useful for long continuous dictation).

**Recording-stop final**: one final inference runs over the remaining window when the audio flow completes. **Short utterances** (< SHORT_UTTERANCE_THRESHOLD_SAMPLES) get a decode-context retry: the TDT decoder is extremely context-sensitive on short clips (real-model probes: 600 ms of leading silence flips blank decodes into correct words; trailing digital silence can flip a correct decode into blank). The primary attempt re-decodes the buffer from frame 0 with SHORT_UTT_LEAD_SILENCE_SAMPLES of silence prepended (and a tail zero-pad to MIN_PADDING_SAMPLES when still short). A decode is accepted when it contains a plausible word (≥2 letters/digits); if the lead attempt yields no word, one retry runs the original context (no lead), and the first attempt with a word wins. There is **no confidence gate** on the Parakeet short-utterance path: a correct-but-low-confidence decode is always emitted (the geometric-mean confidence of a single short word is fragile — one low-probability token, often the trailing period, can drag it below any fixed threshold — and other Parakeet tools emit whatever the model decodes rather than gating on confidence). A no-word decode in both contexts stays silent (no output, no warning) rather than emitting a hallucination.

---

## 7. Post-Processing Pipeline

Applied to every raw transcript string before wrapping in a TranscriptResult:

| Step | Operation |
|---|---|
| 1 | Filler-word removal (um, uh, hmm, etc.) |
| 2 | Stutter collapse (>=3x consecutive word repeats -> single instance) |
| 3 | Phrase-loop deduplication (repeated phrase sequences) |
| 3.5 | Spurious-period removal (`filterSpuriousPeriods`): strips mid-utterance periods produced by Parakeet TDT on prosodic pauses. A period is removed when it is **not** the final token AND either (a) the preceding word is a conjunction/preposition/article/determiner in `NON_SENTENCE_CLOSING_WORDS`, or (b) the sentence segment before the period is fewer than 5 words. Must run before step 8 (capitalisation) to prevent false sentence-start capitalisation. |
| 4 | Leading-dot strip (LEADING_DOTS_RE) |
| 5 | Leading-punctuation strip (LEADING_PUNCT_RE: ^[,;]+) |
| 6 | Multi-dot normalisation (two or more consecutive dots -> single dot) |
| 7 | Missing sentence-space repair (punctuation followed by capital letter without space) |
| 8 | Sentence-boundary capitalisation |

---

## 8. Text Injection and Alignment

### Composing span management (TextInjector)

    [... permanently committed words ...] [last 6 words -- composing span (underlined)]
                                           ^
                                           MUTABLE_WORD_COUNT boundary

- Every Partial: TranscriptAligner.findNewContent identifies genuinely new tokens; setComposingText updates the composing span.
- Every Final: finishComposingText + commitText flushes the composing span.
- Every WindowTrimmed: resetAfterTrim(stableWords) must be called immediately.
  - Commits all composing text except the last TRIM_TAIL_WORD_COUNT uncertain tail words.
  - Clears lastPartial.
  - Re-reads committedWords from the actual field content to re-anchor alignment.

### Three-layer overlap search (TranscriptAligner.findNewContent)

1. Full prefix match: check if partial starts with all committed words -> fast path, emit only the suffix.
2. Suffix-prefix overlap >=2 words: find the longest suffix of committed words that is a prefix of the new partial.
3. Interior scan >=2 words: slide a window across the partial looking for a matching subsequence.

If all three layers fail, the entire partial is returned as new content (alignment recovery, counted in PipelineDiagnostics.alignmentRecoveries).

---

## 9. Model Registry and Import

### Storage layout

    <filesDir>/models/
      parakeet-v3/
        nemo128.onnx
        encoder-model.int8.onnx
        decoder_joint-model.int8.onnx
        config.json
        vocab.txt
      voxtral-mini-4b/    (placeholder - disabled)
      whisper-small-int8/ (placeholder - disabled)


### Install flow (no network access)

1. The model screen's *Download in browser* button opens ModelInfo.archiveUrl (a pinned release asset built by devtools/package-model.sh from the Hugging Face files; MODEL_ARCHIVE_RELEASE is still a TODO placeholder) with ACTION_VIEW; the browser downloads the single ZIP.
2. *Import model file* opens the SAF picker; the user selects the archive.
3. ModelImporter streams the ZIP once: entries whose file name (directories ignored) matches a ModelFile are written to models/.import-<storageDirName>/ while their SHA-256 is computed.
4. Any checksum mismatch, missing file, or non-ZIP input aborts and deletes the staging directory; the previous state is untouched.
5. On success the staging directory is renamed over models/<storageDirName>/ in one step; ModelStorageManager.isModelReady(modelId) then checks all files exist.

---

## 10. Engine Implementations

### ParakeetEngine (active)

NVIDIA Parakeet-TDT 0.6B v3, INT8 quantized, ~700 MB on disk.

3-ONNX pipeline:

| Session | File | Input -> Output |
|---|---|---|
| Preprocessor | nemo128.onnx | Raw PCM float32 -> 128-dim log-mel spectrogram |
| Encoder | encoder-model.int8.onnx | Spectrogram -> [B, 1024, T_enc] encoder features |
| Decoder/Joint | decoder_joint-model.int8.onnx | Encoder features + LSTM state -> [B, T_enc, T_tgt, 8198] logits |

- Tokenizer: SentencePiece-like vocabulary from vocab.json (1024 tokens + blank).
- Decoding: greedy TDT (Token-and-Duration Transducer). LSTM state is carried across strides.
- Logit tensor: first 1025 entries = token probabilities (vocab 1024 + blank); remainder = duration predictions. Only the greedy argmax is currently consumed.

### WhisperEngine (disabled - resource limits)

Targets whisper-large-v3-turbo INT8. Files: encoder_model_int8.onnx, decoder_model_merged_int8.onnx, tokenizer.json. 128 mel bins, 30 s window, 3000 frames. Merged decoder with use_cache_branch flag (autoregressive decoding with KV-cache).

### VoxtralEngine (disabled - resource limits)

Voxtral-Mini-4B-Realtime ONNX (~4 GB RAM requirement). Same Whisper-compatible log-mel preprocessing. Mistral decoder architecture with KV-cache.

---

## 11. Service and Lifecycle Management

### Binding lifecycle

    ParakeetypeInputMethodService.onCreate()
        -> bindService(InferenceService, BIND_AUTO_CREATE)

    ParakeetypeInputMethodService.onDestroy()          (user switched to another keyboard)
        -> unbindService(InferenceService)
               keep-loaded mode (default): service is started + foreground -> stays alive, engine warm
               opted out:                  last client gone -> service destroyed -> engine closed

    AppPreferences.keepModelLoaded (DataStore Flow, observed in InferenceService.onCreate)
        true  -> startForegroundService(self) -> onStartCommand -> startForeground(SPECIAL_USE), START_STICKY
        false -> stopForeground(REMOVE) + stopSelf()  (service lives on while still bound)

Memory pressure: by default RUNNING_LOW / RUNNING_CRITICAL / MODERATE / COMPLETE close the engine; in keep-loaded mode only RUNNING_CRITICAL / onLowMemory do. The IME reloads it on the next onWindowShown via InferenceBinder.reloadIfNeeded().

### Engine reload on model change

    AppPreferences.selectedModelId (DataStore Flow)
        -> InferenceService observer
               -> acquire Mutex
               -> close old SpeechEngine
               -> SpeechEngineFactory.create(newModelId)
               -> engine.load(modelDir)
               -> emit EngineState.Ready
               -> release Mutex

### FileObserver integration

InferenceService watches <filesDir>/models/ for CLOSE_WRITE / MOVED_TO events. The observer only watches the models/ root (not subdirectories); ModelImporter's final directory rename (MOVED_TO) is what triggers the reload check, so a new model loads without an app restart.

### Speech recognizer for other apps (recognition package)

Two entry points bind the same InferenceService as the IME (via InferenceConnection), so all of them share one loaded model:

    ParakeetypeRecognitionService (android.speech.RecognitionService, exported, meta-data @xml/recognition_service)
        client SpeechRecognizer created   -> onCreate -> bind InferenceService (model starts loading)
        startListening(intent)            -> RecognitionSession on an attribution context built from
                                             Callback.getCallingAttributionSource (mic use is attributed
                                             to the calling app; the platform already checked its RECORD_AUDIO)
        stopListening / cancel            -> RecognitionSession.stop() / cancel()
        checkRecognitionSupport (API 33+) -> the 25 Parakeet v3 languages, installed or supported
        model not loaded yet              -> toast "Loading transcription engine…" (the service has no UI)
        no model installed                -> toast "Open Parakeetype to install one" + ERROR_LANGUAGE_UNAVAILABLE
                                             (a background service may not start a dialog activity)
        (Android suppresses both toasts while Parakeetype's notifications are disabled)

    VoiceInputActivity (RecognizerIntent.ACTION_RECOGNIZE_SPEECH, translucent Compose sheet)
        -> requests RECORD_AUDIO if needed -> RecognitionSession -> EXTRA_RESULTS / EXTRA_CONFIDENCE_SCORES
           (or EXTRA_RESULTS_PENDINGINTENT); leaving the activity cancels; while the model loads the
           sheet's title shows the keyboard's "Loading transcription engine…" text

RecognitionSession starts capture at once and buffers it in an unlimited channel while InferenceConnection.awaitRepository() waits for EngineState.Ready (reloading a memory-pressure unload; no model installed -> ERROR_LANGUAGE_UNAVAILABLE); when an installed model has to load first, Listener.onModelLoading(true / false) brackets the wait. VAD is always on and feeds a SpeechEndpointer, which ends a normal session (end of speech = 1 s of silence after sustained speech; EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS / _MINIMUM_LENGTH_MILLIS change it), 8 s without speech ends it with ERROR_SPEECH_TIMEOUT, and EXTRA_SEGMENTED_SESSION (API 33+) reports each utterance via segmentResults until stopped. TranscriptAccumulator rebuilds the full text from the TranscriptResult stream (finals + current partial; WindowTrimmed on the legacy path is merged with TranscriptAligner.findNewContent). EXTRA_AUDIO_SOURCE (client-supplied audio) is rejected with ERROR_CLIENT; EXTRA_LANGUAGE is ignored (Parakeet detects the language itself).

AudioCaptureManager builds its AudioRecord with AudioRecord.Builder.setContext(context) — that is what carries the attribution — and reports the raw level of every chunk through the optional onLevel callback (used for rmsChanged).

---

## 12. State Machines

### Engine state

    Unloaded --load()--> Loading --success--> Ready
                             |                  |
                             +--failure--> Error(message)
                                                |
                                           retry load()--> Loading

### Model install state

    NotDownloaded --import--> Importing(0..1) --verified + renamed--> Ready
                                   |
                  cancel / SHA-256 fail / missing file / not a ZIP
                                   v
                             NotDownloaded   (message shown in a snackbar)

### Keyboard UI state

    Idle --tap mic--> Listening --audio buffered--> Processing(partial)
     ^                    |                               |
     |              release mic                    recording stop
     |                    v                               v
     +--------------- Transcribing <---------- (final inference)
                           |
                      inject text
                           v
                         Idle

---

## 13. Build Configuration and ABI Splits

- compileSdk 37, minSdk 31 (Android 12+), targetSdk 37
- versionCode 12, versionName 0.4.0
- Kotlin 2.4.20, AGP 9.4.1, JVM target 11
- buildFeatures: compose = true, buildConfig = true
- Release: isMinifyEnabled = true, isShrinkResources = true, ProGuard enabled

**ABI splits:**

| ABI | Code offset | versionCode formula |
|---|---|---|
| armeabi-v7a | x1 | defaultVersionCode * 10 + 1 |
| arm64-v8a | x2 | defaultVersionCode * 10 + 2 |
| universal | x0 | defaultVersionCode * 10 + 0 |

dependenciesInfo { includeInApk = false; includeInBundle = false } - required for F-Droid reproducibility.

Repositories: Google, MavenCentral, Gradle Plugin Portal only.

---

## 14. Testing Strategy

All unit tests in app/src/test/kotlin/org/schabi/parakeetype/.
testOptions { unitTests.isReturnDefaultValues = true }.

| Test file | What it covers |
|---|---|
| audio/AudioChunkTest | AudioChunk data class, normalisation |
| audio/MicScorerTest | Mic quality scoring (HF fraction, percentile SNR) |
| audio/RMSVadFilterTest | Energy VAD onset/hangover logic |
| ime/TranscriptAlignerTest | All 3 layers of findNewContent |
| ime/TextInjectorTest | Composing span management, trim resets |
| inference/InferenceRepositoryTest | Sliding-window buffering strategy (stride/trim constants) |
| inference/InferenceRepositoryPipelineTest | Full pipeline integration |
| inference/HumanSpeechPipelineTest | Human speech patterns: pauses, restarts, trailing-off, force-trim |
| inference/ShortUtterancePipelineTest | Short-utterance decode-context retry: no-lead-in word, VAD path, silence/noise no-Final (real model) |
| inference/ParakeetEngineRealAudioTest | One-shot engine path against WAV fixtures (real model) |
| inference/RealAudioPipelineTest | Full-stack streaming dictation, pipe-* no-loss/no-duplication battery (real model) |
| inference/StreamingParityTest | Streaming vs one-shot output parity across fixtures (real model) |
| inference/CleanTranscriptTest | All 8 post-processing steps |
| inference/CollapsePhrasesTest | Phrase-loop deduplication |
| inference/CollapseStuttersTest | Stutter collapse |
| inference/ScriptHallucinationTest | Script-consistency hallucination filter, 20% threshold boundary |
| inference/ParakeetAllLanguagesTest | Post-processing does not block or corrupt any Parakeet-supported language's script |
| inference/ParakeetLanguageTest | Language forcing threads into cleanTranscript (EN vs DE filler/disfluency handling) |
| inference/NumberNormaliserTest | Number-word to digit normalisation |
| inference/GrammarCorrectorTest | GrammarCorrector implementations and repository integration |
| inference/TranscriptResultTest | TranscriptResult sealed class |
| inference/WavReaderTest | WAV parsing, resampling, channel downmix |
| e2e/GoldenPathTest | End-to-end repository + TextInjector golden paths |

Test helpers: RealAudioTestUtils (model-dir resolution, WER), WavReader, FakeSpeechEngine, FakeInputConnection.

**CI behaviour:** the real-model tests resolve the model directory (-Dtest.model.dir > $PARAKEETYPE_TEST_MODEL_DIR > ~/.cache/parakeetype-test-model/parakeet-tdt-0.6b-v3/) and skip themselves (JUnit Assume) when it is absent — so the CI pipeline always runs the model-free suite, while a local machine with the model present runs everything.

Instrumented tests (device/emulator required) in app/src/androidTest/, run with ./gradlew connectedAndroidTest.

---

## 15. Extension Guide: Adding a New Engine

1. **ModelId**: add a new enum value with a stable storageDirName. Never change existing values.
2. **ModelRegistry**: add a ModelInfo (display name, archiveUrl of the single-file ZIP, ModelFile list with SHA-256 hashes, size estimate) and publish the archive. Add to ModelRegistry.all only when confirmed to run within acceptable resource limits on real devices.
3. **New engine class** under inference/: implement SpeechEngine.
4. **SpeechEngineFactory**: add a branch for the new ModelId.

No changes required in InferenceRepository, InferenceService, TextInjector, or any IME class.

---

## 16. Key Conventions and Invariants

| Rule | Details |
|---|---|
| No field injection | Constructor injection only throughout |
| val over var | Avoid nullable types unless genuinely optional |
| No external telemetry | No analytics, crash-reporting SDKs, or SDKs that phone home; the local crash log is only shared by the user |
| Model storage | <filesDir>/models/<storageDirName>/ - no external storage permission |
| SHA-256 verification | Required for all model files on import; add hashes to ModelFile entries |
| No network access | No INTERNET / ACCESS_NETWORK_STATE permission (stripped from onnxruntime's manifest via tools:node="remove"); models come from a single user-downloaded ZIP |
| storageDirName immutability | Changing it breaks existing installations |
| WindowTrimmed handling | TextInjector.resetAfterTrim(stableWords) must be called immediately on every WindowTrimmed event - skipping causes silent word drops on every stride after a window trim |
| Final(isUtteranceBoundary=true) | Do NOT stop audio capture - used for mid-session sentence boundaries in long dictation |
| InferenceService stop | Default: destroyed on the IME's final unbind. Keep-loaded mode: started foreground service, survives the unbind until the setting is turned off |
| Kotlin code style | kotlin.code.style=official |
