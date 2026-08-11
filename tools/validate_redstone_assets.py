#!/usr/bin/env python3
"""Validate the redstone-expansion model JSONs: every texture key must resolve
to a real PNG in the block or item texture directories (the engine throws at
runtime if a face texture is missing)."""
import json
import os
import glob

BLOCKS = "src/main/resources/assets/minecraft/textures/blocks"
ITEMS = "src/main/resources/assets/minecraft/textures/items"
MODELS = "src/main/resources/assets/minecraft/models/block"

block_names = {f[:-4] for f in os.listdir(BLOCKS) if f.endswith(".png")}
item_names = {f[:-4] for f in os.listdir(ITEMS) if f.endswith(".png")}

new_prefixes = ("lamp_", "repeater_", "comparator_", "clutch", "gearshift",
                "item_", "item_drop_")
missing = []
checked = 0
for path in glob.glob(f"{MODELS}/*.json"):
    base = os.path.basename(path)[:-5]
    if not any(base.startswith(p) or base in ("lamp", "comparator", "repeater") for p in new_prefixes):
        continue
    try:
        data = json.load(open(path))
    except Exception as e:
        missing.append(f"{base}: JSON error {e}")
        continue
    if "textures" not in data:
        continue
    checked += 1
    for key, val in data["textures"].items():
        val = val.split("/")[-1].split(":")[-1]
        if val.startswith("#"):
            continue
        if val not in block_names and val not in item_names:
            missing.append(f"{base}.json: texture '{val}' (key {key}) not found")

print(f"checked {checked} new models")
if missing:
    print("MISSING TEXTURES:")
    for m in missing:
        print("  " + m)
else:
    print("all texture references resolve")
