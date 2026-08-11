#!/usr/bin/env python3
"""Print opaque-pixel bounding boxes for the kinetic textures (frame 0 of strips).

Helps decide element sizes for shaft / cog / water-wheel block models.
"""
from PIL import Image

BASE = "src/main/resources/assets/minecraft/textures/blocks"
for f in ["cogwheel", "large_cogwheel", "wheel", "axis", "axis_top"]:
    im = Image.open(f"{BASE}/{f}.png").convert("RGBA")
    frame = im.crop((0, 0, 16, 16)) if im.size[1] > 16 else im
    px = frame.load()
    xs, ys = [], []
    for y in range(16):
        for x in range(16):
            if px[x, y][3] > 200:
                xs.append(x)
                ys.append(y)
    if xs:
        print(f"{f}: opaque bbox x[{min(xs)}..{max(xs)}] y[{min(ys)}..{max(ys)}] count={len(xs)}")
    else:
        print(f"{f}: no opaque pixels")
