#!/usr/bin/env bash
# Builds the single-file Parakeet Ultra model archive that users download in their browser
# and import in Parakeetype (the app itself has no network access), from
# https://huggingface.co/mldecode/parakeet-ultra-onnx-int8.
#
#   devtools/package-model.sh [output-dir]
#
# Downloads the model files from Hugging Face, verifies each against the SHA-256 pinned in
# ModelRegistry.kt, and
# packs them into parakeet-ultra-int8.zip (stored, not compressed: the int8 ONNX weights do
# not compress, and stored entries make the on-device import a straight copy).
#
# The export is a sherpa-onnx bundle (separate decoder.int8.onnx and joiner.int8.onnx,
# 640-dim encoder frames). It has no nemo128.onnx preprocessor (sherpa computes the
# features natively); the front end matches Parakeet TDT 0.6B v3 (128 mel bins,
# per-feature normalisation), so the nemo128.onnx of istupakov's v3 ONNX export is packed
# alongside. tokens.txt is packed as vocab.txt.
#
# The archive also carries the Silero VAD v4 model (silero_vad_v4.onnx, MIT, from
# https://github.com/snakers4/silero-vad at tag v4.0), which the app's voice activity
# detection loads from the installed model directory, so no model weights live in the
# app's source tree.
#
# The model is licensed CC BY 4.0, which only allows redistribution together with the
# licence and an attribution notice, so the archive also contains LICENSE.txt (the full
# licence text) and NOTICE.txt (creators, sources, changes) from devtools/licenses/, plus
# LICENSE-silero-vad.txt (the MIT notice the Silero VAD must be distributed with).
# ModelImporter skips entries that are not model files.
#
# Upload the result as a release asset of the release MODEL_ARCHIVE_RELEASE in
# ModelRegistry.kt points at (https://github.com/theScrabi/Parakeetype/releases/tag/v0.4).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Pinned revisions, so the hashes below keep matching when the repos are updated.
ULTRA_BASE="https://huggingface.co/mldecode/parakeet-ultra-onnx-int8/resolve/3282a6e32885b431c1543d58c7710e6e3412eac0"
V3_BASE="https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"
SILERO_BASE="https://raw.githubusercontent.com/snakers4/silero-vad/915dd3d639b8333a52e001af095f87c5b7f1e0ac/files"
OUT_DIR="${1:-.}"
ARCHIVE="parakeet-ultra-int8.zip"

# base  remote name  archive name  sha256
FILES=(
  "$ULTRA_BASE encoder.int8.onnx encoder.int8.onnx 181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"
  "$ULTRA_BASE decoder.int8.onnx decoder.int8.onnx 0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"
  "$ULTRA_BASE joiner.int8.onnx  joiner.int8.onnx  20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"
  "$ULTRA_BASE tokens.txt        vocab.txt         d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
  "$V3_BASE    nemo128.onnx      nemo128.onnx      a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"
  "$SILERO_BASE silero_vad.onnx  silero_vad_v4.onnx a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

names=()
for entry in "${FILES[@]}"; do
  read -r base remote name sha <<<"$entry"
  echo "Downloading $remote…"
  curl -fL --retry 3 -o "$WORK/$name" "$base/$remote"
  echo "$sha  $WORK/$name" | sha256sum -c --quiet -
  names+=("$name")
done

cp "$SCRIPT_DIR/licenses/CC-BY-4.0.txt" "$WORK/LICENSE.txt"
cp "$SCRIPT_DIR/licenses/parakeet-ultra-NOTICE.txt" "$WORK/NOTICE.txt"
cp "$SCRIPT_DIR/licenses/silero-vad-LICENSE.txt" "$WORK/LICENSE-silero-vad.txt"
names+=(LICENSE.txt NOTICE.txt LICENSE-silero-vad.txt)

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR/$ARCHIVE"
(cd "$WORK" && zip -0 -X -q "$ARCHIVE" "${names[@]}")
mv "$WORK/$ARCHIVE" "$OUT_DIR/$ARCHIVE"

echo
echo "Created $OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
