#!/usr/bin/env python3
"""Rebuild the eight repeater block models with correct vanilla geometry.

The registered repeater_* models had two defects versus vanilla:

  * only the rear torch was modelled (vanilla repeaters have two torches)
  * the east/west variants kept the torches on the z axis; facing east/west
    means the torch pair runs along x

This script rewrites each repeater_{dir}[_on].json keeping its existing
texture map, with an explicit-faced base slab plus the correct torch pair.
Torch faces use #torch (off variants) or #lit_torch (on variants).

Usage: python tools/fix_repeater_models.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                    "assets", "minecraft", "models", "block")

TORSO_UV = {
    "down": [7, 13, 9, 15],
    "up": [7, 6, 9, 8],
    "north": [7, 6, 9, 11],
    "south": [7, 6, 9, 11],
    "west": [7, 6, 9, 11],
    "east": [7, 6, 9, 11],
}
BASE_UV = {
    "down": [0, 0, 16, 16],
    "up": [0, 0, 16, 16],
    "north": [0, 14, 16, 16],
    "south": [0, 14, 16, 16],
    "west": [0, 14, 16, 16],
    "east": [0, 14, 16, 16],
}
FACES = ("down", "up", "north", "south", "west", "east")


def torch(frm, to, tex):
    return {
        "from": frm,
        "to": to,
        "faces": {f: {"uv": TORSO_UV[f], "texture": tex} for f in FACES},
    }


def base():
    tex = {"down": "#down", "up": "#up", "north": "#north",
           "south": "#south", "west": "#west", "east": "#east"}
    return {
        "from": [0, 0, 0],
        "to": [16, 2, 16],
        "faces": {f: {"uv": BASE_UV[f], "texture": tex[f]} for f in FACES},
    }


def main():
    # Torch pair geometry per axis (vanilla): 2x5x2 posts centred on the
    # slab, 2 px from each edge.
    pair_z = [([7, 2, 2], [9, 7, 4]), ([7, 2, 12], [9, 7, 14])]
    pair_x = [([2, 2, 7], [4, 7, 9]), ([12, 2, 7], [14, 7, 9])]
    axis = {"north": pair_z, "south": pair_z, "east": pair_x, "west": pair_x}

    for direction in ("north", "south", "east", "west"):
        for powered in (False, True):
            name = "repeater_%s%s" % (direction, "_on" if powered else "")
            path = os.path.join(ROOT, name + ".json")
            with open(path, "r", encoding="utf-8") as f:
                model = json.load(f)
            tex = "#lit_torch" if powered else "#torch"
            elements = [base()]
            for frm, to in axis[direction]:
                elements.append(torch(frm, to, tex))
            model["elements"] = elements
            with open(path, "w", encoding="utf-8") as f:
                json.dump(model, f, indent=4)
                f.write("\n")
            print("rewrote", name)


if __name__ == "__main__":
    main()
