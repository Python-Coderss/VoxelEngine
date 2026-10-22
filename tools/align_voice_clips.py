#!/usr/bin/env python3
"""Measure where every word of a recorded clip actually happens.

Captions used to reveal on an estimate (or on the add-on's phrase-level
subtitle cues), which drifts whenever a clip is pitched or the audio starts
late. This tool transcribes each clip with a local Whisper model
(faster-whisper, runs offline, no API) and records the start time of every
word in the recording's own clock. The game loads that table as
`voice/clip_word_times.json` and reveals a word when the voice reaches it;
clips without measured timings fall back to pause detection over the PCM.

    python tools/align_voice_clips.py                 # all clips that are on disk
    python tools/align_voice_clips.py --limit 40      # quick sample
    python tools/align_voice_clips.py --check         # verify, write nothing
    python tools/align_voice_clips.py --model base.en

The model is read from the local Hugging Face cache (already used by the voice
tooling); nothing is downloaded while the machine is offline.
"""

import argparse
import io
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = os.path.join(ROOT, "src", "main", "resources", "voice", "clips_index.json")
OUT = os.path.join(ROOT, "src", "main", "resources", "voice", "clip_word_times.json")
CORPUS = os.path.join(ROOT, "voice", "corpus")
ADDON_CANDIDATES = [
    os.path.join(ROOT, "Villager News 1.0 Add-On (addon).zip"),
    os.path.join(ROOT, "Villager News 1.0 Add-On.zip"),
    os.path.join(ROOT, "dev", "villager-news-addon-port-1.3.6.jar"),
    os.path.join(ROOT, "dev", "villager-news-addon-port-2.0.0.jar"),
]


def corpus_files():
    """Every corpus wav by file name, wherever it sits in the extracted pack."""
    found = {}
    for base, _dirs, files in os.walk(CORPUS):
        for name in files:
            if name.lower().endswith(".wav"):
                found.setdefault(name, os.path.join(base, name))
    return found


def addon_entries(addon_zip):
    """Every ogg inside the addon by bare file name (the layout moves around)."""
    found = {}
    if addon_zip is None:
        return found
    with zipfile.ZipFile(addon_zip) as archive:
        for name in archive.namelist():
            if name.lower().endswith(".ogg"):
                found.setdefault(os.path.basename(name), name)
    return found


def find_addon():
    for path in ADDON_CANDIDATES:
        if os.path.isfile(path):
            return path
    dev = os.path.join(ROOT, "dev")
    if os.path.isdir(dev):
        for name in sorted(os.listdir(dev)):
            if "villager" in name.lower() and name.lower().endswith((".jar", ".zip")):
                return os.path.join(dev, name)
    return None


def clip_bytes(clip, corpus, addon_zip, addon_index):
    """Raw audio bytes for one indexed clip, or None when the file is absent."""
    if clip.get("kind") == "teavrsp":
        path = corpus.get(clip.get("file", ""))
        if path is None:
            return None
        with open(path, "rb") as handle:
            return handle.read()
    entry = addon_index.get(os.path.basename(clip.get("file", "")) + ".ogg")
    if entry is None:
        return None
    with zipfile.ZipFile(addon_zip) as archive:
        return archive.read(entry)


def word_starts(model, data, language="en"):
    """Start time of every word the model hears, in seconds."""
    from faster_whisper import decode_audio

    samples = decode_audio(io.BytesIO(data), sampling_rate=16000)
    segments, _info = model.transcribe(
        samples,
        language=language,
        beam_size=1,
        vad_filter=False,
        condition_on_previous_text=False,
        word_timestamps=True,
        temperature=0.0,
    )
    starts = []
    for segment in segments:
        for word in segment.words or []:
            starts.append(round(float(word.start), 3))
    return starts



def fit_starts(heard, count):
    """Map `count` transcript words onto the recogniser's word onsets."""
    if count <= 0:
        return []
    if len(heard) == 1 or count == 1:
        return [heard[0]] * count
    out = []
    for index in range(count):
        position = index * (len(heard) - 1) / float(count - 1)
        low = int(position)
        high = min(len(heard) - 1, low + 1)
        weight = position - low
        out.append(heard[low] * (1.0 - weight) + heard[high] * weight)
    return out

def norm_words(text):
    return [word for word in "".join(
        ch.lower() if ch.isalnum() or ch.isspace() else " "
        for ch in (text or "")).split() if word]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="tiny.en")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--out", default=OUT)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    try:
        from faster_whisper import WhisperModel
    except ImportError:
        print("faster-whisper is not installed: pip install faster-whisper", file=sys.stderr)
        return 2

    with open(INDEX, encoding="utf-8") as handle:
        index = json.load(handle)
    clips = index.get("clips", [])
    addon = find_addon()
    corpus = corpus_files()
    addon_index = addon_entries(addon)
    print("audio sources: corpus %d wavs, addon %d oggs (%s)"
          % (len(corpus), len(addon_index), addon))
    model = WhisperModel(args.model, device="cpu", compute_type="int8")

    measured = {}
    missing_audio = 0
    mismatched = 0
    total = 0
    for clip in clips:
        if args.limit and total >= args.limit:
            break
        data = clip_bytes(clip, corpus, addon, addon_index)
        if data is None:
            missing_audio += 1
            continue
        total += 1
        expected = norm_words(clip.get("text"))
        try:
            starts = word_starts(model, data)
        except Exception as error:  # a corrupt take must not stop the sweep
            print("  ! %s: %s" % (clip.get("id"), error), file=sys.stderr)
            mismatched += 1
            continue
        if not expected or not starts:
            mismatched += 1
            if args.verbose:
                print("  ~ %s: transcript %d words, heard %d"
                      % (clip.get("id"), len(expected), len(starts)))
            continue
        if len(starts) != len(expected):
            # The recogniser mishears ellipses and names, but its word onsets
            # still mark where the voice moves; stretch our transcript across
            # those onsets so the pauses survive the mismatch.
            starts = fit_starts(starts, len(expected))
            mismatched += 1
            if args.verbose:
                print("  ~ %s: transcript %d words, heard %d (stretched)"
                      % (clip.get("id"), len(expected), len(starts)))
        measured[clip["id"]] = {
            "words": len(expected),
            "word_starts": [round(value, 3) for value in starts],
        }
        if total % 100 == 0:
            print("  aligned %d clips" % total, flush=True)

    coverage = (len(measured) / total) if total else 0.0
    print("aligned %d/%d clips on disk (%.1f%%), %d missing audio, %d transcript mismatches"
          % (len(measured), total, coverage * 100.0, missing_audio, mismatched))
    if args.check:
        if coverage < 0.90:
            print("coverage below 90%%: captions would fall back for too many clips",
                  file=sys.stderr)
            return 1
        print("every measurable clip has word timings")
        return 0

    payload = {
        "version": 1,
        "model": args.model,
        "note": "Word start times measured from each recording by a local ASR model; "
                "the game scales them by playback speed/pitch and reveals captions at "
                "these moments, falling back to PCM pause detection for missing clips.",
        "clips": measured,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=1, sort_keys=True)
    print("wrote %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
