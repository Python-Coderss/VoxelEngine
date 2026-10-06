"""Generate entity models + textures for the campaign bosses.

Covers the four bespoke campaign-family bosses requested alongside the story
map (MCSM, Minecraft Legends, Minecraft Dungeons 1 & 2):

  wither_storm    the MCSM Wither Storm: three heads, a glowing command core
                  and trailing tentacles
  great_hog       the Legends Great Hog: a hulking hoglin brute with a hammer
  arch_illager    the Dungeons Arch-Illager: a robed caster with a staff
  heart_of_ender  the Dungeons Heart of Ender: a giant pulsing ender orb

The engine samples cuboid entities through a standard Minecraft box-UV net
(width = 2*(w+d), height = h+d) at 1:1 pixels against a 64x64 texture layer,
so this script packs every part of a texture group into a 64x64 atlas and
paints that exact net. Models and textures are therefore always consistent;
tools/check_entity_uv_space.py can verify the result.

Deterministic (seeded), so re-running produces byte-identical output.

Run: python tools/gen_campaign_entity_textures.py
"""

import json
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft",
                         "models", "entity")
TEX_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft",
                       "textures", "entity")
ATLAS = 64


class Part:
    """One cuboid: 16ths-of-a-block geometry + the pivot it rotates about."""

    def __init__(self, name, frm, size, pivot, texture, color,
                 emissive=False):
        self.name = name
        self.frm = tuple(frm)       # box min corner (pixels, /16 -> blocks)
        self.size = tuple(size)     # box dimensions (pixels)
        self.pivot = tuple(pivot)   # rotation pivot, relative to the entity
        self.texture = texture      # e.g. "wither_storm/wither_storm"
        self.color = tuple(color)   # base RGB
        self.emissive = emissive
        self.uv = (0, 0)            # assigned by the packer

    def box_uv_size(self):
        w, h, d = self.size
        return (2 * (w + d), h + d)


def pack(parts):
    """First-fit every part's box-UV net into the 64x64 atlas.

    Parts are placed largest-area first at the first free grid cell (row-major
    scan), which keeps the layout tight and deterministic. Raises if anything
    overflows.
    """
    grid = [[False] * ATLAS for _ in range(ATLAS)]

    def fits(x, y, w, h):
        if x + w > ATLAS or y + h > ATLAS:
            return False
        for yy in range(y, y + h):
            row = grid[yy]
            for xx in range(x, x + w):
                if row[xx]:
                    return False
        return True

    order = sorted(parts, key=lambda p: (-p.box_uv_size()[0] * p.box_uv_size()[1],
                                         p.name))
    for part in order:
        w, h = part.box_uv_size()
        if w > ATLAS or h > ATLAS:
            raise ValueError("part %s does not fit: %dx%d" % (part.name, w, h))
        placed = False
        for y in range(ATLAS - h + 1):
            for x in range(ATLAS - w + 1):
                if fits(x, y, w, h):
                    for yy in range(y, y + h):
                        for xx in range(x, x + w):
                            grid[yy][xx] = True
                    part.uv = (x, y)
                    placed = True
                    break
            if placed:
                break
        if not placed:
            raise ValueError("atlas full while placing %s" % part.name)


def shade(rgb, delta):
    return tuple(max(0, min(255, c + delta)) for c in rgb)


def paint_part(img, part, rng):
    """Paint the Minecraft box-UV net for one cuboid."""
    px = img.load()
    w, h, d = part.size
    u0, v0 = part.uv
    base = part.color
    boost = 26 if part.emissive else 0

    # Face rectangles in the engine's cuboid net (matches entityCuboidAtlasUv):
    #   rows 0..d :  [d .. d+w] = top,  [d+w .. 2d+w] = bottom
    #   rows d..h :  right | front | left | back
    # (dx, dy, fw, fh, brightness delta)
    faces = [
        (d, 0, w, d, 16 + boost),           # top (+Y)
        (d + w, 0, w, d, -20 + boost),      # bottom (-Y)
        (0, d, d, h, -6 + boost),           # right (+X)
        (d, d, w, h, 2 + boost),            # front (+Z)
        (d + w, d, d, h, -10 + boost),      # left (-X)
        (2 * d + w, d, w, h, -14 + boost),  # back (-Z)
    ]
    for dx, dy, fw, fh, delta in faces:
        for yy in range(dy, dy + fh):
            for xx in range(dx, dx + fw):
                n = rng.randint(-5, 5)
                r, g, b = shade(base, delta + n)
                px[u0 + xx, v0 + yy] = (r, g, b, 255)

    # 1px darker outline keeps the part readable against neighbours.
    total_w, total_h = part.box_uv_size()
    edge = shade(base, -34)
    for xx in range(total_w):
        px[u0 + xx, v0] = (edge[0], edge[1], edge[2], 255)
        px[u0 + xx, v0 + total_h - 1] = (edge[0], edge[1], edge[2], 255)
    for yy in range(total_h):
        px[u0, v0 + yy] = (edge[0], edge[1], edge[2], 255)
        px[u0 + total_w - 1, v0 + yy] = (edge[0], edge[1], edge[2], 255)


