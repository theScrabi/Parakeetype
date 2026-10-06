# Parakeetype

<p align="center">
  <img src="parakeet.svg" alt="Parakeetype icon" width="200" />
</p>

A privacy-focused speech-to-text keyboard (IME) and system speech recognizer for Android — other apps can use it for voice input through Android's standard `SpeechRecognizer` / `RecognizerIntent` APIs. Speech recognition runs entirely on-device - the app has no internet access at all, no account, no data leaving your phone.

It uses [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra), Moondream's further-trained version of NVIDIA's [Parakeet-TDT v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) automatic speech recognition model, quantized to INT8 and run via [ONNX Runtime](https://onnxruntime.ai/) for efficient on-device inference. Voice activity detection uses [Silero VAD v6](https://github.com/snakers4/silero-vad) (also ONNX, also fully on-device) to suppress silence before it ever reaches the ASR model.


## Screenshots
| ![Setup](fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg) | ![Install the model](fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg) | ![Model imported](fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg) |
|---|---|---|
| ![Settings](fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg) | ![Keyboard](fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg) | ![Hold to talk, drag left to lock](fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg) |

![Landscape: keyboard docked to the right](fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg)

---

## Features

- **Fully offline after setup** - audio is never transmitted anywhere
- **Real-time transcription** - progressive partial results while you speak
- **Works in any app** - injects text via Android's standard `InputConnection` API
- **Speech recognizer for other apps** - implements Android's `RecognitionService` (for apps using `SpeechRecognizer`, and selectable as the system's voice-input service) and handles `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (the "tap the mic" voice input other apps launch), all on-device with the same model as the keyboard
- **Parakeet Ultra** (Parakeet-TDT 0.6B v3, further trained) - INT8 quantized, ~630 MB, robust against background noise, runs on mid-range hardware
- **Voice Activity Detection** - Silero VAD v6 neural network (ONNX) filters silence before it reaches the ASR model; ships in the model archive and falls back to energy-threshold VAD if it can't load
- **Configurable trigger modes** - hold-to-talk or tap-to-toggle
- **Instant mode** - when you switch to Parakeetype (e.g. with the microphone key of another keyboard) it starts listening right away, types the text once you stop speaking and switches back to your keyboard (touch the talk button to stay on Parakeetype). Opt-in; off by default.
- **One-handed layout** - in landscape the keyboard controls are docked to the right (or left, for left-handed use); in portrait they can optionally be docked left or right too (useful on tablets); an opt-in left-handed mode mirrors the buttons (Enter on the left, drag right to lock)
- **Keep model loaded** - keeps the ~630 MB model in RAM via a foreground service while you use another keyboard, so switching back to Parakeetype needs no reload. On by default; can be turned off in the settings.
- **Optional microphone calibration** - a settings screen that records a short reference clip on each available microphone, ranks them by capture fidelity, and selects the best one for dictation. Opt-in; off by default.
- **Localized UI** - the app is translated into the 25 languages Parakeet-TDT v3 transcribes (Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, Ukrainian); on Android 13+ the language can be chosen per app in the system settings
- **No Google Play Services, no telemetry, no analytics**

---

## Requirements

| Requirement | Minimum                                                                |
|---|------------------------------------------------------------------------|
| Android version | 12 (API 31)                                                            |
| RAM | 4 GB recommended                                                       |
| Free storage | ~750 MB (for ASR model files) |
| Permissions | `RECORD_AUDIO`, `POST_NOTIFICATIONS` (no `INTERNET`) |

> Parakeetype has **no network access**. You download the model archive once in your browser and import it into the app; the keyboard itself never goes online.

---

## Getting Started

1. **Install** the APK from [Releases](../../releases) or build from source (see below).
2. **Open the Parakeetype app** and follow the three setup steps:
   - Enable Parakeetype in *System Settings → Keyboard / Input Methods*
   - Grant the microphone permission
   - Install the model: tap *Download in browser* to fetch the single model archive (~630 MB, Wi-Fi recommended), then *Import model file* and pick the downloaded ZIP
3. **Switch** to the Parakeetype keyboard in any text field and tap the mic button.

> **Keyboard not showing?** With a physical keyboard connected (Bluetooth keyboard, or the Android emulator's virtual hardware keyboard) Android hides on-screen keyboards unless *System Settings → Keyboard → Physical keyboard → Use on-screen keyboard* is enabled.

---

## Architecture

Parakeetype is structured as a clean layered pipeline. The `SpeechEngine` interface decouples all inference code from the service and audio layers - adding a new backend means implementing that one interface and nothing else.

```
┌─────────────────────────────────┐
│         Active App              │
│         (Text Field)            │
└──────────────┬──────────────────┘
               │  InputConnection API
