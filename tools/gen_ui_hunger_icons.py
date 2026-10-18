#!/usr/bin/env python3
"""Stamp the HUD hunger (drumstick) icons into src/main/resources/ui/ui.png.

The UI atlas had no food/hunger icon, so the HUD food bar borrowed the
``cooked_chicken`` item icon from the item atlas. This script adds three
9x9 hunger icons to the (previously empty) lower-left corner of the atlas and
is idempotent: it clears the three icon rectangles and redraws them.

Layout (must match com.voxel.ui.UIConstants):

    ( 0, 104) 9x9  -> HUNGER_EMPTY
    (10, 104) 9x9  -> HUNGER_HALF
    (20, 104) 9x9  -> HUNGER_FULL

Run:  python3 tools/gen_ui_hunger_icons.py
"""

import os

from PIL import Image

ATLAS = os.path.join("src", "main", "resources", "ui", "ui.png")

ICON = 9
Y = 104
XS = (0, 10, 20)  # empty, half, full

TRANSPARENT = (0, 0, 0, 0)

# Silhouette: '.' transparent, '#' drumstick. Meat blob upper-right, bone
# sticking out of the lower-left, exactly like Minecraft's hunger sprite.
SHAPE = (
    "...###...",
    "..#####..",
    ".#######.",
    ".#######.",
    ".######..",
    "..#####..",
    "..#####..",
    ".###.#...",
    ".##......",
)

MEAT = (150, 78, 30, 255)
MEAT_HI = (196, 118, 46, 255)
BONE = (238, 232, 208, 255)
BONE_SH = (196, 188, 158, 255)
EMPTY_FILL = (58, 58, 58, 255)
EMPTY_EDGE = (104, 104, 104, 255)


def _color_for(x, y, mode):
    """Color for a silhouette pixel in empty/half/full mode."""
    if mode == "empty":
        # Uniform dark silhouette, lighter along the top edge.
        return EMPTY_EDGE if y == 0 else EMPTY_FILL
    # Coloured drumstick: bone at the lower-left, meat everywhere else.
    if y >= 6 and x <= 4:
        return BONE if (y == 6 and x >= 2) or (y == 7) else BONE_SH
    if x >= 5 and y <= 1:
        return MEAT_HI
    return MEAT


def draw_icon(img, ox, mode):
    px = img.load()
    for y, row in enumerate(SHAPE):
        for x, ch in enumerate(row):
            if ch != "#":
                continue
            # Half icon: the left plateau stays empty so the right (meat) side
            # reads as the filled half, matching the half-heart convention.
            if mode == "half" and x < 4:
                px[ox + x, Y + y] = EMPTY_EDGE if y == 0 else EMPTY_FILL
            else:
                px[ox + x, Y + y] = _color_for(x, y, mode)


def main():
    if not os.path.exists(ATLAS):
        raise SystemExit("missing atlas: " + os.path.abspath(ATLAS))
    img = Image.open(ATLAS).convert("RGBA")
    if img.size != (256, 256):
        raise SystemExit("unexpected atlas size %r (expected 256x256)" % (img.size,))
    px = img.load()

    # Clear the icon rectangles first (idempotent re-runs).
    for ox in XS:
        for y in range(Y, Y + ICON):
            for x in range(ox, ox + ICON):
                px[x, y] = TRANSPARENT

    for ox, mode in zip(XS, ("empty", "half", "full")):
        draw_icon(img, ox, mode)

    img.save(ATLAS)
    print("stamped hunger icons at", [(ox, Y) for ox in XS], "->", ATLAS)


if __name__ == "__main__":
    main()
