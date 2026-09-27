# Parakeetype — Release Process

Complete checklist for publishing a new version to GitHub Releases.
Follow every step in order. The fastlane character limit in step 2 is **strict** —
a previous release shipped with an over-long description; do not repeat that.

## 0. Before you start

- All changes committed and pushed to `main`; CI is green.
- **Manual pre-release checklist complete on a real device:** `docs/manual-release-checklist.md`.
  An agent must walk through it with the developer and get explicit sign-off.
- Docs updated to reflect the new state: `AGENTS.md`, `README.md`, `docs/architecture.md`
  (only what actually changed — do not rewrite sections that are still accurate).

## 1. Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>      // integer; Android and app stores use this to detect updates
versionName = "0.x.y"             // shown to users; must match the git tag (without "v")
```

Rules:
- `versionCode` increments by 1 for every release — never reused, never skipped.
- `versionName`: `0.MAJOR.PATCH` — bump PATCH for fixes and small features, MAJOR for large feature additions.
- The ABI-split versionCode (`versionCode * 10 + abiOffset`) is computed by the build script; only edit `versionCode` here.

Also update:

- **`how-to-release.txt`** — change the tag command to the new version:

  ```
  git tag v0.x.y && git push origin v0.x.y
  ```

- **`metadata/org.schabi.parakeetype.yml`**:
  - `CurrentVersion` → new `versionName`.
  - `CurrentVersionCode` → new `versionCode`.
  - Append a new entry to the `Builds:` list:

    ```yaml
      - versionName: '0.x.y'
        versionCode: <N>
        commit: v0.x.y
        subdir: app
        gradle:
          - release
    ```

## 2. Write the fastlane changelog

Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

**Strict rules:**

1. File name is the plain integer `versionCode` (e.g. `10.txt` for versionCode 10) — not the versionName.
2. First line: `Nth patch (vX.Y.Z).` — keep the phrasing consistent with the previous entries.
3. **The whole file must be ≤ 500 characters.** App stores truncate longer changelogs.
   Verify before committing:

   ```bash
   wc -m fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
   ```

4. Blank line, then plain English focused on user-visible changes. Skip internal refactors
   unless the user would notice them.

## 3. Commit everything

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

## 4. Tag the release

The tag must match `versionName` from `app/build.gradle.kts` with a `v` prefix:

```bash
git tag v0.x.y
git push origin v0.x.y
```

## 5. The GitHub Release is created automatically

Pushing the tag triggers the release job in `.github/workflows/release-f-droid.yml`:

1. Decodes the release keystore from repo secrets (the keystore never touches the source tree).
2. Builds the signed release APKs (ABI splits + universal).
3. Renames them to `parakeetype-<version>.apk` / `parakeetype-<version>-<abi>.apk` and writes a
   `.sha256` checksum file next to each.
4. Creates the GitHub Release with all APKs and checksums attached
   (`softprops/action-gh-release`; release notes are auto-generated from the commits
   since the previous tag).

Watch the workflow run on the tag push and confirm it ends green.
No local `assembleRelease` and no manual APK attachment is needed.

### APK size budget

Keep the `arm64-v8a` APK (what modern devices download) at **≤ 30 MB**.
`libonnxruntime.so` alone is ~28 MB, so the build deflates native libraries
(`useLegacyPackaging = true` in `app/build.gradle.kts`), which keeps `arm64-v8a` at
~15 MB. Do not revert that setting. Check the sizes after every `assembleRelease`:

```bash
ls -la app/build/outputs/apk/release/
```

## Version numbering conventions

| Segment        | Rule                                                                          |
|----------------|-------------------------------------------------------------------------------|
| `versionCode`  | Increment by 1 for every release, no exceptions                               |
| `versionName`  | `0.MAJOR.PATCH` — PATCH for fixes/small features, MAJOR for large features    |
| Git tag        | `v` + `versionName` (e.g. `v0.3.0`), must match exactly                       |
| Fastlane file  | Plain `versionCode` integer (e.g. `10.txt`)                                   |
| Changelog size | ≤ 500 characters (`wc -m`)                                                    |
