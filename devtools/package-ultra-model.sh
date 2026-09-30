#!/usr/bin/env bash
# Builds the single-file Parakeet Ultra model archive that users download in their browser
# and import in Parakeetype (the app itself has no network access). Experimental — the
# counterpart of package-model.sh for https://huggingface.co/mldecode/parakeet-ultra-onnx-int8.
#
#   devtools/package-ultra-model.sh [output-dir]
#
# Downloads the model files from Hugging Face, verifies each against a pinned SHA-256, and
# packs them into parakeet-ultra-int8.zip (stored, not compressed: the int8 ONNX weights do
# not compress, and stored entries make the on-device import a straight copy).
#
# The export is a sherpa-onnx bundle, so its layout differs from the istupakov v3 export:
#   - decoder and joint network are separate files (decoder.int8.onnx, joiner.int8.onnx)
#     instead of decoder_joint-model.int8.onnx;
#   - the encoder emits 640-dim frames (projection folded in), not 1024-dim;
#   - there is no nemo128.onnx preprocessor (sherpa computes the features natively) and no
#     config.json. The front end matches v3 (128 mel bins, per-feature normalisation), so
#     the v3 nemo128.onnx is packed alongside;
#   - tokens.txt is byte-identical to v3's vocab.txt and is packed under that name.
#
# The model is licensed CC BY 4.0, which only allows redistribution together with the
# licence and an attribution notice, so the archive also contains LICENSE.txt (the full
# licence text) and NOTICE.txt (creators, sources, changes) from devtools/licenses/.
# ModelImporter skips entries that are not model files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Pinned revisions, so the hashes below keep matching when the repos are updated.
ULTRA_BASE="https://huggingface.co/mldecode/parakeet-ultra-onnx-int8/resolve/3282a6e32885b431c1543d58c7710e6e3412eac0"
V3_BASE="https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"
OUT_DIR="${1:-.}"
ARCHIVE="parakeet-ultra-int8.zip"

# base  remote name  archive name  sha256
FILES=(
  "$ULTRA_BASE encoder.int8.onnx encoder.int8.onnx 181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"
  "$ULTRA_BASE decoder.int8.onnx decoder.int8.onnx 0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"
  "$ULTRA_BASE joiner.int8.onnx  joiner.int8.onnx  20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"
  "$ULTRA_BASE tokens.txt        vocab.txt         d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
  "$V3_BASE    nemo128.onnx      nemo128.onnx      a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"
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
names+=(LICENSE.txt NOTICE.txt)

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR/$ARCHIVE"
(cd "$WORK" && zip -0 -X -q "$ARCHIVE" "${names[@]}")
mv "$WORK/$ARCHIVE" "$OUT_DIR/$ARCHIVE"

echo
echo "Created $OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
