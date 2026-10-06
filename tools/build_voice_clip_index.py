"""Build the runtime voice-clip index (no synthesis, clips only).

Sources
-------
1. Villager News 1.0 Add-On (addon).zip
   - RP/sounds/sound_definitions.json  : sound event -> ogg file under sounds/
   - BP/scripts/oreville/ebi.js        : dialog segments pairing soundId with
                                         timed subtitle cues (text.oreville_vn.*)
   - RP/texts/en_US.lang               : subtitle key -> spoken text
2. TEAVSRP corpus (voice/corpus/assets/minecraft/sounds/mob/villager/*.wav)
   Clips are named after their transcripts.

Output: src/main/resources/voice/clips_index.json with entries
    {"id": "...", "kind": "addon"|"teavrsp", "file": "...", "text": "...",
     "duration": seconds}

Run:  python tools/build_voice_clip_index.py [--zip PATH] [--corpus DIR]
"""

import argparse
import json
import os
import re
import struct
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ZIP = os.path.join(ROOT, "Villager News 1.0 Add-On (addon).zip")
DEFAULT_CORPUS = os.path.join(ROOT, "voice", "corpus", "assets", "minecraft",
                              "sounds", "mob", "villager")
OUTPUT = os.path.join(ROOT, "src", "main", "resources", "voice", "clips_index.json")

SOUND_DIR = "sounds/oreville/vn"


def lang_texts(raw):
    """Parse en_US.lang into key -> text (trailing \\t# comments stripped)."""
    out = {}
    for line in raw.decode("utf-8", "replace").splitlines():
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.replace("\t#", "").strip()
        out[key.strip()] = value
    return out


def parse_sound_definitions(raw):
    """sound event id -> list of clip basenames (no extension)."""
    data = json.loads(raw.decode("utf-8", "replace"))
    defs = data.get("sound_definitions", data)
    out = {}
    for event, spec in defs.items():
        names = []
        for entry in spec.get("sounds", []) if isinstance(spec, dict) else []:
            name = entry.get("name", entry) if isinstance(entry, dict) else entry
            name = str(name).replace("\\", "/")
            if name.startswith(SOUND_DIR + "/"):
                name = name[len(SOUND_DIR) + 1:]
            names.append(os.path.splitext(os.path.basename(name))[0])
        if names:
            out[event] = names
    return out


def parse_ebi_dialogs(raw, texts):
    """sound event id -> (subtitle cues, duration) harvested from ebi.js.

    Dialog segments look like (minified):
      {animationName:"...",soundId:"oreville_vn:qosovr",duration:1.81,
       weight:.5,aswuwr:{0:{ysyeto:"text.oreville_vn.ovgjjw"},
       .87:{ysyeto:"text.oreville_vn.ynuimn"}}}
    Subtitle cues inside aswuwr are time-stamped and source-ordered; each is
    kept as (timeSeconds, fragmentText) so the game can reveal captions on the
    addon's own timing.
    """
    src = raw.decode("utf-8", "replace")
    marks = [m for m in re.finditer(r'soundId:"([^"]+)"', src)]
    out = {}
    for i, m in enumerate(marks):
        event = m.group(1)
        seg_end = marks[i + 1].start() if i + 1 < len(marks) else len(src)
        segment = src[m.end():seg_end]

        duration = 0.0
        dm = re.search(r"duration:([0-9]*\.?[0-9]+)", segment)
        if dm:
            duration = float(dm.group(1))

        entries = []
        am = re.search(r"aswuwr:", segment)
        if am:
            # Brace-match the cue table, then pull (time, subtitle) in order.
            depth = 0
            start = segment.index("{", am.end())
            end = start
            for end in range(start, len(segment)):
                c = segment[end]
                if c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        break
            cue_block = segment[start:end + 1]
            for cm in re.finditer(
                    r'([0-9]*\.?[0-9]+):\{ysyeto:"(text\.[A-Za-z0-9_.:-]+)"',
                    cue_block):
                line = texts.get(cm.group(2), "")
                if line:
                    entries.append([round(float(cm.group(1)), 3), line])
        entries.sort(key=lambda cue: cue[0])

        if event not in out or (entries and not out[event][0]):
            out[event] = (entries, duration)
    return out


def wav_duration(path):
    """Duration in seconds from a PCM WAV header (RIFF/data sizes)."""
    with open(path, "rb") as fh:
        head = fh.read(12)
        if len(head) < 12 or head[:4] != b"RIFF":
            return 0.0
        channels, rate, byte_rate = 0, 0, 0
        bits = 16
        data_size = 0
        while True:
            chunk = fh.read(8)
            if len(chunk) < 8:
                break
            cid, size = struct.unpack("<4sI", chunk)
            if cid == b"fmt ":
                fmt = fh.read(size)
                channels, rate, _, _, bits = struct.unpack("<HHIIH", fmt[2:16])
            elif cid == b"data":
                data_size = size
                fh.seek(size, os.SEEK_CUR)
            else:
                fh.seek(size + (size & 1), os.SEEK_CUR)
        if not rate or not channels:
            return 0.0
        return data_size / float(channels * (bits / 8.0) * rate)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", default=DEFAULT_ZIP)
    ap.add_argument("--corpus", default=DEFAULT_CORPUS)
    ap.add_argument("--output", default=OUTPUT)
    args = ap.parse_args()

    entries = []

    def build_entry(eid, kind, fname, cues, duration):
        text = " ".join(fragment for _, fragment in cues).strip()
        return {
            "id": eid,
            "kind": kind,
            "file": fname,
            "text": text,
            "duration": round(duration, 3),
            "cues": cues,
        }

    with zipfile.ZipFile(args.zip) as z:
        rp = "Villager News 1.0 Add-On RP/"
        bp = "Villager News 1.0 Add-On BP/"
        texts = lang_texts(z.read(rp + "texts/en_US.lang"))
        definitions = parse_sound_definitions(z.read(rp + "sounds/sound_definitions.json"))
        dialogs = parse_ebi_dialogs(z.read(bp + "scripts/oreville/ebi.js"), texts)

        ogg_files = {n[len(rp + SOUND_DIR) + 1:-4]: n
                     for n in z.namelist()
                     if n.startswith(rp + SOUND_DIR + "/") and n.endswith(".ogg")}

        for event in sorted(definitions):
            cues, duration = dialogs.get(event, ([], 0.0))
            for name in definitions[event]:
                if name not in ogg_files:
                    continue
                entries.append(build_entry("addon:" + event, "addon", name,
                                           cues, duration))

    if os.path.isdir(args.corpus):
        for fname in sorted(os.listdir(args.corpus)):
            if not fname.lower().endswith(".wav"):
                continue
            stem = os.path.splitext(fname)[0]
            entries.append(build_entry("teavrsp:" + stem, "teavrsp", fname,
                                       [[0.0, stem]],
                                       wav_duration(os.path.join(args.corpus, fname))))

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as out:
        json.dump({"version": 1,
                   "sources": ["Villager News 1.0 Add-On (Oreville Studios & Element Animation)",
                               "TEAVSRP corpus (transcript-renamed)"],
                   "soundDir": SOUND_DIR,
                   "clips": entries}, out, ensure_ascii=False, separators=(",", ":"))
    voiced = sum(1 for e in entries if e["text"])
    print("wrote %s: %d clips (%d with transcripts)" % (args.output, len(entries), voiced))


if __name__ == "__main__":
    main()