┌──────────────▼──────────────────┐
│  ParakeetypeInputMethodService     │  ← Android IME service
│  (LifecycleOwner + Compose UI)  │
│  ┌───────────────────────────┐  │
│  │    KeyboardViewModel      │  │  ← UI state + capture lifecycle
│  └───────────────────────────┘  │
└──────────────┬──────────────────┘
               │  binds to
┌──────────────▼──────────────────┐
│       InferenceService          │  ← Foreground service (keeps engine alive)
│  ┌───────────────────────────┐  │
│  │    InferenceRepository    │  │  ← Sliding-window buffer (30 s max)
│  │  ┌─────────────────────┐  │  │
│  │  │   SpeechEngine      │  │  │  ← Interface (swap models here)
│  │  │   (ParakeetEngine)  │  │  │
│  │  └─────────────────────┘  │  │
│  └───────────────────────────┘  │
└──────────────┬──────────────────┘
               │  Flow<AudioChunk>
┌──────────────▼──────────────────┐
│    AudioCaptureManager          │  ← 16 kHz / 16-bit / mono PCM
│    SileroVadFilter              │  ← Neural VAD (Silero v6, ONNX)
│    RMSVadFilter                 │  ← Energy-threshold fallback
└─────────────────────────────────┘
```

### Key components

| Package | Class | Role |
|---|---|---|
| `inference` | `SpeechEngine` | Interface - model-agnostic contract for loading, transcribing, and closing any ASR engine |
| `inference` | `ParakeetEngine` | Implements `SpeechEngine` using three ONNX sessions (preprocessor → encoder → decoder/joint) |
| `inference` | `InferenceService` | `LifecycleService` that owns the engine and exposes `InferenceRepository` to bound clients |
| `inference` | `InferenceRepository` | Sliding-window inference driver: buffers audio chunks, waits for ≥ 2 s of context, then fires a partial inference every 1 s up to a 30 s hard ceiling; tracks the last 3 partials and performs **stable-chunk trims** when a common leading-word prefix is confirmed, emitting `TranscriptResult.WindowTrimmed` to `TextInjector`; force-trims on divergence loops (> 12 s) and silence runs (2 consecutive blank strides); applies a **post-processing pipeline** to every raw transcript (filler-word removal, stutter collapse ≥ 3×, phrase-loop deduplication, leading-dot / leading-punct stripping, trailing-dot normalisation, missing sentence-space repair, sentence-boundary capitalisation) |
| `audio` | `AudioCaptureManager` | Opens `AudioRecord` with the vendor-recommended DEFAULT source, emits 32 ms `AudioChunk`s as a cold `Flow`; applies the calibration-selected microphone when set; drains hardware buffer and VAD hangover on stop |
| `audio` | `VadFilter` | Interface - common contract for VAD implementations (process, flush, isSpeechActive) |
| `audio` | `SileroVadFilter` | Neural VAD using Silero v6 (ONNX); preserves RNN state and 64-sample context across chunks; primary filter when model is available |
| `audio` | `RMSVadFilter` | Energy-threshold VAD; used as fallback when Silero ONNX model can't load |
| `ime` | `ParakeetypeInputMethodService` | Core IME; wires Compose view tree, binds `InferenceService`, drives capture lifecycle |
| `recognition` | `ParakeetypeRecognitionService`, `VoiceInputActivity` | Speech recognizer for other apps (`SpeechRecognizer` / `ACTION_RECOGNIZE_SPEECH`); both run a `RecognitionSession` against the shared `InferenceService` |
| `ime` | `TextInjector` | Writes partial/final text into the focused field via `InputConnection`; keeps the last 6 words as a mutable composing span (underlined) and permanently freezes earlier words; delegates new-content discovery to `TranscriptAligner.findNewContent`; on `WindowTrimmed` performs a three-step reset (commit composing minus last 2 uncertain tail words, clear `lastPartial`, re-anchor `committedWords` from the actual field content); two-layer alignment recovery (field-scan → composing-commit fallback) prevents silent word drops on complete divergence |
| `ime` | `TranscriptAligner` | Stateless alignment utilities (`normalizeWord`, `splitToWords`, `findNewContent`); `findNewContent` uses a three-layer overlap search - (1) full prefix match, (2) suffix-prefix overlap ≥ 2 words, (3) interior scan ≥ 2 words - to locate genuinely new content in a fresh partial relative to already-committed words, tolerating Parakeet attention drift and post-trim leading garbage tokens |
| `ui` | `KeyboardViewModel` | Bridges IME lifecycle, audio capture, and inference results into `KeyboardUiState`; owns `captureJob` |
| `settings` | `ModelImporter` | Installs a model from the single ZIP archive the user picked (SAF); verifies every file's SHA-256 and swaps the model in atomically |
| `settings` | `ModelStorageManager` | Manages model file paths inside `filesDir` (no external storage permission needed) |

### Inference pipeline (Parakeet Ultra)

1. Raw PCM (16-bit signed) is normalised to `float32 [-1, 1]`
2. **`nemo128.onnx`** - computes 128-dim log-mel spectrogram features
3. **`encoder.int8.onnx`** - FastConformer encoder → `[B, 640, T_enc]`
4. **`decoder.int8.onnx`** + **`joiner.int8.onnx`** - greedy TDT decoding with LSTM state carry-over
5. Token IDs are mapped to text via `vocab.txt`

Partial results are emitted every ~1 s once ≥ 2 s of audio is in the rolling window; the window grows up to a hard 30 s ceiling. After every partial the last 3 results are compared - if their leading words form a stable common prefix, the corresponding audio is trimmed from the front of the window (retaining 4 s of tail context) and `TranscriptResult.WindowTrimmed` is emitted so `TextInjector` can re-anchor its alignment state. Silence runs (2 consecutive blank strides) and divergence loops (window > 12 s with no common prefix) trigger unconditional force-trims. Every raw transcript passes through an 8-step post-processing pipeline before emission: filler-word removal → stutter collapse (≥ 3× repeats) → phrase-loop deduplication → leading-dot strip → leading-punct strip → multi-dot normalisation → missing sentence-space repair → sentence-boundary capitalisation. A final inference pass runs over the entire remaining window when recording stops; clips shorter than 1.25 s are zero-padded to give the encoder sufficient frames.

---

## Adding a New Model

To add a new model backend, implement the `SpeechEngine` interface:

```kotlin
interface SpeechEngine {
    val isLoaded: Boolean
    fun load(modelDir: File)
    fun transcribe(chunk: AudioChunk): TranscriptResult
    val currentLanguage: String
    fun setLanguage(tag: String)
    fun setLanguageConstraints(tags: List<String>)
    fun close()
}
```

To add, for example, a Whisper or Moonshine backend:

1. Create a new class implementing `SpeechEngine` (e.g. `WhisperEngine`).
2. Add a `ModelId` enum value and a `ModelInfo` entry in `ModelRegistry` - this covers display name, the single-file archive URL, the file list with SHA-256 hashes, and size estimate. Build and publish the archive (see `devtools/package-model.sh`).
3. Add a branch in `SpeechEngineFactory` to instantiate the new engine for that `ModelId`.

The repository and IME layers don't need to change.

---

## Building from Source

```bash
git clone https://github.com/theScrabi/Parakeetype.git
cd parakeetype
./gradlew assembleRelease
```

**Requirements:** JDK 17 to run the build (code targets Java 11; the Gradle wrapper is included), Android SDK 31–36.

A debug build for sideloading:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capturing microphone input for speech recognition |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Keeping the inference engine alive while the keyboard is in use |
| `FOREGROUND_SERVICE_SPECIAL_USE` | *Keep model loaded* setting (on by default): keeps the model in RAM while another keyboard is active |
| `POST_NOTIFICATIONS` | Showing the required foreground service notification |

No permission is used for any purpose beyond what is listed above.

---

## Privacy

- Audio stays on your device - all recognition runs locally via ONNX Runtime.
- No analytics, crash-reporting services, or third-party SDKs are included.
- If Parakeetype crashes, a crash log (only Parakeetype's own log lines, which may include dictated text) is saved on the device and you are offered to share it. It is never sent anywhere unless you share it yourself.
- No accounts or sign-in of any kind.
- No network access at all: the app has no `INTERNET` permission. The model archive is downloaded by you in your browser (or copied from a computer) and imported from local storage.

---

## Contributing

Bug reports and pull requests are welcome. Please open an issue first for significant changes so we can discuss the approach.

- Follow the existing Kotlin code style (`kotlin.code.style=official`)
- Keep the `SpeechEngine` interface stable - new engines should be additive
- Unit tests for business logic live in `app/src/test/`. The real-audio tests need the model, which is not in the repository: run `devtools/fetch-test-model.sh` to download it. Their speech WAV fixtures are internal development data and not published; without the model or the fixtures those tests are skipped

---

## License

This project is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

The Parakeet Ultra model weights are distributed separately under [CC-BY-4.0](https://huggingface.co/moondream/parakeet-ultra) by Moondream, based on [Parakeet-TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by NVIDIA.

The model archive also contains the [Silero VAD v6](https://github.com/snakers4/silero-vad) model by the Silero Team, licensed under the MIT License. The archive carries both licences and an attribution notice.
