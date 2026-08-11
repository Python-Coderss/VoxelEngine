#!/usr/bin/env python3
"""ASCII preview of kinetic textures (frame 0 of animated strips)."""
from PIL import Image

BASE = "src/main/resources/assets/minecraft/textures/blocks"
for f in ["cogwheel", "large_cogwheel", "wheel", "axis", "axis_top"]:
    im = Image.open(f"{BASE}/{f}.png").convert("RGBA")
    frame = im.crop((0, 0, 16, 16)) if im.size[1] > 16 else im
    px = frame.load()
    print(f"--- {f} ---")
    for y in range(16):
        row = ""
        for x in range(16):
            r, g, b, a = px[x, y]
            if a < 128:
                row += "."
            else:
                lum = (r * 3 + g * 6 + b) // 10
                row += "@" if lum > 180 else ("#" if lum > 120 else ("+" if lum > 60 else "*"))
        print(row)
    print()
