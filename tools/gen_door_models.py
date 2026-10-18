#!/usr/bin/env python3
"""Generate the door / trapdoor block models used by the authored worlds.

The resource tree already ships the vanilla *textures* (door_wood_lower,
door_spruce_upper, trapdoor, ...) and the vanilla *geometry* (door_bottom,
door_top, wooden_trapdoor_bottom, ...), but none of it was registered as a
block. Two things are missing for usable doors:

1. Consistent, readable block names. Vanilla names the oak door
   ``door_bottom``/``door_top`` but the other woods ``<wood>_door_bottom``.
   This script writes thin ``<wood>_door_lower``/``<wood>_door_upper`` aliases
   that just ``parent`` the vanilla model, so every wood is named the same way.

2. Both wall orientations. ``door_bottom`` is a panel against the *west* edge
   of its cell (full depth in Z), which only looks right in a wall that runs
   along Z. Doors in a wall running along X need the same geometry rotated 90
   degrees about Y, which vanilla does not ship as a model. This script
   rotation-transforms the vanilla elements (and remaps the face keys) to emit
   ``<wood>_door_lower_ew`` / ``<wood>_door_upper_ew``.

Both outputs keep all six faces bound to concrete textures so
``BlockDataManager.registerBlock`` resolves them (it throws if a face is left
unresolved).

Run:  python3 tools/gen_door_models.py
"""

import json
import os

BLOCK_MODELS = os.path.join("src", "main", "resources", "assets", "minecraft", "models", "block")

# wood key -> (vanilla lower model, vanilla upper model, lower texture, upper texture)
WOODS = {
    "oak": ("wooden_door_bottom", "wooden_door_top", "door_wood_lower", "door_wood_upper"),
    "spruce": ("spruce_door_bottom", "spruce_door_top", "door_spruce_lower", "door_spruce_upper"),
    "birch": ("birch_door_bottom", "birch_door_top", "door_birch_lower", "door_birch_upper"),
    "dark_oak": ("dark_oak_door_bottom", "dark_oak_door_top",
                 "door_dark_oak_lower", "door_dark_oak_upper"),
}

# Rotating +90 degrees about Y maps x -> z and z -> -x, so the face whose
# outward normal pointed along a given axis ends up on another face.
FACE_AFTER_Y_ROTATION = {
    "down": "down",
    "up": "up",
    "east": "north",   # +X -> -Z
    "west": "south",   # -X -> +Z
    "south": "east",   # +Z -> +X
    "north": "west",   # -Z -> -X
}


def load_model(name):
    with open(os.path.join(BLOCK_MODELS, name + ".json"), "r", encoding="utf-8") as fh:
        return json.load(fh)


def write_model(name, obj):
    path = os.path.join(BLOCK_MODELS, name + ".json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=4)
        fh.write("\n")
    return path


def write_alias(name, parent):
    """A child model that only re-points at an existing vanilla model."""
    return write_model(name, {"parent": "block/" + parent})


def write_rotated(name, base_name, lower_tex, upper_tex):
    """Rotate a vanilla door half 90 degrees about Y so it faces the other wall."""
    base = load_model(base_name)
    elements = []
    for el in base["elements"]:
        fx, fy, fz = el["from"]
        tx, ty, tz = el["to"]
        rotated = {
            "from": [fz, fy, 16 - tx],
            "to": [tz, ty, 16 - fx],
            "faces": {},
        }
        for face, spec in el["faces"].items():
            rotated["faces"][FACE_AFTER_Y_ROTATION[face]] = dict(spec)
        elements.append(rotated)
    return write_model(name, {
        "ambientocclusion": False,
        "textures": {
            "particle": "#bottom",
            "bottom": "blocks/" + lower_tex,
            "top": "blocks/" + upper_tex,
        },
        "elements": elements,
    })


def main():
    if not os.path.isdir(BLOCK_MODELS):
        raise SystemExit("missing model dir: " + os.path.abspath(BLOCK_MODELS))
    written = []
    for wood, (lower, upper, tex_lo, tex_hi) in WOODS.items():
        # North/south-facing walls (panel against the west edge of the cell).
        written.append(write_alias(wood + "_door_lower", lower))
        written.append(write_alias(wood + "_door_upper", upper))
        # East/west-facing walls (same panel rotated 90 degrees). The wood's
        # own model is only a texture alias with no elements, so the rotation
        # is taken from the shared geometry models.
        written.append(write_rotated(wood + "_door_lower_ew", "door_bottom", tex_lo, tex_hi))
        written.append(write_rotated(wood + "_door_upper_ew", "door_top", tex_lo, tex_hi))

    # Trapdoors: vanilla geometry lives in wooden_trapdoor_bottom /
    # iron_trapdoor_bottom, which already bind the right textures.
    written.append(write_alias("oak_trapdoor", "wooden_trapdoor_bottom"))
    written.append(write_alias("iron_trapdoor", "iron_trapdoor_bottom"))

    print("wrote %d door/trapdoor models into %s" % (len(written), BLOCK_MODELS))


if __name__ == "__main__":
    main()