def build(boss, parts):
    """Pack, paint and write one boss's textures + model JSON."""
    groups = {}
    for part in parts:
        groups.setdefault(part.texture, []).append(part)

    for texture, group in groups.items():
        pack(group)
        img = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
        for part in group:
            seed = 4207 + sum(ord(c) for c in part.name)
            paint_part(img, part, random.Random(seed))
        out_dir = os.path.join(TEX_OUT, *texture.split("/")[:-1])
        os.makedirs(out_dir, exist_ok=True)
        img.save(os.path.join(TEX_OUT, *texture.split("/")) + ".png")

    doc = {"parts": []}
    for part in parts:
        entry = {
            "name": part.name,
            "from": list(part.frm),
            "size": list(part.size),
            "absolute_offset": list(part.pivot),
            "texture": part.texture,
            "uv": list(part.uv),
            "texture_mapping": "cuboid_atlas",
        }
        if part.emissive:
            entry["emissive"] = True
        doc["parts"].append(entry)

    with open(os.path.join(MODEL_OUT, boss + ".json"), "w") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")
    print("wrote", boss, "(%d parts, %d textures)" % (len(parts), len(groups)))


# ── Wither Storm ────────────────────────────────────────────────────────────
# The MCSM sky-horror: a fused three-headed hulk over a glowing command core.
WITHER_STORM = [
    Part("tentacle_a", (-2, 2, -2), (5, 16, 5), (-2, 18, 0),
         "wither_storm/wither_storm_tentacles", (54, 46, 64)),
    Part("tentacle_b", (-2, 2, -2), (5, 16, 5), (2, 18, 0),
         "wither_storm/wither_storm_tentacles", (54, 46, 64)),
    Part("tentacle_c", (-2, 2, -2), (5, 16, 5), (0, 18, -2),
         "wither_storm/wither_storm_tentacles", (48, 40, 58)),
    Part("tentacle_d", (-2, 4, -2), (5, 12, 5), (0, 18, 2),
         "wither_storm/wither_storm_tentacles", (48, 40, 58)),
    Part("body_lower", (-7, 14, -5), (14, 12, 10), (0, 20, 0),
         "wither_storm/wither_storm_body", (74, 62, 84)),
    Part("body_upper", (-10, 24, -6), (20, 10, 12), (0, 29, 0),
         "wither_storm/wither_storm_body", (80, 66, 90)),
    Part("core_glow", (-4, 26, -4), (8, 8, 8), (0, 30, 0),
         "wither_storm/wither_storm_body", (86, 226, 214), emissive=True),
    Part("neck", (-4, 32, -4), (8, 10, 8), (0, 37, 0),
         "wither_storm/wither_storm_heads", (70, 58, 80)),
    Part("head_center", (-6, 38, -6), (12, 12, 12), (0, 44, 0),
         "wither_storm/wither_storm_heads", (92, 76, 102)),
    Part("head_left", (-16, 40, -4), (8, 8, 8), (-12, 44, 0),
         "wither_storm/wither_storm_heads", (86, 70, 96)),
    Part("head_right", (8, 40, -4), (8, 8, 8), (12, 44, 0),
         "wither_storm/wither_storm_heads", (86, 70, 96)),
]

# ── Great Hog ───────────────────────────────────────────────────────────────
# The Legends warlord: hoglin bulk, bronze wargear and a two-handed hammer.
GREAT_HOG = [
    Part("leg_fl", (-8, 0, -4), (6, 12, 6), (-5, 12, 0),
         "great_hog/great_hog_legs", (104, 72, 60)),
    Part("leg_fr", (2, 0, -4), (6, 12, 6), (5, 12, 0),
         "great_hog/great_hog_legs", (104, 72, 60)),
    Part("leg_bl", (-8, 0, 4), (6, 12, 6), (-5, 12, 0),
         "great_hog/great_hog_legs", (96, 66, 56)),
    Part("leg_br", (2, 0, 4), (6, 12, 6), (5, 12, 0),
         "great_hog/great_hog_legs", (96, 66, 56)),
    Part("body", (-9, 12, -5), (18, 16, 10), (0, 20, 0),
         "great_hog/great_hog", (124, 86, 70)),
    Part("head", (-5, 28, -5), (10, 12, 10), (0, 34, 0),
         "great_hog/great_hog", (132, 94, 78)),
    Part("snout", (-4, 30, -10), (8, 6, 6), (0, 33, -4),
         "great_hog/great_hog", (150, 110, 96)),
    Part("tusk_l", (-5, 28, -11), (4, 4, 6), (-4, 30, -5),
         "great_hog/great_hog", (232, 222, 200)),
    Part("tusk_r", (1, 28, -11), (4, 4, 6), (4, 30, -5),
         "great_hog/great_hog", (232, 222, 200)),
    Part("belt", (-8, 12, -5), (16, 5, 10), (0, 16, 0),
         "great_hog/great_hog_wargear", (92, 74, 46)),
    Part("pauldron_l", (-14, 26, -4), (8, 8, 8), (-10, 30, 0),
         "great_hog/great_hog_wargear", (78, 70, 74)),
    Part("pauldron_r", (6, 26, -4), (8, 8, 8), (10, 30, 0),
         "great_hog/great_hog_wargear", (78, 70, 74)),
    Part("hammer_handle", (-2, 14, -20), (4, 16, 4), (0, 20, -10),
         "great_hog/great_hog_wargear", (110, 78, 52)),
    Part("hammer_head", (-5, 28, -24), (10, 10, 10), (0, 30, -19),
         "great_hog/great_hog_wargear", (96, 88, 92)),
]

