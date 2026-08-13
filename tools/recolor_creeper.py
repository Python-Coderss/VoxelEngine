#!/usr/bin/env python3
"""Recolor the creeper texture so its green skin uses the modern grayscale
grass palette (grass_block_top). The engine then applies biome grass tinting,
so the creeper's body ends up matching the surrounding grass color.

Implements the user's 256 find/replace: for each of the 16x16 alpha-grass-top
texels, map that green to the same position in the modern grayscale
grass_block_top. Every matching (and near-matching green) pixel in the creeper
skin is replaced through that lookup table.
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FIND = os.path.join(ROOT, 'tools', 'assets', 'alpha_grass_top.png')
REPLACE = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'minecraft',
                       'textures', 'blocks', 'grass_block_top.png')
CREEPER = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'minecraft',
                       'textures', 'entity', 'creeper', 'creeper.png')


def main():
    find = Image.open(FIND).convert('RGBA')
    replace = Image.open(REPLACE).convert('RGBA')

    # Build the 256-entry find -> replace LUT (positional; first wins).
    lut = {}
    for y in range(16):
        for x in range(16):
            f = find.getpixel((x, y))[:3]
            r = replace.getpixel((x, y))[:3]
            lut.setdefault(f, r)

    find_colors = list(lut.keys())

    def nearest(rgb):
        best, best_d = None, None
        dr = [0, 0, 0]
        for f in find_colors:
            dr[0] = f[0] - rgb[0]
            dr[1] = f[1] - rgb[1]
            dr[2] = f[2] - rgb[2]
            d = dr[0] * dr[0] + dr[1] * dr[1] + dr[2] * dr[2]
            if best_d is None or d < best_d:
                best_d = d
                best = f
        return lut[best]

    creeper = Image.open(CREEPER).convert('RGBA')
    w, h = creeper.size
    changed = 0
    for y in range(h):
        for x in range(w):
            p = creeper.getpixel((x, y))
            if p[3] == 0:
                continue
            r, g, b = p[0], p[1], p[2]
            if (r, g, b) in lut:
                new = lut[(r, g, b)]
            elif g > r and g > b and g > 30:
                # Green skin shade outside the exact alpha palette (modern creeper
                # uses its own green ramp) - snap to the nearest alpha green.
                new = nearest((r, g, b))
            else:
                continue
            creeper.putpixel((x, y), (new[0], new[1], new[2], p[3]))
            changed += 1

    # The engine's entity texture atlas is 64x64 but the creeper skin is 64x32
    # (top half). Pad top-aligned so the model's 0..32 UVs sample correctly.
    if creeper.width != 64 or creeper.height != 64:
        padded = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
        padded.paste(creeper, (0, 0))
        creeper = padded

    creeper.save(CREEPER)
    print('recolored %d pixels in %s' % (changed, CREEPER))


if __name__ == '__main__':
    main()
