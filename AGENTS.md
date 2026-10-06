# Parakeetype — Agent Guide

Android IME (keyboard) that does on-device speech-to-text via ONNX Runtime. No cloud, no Google Play Services, **no network access at all** (no `INTERNET` permission).

## Build & Test

```bash
./gradlew assembleDebug          # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified + resource-shrunk release APK
./gradlew test                   # JVM unit tests (app/src/test/)
./gradlew connectedAndroidTest   # instrumented tests (requires an emulator, see below)
```

**Never access a physical phone — emulator only.** Do not run `adb` (install, logcat, shell, dumpsys, …) or `connectedAndroidTest` against a physical device, even if one is connected. For on-device debugging, use an emulator and target it explicitly, e.g. `adb -s emulator-5554 …`. **Do not start the emulator yourself — ask the user to spawn it** (it cannot be launched from the agent sandbox) and wait until `adb devices` lists it.

Target SDK 37, min SDK 31 (Android 12 — required by Material You dynamic colour, which `ParakeetypeTheme` and `ParakeetypeKeyboardTheme` share so the app, the voice-input sheet and the keyboard all use the system palette), JDK 11, Kotlin official code style (`kotlin.code.style=official`).

## Package Structure

All source lives under `app/src/main/kotlin/` (package root `org.schabi.parakeetype`):

| Package | Key files | Responsibility |
|---|---|---|
|`inference`|`SpeechEngine`, `ParakeetEngine`, `ChunkStreamingEngine`, `WhisperEngine`, `VoxtralEngine`, `InferenceRepository`, `InferenceService`, `SpeechEngineFactory`, `TranscriptResult`, `EngineState`, `PipelineDiagnostics`, `NumberNormaliser`, `GrammarCorrector`|ASR pipeline, sliding window, post-processing, foreground service|
| `audio` | `AudioCaptureManager`, `MicCalibrationManager`, `SileroVadFilter`, `RMSVadFilter`, `VadFilter`, `SpeechEndpointer`, `AudioChunk`, `PermissionHelper` | Mic capture + VAD + end-of-speech detection + optional mic calibration |
| `ime` | `ParakeetypeInputMethodService`, `TextInjector`, `TranscriptAligner`, `EnterAction` | Keyboard / text insertion |
| `recognition` | `ParakeetypeRecognitionService`, `VoiceInputActivity`, `RecognitionSession`, `InferenceConnection`, `TranscriptAccumulator` | Speech recognizer for other apps (`RecognitionService` + `ACTION_RECOGNIZE_SPEECH`) |
| `settings/model` | `ModelRegistry`, `ModelId`, `ModelImporter`, `ModelStorageManager`, `ModelState`, `ModelViewModel` | Model lifecycle: single-archive import from local storage |
| `settings/preferences` | `AppPreferences`, `PreferencesViewModel` | DataStore-backed user preferences |
| `settings/screens` | `HomeScreen`, `ModelScreen`, `PreferencesScreen`, `MicCalibrationScreen` | Settings Compose UI |
| `ui/keyboard` | `KeyboardViewModel`, `KeyboardUiState`, `KeyboardScreen`, `ImeComposeView` | IME Compose hosting, UI state |
| `ui/keyboard/components` | `TalkButton`, `StatusIndicator`, `KeyboardActionButton`, `LanguageSelector` | Keyboard UI sub-components |
| `ui/theme` | `ParakeetypeKeyboardTheme` | Compose theming |
| `crash` | `ParakeetypeApplication`, `CrashReporter`, `CrashReportDialog`, `CrashReportFormatter`, `LogcatReader` | Local-only crash log: capture, notification, share dialog |

## Architecture — What Isn't Obvious from Single Files

**`InferenceService` is a `LifecycleService`** that the IME binds to. The engine stays alive across keyboard hide/show cycles (the IME stays bound while it is the current input method). With the **Keep model loaded** setting (`AppPreferences.keepModelLoaded`, **on by default — opt-out**) the service additionally *starts* itself as a `specialUse` foreground service (`InferenceService.startKeepLoaded`, persistent notification) so it survives the unbind and the model stays warm; in that mode only critical memory pressure (`TRIM_MEMORY_RUNNING_CRITICAL` / `onLowMemory`) unloads it. Turning the setting off calls `stopForeground` + `stopSelf`, returning to bound-only behaviour: when the user switches to another keyboard the system destroys the IME, the last binding goes away, and the service is destroyed with the model unloaded.

