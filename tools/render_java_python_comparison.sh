#!/usr/bin/env bash
# Render identical lines through one persistent Java process and Python.
# Outputs stay separate from references and the game's voice cache.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="dev/voice-comparison"
JAVA_OUT="$OUT/java"
PYTHON_OUT="$OUT/python"
OVERWRITE=1
if [ "${1:-}" = "--reuse" ]; then OVERWRITE=0; fi
mkdir -p "$JAVA_OUT" "$PYTHON_OUT"

if [ ! -f target/cp.txt ]; then
  ./mvnw -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=target/cp.txt
fi
CP="target/classes;$(tr -d '\r' < target/cp.txt)"
PY="../villager_voice/python/.venv/Scripts/python.exe"
if [ ! -x "$PY" ]; then echo "Missing Python environment: $PY" >&2; exit 1; fi

sanitize() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' \
    | sed 's/[^a-z0-9]\+/_/g; s/^_\+//; s/_\+$//' | head -c 60
}

LINES=(
  "Hello"
  "I am haggling you"
  "No!!!"
  "What are you doing"
  "Aha that seems like a deal that will work for both of us"
)

LINES_JSON="$OUT/lines.json"
python - "$LINES_JSON" <<'PY'
import json, sys
lines = [
 ('hello', 'Hello'),
 ('i_am_haggling_you', 'I am haggling you'),
 ('no', 'No!!!'),
 ('what_are_you_doing', 'What are you doing'),
 ('aha_that_seems_like_a_deal_that_will_work_for_both_of_us',
  'Aha that seems like a deal that will work for both of us'),
]
json.dump([{'id': i, 'text': t} for i, t in lines], open(sys.argv[1], 'w', encoding='utf-8'))
PY

# One JVM loads every neural model once. Each line is timed inside Main and
# fails if synthesis itself exceeds 30 seconds.
JAVA_BATCH_OUT="$JAVA_OUT" 
if [ "$OVERWRITE" -eq 1 ]; then
  java -cp "$CP" villager.voice.Main --models models/java --lines "$LINES_JSON" \
    --outdir "$JAVA_OUT" --overwrite
else
  java -cp "$CP" villager.voice.Main --models models/java --lines "$LINES_JSON" \
    --outdir "$JAVA_OUT"
fi

for text in "${LINES[@]}"; do
  id="$(sanitize "$text")"
  out="$PYTHON_OUT/${id}.wav"
  if [ "$OVERWRITE" -eq 0 ] && [ -s "$out" ]; then continue; fi
  echo "Python: $text"
  timeout 30 "$PY" ../villager_voice/python/villager_line.py "$text" -o "$out"
done
python tools/build_java_python_comparison.py "$OUT"
echo "Comparison page: $OUT/index.html"
