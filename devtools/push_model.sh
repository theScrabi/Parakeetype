#!/usr/bin/env bash
# =============================================================================
# push_model.sh - push Parakeet Ultra model files directly into the app's
# internal storage on a connected device / emulator.
#
# Prerequisites:
#   - adb in PATH
#   - Device connected with USB debugging enabled
#   - App installed as a DEBUG build (run-as requires android:debuggable="true")
#   - Model files already downloaded locally (e.g. from huggingface-cli or wget)
#
# Usage:
#   ./devtools/push_model.sh [/path/to/local/model/dir]
#
#   If no directory is given the script looks in ./model_files/
#
# After this runs, open the Parakeetype app - ModelStorageManager.isModelReady()
# will return true immediately, no download required.
#
# Why this works across reinstalls:
#   adb install -r  (Android Studio "Run")   → data PRESERVED  ✓
#   adb uninstall   (manual uninstall)       → data LOST       ✗  (re-run script)
# =============================================================================

set -euo pipefail

PACKAGE="org.schabi.parakeetype"
INTERNAL_MODEL_DIR="/data/user/0/${PACKAGE}/files/models/parakeet-ultra"
STAGING_DIR="/sdcard/tmp_parakeetype_push"
LOCAL_DIR="${1:-./model_files}"

MODEL_FILES=(
  "encoder.int8.onnx"
  "decoder.int8.onnx"
  "joiner.int8.onnx"
  "nemo128.onnx"
  "vocab.txt"
  "silero_vad_v6.onnx"
)

# ---- Sanity checks -----------------------------------------------------------

if ! command -v adb &>/dev/null; then
  echo "❌  adb not found in PATH. Install Android Platform Tools."
  exit 1
fi

if ! adb get-state &>/dev/null; then
  echo "❌  No device/emulator detected. Connect a device and enable USB debugging."
  exit 1
fi

for f in "${MODEL_FILES[@]}"; do
  if [[ ! -f "${LOCAL_DIR}/${f}" ]]; then
    echo "❌  Missing local file: ${LOCAL_DIR}/${f}"
    echo "    Build the model archive with devtools/package-model.sh and unzip it there."
    exit 1
  fi
done

# ---- Stage on shared storage (adb push can't write to /data directly) --------

echo "📂  Creating staging directory on device: ${STAGING_DIR}"
adb shell "mkdir -p ${STAGING_DIR}"

for f in "${MODEL_FILES[@]}"; do
  echo "⬆️   Pushing  ${f}  ($(du -sh "${LOCAL_DIR}/${f}" | cut -f1))"
  adb push "${LOCAL_DIR}/${f}" "${STAGING_DIR}/${f}"
done

# ---- Move into app internal storage via run-as --------------------------------

echo ""
echo "📦  Installing into app internal storage via run-as ${PACKAGE} …"
adb shell "run-as ${PACKAGE} mkdir -p ${INTERNAL_MODEL_DIR}"

for f in "${MODEL_FILES[@]}"; do
  adb shell "run-as ${PACKAGE} cp ${STAGING_DIR}/${f} ${INTERNAL_MODEL_DIR}/${f}"
  echo "    ✓  ${f}"
done

# ---- Cleanup -----------------------------------------------------------------

adb shell "rm -rf ${STAGING_DIR}"

echo ""
echo "✅  All model files installed to ${INTERNAL_MODEL_DIR}"
echo "    Open the Parakeetype app - the model is ready, no download needed."

