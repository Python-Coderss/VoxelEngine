"""Extract the full Triggers & Reactions handbook (trigger names + descriptions)
and inspect sounds.json keys from the Villager News addon."""
import re
import sys
import json
import os

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vn_addon"
if os.name == "nt" and not os.path.isdir(ROOT):
    ROOT = os.path.join(os.environ.get("TEMP", "/tmp"), "vn_addon")
p = os.path.join(ROOT, "Villager News 1.0 Add-On BP", "scripts", "oreville", "ebi.js")
s = open(p, encoding="utf-8", errors="replace").read()

# Trigger names look like Title Case short labels near 'eTrigger' descriptions.
# Extract every short Title-Case label string.
labels = re.findall(r'"((?:[A-Z][A-Za-z0-9\'’!\-]+[ ,&]?)?[^"\\]{2,44})"', s)
seen = set()
triggers = []
for t in labels:
    tt = t.strip()
    if not tt or tt in seen:
        continue
    seen.add(tt)
    # heuristic: Title Case short label, no sentence punctuation
    if re.match(r"^[A-Z][A-Za-z0-9\'’,&!\- ]{2,44}$", tt) and not re.search(r"[.?:;]", tt) \
            and not re.search(r"^(Choose|Provides|Enable|Use|Browse|Whether|How |Whether)", tt):
        triggers.append(tt)

print("=== LABELS (possible trigger/reaction names) ===")
for t in triggers:
    print(" |", t)

print()
print("=== sounds.json keys (sample) ===")
sp = os.path.join(ROOT, "Villager News 1.0 Add-On RP", "sounds.json")
d = json.load(open(sp, encoding="utf-8"))
ks = list(d.keys())
print("count:", len(ks))
for k in ks[:40]:
    print(" *", k)
