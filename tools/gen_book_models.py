#!/usr/bin/env python3
"""Emit the block models that render the book items.

Items are drawn as miniature block AABB models by the raytracer, so a book is
built from four elements rather than a flat sprite:

  * a back cover board
  * the page stack (inset, poking slightly past the fore-edge so the leaf
    edges are visible)
  * a front cover board
  * a spine block wrapping the bound edge (the west face)

Each model maps one texture per face direction as well as per element face, so
the spine reads on the west face, the covers on the flat faces and the pages on
the fore-edge / top / bottom. Two orientations are produced per book:

  item_<name>.json       lying flat (crafting-table grid, held preview)
  item_drop_<name>.json  standing upright (dropped in the world)

Run: python3 tools/gen_book_models.py
"""

import json
import os

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "assets", "minecraft", "models", "block",
)

FULL_UV = [0, 0, 16, 16]

# name -> cover texture
BOOKS = {
    "book": "book_cover",
    "writable_book": "book_cover_writable",
    "written_book": "book_cover_written",
    "enchanted_book": "book_cover_enchanted",
}


def _faces(directions):
    return {d: {"uv": list(FULL_UV), "texture": "#" + directions[d]}
            for d in ("down", "up", "north", "south", "west", "east")}


def _element(frm, to, directions):
    return {"from": list(frm), "to": list(to), "faces": _faces(directions)}


def flat_model(cover):
    """A closed book lying on a table: covers top/bottom, spine on the west."""
    return {
        "textures": {
            "cover": cover, "pages": "book_pages", "spine": "book_spine",
            "down": cover, "up": cover,
            "north": "book_pages", "south": "book_pages",
            "west": "book_spine", "east": "book_pages",
        },
        "elements": [
            # back cover board
            _element([2, 0, 3.2], [14, 0.6, 12.8],
                     {"down": "cover", "up": "pages", "north": "cover",
                      "south": "cover", "west": "cover", "east": "cover"}),
            # page stack (east edge = fore-edge, proud of the covers)
            _element([3.4, 0.6, 3.7], [14.4, 2.8, 12.3],
                     {"down": "pages", "up": "pages", "north": "pages",
                      "south": "pages", "west": "pages", "east": "pages"}),
            # front cover board
            _element([2, 2.8, 3.2], [14, 3.4, 12.8],
                     {"down": "pages", "up": "cover", "north": "cover",
                      "south": "cover", "west": "cover", "east": "cover"}),
            # spine wrapping the bound (west) edge
            _element([2, 0, 3.2], [3.4, 3.4, 12.8],
                     {"down": "cover", "up": "cover", "north": "cover",
                      "south": "cover", "west": "spine", "east": "pages"}),
        ],
    }


def upright_model(cover):
    """A closed book standing on its bottom edge: spine still on the west."""
    return {
        "textures": {
            "cover": cover, "pages": "book_pages", "spine": "book_spine",
            "down": "book_pages", "up": "book_pages",
            "north": cover, "south": cover,
            "west": "book_spine", "east": "book_pages",
        },
        "elements": [
            # front cover board (north face)
            _element([3, 0, 6.0], [13, 16, 6.6],
                     {"down": "pages", "up": "pages", "north": "cover",
                      "south": "pages", "west": "cover", "east": "cover"}),
            # back cover board (south face)
            _element([3, 0, 9.4], [13, 16, 10.0],
                     {"down": "pages", "up": "pages", "north": "pages",
                      "south": "cover", "west": "cover", "east": "cover"}),
            # page stack (east edge = fore-edge)
            _element([3.4, 0, 6.4], [13.4, 16, 9.6],
                     {"down": "pages", "up": "pages", "north": "pages",
                      "south": "pages", "west": "pages", "east": "pages"}),
            # spine wrapping the bound (west) edge
            _element([2.4, 0, 6.0], [3.4, 16, 10.0],
                     {"down": "pages", "up": "pages", "north": "cover",
                      "south": "cover", "west": "spine", "east": "pages"}),
        ],
    }


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, cover in BOOKS.items():
        for prefix, builder in (("item_", flat_model), ("item_drop_", upright_model)):
            path = os.path.join(OUT_DIR, prefix + name + ".json")
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(builder(cover), fh, indent=4)
                fh.write("\n")
            print("wrote", path)


if __name__ == "__main__":
    main()
