#!/usr/bin/env bash
# Downloads the model the real-audio JVM tests need; without it those tests skip themselves
# (JUnit Assume) and `./gradlew test` runs only the model-free suite.
#
#   devtools/fetch-test-model.sh
#
# Fetches the Parakeet Ultra model archive (parakeet-ultra-int8.zip, built by
# devtools/package-model.sh; ~630 MB, includes the Silero VAD) from the pinned release page
# and unpacks it into ~/.cache/parakeetype-test-model/parakeet-ultra/ (see
# RealAudioTestUtils.resolveModelDir), verifying every file against its pinned SHA-256.
#
# The speech WAV fixtures (app/src/test/resources/audio/) are internal development data:
# they are neither in the repository nor published, and tests that need them skip
# themselves when they are missing.
set -euo pipefail

RELEASE="https://github.com/theScrabi/Parakeetype/releases/download/v0.4"

MODEL_ZIP="parakeet-ultra-int8.zip"
MODEL_DIR="$HOME/.cache/parakeetype-test-model/parakeet-ultra"
# file  sha256 — same as the parakeetUltra entry in ModelRegistry.kt
MODEL_FILES=(
  "encoder.int8.onnx  181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"
  "decoder.int8.onnx  0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"
  "joiner.int8.onnx   20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"
  "nemo128.onnx       a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"
  "vocab.txt          d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
  "silero_vad_v6.onnx 1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $MODEL_ZIP…"
curl -fL --retry 3 -o "$WORK/$MODEL_ZIP" "$RELEASE/$MODEL_ZIP"
mkdir -p "$WORK/model"
unzip -q "$WORK/$MODEL_ZIP" -d "$WORK/model"
for entry in "${MODEL_FILES[@]}"; do
  read -r name sha <<<"$entry"
  echo "$sha  $WORK/model/$name" | sha256sum -c --quiet -
done
mkdir -p "$MODEL_DIR"
for entry in "${MODEL_FILES[@]}"; do
  read -r name _ <<<"$entry"
  mv "$WORK/model/$name" "$MODEL_DIR/$name"
done
echo "Model in $MODEL_DIR"
