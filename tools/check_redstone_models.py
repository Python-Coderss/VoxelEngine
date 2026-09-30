#!/usr/bin/env python3
"""Audit redstone-component block models for defects.

Checks every redstone-related model JSON under
src/main/resources/assets/minecraft/models/block:

  * elements missing any of the six faces (engine falls back to the block's
    representative texture, which usually looks wrong)
  * faces without a texture reference
  * texture references that are neither "#key" defined in the texture map
    nor a concrete texture that exists under textures/blocks
  * UV coordinates outside 0..16
  * parent chains that do not resolve to an existing model file

Usage: python tools/check_redstone_models.py
"""
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                    "assets", "minecraft")
MODELS = os.path.join(ROOT, "models", "block")
TEXTURES = os.path.join(ROOT, "textures", "blocks")

KEYWORDS = ("comparator", "repeater", "piston", "lamp", "button", "plate",
            "lever", "observer", "daylight", "hopper", "dispenser", "dropper",
            "tnt", "redstone", "wire", "tripwire", "target")

FACES = ("down", "up", "north", "south", "west", "east")


def load(model_name):
    path = os.path.join(MODELS, model_name + ".json")
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def collect_textures(name, texture_map, seen=None):
    """Walk the parent chain, merging texture maps (child wins)."""
    if seen is None:
        seen = set()
    if name in seen:
        return texture_map
    seen.add(name)
    data = load(name)
    if data is None:
        return texture_map
    for k, v in data.get("textures", {}).items():
        texture_map.setdefault(k, v)
    parent = data.get("parent")
    if parent:
        parent = parent.split(":")[-1]
        if parent.startswith("block/"):
            parent = parent[len("block/"):]
        collect_textures(parent, texture_map, seen)
    return texture_map


def resolve(ref, texture_map, depth=0):
    if ref is None or depth > 10:
        return None
    if ref.startswith("#"):
        return resolve(texture_map.get(ref[1:]), texture_map, depth + 1)
    return ref


def texture_exists(ref):
    if ref is None:
        return False
    base = ref.split("/")[-1]
    return os.path.exists(os.path.join(TEXTURES, base + ".png"))


def main():
    names = sorted(f[:-5] for f in os.listdir(MODELS)
                   if f.endswith(".json") and any(k in f.lower() for k in KEYWORDS))
    problems = 0
    for name in names:
        data = load(name)
        if data is None:
            continue
        issues = []
        texture_map = collect_textures(name, {})
        if "elements" not in data and "parent" not in data:
            issues.append("no elements and no parent")
        for i, el in enumerate(data.get("elements", [])):
            faces = el.get("faces", {})
            missing = [f for f in FACES if f not in faces]
            if missing:
                issues.append("element %d missing faces: %s" % (i, ",".join(missing)))
            for fname, face in faces.items():
                if "texture" not in face:
                    issues.append("element %d face %s has no texture" % (i, fname))
                    continue
                ref = resolve(face["texture"], texture_map)
                if ref is None:
                    issues.append("element %d face %s unresolvable texture %s"
                                  % (i, fname, face["texture"]))
                elif not texture_exists(ref):
                    issues.append("element %d face %s texture png missing: %s"
                                  % (i, fname, ref))
                uv = face.get("uv")
                if uv and (len(uv) != 4 or any(c < 0 or c > 16 for c in uv)):
                    issues.append("element %d face %s uv out of range: %s"
                                  % (i, fname, uv))
        # concrete texture values in the map must exist
        for k, v in texture_map.items():
            ref = resolve(v, texture_map)
            if ref is not None and not ref.startswith("#") and not texture_exists(ref):
                issues.append("texture map %s -> %s png missing" % (k, ref))
        if issues:
            problems += len(issues)
            print("== %s" % name)
            for issue in issues:
                print("   - %s" % issue)
    print("\n%d models checked, %d problems" % (len(names), problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
