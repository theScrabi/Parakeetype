# Outspoke — Manual Pre-Release Checklist

Run on a **real device** before every release. An agent must walk through every item with
the developer and get explicit confirmation before the release tag is pushed.
Do not skip items "because they worked last time", and do not accept "I'm sure it's fine"
as a result — each box needs an observed outcome.

## 1. Core dictation

- [ ] Hold the mic button, speak a short sentence, release → correct text committed.
- [ ] Speak 3+ sentences with natural pauses → no dropped or duplicated words,
      sentence boundaries correct.
- [ ] Trail off mid-sentence, then continue → no garbage committed, continuation works.
- [ ] Restart from the beginning mid-sentence → handled sanely (no duplication).
- [ ] Single-word utterances: "thanks", "yes", "no" → recognised reliably (3 tries each).
- [ ] Silence / room tone only → no ghost words committed.
- [ ] Dictation into a real third-party app (Messages, browser search box) → text lands
      correctly; partial results show as an underlined composing span.

## 2. Languages

- [ ] English dictation → correct.
- [ ] German dictation (umlauts) → correct.
- [ ] One non-Latin script language (e.g. Russian) → Cyrillic passes, nothing suppressed.
- [ ] Language selector on the keyboard → switching languages mid-session works.

## 3. Model & settings

- [ ] Model download from scratch (fresh install or deleted model dir) → resumable,
      SHA-256 verified, engine loads, dictation works.
- [ ] VAD sensitivity toggle, post-processing toggle, tutorial reset → all work, no crash.
- [ ] Mic calibration (optional): open the calibration screen, run it (2+ mics if the
      device has them) → a mic is selected, dictation uses it, and the selection survives
      an app restart.

## 4. Privacy audit

- [ ] `adb shell ls -R /sdcard/Android/data/dev.brgr.outspoke/files/`
      → **no audio/WAV files anywhere** (the debug audio-tap was removed in this release;
      any new file here is a regression).
- [ ] Dictate a sentence containing a distinctive word, then:
      `adb logcat -d | grep -i "DISTINCTIVE_WORD"`
      → **no hits** (transcribed text must not reach device logs).
- [ ] `adb logcat -d | grep -iE "PARTIAL|FINAL|GRAMMAR|HALLUCINATION|STUTTER"`
      → structural logs only (char/word counts, confidences) — no transcribed content.

## 5. Release artifacts

- [ ] `./gradlew assembleRelease` builds.
- [ ] APK sizes within IzzyOnDroid's ~30 MB per-app budget
      (<https://izzyondroid.org/docs/general/AppInclusionPolicy/>):
      `arm64-v8a` ≤ 30 MB (the universal may be larger).
- [ ] `./gradlew test` locally (with model) → full suite green.
- [ ] Changelog file: filename = plain versionCode integer; first line `Nth patch (vX.Y.Z).`;
      `wc -m` ≤ 500 characters (see `docs/release-process.md`).
- [ ] `app/build.gradle.kts` versionCode/versionName bumped; `how-to-release.txt`
      tag command updated; `metadata/dev.brgr.outspoke.yml` CurrentVersion /
      CurrentVersionCode / Builds updated.
- [ ] `AGENTS.md`, `README.md`, `docs/architecture.md` reflect the new state.
- [ ] CI green on `main`.
