#!/usr/bin/env python3
"""Bind every Villager TV script line to a real recording.

The TV broadcasts from exactly one collection: the Villager News 1.0 Add-On
recordings indexed in ``voice/clips_index.json`` with ``kind == "addon"``.
Every scripted line in ``voice/tv_guide.json`` is therefore written as the
transcript of an actual take, and this tool pins the line to that take:

  * a line is pinned to the addon clip whose transcript is the caption, so what
    the screen shows is what you hear;
  * the same recording is not reused for two lines while another take of the
    same caption is still free;
  * a line with no matching recording keeps no ``clip`` and falls back at
    runtime to the addon-only situation match -- there is no other collection
    to fall back to.

Deterministic and idempotent: running it twice produces the same file.

Usage:  python3 tools/bind_tv_clips.py [--check]

``--check`` verifies the guide without rewriting it: every pinned clip must be
an addon clip whose transcript is the line's caption. Prints the number of
lines still relying on the runtime fallback and exits non-zero on any bad pin.
"""

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "src/main/resources/voice/clips_index.json"
GUIDE = ROOT / "src/main/resources/voice/tv_guide.json"

# The one collection the TV is allowed to broadcast.
ADDON_KIND = "addon"

CLIPS_NOTE = (
    "Every line's \"clip\" is an id from voice/clips_index.json with "
    "kind=\"addon\" (Villager News 1.0 Add-On) whose transcript is the line's "
    "caption. Re-pin with tools/bind_tv_clips.py."
)


def norm(text):
    """Lowercase, strip punctuation, collapse whitespace."""
    text = unicodedata.normalize("NFKC", text or "").lower()
    text = re.sub(r"[^a-z0-9 ]+", " ", text)
    return " ".join(text.split())


def load_addon_clips():
    """Addon clips by normalized transcript, each list sorted by id."""
    raw = json.loads(INDEX.read_text(encoding="utf-8"))
    by_norm = {}
    ids = {}
    for entry in raw["clips"]:
        if entry.get("kind") != ADDON_KIND:
            continue
        text = entry.get("text") or ""
        ids[entry["id"]] = norm(text)
        key = norm(text)
        if key:
            by_norm.setdefault(key, []).append(entry["id"])
    for key in by_norm:
        by_norm[key].sort()
    return ids, by_norm


def lines_of(guide):
    """Yield every scripted line object, in file order."""
    for channel in guide["channels"]:
        for programme in channel["programmes"]:
            for line in programme["lines"]:
                yield line


def bind(guide, by_norm, rewrite=True):
    """Pin each line to a recording of its caption; return (pinned, unpinned)."""
    used = set()
    pinned = 0
    unpinned = []
    for line in lines_of(guide):
        caption = line.get("text", "")
        if not caption:
            continue
        candidates = by_norm.get(norm(caption), [])
        chosen = None
        for clip in candidates:
            if clip not in used:
                chosen = clip
                break
        if chosen is None and candidates:
            chosen = candidates[0]        # every take of this caption is used
        if chosen is None:
            if "clip" in line and rewrite:
                del line["clip"]
            unpinned.append(caption)
            continue
        used.add(chosen)
        pinned += 1
        if rewrite:
            line["clip"] = chosen
    return pinned, unpinned


def check(guide, ids):
    """Return (total lines, unpinned lines, list of problems)."""
    problems = []
    unpinned = []
    total = 0
    for line in lines_of(guide):
        caption = line.get("text", "")
        if not caption:
            continue
        total += 1
        clip = line.get("clip")
        if clip is None:
            unpinned.append(caption)
            continue
        if clip not in ids:
            problems.append(f"'{caption[:44]}' -> '{clip}' is not an addon clip")
        elif ids[clip] != norm(caption):
            problems.append(f"'{caption[:44]}' -> '{clip}' does not say that")
    return total, unpinned, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the guide without rewriting it")
    args = parser.parse_args()

    ids, by_norm = load_addon_clips()
    guide = json.loads(GUIDE.read_text(encoding="utf-8"))

    if args.check:
        total, unpinned, problems = check(guide, ids)
        print(f"{INDEX.name}: {len(ids)} addon recordings")
        print(f"{GUIDE.name}: {total} scripted lines")
        for problem in problems:
            print(f"  FAIL {problem}")
        if unpinned:
            print(f"  {len(unpinned)} lines have no matching recording and use "
                  f"the addon-only situation fallback:")
            for caption in unpinned[:10]:
                print(f"    - {caption[:70]}")
        else:
            print("  every line is pinned to a recording of its caption")
        print("OK" if not problems else "FAILED")
        return 0 if not problems else 1

    pinned, unpinned = bind(guide, by_norm, rewrite=True)
    guide["_clips"] = CLIPS_NOTE
    GUIDE.write_text(json.dumps(guide, indent=2, ensure_ascii=False) + "\n",
                     encoding="utf-8")
    print(f"pinned {pinned} lines to their own addon recordings")
    print(f"{len(unpinned)} lines have no matching recording and keep the "
          f"addon-only situation fallback")
    for caption in unpinned[:10]:
        print(f"  - {caption[:70]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
