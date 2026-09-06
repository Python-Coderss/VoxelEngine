#!/usr/bin/env bash
# Batch-convert a folder of uncompressed audio (WAV/FLAC/AIFF/M4A) into the
# mono MP3 playlist the game plays from src/main/resources/music.
#
#   usage:  tools/convert_music.sh <input-dir> [output-dir]
#
# The game only needs one speaker, so everything is downmixed to mono
# (-ac 1) and compressed to 96 kbps MP3 — a ~4 GB WAV collection lands at
# roughly a tenth of the size with no audible loss for game ambience.
#
# Requires ffmpeg on PATH (https://ffmpeg.org). Example:
#   tools/convert_music.sh ~/Downloads/MCSM-ripped ~/Downloads/game-music
#   cp ~/Downloads/game-music/*.mp3 src/main/resources/music/

set -euo pipefail

SRC="${1:?usage: tools/convert_music.sh <input-dir> [output-dir]}"
OUT="${2:-src/main/resources/music}"

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg not found on PATH — install it first (https://ffmpeg.org)" >&2
    exit 1
fi
if [[ ! -d "$SRC" ]]; then
    echo "input directory not found: $SRC" >&2
    exit 1
fi

mkdir -p "$OUT"
count=0
skipped=0
while IFS= read -r -d '' f; do
    base="$(basename "$f")"
    base="${base%.*}"
    out="$OUT/$base.mp3"
    if [[ -f "$out" ]]; then
        skipped=$((skipped + 1))
        continue
    fi
    ffmpeg -hide_banner -loglevel error -y -i "$f" \
        -ac 1 -ar 44100 -codec:a libmp3lame -b:a 96k "$out"
    echo "converted: $base.mp3 ($(du -h "$out" | cut -f1))"
    count=$((count + 1))
done < <(find "$SRC" -type f \( -iname '*.wav' -o -iname '*.flac' -o -iname '*.m4a' \
        -o -iname '*.aiff' -o -iname '*.aif' -o -iname '*.ogg' \) -print0 | sort -z)

echo "done: $count converted, $skipped already present -> $OUT"