**Model install (no network):** `ModelImporter` streams the user-picked ZIP once, writes the model's files into `models/.import-<dir>/` while hashing them, and only when every file is present and verified renames the staging directory over `models/<storageDirName>/`. That single rename is also what `InferenceService`'s `FileObserver` on `models/` reacts to (it only watches the root, not subdirectories), so the engine loads right after the import.

**`SpeechEngine` is the only seam for adding a new model.** Implement `load`, `transcribe`, `close`, `setLanguage`, `setLanguageFilter`, register a `ModelId` enum value and a `ModelInfo` in `ModelRegistry`, add a branch in `SpeechEngineFactory`. Nothing in the IME or repository layer needs to change.

**`TranscriptResult` is a sealed class** with four variants that flow from `InferenceRepository` to `TextInjector`:
- `Partial` — show as composing (underlined) text, keep last 6 words mutable
- `Final` — commit text; when `isUtteranceBoundary = true`, do NOT stop capture
- `WindowTrimmed` — call `TextInjector.resetAfterTrim(stableWords)` immediately; skipping this causes silent word drops on every stride after a window trim
- `Failure` — log, surface to user

**`TextInjector` maintains a composing span** of the last 6 words via `InputConnection.setComposingText`. Words before that are permanently committed. `TranscriptAligner.findNewContent` does a three-layer overlap search (full prefix → suffix-prefix ≥ 2 words → interior scan) to locate genuinely new tokens in each partial.

**`InferenceRepository` sliding window:** partials fire every ~1 s once ≥ 2 s of audio is buffered; hard ceiling 30 s. Stable-prefix trims, silence-trims (2 blank strides), and force-trims (window > 12 s, no stable prefix) all emit `WindowTrimmed`. Every raw transcript passes an 8-step post-processing pipeline (filler removal → stutter collapse → phrase dedup → spurious-period removal → leading-dot strip → leading-punct strip → multi-dot normalisation → missing sentence-space repair → sentence-boundary capitalisation) before emission. **Short utterances** (< 2.5 s) use a decode-context retry at final flush: the TDT decoder is extremely context-sensitive on short clips, so the primary attempt re-decodes from frame 0 with 600 ms of lead silence prepended, and a low-confidence/blank result retries once without the lead; the best non-empty attempt wins, and a "Low confidence" failure is emitted only when a non-blank result fails the 0.55 gate in every context.

**Parakeet uses the chunked-TDT streaming path (active since v0.3.0):** audio is buffered and decoded in 2 s chunks with the TDT decoder's LSTM state carried across chunks (`ChunkStreamingEngine` — the capability interface `ParakeetEngine` implements; non-Parakeet engines fall back to the legacy sliding-window path above). Every chunk re-runs the post-processing pipeline over the entire accumulated utterance, so the per-chunk cost must stay bounded: `collapseRepeatedPhrases` (phrase dedup) caps candidate phrase length at 8 words to keep the scan near-linear (v0.3.1) — an unbounded scan grows cubic in utterance length and breaks the streaming path's real-time budget within a few minutes of dictation.

