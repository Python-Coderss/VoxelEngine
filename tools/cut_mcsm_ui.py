#!/usr/bin/env python3
"""Cut the Story Mode menu plates out of the shipped MCSM UI spritesheet.

The PSP-era port ships `menu-spritesheet.png` inside `assets-ui.zip`, and its
menu rows are the ones the game actually draws: an indigo plate with a
pale-yellow frame for the focused row, a grey plate for the rest. The old
`mcsm_ui.png` atlas held sepia copies of those plates, which is why the menu
came out brown; this rebuilds the atlas from the real art.

Atlas layout (256x256, matching MCGui.MCSM_ROW / MCSM_ROW_HOT):

  (0,   0) 196x64   logo, left untouched (its crop is a separate open issue)
  (0,  68) 185x27   focused row: the indigo plate with its yellow frame
  (0, 104) 181x23   idle row: the same plate recoloured navy, frame to ink

Usage: python tools/cut_mcsm_ui.py [path/to/menu-spritesheet.png]
"""

import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                    "assets", "minecraft", "textures", "gui")
ATLAS = os.path.join(ROOT, "mcsm_ui.png")
ZIP = os.path.join(os.path.dirname(__file__), "..", "entitybtw mcsm_portable main assets-ui.zip")

# Pixel rectangles inside the spritesheet (measured, not guessed).
FOCUS_ROW = (0, 164, 183, 189)   # yellow frame + indigo fill
IDLE_ROW = (200, 165, 364, 188)  # grey plate

# Story Mode row colours, sampled from the sheet.
FRAME = (11, 16, 32)      # ink frame for the idle row
IDLE_FILL = (52, 58, 110)  # muted indigo, so a focused row still stands out


def load_sheet():
    if len(sys.argv) > 1:
        return Image.open(sys.argv[1]).convert("RGBA")
    if os.path.exists(ZIP):
        with zipfile.ZipFile(ZIP) as z:
            with z.open("menu-spritesheet.png") as f:
                import io
                return Image.open(io.BytesIO(f.read())).convert("RGBA")
    raise SystemExit("menu-spritesheet.png not found; pass its path")


def recolour(plate):
    """Grey plate -> navy plate: keep the art's own bevel, swap the hue."""
    out = Image.new("RGBA", plate.size)
    src = plate.load()
    dst = out.load()
    for y in range(plate.height):
        for x in range(plate.width):
            r, g, b, a = src[x, y]
            if a < 16:
                dst[x, y] = (0, 0, 0, 0)
                continue
            lum = (r + g + b) / 3.0 / 255.0
            fill = tuple(min(255, int(c * (0.35 + lum * 0.95))) for c in IDLE_FILL)
            edge = lum < 0.30
            dst[x, y] = (FRAME + (a,)) if edge else (fill + (a,))
    return out


def main():
    sheet = load_sheet()
    atlas = Image.open(ATLAS).convert("RGBA") if os.path.exists(ATLAS) else \
        Image.new("RGBA", (256, 256), (0, 0, 0, 0))

    hot = sheet.crop(FOCUS_ROW).resize((185, 27), Image.NEAREST)
    atlas.paste(hot, (0, 68), hot)

    idle = recolour(sheet.crop(IDLE_ROW)).resize((181, 23), Image.NEAREST)
    atlas.paste(idle, (0, 104), idle)

    atlas.save(ATLAS)
    print("wrote", os.path.relpath(ATLAS))


if __name__ == "__main__":
    main()
