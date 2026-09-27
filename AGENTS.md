# Parakeetype — Agent Guide

Android IME (keyboard) that does on-device speech-to-text via ONNX Runtime. No cloud, no Google Play Services, **no network access at all** (no `INTERNET` permission).

## Build & Test

```bash
./gradlew assembleDebug          # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified + resource-shrunk release APK
./gradlew test                   # JVM unit tests (app/src/test/)
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
```

Target SDK 36, min SDK 31 (Android 12 — required by Material You dynamic colour in `ParakeetypeTheme`), JDK 11, Kotlin official code style (`kotlin.code.style=official`).

## Package Structure

All source lives under `app/src/main/kotlin/` (package root `org.schabi.parakeetype`):

| Package | Key files | Responsibility |
|---|---|---|
|`inference`|`SpeechEngine`, `ParakeetEngine`, `ChunkStreamingEngine`, `WhisperEngine`, `VoxtralEngine`, `InferenceRepository`, `InferenceService`, `SpeechEngineFactory`, `TranscriptResult`, `EngineState`, `PipelineDiagnostics`, `NumberNormaliser`, `GrammarCorrector`|ASR pipeline, sliding window, post-processing, foreground service|
| `audio` | `AudioCaptureManager`, `MicCalibrationManager`, `SileroVadFilter`, `RMSVadFilter`, `VadFilter`, `AudioChunk`, `PermissionHelper` | Mic capture + VAD + optional mic calibration |
| `ime` | `ParakeetypeInputMethodService`, `TextInjector`, `TranscriptAligner`, `EnterAction` | Keyboard / text insertion |
| `settings/model` | `ModelRegistry`, `ModelId`, `ModelImporter`, `ModelStorageManager`, `ModelState`, `ModelViewModel` | Model lifecycle: single-archive import from local storage |
| `settings/preferences` | `AppPreferences`, `PreferencesViewModel` | DataStore-backed user preferences |
| `settings/screens` | `HomeScreen`, `ModelScreen`, `PreferencesScreen`, `MicCalibrationScreen` | Settings Compose UI |
| `ui/keyboard` | `KeyboardViewModel`, `KeyboardUiState`, `KeyboardScreen`, `ImeComposeView` | IME Compose hosting, UI state |
| `ui/keyboard/components` | `TalkButton`, `StatusIndicator`, `KeyboardActionButton`, `KeyboardTutorialOverlay`, `LanguageSelector` | Keyboard UI sub-components |
| `ui/theme` | `ParakeetypeKeyboardTheme` | Compose theming |

## Architecture — What Isn't Obvious from Single Files

**`InferenceService` is a `LifecycleService`** that the IME binds to. The engine stays alive across keyboard hide/show cycles (the IME stays bound while it is the current input method). By default the service is *only bound*: when the user switches to another keyboard the system destroys the IME, the last binding goes away, and the service is destroyed with the model unloaded. With the opt-in **Keep model loaded** setting (`AppPreferences.keepModelLoaded`) the service additionally *starts* itself as a `specialUse` foreground service (`InferenceService.startKeepLoaded`, persistent notification) so it survives the unbind and the model stays warm; in that mode only critical memory pressure (`TRIM_MEMORY_RUNNING_CRITICAL` / `onLowMemory`) unloads it. Turning the setting off calls `stopForeground` + `stopSelf`, returning to bound-only behaviour.

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

**VAD is dual-layer:** `SileroVadFilter` (Silero v4 ONNX) is primary; `RMSVadFilter` (energy threshold) is the automatic fallback if the ONNX VAD model fails to load.

**Keyboard keys.** Enter and switch-keyboard are `KeyboardActionButton`s that fire on pointer-down via `detectTapGestures(onPress)`, not from a `LaunchedEffect` on the pressed state — a recomposition-driven trigger drops taps whose press and release land before the next frame. The delete keys (trash, delete word) are regular Material 3 `IconButton`s (`DeleteKey` in `KeyboardScreen`) that fire on release. Every key press gives `HapticFeedbackType.KeyboardTap` (honours the system keyboard-vibration setting); keep that in both components. `TalkButton` buzzes the same way on every finger down and finger up. In HOLD mode, dragging **left** past 56 dp locks continuous recording; the lock hint floats to the left of the button (over the delete-all key). The keyboard's top row shows the `LanguageSelector` only for Whisper models while idle, otherwise the `StatusIndicator`; there is no waveform.

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
- **Models are installed from a single file.** The user downloads one ZIP archive (`ModelInfo.archiveUrl`, a pinned release asset built with `devtools/package-model.sh` — the URL `MODEL_ARCHIVE_RELEASE` is still a TODO placeholder, no archive is hosted yet) in their **browser** and imports it through the system file picker (`ModelImporter`, SAF `OpenDocument` — no storage permission). Keep it to one file; do not reintroduce multi-file picking or in-app downloads.
- ABI splits produce per-ABI APKs: `armeabi-v7a` (×1), `arm64-v8a` (×2), universal (×0 offset). `versionCode = defaultVersionCode * 10 + abiOffset`.
- `dependenciesInfo` is disabled in the APK for F-Droid reproducibility.
- Do not add analytics, crash reporters, or any SDK that phones home.
- **Launcher icon** is generated from `parakeet.svg` (project root): `drawable/ic_launcher_foreground.xml` holds the SVG's visible parakeet paths (coordinates rounded to 3 decimals) scaled into the 66 dp safe zone; the background layer is plain white (the SVG's circle); the monochrome/themed layer reuses the foreground. `fastlane/.../images/icon.png` is the SVG rendered at 512 px.
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

Also update `metadata/org.schabi.parakeetype.yml`:

- Set `CurrentVersion` to the new `versionName`.
- Set `CurrentVersionCode` to the new `versionCode`.
- Append a new entry to the `Builds:` list:

```yaml
  - versionName: '0.x.y'
    versionCode: <N>
    commit: v0.x.y
    subdir: app
    gradle:
      - release
```

### 2. Write the fastlane changelog

Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

- File name is the plain integer `versionCode` (e.g. `8.txt` for versionCode 8).
- First line: `Nth patch (vX.Y.Z).` — keep phrasing consistent with previous entries.
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

