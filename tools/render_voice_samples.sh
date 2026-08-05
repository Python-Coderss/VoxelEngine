#!/usr/bin/env bash
# Render villager voice samples for the spectrogram comparison page.
#
# Usage:
#   bash tools/render_voice_samples.sh
#
# Output: dev/voice-samples/
#   * ref/  — reference corpus clips (transcript-named, sanitized filenames)
#   * gen/  — neural versions of the same lines
#   * 01..06_*.wav — emotion preset clips
#   * index.html — self-contained player + spectrograms built by
#     tools/build_voice_samples_page.py (WAVs base64-inlined; works from file://)
#
# The first run assembles the ~545 MB model bundle into dev/voice-models/ and
# warms up the ONNX sessions, so give it a few minutes.
set -euo pipefail
cd "$(dirname "$0")/.."

# Comparison samples should reflect the current neural pipeline by default.
# Use --reuse only when deliberately inspecting an older render.
OVERWRITE=1
if [ "${1:-}" = "--reuse" ]; then OVERWRITE=0; fi
if [ "${1:-}" = "--overwrite" ]; then OVERWRITE=1; fi

OUT="dev/voice-samples"
REF_SRC="voice/corpus/assets/minecraft/sounds/mob/villager"
mkdir -p "$OUT/gen" "$OUT/ref"

if [ ! -f target/cp.txt ]; then
  echo "Building classpath..."
  ./mvnw -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
    -Dmdep.outputFile=target/cp.txt
fi
CP="target/classes;$(tr -d '\r' < target/cp.txt)"

sanitize() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' \
    | sed 's/[^a-z0-9]\+/_/g; s/^_\+//; s/_\+$//' | head -c 60
}

# ---------------------------------------------------------------------------
# 1. Copy every reference corpus clip with a sanitized filename.
# ---------------------------------------------------------------------------
for src in "$REF_SRC"/*.wav; do
  id="$(sanitize "$(basename "$src" .wav)")"
  # Copy only; never decode, normalize, or overwrite the source corpus.
  cp "$src" "$OUT/ref/$id.wav"
done
echo "copied $(ls "$OUT"/ref/*.wav | wc -l) reference clips"

# Remove stale generated formant artifacts only. The reference corpus above is
# deliberately untouched, and the neural outputs are the sole generated target.
rm -f "$OUT"/gen/*_formant.wav "$OUT"/gen/*_neural_aligned.wav "$OUT"/06_formant_fallback.wav

# ---------------------------------------------------------------------------
# 2. Render neural versions of the same lines for comparison, then create
# time-aligned analysis copies. The raw neural clips remain available, while
# *_aligned.wav are the comparison-only versions; refs are never changed.
# Existing neural comparison clips are refreshed by default; --reuse skips them.
# ---------------------------------------------------------------------------
render_if_needed() {
  local outfile="$1"; shift
  if [ "$OVERWRITE" -eq 0 ] && [ -f "$outfile" ]; then return 0; fi
  java -cp "$CP" villager.voice.Main "$@" -o "$outfile" >/dev/null
}
MATCHED=(
  "I am haggling you"
  "Hello"
  "No!!!"
  "Aha that seems like a deal that will work for both of us"
  "What are you doing"
  "Its a nice day we re having"
)
for text in "${MATCHED[@]}"; do
  id="$(sanitize "$text")"
  render_if_needed "$OUT/gen/${id}_neural.wav" "$text"
  if [ -f "$OUT/ref/$id.wav" ]; then
    python tools/align_neural_to_reference.py \
      "$OUT/ref/$id.wav" "$OUT/gen/${id}_neural.wav" \
      "$OUT/gen/${id}_neural_aligned.wav" >/dev/null
  fi
  echo "matched $id"
done

# ---------------------------------------------------------------------------
# 3. Emotion preset clips (all neural, spoken by default).
# ---------------------------------------------------------------------------
render_if_needed "$OUT/01_default_spoken.wav" "The shop is open. Let us make a fair deal."
render_if_needed "$OUT/02_daytime_happy.wav" "A good morning for planting, hmm." --emotion happy
render_if_needed "$OUT/03_night_neutral.wav" "It is late. You should find a bed, hmm."
render_if_needed "$OUT/04_angry_no.wav" "No!!!" --emotion angry
render_if_needed "$OUT/05_singing_profile.wav" \
  "Aha that seems like a deal that will work for both of us" --emotion happy --singing 1.0
echo "presets done"

# ---------------------------------------------------------------------------
# 4. Build the spectrogram comparison page.
# ---------------------------------------------------------------------------
python tools/build_voice_samples_page.py
echo "Player page: $OUT/index.html ($(du -h "$OUT/index.html" | cut -f1))"