# ── Arch-Illager ────────────────────────────────────────────────────────────
# The Dungeons antagonist: a floating robed caster with a crackling staff.
ARCH_ILLAGER = [
    Part("robe", (-5, 0, -4), (10, 18, 8), (0, 18, 0),
         "arch_illager/arch_illager", (74, 60, 92)),
    Part("body", (-4, 14, -3), (8, 14, 6), (0, 21, 0),
         "arch_illager/arch_illager", (66, 54, 84)),
    Part("hood", (-6, 26, -4), (12, 10, 10), (0, 32, 0),
         "arch_illager/arch_illager", (58, 46, 78)),
    Part("head", (-4, 28, -4), (8, 10, 8), (0, 33, 0),
         "arch_illager/arch_illager", (110, 122, 98)),
    Part("nose", (-1, 30, -6), (2, 4, 2), (0, 32, -4),
         "arch_illager/arch_illager", (124, 134, 108)),
    Part("arm_r", (-8, 16, -2), (4, 10, 4), (-6, 26, 0),
         "arch_illager/arch_illager", (70, 58, 88)),
    Part("arm_l", (4, 16, -2), (4, 10, 4), (6, 26, 0),
         "arch_illager/arch_illager", (70, 58, 88)),
    Part("crown", (-4, 36, -4), (8, 4, 8), (0, 38, 0),
         "arch_illager/arch_illager_staff", (222, 182, 70)),
    Part("staff_rod", (-11, 4, -1), (3, 18, 3), (-9, 22, 0),
         "arch_illager/arch_illager_staff", (96, 74, 58)),
    Part("staff_orb", (-13, 22, -3), (6, 6, 6), (-10, 25, 0),
         "arch_illager/arch_illager_staff", (118, 224, 255), emissive=True),
]

# ── Heart of Ender ──────────────────────────────────────────────────────────
# The Dungeons finale: a segmented ender orb crowned by a burning eye.
HEART_OF_ENDER = [
    Part("tendril_a", (-2, 0, -2), (4, 18, 4), (-4, 18, 0),
         "heart_of_ender/heart_of_ender_tendrils", (82, 42, 122)),
    Part("tendril_b", (-2, 0, -2), (4, 18, 4), (4, 18, 0),
         "heart_of_ender/heart_of_ender_tendrils", (82, 42, 122)),
    Part("tendril_c", (-2, 0, -2), (4, 18, 4), (0, 18, -4),
         "heart_of_ender/heart_of_ender_tendrils", (74, 38, 112)),
    Part("tendril_d", (-2, 0, -2), (4, 18, 4), (0, 18, 4),
         "heart_of_ender/heart_of_ender_tendrils", (74, 38, 112)),
    Part("tendril_e", (-2, 2, -2), (4, 16, 4), (0, 18, 2),
         "heart_of_ender/heart_of_ender_tendrils", (68, 34, 104)),
    Part("orb_lower", (-4, 8, -4), (8, 8, 8), (0, 12, 0),
         "heart_of_ender/heart_of_ender", (110, 56, 160)),
    Part("orb_main", (-8, 14, -8), (16, 16, 16), (0, 22, 0),
         "heart_of_ender/heart_of_ender", (126, 66, 180)),
    Part("orb_upper", (-4, 30, -4), (8, 8, 8), (0, 34, 0),
         "heart_of_ender/heart_of_ender", (118, 60, 170)),
    Part("eye", (-4, 18, -10), (8, 8, 8), (0, 22, -6),
         "heart_of_ender/heart_of_ender", (124, 240, 200), emissive=True),
]

BOSSES = {
    "wither_storm": WITHER_STORM,
    "great_hog": GREAT_HOG,
    "arch_illager": ARCH_ILLAGER,
    "heart_of_ender": HEART_OF_ENDER,
}


def main():
    os.makedirs(MODEL_OUT, exist_ok=True)
    for boss in sorted(BOSSES):
        build(boss, BOSSES[boss])


if __name__ == "__main__":
    main()
