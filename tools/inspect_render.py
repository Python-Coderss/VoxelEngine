#!/usr/bin/env python3
"""Inspect a captured frame in the terminal: downsampled first, then crops.

The game's `/screenshot` (and `VOXEL_UI_PROBE`) writes the whole window,
including the HUD. This tool makes that image readable without a viewer:

    python tools/inspect_render.py screenshots/screenshot_123.png
    python tools/inspect_render.py shot.png --crop 520,560,420,180 --cols 90

The whole-frame view is a coarse luminance/hue map. A crop is rendered at a
much finer character grid, so prompt cards, text and marker geometry can be
checked at full resolution after the layout looks right downsampled.
"""

import argparse
import os
import sys


def load(path):
    try:
        from PIL import Image
    except ImportError:
        print("Pillow is required: pip install pillow", file=sys.stderr)
        raise SystemExit(2)
    image = Image.open(path).convert("RGB")
    return image


def cell_ascii(image, cols, rows, ramp=" .:-=+*#%@"):
    width, height = image.size
    px = image.load()
    lum_lines = []
    hue_lines = []
    for ry in range(rows):
        y0 = ry * height // rows
        y1 = max(y0 + 1, (ry + 1) * height // rows)
        lum = []
        hue = []
        for cx in range(cols):
            x0 = cx * width // cols
            x1 = max(x0 + 1, (cx + 1) * width // cols)
            r = g = b = 0
            n = 0
            for y in range(y0, y1, max(1, (y1 - y0) // 4)):
                for x in range(x0, x1, max(1, (x1 - x0) // 4)):
                    pr, pg, pb = px[x, y]
                    r += pr
                    g += pg
                    b += pb
                    n += 1
            r //= n
            g //= n
            b //= n
            lv = 0.2126 * r + 0.7152 * g + 0.0722 * b
            lum.append(ramp[min(len(ramp) - 1, int(lv / 255.0 * (len(ramp) - 1) + 0.5))])
            hue.append(hue_letter(r, g, b))
        lum_lines.append("".join(lum))
        hue_lines.append("".join(hue))
    return lum_lines, hue_lines


def hue_letter(r, g, b):
    mx = max(r, g, b)
    mn = min(r, g, b)
    if mx < 12 or (mx - mn) / mx < 0.15:
        return "."
    if mx == r:
        h = 60.0 * (((g - b) / (mx - mn)) % 6.0)
    elif mx == g:
        h = 60.0 * (((b - r) / (mx - mn)) + 2.0)
    else:
        h = 60.0 * (((r - g) / (mx - mn)) + 4.0)
    if h < 0:
        h += 360.0
    letter = ("R" if h < 30 or h >= 330 else "Y" if h < 90 else "G" if h < 150
              else "C" if h < 210 else "B" if h < 270 else "M")
    return letter if mx >= 110 else letter.lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("image")
    parser.add_argument("--cols", type=int, default=100)
    parser.add_argument("--rows", type=int, default=40)
    parser.add_argument("--crop", help="x,y,w,h in full-resolution pixels")
    parser.add_argument("--out", help="optional crop PNG path")
    args = parser.parse_args()

    image = load(args.image)
    print("image: %s (%dx%d)" % (args.image, image.size[0], image.size[1]))
    if args.crop:
        parts = [int(v) for v in args.crop.split(",")]
        if len(parts) != 4:
            print("--crop wants x,y,w,h", file=sys.stderr)
            return 2
        box = (parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
        image = image.crop(box)
        print("crop: %s -> %dx%d" % (box, image.size[0], image.size[1]))
        if args.out:
            image.save(args.out)
            print("wrote %s" % args.out)

    lum, hue = cell_ascii(image, args.cols, args.rows)
    print("luminance (top row first):")
    for line in lum:
        print(line)
    print("hue ('.' = grey, lower case = dim):")
    for line in hue:
        print(line)

    colors = image.getcolors(maxcolors=1 << 24) or []
    dominant = sorted(colors, reverse=True)[:5]
    print("top colours (count, rgb): %s" % dominant)
    return 0


if __name__ == "__main__":
    sys.exit(main())
