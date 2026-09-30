#!/usr/bin/env bash
# Builds the single-file Parakeet-V3 model archive that users download in their browser
# and import in Parakeetype (the app itself has no network access).
#
#   devtools/package-model.sh [output-dir]
#
# Downloads the five model files from Hugging Face, verifies each against the SHA-256
# pinned in ModelRegistry.kt, and packs them into parakeet-tdt-0.6b-v3-int8.zip
# (stored, not compressed: the int8 ONNX weights do not compress, and stored entries make
# the on-device import a straight copy).
#
# The model is licensed CC BY 4.0, which only allows redistribution together with the
# licence and an attribution notice, so the archive also contains LICENSE.txt (the full
# licence text) and NOTICE.txt (creator, source, changes) from devtools/licenses/.
# ModelImporter skips entries that are not model files.
#
# Upload the result as a release asset of the release MODEL_ARCHIVE_RELEASE in
# ModelRegistry.kt points at (https://github.com/theScrabi/Parakeetype/releases/tag/v0.4).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"
OUT_DIR="${1:-.}"
ARCHIVE="parakeet-tdt-0.6b-v3-int8.zip"

# filename  sha256 (must match ModelRegistry.kt)
FILES=(
  "encoder-model.int8.onnx       6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"
  "decoder_joint-model.int8.onnx eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"
  "nemo128.onnx                  a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"
  "config.json                   666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466"
  "vocab.txt                     d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

names=()
for entry in "${FILES[@]}"; do
  read -r name sha <<<"$entry"
  echo "Downloading $name…"
  curl -fL --retry 3 -o "$WORK/$name" "$BASE/$name"
  echo "$sha  $WORK/$name" | sha256sum -c --quiet -
  names+=("$name")
done

cp "$SCRIPT_DIR/licenses/CC-BY-4.0.txt" "$WORK/LICENSE.txt"
cp "$SCRIPT_DIR/licenses/parakeet-tdt-0.6b-v3-NOTICE.txt" "$WORK/NOTICE.txt"
names+=(LICENSE.txt NOTICE.txt)

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR/$ARCHIVE"
(cd "$WORK" && zip -0 -X -q "$ARCHIVE" "${names[@]}")
mv "$WORK/$ARCHIVE" "$OUT_DIR/$ARCHIVE"

echo
echo "Created $OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
