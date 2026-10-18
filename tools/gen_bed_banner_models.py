#!/usr/bin/env python3
"""Generate the bed and standing-banner block models.

Both are multiblocks, like doors: a bed is a foot cell plus a head cell on one
horizontal axis, and a standing banner is a lower cell plus an upper cell.
Vanilla ships neither the geometry nor a usable item texture for them in this
resource tree, so this script emits small, readable models from the block
textures that do exist (oak planks for the frame, white/red wool for the
mattress, blanket and cloth).

Every model binds all six faces to concrete textures, which
``BlockDataManager.registerBlock`` requires, and writes the bed halves for each
of the four facings. Banners share geometry between north/south (cloth in the
XY plane) and east/west (cloth in the ZY plane).

Run:  python3 tools/gen_bed_banner_models.py
"""

import json
import os

BLOCK_MODELS = os.path.join("src", "main", "resources", "assets", "minecraft",
                            "models", "block")
ITEM_MODELS = os.path.join("src", "main", "resources", "assets", "minecraft",
                           "models", "item")

FACES = ("down", "up", "north", "south", "west", "east")


def box(frm, to, texture):
    """One cuboid with every face bound to a concrete texture key."""
    return {
        "from": list(frm),
        "to": list(to),
        "faces": {face: {"texture": texture, "uv": [0, 0, 16, 16]} for face in FACES},
    }


def bed_half(head):
    """Legs and mattress, shared by both halves."""
    return [
        box((0, 0, 0), (2, 3, 2), "#frame"),
        box((14, 0, 0), (16, 3, 2), "#frame"),
        box((0, 0, 14), (2, 3, 16), "#frame"),
        box((14, 0, 14), (16, 3, 16), "#frame"),
        box((0, 3, 0), (16, 9, 16), "#mattress"),
        # Blanket over the lower half of the mattress.
        box((1, 9, 1), (15, 10, 15), "#blanket"),
    ]


def bed_model(head, facing):
    """A head or foot half with its end board (and pillow for the head)."""
    elements = bed_half(head)

    if head:
        # Board on the far (head) edge of the head cell, pillow inboard of it.
        if facing == "north":
            elements.append(box((0, 9, 0), (16, 15, 2), "#frame"))
            elements.append(box((2, 9, 2), (14, 11, 7), "#pillow"))
        elif facing == "south":
            elements.append(box((0, 9, 14), (16, 15, 16), "#frame"))
            elements.append(box((2, 9, 9), (14, 11, 14), "#pillow"))
        elif facing == "east":
            elements.append(box((14, 9, 0), (16, 15, 16), "#frame"))
            elements.append(box((9, 9, 2), (14, 11, 14), "#pillow"))
        else:  # west
            elements.append(box((0, 9, 0), (2, 15, 16), "#frame"))
            elements.append(box((2, 9, 2), (7, 11, 14), "#pillow"))
    else:
        # Footboard on the end away from the head.
        if facing == "north":
            elements.append(box((0, 9, 14), (16, 14, 16), "#frame"))
        elif facing == "south":
            elements.append(box((0, 9, 0), (16, 14, 2), "#frame"))
        elif facing == "east":
            elements.append(box((0, 9, 0), (2, 14, 16), "#frame"))
        else:  # west
            elements.append(box((14, 9, 0), (16, 14, 16), "#frame"))

    return {
        "ambientocclusion": False,
        "textures": {
            "particle": "#frame",
            "frame": "blocks/planks_oak",
            "mattress": "blocks/wool_colored_white",
            "blanket": "blocks/wool_colored_red",
            "pillow": "blocks/wool_colored_white",
        },
        "elements": elements,
    }


def banner_model(upper, along_z):
    """A standing banner: pole, finial and the cloth panel."""
    elements = []
    if along_z:
        pole = box((0, 0, 7), (2, 16, 9), "#pole")
        cloth = box((2, 0, 7), (15, 13 if upper else 16, 8), "#cloth")
        cap = box((0, 15, 7), (4, 16, 9), "#pole")
    else:
        pole = box((7, 0, 0), (9, 16, 2), "#pole")
        cloth = box((7, 0, 2), (8, 13 if upper else 16, 15), "#cloth")
        cap = box((7, 15, 0), (9, 16, 4), "#pole")
    elements.extend([pole, cloth])
    if upper:
        elements.append(cap)
    return {
        "ambientocclusion": False,
        "textures": {
            "particle": "#cloth",
            "cloth": "blocks/wool_colored_white",
            "pole": "blocks/planks_oak",
        },
        "elements": elements,
    }


def write_model(directory, name, obj):
    path = os.path.join(directory, name + ".json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(obj, handle, indent=4)
        handle.write("\n")
    return path


def main():
    if not os.path.isdir(BLOCK_MODELS):
        raise SystemExit("missing model dir: " + os.path.abspath(BLOCK_MODELS))
    written = []

    # The canonical "bed" block is the north-facing foot; the item model links
    # to it, and the other facings are variants of the same item.
    facing_names = {
        "north": ("bed", "bed_head"),
        "south": ("bed_foot_south", "bed_head_south"),
        "east": ("bed_foot_east", "bed_head_east"),
        "west": ("bed_foot_west", "bed_head_west"),
    }
    for facing, (foot, head) in facing_names.items():
        written.append(write_model(BLOCK_MODELS, foot, bed_model(False, facing)))
        written.append(write_model(BLOCK_MODELS, head, bed_model(True, facing)))

    written.append(write_model(BLOCK_MODELS, "banner", banner_model(False, True)))
    written.append(write_model(BLOCK_MODELS, "banner_upper", banner_model(True, True)))
    written.append(write_model(BLOCK_MODELS, "banner_ew", banner_model(False, False)))
    written.append(write_model(BLOCK_MODELS, "banner_upper_ew", banner_model(True, False)))

    # Inventory icons: the resource pack only shipped builtin/entity models
    # with no texture, so the bed and banner appeared blank in the inventory.
    icon = {"parent": "item/generated", "textures": {"layer0": "blocks/wool_colored_white"}}
    written.append(write_model(ITEM_MODELS, "bed", icon))
    written.append(write_model(ITEM_MODELS, "banner", icon))

    print("wrote %d bed/banner models" % len(written))


if __name__ == "__main__":
    main()
