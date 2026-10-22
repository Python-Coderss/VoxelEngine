#!/usr/bin/env python3
"""Generate the MCSM-style UI parchment the HUD panels are printed on.

The palette this finishes is sampled from Minecraft: Story Mode's 2015
interface and the pre-2020 vanilla GUI: warm sepia ink, cream lettering, one
amber accent. Nothing here is blue-grey.

Output (16x16, repeats seamlessly, near-white so a UILayer vertex colour can
tint it to any panel shade):

  src/main/resources/assets/minecraft/textures/gui/mcsm_parchment.png

Usage: python tools/gen_mcsm_ui.py
"""
import math
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                    "assets", "minecraft", "textures", "gui")
SIZE = 16
TWO_PI = math.pi * 2.0


def parchment(size=SIZE):
    """A seamless warm paper grain: low contrast so panels stay readable."""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            # Periodic waves only, so the left edge meets the right one and
            # the top edge meets the bottom: no seam when the quad tiles it.
            grain = (
                5.0 * math.sin(TWO_PI * 3.0 * x / size)
                + 5.0 * math.sin(TWO_PI * 5.0 * y / size)
                + 3.0 * math.sin(TWO_PI * 7.0 * (x + y) / size)
                + 2.0 * math.sin(TWO_PI * 11.0 * (x - y) / size)
            )
            # Occasional darker fibre flecks, mirrored so the tile still wraps.
            fleck = -6.0 if ((x * 5 + y * 3) % 13) == 0 else 0.0
            v = max(0.0, min(255.0, 232.0 + grain + fleck))
            px[x, y] = (int(v), int(v * 0.972), int(v * 0.918), 255)
    return img


def main():
    os.makedirs(ROOT, exist_ok=True)
    out = os.path.join(ROOT, "mcsm_parchment.png")
    parchment().save(out)
    print("wrote", os.path.relpath(out))


if __name__ == "__main__":
    main()
