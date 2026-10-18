#!/usr/bin/env python3
"""Generate the 16x16 block textures used by the book item models.

The engine maps one texture per face *direction* (and per model element face),
so a convincing book needs three distinct materials rather than a single sprite:
a cover, a page stack (top/bottom + fore-edge) and the spine. Written by hand as
deterministic pixel art so the assets can be regenerated at any time.

Outputs (textures/items/):
  book_cover.png            leather cover, default/plain book
  book_cover_writable.png   lighter cloth cover for the writable book
  book_cover_written.png    red cover for the written book
  book_cover_enchanted.png  purple cover for the enchanted book
  book_pages.png            page stack (cream with leaf lines)
  book_spine.png            dark leather spine with raised bands + a title bar
"""

import os

from PIL import Image

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "assets", "minecraft", "textures", "items",
)

SIZE = 16


def _new(color):
    return Image.new("RGBA", (SIZE, SIZE), color + (255,))


def cover(base, border, light, dark, accent):
    """A leather cover: filled base, 1px border, bevel and corner studs."""
    img = _new(base)
    px = img.load()
    for x in range(SIZE):
        for y in range(SIZE):
            if x == 0 or y == 0 or x == SIZE - 1 or y == SIZE - 1:
                px[x, y] = border + (255,)
            elif x == 1 or y == 1:
                px[x, y] = light + (255,)
            elif x == SIZE - 2 or y == SIZE - 2:
                px[x, y] = dark + (255,)
    for cx, cy in ((2, 2), (SIZE - 3, 2), (2, SIZE - 3), (SIZE - 3, SIZE - 3)):
        px[cx, cy] = accent + (255,)
    return img


def pages():
    """Page stack: cream base with a leaf line every other row and a soft edge."""
    img = _new((237, 227, 200))
    px = img.load()
    line = (214, 201, 166)
    shadow = (190, 176, 141)
    for y in range(1, SIZE - 1, 2):
        for x in range(1, SIZE - 1):
            px[x, y] = line + (255,)
    for i in range(SIZE):
        px[i, SIZE - 1] = shadow + (255,)
        px[SIZE - 1, i] = shadow + (255,)
    return img


def spine():
    """Dark leather spine: raised binding bands and a gold title bar."""
    img = _new((82, 55, 31))
    px = img.load()
    band = (58, 38, 20)
    gold = (201, 162, 39)
    for x in range(SIZE):
        px[x, 0] = (110, 76, 44, 255)
        for y in (3, 4, 11, 12):
            px[x, y] = band + (255,)
        for y in (7, 8):
            px[x, y] = gold + (255,)
    return img


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    outputs = {
        # plain book: brown leather
        "book_cover.png": cover((107, 74, 47), (74, 50, 31),
                                (138, 100, 64), (60, 40, 24), (201, 162, 39)),
        # writable book: lighter tan cloth
        "book_cover_writable.png": cover((181, 129, 58), (120, 82, 34),
                                         (214, 166, 90), (92, 62, 26), (240, 214, 140)),
        # written book: red cover
        "book_cover_written.png": cover((139, 58, 47), (92, 36, 29),
                                        (176, 84, 68), (60, 24, 20), (224, 190, 120)),
        # enchanted book: purple cover with a lighter rune bevel
        "book_cover_enchanted.png": cover((75, 46, 131), (46, 28, 84),
                                          (107, 74, 168), (38, 22, 70), (216, 190, 255)),
        "book_pages.png": pages(),
        "book_spine.png": spine(),
    }
    for name, img in outputs.items():
        path = os.path.join(OUT_DIR, name)
        img.save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
