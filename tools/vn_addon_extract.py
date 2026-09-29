"""Extract candidate villager dialogue lines and animation metadata from the
Villager News 1.0 Add-On (extracted copy under /tmp/vn_addon or given root).

Usage: python tools/vn_addon_extract.py [addon_root]
"""
import re
import sys
import json
import os

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vn_addon"
if os.name == "nt" and not os.path.isdir(ROOT):
    ROOT = os.path.join(os.environ.get("TEMP", "/tmp"), "vn_addon")

BP = os.path.join(ROOT, "Villager News 1.0 Add-On BP")
RP = os.path.join(ROOT, "Villager News 1.0 Add-On RP")


def dialogue_candidates():
    p = os.path.join(BP, "scripts", "oreville", "ebi.js")
    s = open(p, encoding="utf-8", errors="replace").read()
    strs = re.findall(r'"((?:[^"\\]|\\.){12,300})"', s)
    out, seen = [], set()
    for t in strs:
        if t in seen:
            continue
        seen.add(t)
        if not re.search(r"[a-z] [a-z]", t):
            continue
        if re.search(r"[{};=<>]|function|_|\\u00|\.png|\.ogg|animation\.|controller\.", t):
            continue
        if not re.search(r"[.!?]$", t.strip()) and not re.search(r"^\w[\w ]{0,40}$", t):
            continue
        out.append(t)
    return out


def sound_events():
    p = os.path.join(RP, "sounds.json")
    d = json.load(open(p, encoding="utf-8"))
    return d


def main():
    cands = dialogue_candidates()
    print("DIALOGUE CANDIDATES:", len(cands))
    for t in cands[:120]:
        print(" |", t)
    try:
        sounds = sound_events()
        print("\nSOUND EVENT COUNT:", len(sounds))
        for k in list(sounds)[:20]:
            print(" *", k, str(sounds[k])[:120])
    except Exception as e:
        print("sounds.json:", e)


if __name__ == "__main__":
    main()
