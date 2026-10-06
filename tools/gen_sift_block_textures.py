"""Generate 16x16 textures + cube_all models for The Sift block set.

The Sift is Minecraft's fourth dimension (announced Sept 2026, Java/Bedrock
2027): a bright, soul-filled realm with the Meadow and Carapace biomes, shifting
tides, hazardous blocks and a sifting block that strains buried material out of
the dimension's unique soil.

Deterministic (seeded) pixel patterns, so re-running produces identical PNGs.

Blocks (IDs 927-936, registered in Main):
  sift_stone      pale violet bedrock of the Sift
  meadow_grass    vivid meadow turf
  sift_soil       warm humus with soul specks
  carapace        chitinous shell plating
  carapace_lamp   glow-emitting chitin
  sift_bed        the sifting block (layered sieve)
  rift_frame      rift gate frame with a glowing seam
  sift_portal     swirling rift surface
  soul_lamp       soul-lit lamp
  soul_slate      smooth slate tile
"""

import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft",
                       "textures", "blocks")
MODEL_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft",
                        "models", "block")
SIZE = 16


def noise(img, rng, base, spread):
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = max(0, min(255, base + rng.randint(-spread, spread)))
            r, g, b = px[x, y][:3]
            px[x, y] = (min(255, r + v - base), min(255, g + v - base),
                        min(255, b + v - base), 255)


def sift_stone(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (124, 114, 146, 255))
    noise(img, rng, 124, 12)
    px = img.load()
    for _ in range(10):  # soul flecks
        x, y = rng.randint(0, 15), rng.randint(0, 15)
        px[x, y] = (176, 200, 226, 255)
    return img


def meadow_grass(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (96, 206, 148, 255))
    noise(img, rng, 96, 14)
    px = img.load()
    for _ in range(18):  # brighter blades
        x, y = rng.randint(0, 15), rng.randint(0, 15)
        px[x, y] = (150, 236, 176, 255)
    return img


def sift_soil(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (118, 88, 66, 255))
    noise(img, rng, 118, 16)
    px = img.load()
    for _ in range(8):  # buried soul grains
        x, y = rng.randint(0, 15), rng.randint(0, 15)
        px[x, y] = (198, 216, 236, 255)
    return img


def carapace(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (78, 66, 96, 255))
    noise(img, rng, 78, 10)
    px = img.load()
    for y in range(0, SIZE, 4):  # shell ridge bands
        for x in range(SIZE):
            px[x, y] = (58, 48, 74, 255)
    for x in range(0, SIZE, 4):  # plate seams
        for y in range(SIZE):
            px[x, y] = (98, 84, 120, 255)
    return img


def carapace_lamp(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (120, 224, 240, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = 200 + rng.randint(-20, 20)
            px[x, y] = (120, v, 240, 255)
    for i in range(SIZE):  # bright rim
        px[i, 0] = (210, 250, 255, 255)
        px[i, SIZE - 1] = (210, 250, 255, 255)
    return img


def sift_bed(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (168, 150, 120, 255))
    noise(img, rng, 168, 10)
    px = img.load()
    for i in range(SIZE):  # sieve grid
        for c in (0, 5, 10, 15):
            px[c, i] = (110, 96, 78, 255)
            px[i, c] = (110, 96, 78, 255)
    for x in range(2, SIZE - 2):  # a couple of caught glints
        if (x * 7) % 5 == 0:
            px[x, 7] = (226, 240, 255, 255)
    return img


def rift_frame(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (44, 34, 62, 255))
    noise(img, rng, 44, 6)
    px = img.load()
    for i in range(SIZE):  # glowing seam
        px[i, 7] = (140, 200, 255, 255)
        px[i, 8] = (180, 226, 255, 255)
        px[7, i] = (140, 200, 255, 255)
        px[8, i] = (180, 226, 255, 255)
    return img


def sift_portal(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (150, 120, 232, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = rng.randint(-24, 24)
            swirl = (x + y) % 6
            px[x, y] = (150 + v, 120 + v + swirl * 4, 232, 255)
    return img


def soul_lamp(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (170, 232, 255, 255))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            d = abs(x - 7.5) + abs(y - 7.5)
            v = max(0, 120 - int(d * 14)) + rng.randint(-8, 8)
            px[x, y] = (170, min(255, 232 + v // 3), 255, 255)
    for i in range(SIZE):
        px[i, 0] = (120, 150, 180, 255)
        px[i, SIZE - 1] = (120, 150, 180, 255)
        px[0, i] = (120, 150, 180, 255)
        px[SIZE - 1, i] = (120, 150, 180, 255)
    return img


def soul_slate(rng):
    img = Image.new("RGBA", (SIZE, SIZE), (64, 64, 84, 255))
    noise(img, rng, 64, 7)
    px = img.load()
    for i in range(SIZE):  # subtle panel lines
        px[i, 5] = (50, 50, 68, 255)
        px[i, 10] = (50, 50, 68, 255)
        px[5, i] = (50, 50, 68, 255)
    return img


GENERATORS = {
    "sift_stone": sift_stone,
    "meadow_grass": meadow_grass,
    "sift_soil": sift_soil,
    "carapace": carapace,
    "carapace_lamp": carapace_lamp,
    "sift_bed": sift_bed,
    "rift_frame": rift_frame,
    "sift_portal": sift_portal,
    "soul_lamp": soul_lamp,
    "soul_slate": soul_slate,
}

MODEL = ('{\n  "parent": "block/cube_all",\n  "textures": {\n'
         '    "all": "%s"\n  }\n}\n')


def main():
    os.makedirs(TEX_OUT, exist_ok=True)
    os.makedirs(MODEL_OUT, exist_ok=True)
    for name in sorted(GENERATORS):
        rng = random.Random(9270 + sum(ord(c) for c in name))
        img = GENERATORS[name](rng)
        img.save(os.path.join(TEX_OUT, name + ".png"))
        with open(os.path.join(MODEL_OUT, name + ".json"), "w") as fh:
            fh.write(MODEL % name)
        print("wrote", name)


if __name__ == "__main__":
    main()