**Crash reports are local-only.** `ParakeetypeApplication` installs `CrashReporter` in every process. JVM crashes are written by an uncaught-exception handler (stack trace + the app's own logcat) to `<filesDir>/crash/pending.log`. Native crashes and ANRs never reach that handler, so the next process start reads them from `ApplicationExitInfo` (`last_exit_ts` tracks what was already seen) while logcat still holds the pre-crash lines. A new crash posts a notification that opens `SettingsActivity`, which shows `CrashReportDialog` (Share via `FileProvider` share sheet / Dismiss — both discard the pending report). Nothing is uploaded; `crash/` is excluded from backups. Release builds use `-dontobfuscate` so stack traces stay readable.

**Speech recognizer for other apps (`recognition`).** `ParakeetypeRecognitionService` (an exported `android.speech.RecognitionService`, for `SpeechRecognizer` clients and the system voice-input setting) and `VoiceInputActivity` (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`) both run a `RecognitionSession` and bind the same `InferenceService` as the IME through `InferenceConnection`, so they share the loaded model. The session starts capture immediately and buffers audio while the model loads (reported through `RecognitionSession.Listener.onModelLoading`: the UI-less service shows a toast, the voice-input sheet shows the keyboard's loading text as its title); VAD is always on, and the first utterance boundary ends the session (segmented sessions, API 33+, keep going until stopped). Without an installed model the service shows a toast telling the user to install one in Parakeetype (next to reporting `ERROR_LANGUAGE_UNAVAILABLE`) — it cannot open a dialog, a background service may not start activities. Android suppresses these toasts (logcat: "Suppressing toast … by user request") while Parakeetype's notifications are disabled — accepted, the user chose not to be notified; the sheet shows the message with an "Open Parakeetype" button. `TranscriptAccumulator` rebuilds the full text from the `TranscriptResult` stream (the plain-string counterpart of `TextInjector`). The service must record on the attribution context created in `onStartListening` from `Callback.getCallingAttributionSource()` (created before `onStartListening` returns) — `AudioCaptureManager` builds its `AudioRecord` with `setContext(context)` for this.

**VAD is dual-layer:** `SileroVadFilter` (Silero v4 ONNX) is primary; `RMSVadFilter` (energy threshold) is the automatic fallback if the ONNX VAD model fails to load.

**Microphone in use by another app.** Android doesn't make a recording fail while a phone call or another app has the microphone. It silences the recording instead, which then only delivers zeros. `AudioCaptureManager` polls `activeRecordingConfiguration.isClientSilenced` every ~300 ms, and also checks that `startRecording` actually started. In either case it throws `MicrophoneBusyException`. The keyboard commits the partial, shows `ErrorReason.MicBusy` in the status row (no button), and after 3 s hides itself (an instant-mode session switches back instead). The voice-input sheet shows the message and closes after 3 s. The recognition service reports `ERROR_AUDIO` with a toast.

**End of speech (`SpeechEndpointer`)** is detected from the VAD's raw per-frame speech probability (`VadFilter.lastSpeechProbability`, fed through `AudioCaptureManager.startCapture(onSpeechProbability)` on the capture thread), never from the VAD's filtered output or its silence boundaries: that output opens on one frame ≥ 0.3 and replays its 600 ms lead-in, so a breath or a tap on the phone looked like resumed speech and postponed the end forever whenever the endpoint waited past the boundary. Only 4 consecutive frames (120 ms) at ≥ 0.5 count as speech (the shortest words reach 7+, breaths and taps 0–2); silence is counted in frames from the last such run. Used by `RecognitionSession` (1 s default, or the client's `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` / minimum length) and by instant mode (2 s).

**Keyboard keys.** Enter and switch-keyboard are `KeyboardActionButton`s that fire on pointer-down via `detectTapGestures(onPress)`, not from a `LaunchedEffect` on the pressed state — a recomposition-driven trigger drops taps whose press and release land before the next frame. The delete keys (trash, delete word) are regular Material 3 `IconButton`s (`DeleteKey` in `KeyboardScreen`) that fire on release. Every key press gives `HapticFeedbackType.KeyboardTap` (honours the system keyboard-vibration setting); keep that in both components. `TalkButton` buzzes the same way on every finger down and finger up. While listening (held or locked — a locked recording keeps the listening look) a tinted halo behind it follows the microphone level (`KeyboardViewModel.micLevel`, same scale as the voice-input sheet). In HOLD mode, dragging **left** past 56 dp locks continuous recording; the lock hint floats to the left of the button (over the delete-all key). **Left-handed mode** (opt-in, `AppPreferences.leftHandedMode`, key `left_handed_mode`) mirrors the button row — `[Enter] · [Delete Word] · [Talk] · [Delete All]` — and the lock (`TalkButton(lockToRight = true)`): drag **right** to lock, the hint floats right of the button, still over delete-all. The row uses `Arrangement.Absolute` / `AbsoluteAlignment`, so its order is physical in RTL locales too; the top row is not mirrored. The keyboard's top row shows the `LanguageSelector` only for Whisper models while idle, otherwise the `StatusIndicator`; there is no waveform. While no model is installed (`EngineLoading(ModelNotDownloaded)`) the button row is replaced by a single filled "Open Parakeetype" key (Enter-key colours); talk, delete and Enter are hidden.

**Instant mode (opt-in, `AppPreferences.instantMode`, key `instant_mode`).** Meant for use from another keyboard's microphone key: the system creates a fresh IME service on every switch to Parakeetype, so `ParakeetypeInputMethodService` treats the first `onStartInputView` within 3 s of `onCreate` as "just switched here" (a default keyboard created at boot is shown much later) and calls `KeyboardViewModel.requestInstantStart`. Recording starts once the engine is Ready with a bound repository (audio before that is not buffered), with VAD forced on; the talk button looks as if it were held (listening colours, microphone icon — the same look as a locked recording). A `SpeechEndpointer` stops capture after 2 s of silence following speech or after 8 s without speech; when the transcription flow completes the IME calls `switchToPreviousInputMethod()` (`KeyboardViewModel.onInstantSessionFinished`; no picker if there is none). Until the switch the keyboard stays in `KeyboardUiState.Transcribing`, never Idle (after an automatic stop the final pass takes milliseconds). All keys (delete, Enter) stay visible. Touching the talk button during the session means "stay here": a press takes it over (`KeyboardViewModel.onTalkPress`, the button's `onRecordStart`) — recording continues as a normal hold, release stops it, drag left locks it, the endpointer no longer stops it — and a stop via the button (`onRecordStop`, e.g. a tap in TAP_TOGGLE) ends it without switching; the endpointer uses the private `stopRecording` instead. A session that ends in `KeyboardUiState.Error` stays on the keyboard; leaving the field (`commitPartialAndStop`) cancels it without switching.

**Keyboard size & position.** `ParakeetypeInputMethodService` computes the window height (20 % of the screen height, at least 130 dp of content, plus the bottom nav bar) and the side insets on every access — never cache them, the IME service survives rotation. The UI is one layout for all orientations: in landscape it is docked (≤ 400 dp wide) to the right by default or to the left (`keyboard_position_landscape`); in portrait it is centred by default or docked left/right (`keyboard_position_portrait`, for tablets / one-handed use). Left / right are physical (`AbsoluteAlignment`), also in RTL locales. `ParakeetypeKeyboardTheme` provides `LocalContentColor` — without it ripples are black and invisible on the dark keyboard. The Enter key (`EnterAction`): multi-line → newline; explicit SEARCH/SEND/GO/NEXT/DONE → `performEditorAction`; no action, `IME_FLAG_NO_ENTER_ACTION`, or `TYPE_NULL` → raw `KEYCODE_ENTER` (`ENTER_KEY`).

## Adding a New Model

1. Add a `ModelId` enum value with a stable `storageDirName` (changing it breaks existing installs).
2. Add a `ModelInfo` + private val in `ModelRegistry` (its `archiveUrl` single-file ZIP and the `ModelFile` list with SHA-256s) — only add it to `ModelRegistry.all` when the engine works on-device. Publish the archive (see `devtools/package-model.sh`).
3. Implement `SpeechEngine` in a new class under `inference/`.
4. Add a branch in `SpeechEngineFactory`.

`VOXTRAL_MINI` and `WHISPER_SMALL` exist in the registry but are commented out of `ModelRegistry.all` — they couldn't run within acceptable resource limits on real devices.

## Key Conventions

- **No field injection** — constructor injection only.
- **`val` over `var`**; avoid nullable types unless genuinely optional.
- **`application.yml`** is not used (Android project) — preferences go through `DataStore` (`settings/preferences/`).
- **Model files** are stored in `<filesDir>/models/<storageDirName>/` — no external storage permission.
- **SHA-256** is verified for every model file on import; add hashes to the `ModelFile` entries in `ModelRegistry`.
- **No network access.** The app has no `INTERNET` / `ACCESS_NETWORK_STATE` permission — the manifest strips the ones `onnxruntime-android` declares via `tools:node="remove"`. Never add HTTP clients, network permissions, or any code that contacts a server.
- **Models are installed from a single file.** The user downloads one ZIP archive (`ModelInfo.archiveUrl`, a release asset built with `devtools/package-model.sh` and hosted on the pinned release page `MODEL_ARCHIVE_RELEASE` = https://github.com/theScrabi/Parakeetype/releases/tag/v0.4) in their **browser** and imports it through the system file picker (`ModelImporter`, SAF `OpenDocument` — no storage permission). Keep it to one file; do not reintroduce multi-file picking or in-app downloads.
- ABI splits produce per-ABI APKs: `armeabi-v7a` (×1), `arm64-v8a` (×2), universal (×0 offset). `versionCode = defaultVersionCode * 10 + abiOffset`.
- `dependenciesInfo` is disabled in the APK for F-Droid reproducibility.
- Do not add analytics, crash-reporting SDKs, or anything that phones home. The built-in `crash` package only writes a local log that the user can share themselves; never make it upload anything.
- **Launcher icon** is generated from `parakeet.svg` (project root): `drawable/ic_launcher_foreground.xml` holds the SVG's visible parakeet paths (coordinates rounded to 3 decimals) scaled into the 66 dp safe zone; the background layer is plain white (the SVG's circle); the monochrome/themed layer reuses the foreground. `fastlane/.../images/icon.png` is the SVG rendered at 512 px.
- **UI strings are localized** into the 25 Parakeet-TDT v3 languages (`values-<lang>/strings.xml`: bg, cs, da, de, el, es, et, fi, fr, hr, hu, it, lt, lv, mt, nl, pl, pt, ro, ru, sk, sl, sv, uk; English is the unqualified `values/`). Every user-visible string — including content descriptions — goes through a string resource; when you add or change one, update all locales (same keys, same order, same format args; escape `\'` and `\"`). `generateLocaleConfig` lists the locales for the Android 13+ per-app language setting (`res/resources.properties` declares the default `en-US`).
- **No word-suggestion / correction system.** It was removed in favour of a lean keyboard (the IME deletes the legacy `<filesDir>/suggestion_files/` on start). Do not reintroduce tap-a-word alternatives, dictionaries or language models.

## Release Process

Complete checklist for publishing a new version to GitHub Releases.

### 1. Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>      // integer; Android and app stores use this to detect updates
versionName = "0.x.y"             // shown to users; must match the git tag (without "v")
```

The ABI-split `versionCode` formula is `defaultVersionCode * 10 + abiOffset` (handled automatically by the build script — only edit `defaultVersionCode` here).

Also update `how-to-release.txt` — change the tag command to use the new version:

```
git tag v0.x.y && git push origin v0.x.y
```

Also update `metadata/org.schabi.parakeetype.yml` (a copy of the entry in F-Droid's fdroiddata repo; F-Droid's checkupdates bot updates fdroiddata itself from the new tag):

- Set `CurrentVersion` to the new `versionName`.
- Set `CurrentVersionCode` to the arm64 split's code (`versionCode * 10 + 2`).
- Replace the two `Builds:` entries (one per ABI split) with the new version:

```yaml
  - versionName: 0.x.y
    versionCode: <N * 10 + 1>
    commit: v0.x.y
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

  - versionName: 0.x.y
    versionCode: <N * 10 + 2>
    commit: v0.x.y
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```

### 2. Write the fastlane changelog

Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

- File name is the plain integer `versionCode` (e.g. `8.txt` for versionCode 8).
- First line: `Nth patch (vX.Y.Z).` (or `… release (vX.Y.Z).` for a MAJOR bump) — keep phrasing consistent with previous entries.
- Blank line, then a plain-English description of what changed. Focus on user-visible changes; skip internal refactors unless they fix something the user would notice.
- Keep it under ~500 characters — app stores truncate long changelogs.

### 3. Update documentation

Review and update these files so they reflect the new state of the codebase:

- **`AGENTS.md`** — package table, architecture notes, supported language lists.
- **`README.md`** — features list, requirements, permissions table, privacy section.
- **`docs/architecture.md`** — package table, AppPreferences schema, version line in §13, any new subsystems.

Only update what actually changed; do not rewrite sections that are still accurate.

### 4. Commit everything

Stage and commit all changed files together in one commit:

```bash
git add app/build.gradle.kts \
        how-to-release.txt \
        metadata/org.schabi.parakeetype.yml \
        fastlane/metadata/android/en-US/changelogs/<versionCode>.txt \
        AGENTS.md README.md docs/architecture.md \
        # …any other changed source files
git commit -m "release v0.x.y"
git push
```

### 5. Tag the release

The tag must match `versionName` from `app/build.gradle.kts` with a `v` prefix:

```bash
git tag v0.x.y
git push origin v0.x.y
```

### 6. The GitHub Release is created automatically

Pushing the tag triggers the release job in `.github/workflows/release-f-droid.yml`: it decodes the release keystore from repo secrets (never in the source tree), builds the signed release APKs, renames them to `parakeetype-<version>.apk` / `parakeetype-<version>-<abi>.apk`, writes a `.sha256` checksum next to each, and creates the GitHub Release with all of them attached (release notes are auto-generated from the commits since the previous tag).

Watch the workflow run on the tag push and confirm it ends green. No local build or manual APK attachment is needed.

### Version numbering conventions

| Segment | Rule |
|---|---|
| `versionCode` | Increment by 1 for every release, no exceptions. Never reuse or skip. |
| `versionName` | `0.MAJOR.PATCH` — bump PATCH for fixes and small features, bump MAJOR for large feature additions. |
| Git tag | Always `v` + `versionName` (e.g. `v0.2.3`). Must match exactly. |
| Fastlane file | Always plain `versionCode` integer (e.g. `8.txt`). |

