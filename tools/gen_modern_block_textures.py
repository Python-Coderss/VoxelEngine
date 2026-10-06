"""Generate 16x16 block textures for the ancient-builder modern-era set.

Deterministic (seeded) pixel patterns so re-running produces identical PNGs.
Output: src/main/resources/assets/minecraft/textures/blocks/<name>.png

Blocks covered (IDs 920-927, registered in Main):
  concrete        pale precast concrete panels
  concrete_dark   asphalt / dark concrete
  steel_beam      brushed steel girder with rivets
  ceiling_light   emissive light panel
  tile_block      white tile with grout grid
  office_glass    blue-tinted office glass with a shine streak
  marble          polished marble with veins
"""

import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft",
                   "textures", "blocks")
SIZE = 16


def noise(img, rng, base, spread):
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = max(0, min(255, base + rng.randint(-spread, spread)))
            r, g, b = px[x, y][:3]
            px[x, y] = (min(255, r + v - base), min(255, g + v - base),
                        min(255, b + v - base), 255)


def concrete(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (198, 198, 194, 255))
    noise(img, rng, 198, 10)
    px = img.load()
    for i in range(SIZE):  # panel seams
        px[i, 8] = (170, 170, 166, 255)
        px[8, i] = (170, 170, 166, 255)
    return img


def concrete_dark(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (58, 58, 62, 255))
    noise(img, rng, 58, 8)
    px = img.load()
    for i in range(SIZE):
        px[i, 0] = (44, 44, 48, 255)
        px[0, i] = (44, 44, 48, 255)
    return img


def steel_beam(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (148, 152, 160, 255))
    px = img.load()
    for x in range(SIZE):  # brushed vertical highlight
        shade = 158 if x % 4 in (1, 2) else 138
        for y in range(SIZE):
            v = shade + rng.randint(-5, 5)
            px[x, y] = (v, v + 4, v + 10, 255)
    for rx, ry in ((2, 2), (13, 2), (2, 13), (13, 13)):  # rivets
        px[rx, ry] = (112, 116, 124, 255)
    return img


def ceiling_light(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (252, 250, 238, 255))
    px = img.load()
    for i in range(SIZE):  # soft frame
        px[i, 0] = (226, 224, 210, 255)
        px[i, SIZE - 1] = (226, 224, 210, 255)
        px[0, i] = (226, 224, 210, 255)
        px[SIZE - 1, i] = (226, 224, 210, 255)
    for y in range(2, SIZE - 2, 4):
        for x in range(2, SIZE - 2):
            px[x, y] = (255, 255, 246, 255)
    return img


def tile_block(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (232, 232, 228, 255))
    noise(img, rng, 232, 5)
    px = img.load()
    for i in range(SIZE):
        for c in (0, 8):  # grout grid, 8x8 tiles
            px[c, i] = (196, 196, 192, 255)
            px[i, c] = (196, 196, 192, 255)
    return img


def office_glass(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (122, 168, 202, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = rng.randint(-6, 6)
            px[x, y] = (122 + v, 168 + v, 202 + v, 255)
    for i in range(SIZE):  # diagonal shine streak
        j = (i + 4) % SIZE
        px[i, j] = (198, 226, 244, 255)
        px[i, (j + 1) % SIZE] = (172, 210, 234, 255)
    return img


def marble(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (236, 232, 224, 255))
    noise(img, rng, 236, 6)
    px = img.load()
    x, y = 1, 3
    while x < SIZE and y < SIZE:  # a wandering vein
        px[x, y] = (198, 192, 182, 255)
        x += 1
        y += 1 if rng.random() < 0.4 else 0
    return img


GENERATORS = {
    "concrete": concrete,
    "concrete_dark": concrete_dark,
    "steel_beam": steel_beam,
    "ceiling_light": ceiling_light,
    "tile_block": tile_block,
    "office_glass": office_glass,
    "marble": marble,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in sorted(GENERATORS):
        rng = random.Random(9200 + sum(ord(c) for c in name))
        img = GENERATORS[name](rng)
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
