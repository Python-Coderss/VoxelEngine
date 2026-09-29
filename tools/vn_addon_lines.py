"""Dig into ebi.js for reaction line data structures (not UI handbook text)."""
import re
import sys
import json
import os

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vn_addon"
if os.name == "nt" and not os.path.isdir(ROOT):
    ROOT = os.path.join(os.environ.get("TEMP", "/tmp"), "vn_addon")
p = os.path.join(ROOT, "Villager News 1.0 Add-On BP", "scripts", "oreville", "ebi.js")
s = open(p, encoding="utf-8", errors="replace").read()

# 1) Find object keys that look like data tables
for key in ["reaction", "Reaction", "trigger", "Trigger", "line", "Line", "subtitle", "Subtitle",
            "chattiness", "dialogue", "voice", "subtitleText", "text"]:
    n = len(re.findall(r'"?' + key + r'"?\s*:', s))
    print(key, "->", n)

print()
# 2) Subtitle-style entries: short sentences without UI punctuation, not sentences ending in 'period' descriptions
strs = re.findall(r'"((?:[^"\\]|\\.){4,160})"', s)
line_like = []
for t in strs:
    tt = t.strip()
    if not re.search(r"[.!?]$", tt):
        continue
    if re.search(r"[{};=<>\\]|_|\.png|\.ogg|http", tt):
        continue
    # skip description sentences (they explain triggers)
    if re.search(r"^(Choose|Provides|No |Error|Enable|Use |You can|Their |Play |Move |Look |Stay |Shear|Set off|Walk |Jump |Eat |Harvest|Open |Attach|Close|Crouch|Fly |Glide|Teleport|Hold|Wear|Have |Lower|Higher|Most |Not every|Some |Many |Different|Reactions|Villagers|Triggers|Baby|Rare|Wandering|One of)", tt):
        continue
    line_like.append(tt)
print("LINE-LIKE:", len(line_like))
for t in line_like[:100]:
    print(" *", t)
