#!/usr/bin/env bash
# Downloads the speech engine and the on-device AI models into the app, from their original
# publishers. Run once before building:  ./scripts/download-models.sh   (add --voxlock for the
# optional 80 MB voice-isolation model). Works in Git Bash on Windows, macOS and Linux.
set -euo pipefail
cd "$(dirname "$0")/.."

R=https://github.com/k2-fsa/sherpa-onnx/releases/download
A=app/src/main/assets
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p app/libs "$A/denoise" "$A/vad" "$A/final" "$A/speaker" "$A/kws"

get() { echo "  $2"; curl -fsSL -o "$2" "$1"; }

echo "Speech engine (sherpa-onnx 2.0.1, Apache-2.0)"
get "$R/v2.0.1/sherpa-onnx-2.0.1.aar" app/libs/sherpa-onnx-2.0.1.aar

echo "Noise removal (GTCRN, DeepFilterNet-based DPDFNet)"
get "$R/speech-enhancement-models/gtcrn_simple.onnx" "$A/denoise/gtcrn_simple.onnx"
get "$R/speech-enhancement-models/dpdfnet2.onnx" "$A/denoise/dpdfnet2.onnx"

echo "Speech detection (Silero VAD)"
get "$R/asr-models/silero_vad.onnx" "$A/vad/silero_vad.onnx"

echo "Voice ID (NVIDIA TitaNet small)"
get "$R/speaker-recongition-models/nemo_en_titanet_small.onnx" "$A/speaker/titanet_small_en.onnx"

echo "Speech to text (NVIDIA Parakeet TDT-CTC 110M, int8)"
P=sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8
get "$R/asr-models/$P.tar.bz2" "$TMP/p.tar.bz2"
tar -xjf "$TMP/p.tar.bz2" -C "$TMP"
cp "$TMP/$P/model.int8.onnx" "$A/final/model.int8.onnx"
cp "$TMP/$P/tokens.txt" "$A/final/tokens.txt"

echo "Wake words (keyword spotter, GigaSpeech 3.3M)"
K=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
get "$R/kws-models/$K.tar.bz2" "$TMP/k.tar.bz2"
tar -xjf "$TMP/k.tar.bz2" -C "$TMP"
cp "$TMP/$K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/encoder.int8.onnx"
cp "$TMP/$K/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/decoder.int8.onnx"
cp "$TMP/$K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/joiner.int8.onnx"
cp "$TMP/$K/tokens.txt" "$A/kws/tokens.txt"

if [[ "${1:-}" == "--voxlock" ]]; then
  echo "Optional: voice isolation (VoxLock)"
  mkdir -p "$A/voxlock"
  get "$R/speech-enhancement-models/sherpa-onnx-voxlock-int8.tar.bz2" "$TMP/v.tar.bz2"
  tar -xjf "$TMP/v.tar.bz2" -C "$TMP"
  cp "$TMP/sherpa-onnx-voxlock-int8/enroll.onnx" "$TMP/sherpa-onnx-voxlock-int8/chunk_int8.onnx" "$A/voxlock/"
fi

echo "Done."
