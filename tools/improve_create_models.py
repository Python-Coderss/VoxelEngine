#!/usr/bin/env python3
"""
Improve the Create-mod machine 3D models.

The engine renders each block as a cube whose six faces take a separate
texture from the block-model JSON (up/down/north/south/west/east), with
optional `elements` (AABBs) carving the shape for non-full blocks. Most
Create machines were single-texture full cubes, so they read as painted
boxes. This script:

  1. Generates per-face textures (top / side / front / bottom) for every
     solid machine, so they look like proper blocks the way vanilla machines
     (furnace, piston, dispenser) do.
  2. Keeps the spinning faces animated. Wheel-like faces use 4-frame rotation
     of the *detail* over an opaque background (whole-image rotation would
     punch transparent holes into solid blocks); the belt/drill use a 4-frame
     scroll. Frames are 16x64 vertical strips, the convention the engine's
     TextureManager auto-detects.
  3. Writes AABB shapes for the genuinely thin blocks (windmill sail, belt).

Run:  python tools/improve_create_models.py
"""
from PIL import Image, ImageDraw
import json, os, math

TEX = "src/main/resources/assets/minecraft/textures/blocks/"
MOD = "src/main/resources/assets/minecraft/models/block/"

# ── Palette ────────────────────────────────────────────────────────────
BRASS      = (196, 154, 78, 255)
BRASS_D    = (130, 98, 44, 255)
BRASS_L    = (244, 210, 130, 255)
IRON       = (132, 136, 144, 255)
IRON_D     = (88, 92, 98, 255)
IRON_VD    = (46, 48, 52, 255)
STEEL_L    = (188, 192, 200, 255)
COPPER     = (200, 110, 60, 255)
COPPER_D   = (120, 62, 30, 255)
COPPER_L   = (235, 160, 105, 255)
STONE      = (150, 148, 140, 255)
STONE_D    = (104, 102, 96, 255)
WOOD       = (150, 110, 70, 255)
WOOD_D     = (100, 70, 42, 255)
BELT       = (58, 54, 52, 255)
TREAD      = (96, 90, 86, 255)
CANVAS     = (226, 218, 192, 255)
CANVAS_BAR = (110, 74, 40, 255)
FIRE       = (255, 160, 40, 255)
FIRE_HOT   = (255, 220, 120, 255)


def save(name, img):
    img.save(TEX + name + ".png")


def opaque(bg=(0, 0, 0, 0)):
    img = Image.new("RGBA", (16, 16), bg)
    return img, ImageDraw.Draw(img)


def frame_ring(d, color, width=2, inset=0):
    d.rectangle([inset, inset, 15 - inset, 15 - inset], outline=color, width=width)


def spokes(d, cx, cy, r0, r1, n, color, offset=0.0, width=1):
    for i in range(n):
        a = offset + i * 360.0 / n
        x0 = cx + math.cos(math.radians(a)) * r0
        y0 = cy + math.sin(math.radians(a)) * r0
        x1 = cx + math.cos(math.radians(a)) * r1
        y1 = cy + math.sin(math.radians(a)) * r1
        d.line([(x0, y0), (x1, y1)], fill=color, width=width)


def detail_rot_strip(base_color, detail_fn, frames=4):
    """4-frame strip: opaque base + rotating detail (no transparency holes)."""
    strip = Image.new("RGBA", (16, 16 * frames))
    for k in range(frames):
        img, d = opaque(base_color)
        detail_fn(d, k * 45.0)
        strip.paste(img, (0, 16 * k))
    return strip


def scroll_strip(base):
    """4-frame horizontal scroll strip from a 16x16 base."""
    w, h = base.size
    strip = Image.new("RGBA", (w, h * 4))
    for i, s in enumerate((0, 4, 8, 12)):
        f = Image.new("RGBA", (w, h))
        f.paste(base.crop((s, 0, w, h)), (0, 0))
        f.paste(base.crop((0, 0, s, h)), (w - s, 0))
        strip.paste(f, (0, i * h))
    return strip


# ══════════════════════════════════════════════════════════════════════
#  MILLSTONE — grooved stone top (rotating grooves), plain stone sides
# ══════════════════════════════════════════════════════════════════════
def millstone_top_detail(d, a):
    d.ellipse([3, 3, 12, 12], outline=STONE_D)
    spokes(d, 8, 8, 5, 8, 8, STONE_D, offset=a)
    d.ellipse([6, 6, 9, 9], fill=STONE_D)
    d.rectangle([0, 0, 15, 15], outline=STONE_D)


save("millstone_top", detail_rot_strip(STONE, millstone_top_detail))

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=STONE if y % 4 else STONE_D)
d.rectangle([0, 0, 16, 1], fill=STONE_D)
d.rectangle([0, 15, 16, 16], fill=STONE_D)
save("millstone_side", img)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=STONE_D if y % 4 else (110, 108, 102, 255))
frame_ring(d, (70, 70, 66, 255))
save("millstone_bottom", img)


# ══════════════════════════════════════════════════════════════════════
#  CRUSHING WHEEL — ribbed vertical wheel (rotating ribs)
# ══════════════════════════════════════════════════════════════════════
WHEEL = (118, 112, 104, 255)
WHEEL_D = (78, 72, 66, 255)
RIB = (52, 50, 46, 255)


def crusher_wheel_detail(d, a):
    d.ellipse([2, 2, 13, 13], outline=WHEEL_D)
    spokes(d, 8, 8, 4, 7, 8, RIB, offset=a)
    d.ellipse([5, 5, 10, 10], outline=WHEEL_D)
    d.ellipse([7, 7, 9, 9], fill=RIB)
    d.rectangle([0, 0, 15, 15], outline=WHEEL_D)


save("crusher_wheel", detail_rot_strip(WHEEL, crusher_wheel_detail))

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=WHEEL_D if y % 3 == 0 else (90, 86, 80, 255))
frame_ring(d, (46, 44, 40, 255))
d.rectangle([6, 0, 9, 16], fill=(70, 66, 60, 255))
save("crusher_rim", img)


# ══════════════════════════════════════════════════════════════════════
#  MECHANICAL PRESS — heavy frame with a raised head
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=STEEL_L if y % 3 else IRON)
d.rectangle([1, 2, 3, 14], fill=IRON_D)
d.rectangle([12, 2, 14, 14], fill=IRON_D)
d.rectangle([6, 3, 9, 12], fill=IRON_VD)
d.rectangle([2, 6, 13, 9], fill=IRON)
save("press_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON)
d.rectangle([2, 2, 14, 14], fill=IRON_D)
d.rectangle([4, 4, 12, 12], fill=IRON)
d.rectangle([6, 6, 9, 9], fill=STEEL_L)
save("press_top", img)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=IRON if y % 3 else IRON_D)
d.rectangle([4, 2, 11, 7], fill=STEEL_L)
d.rectangle([4, 2, 11, 7], outline=IRON_D)
d.rectangle([5, 7, 10, 13], fill=IRON_VD)
save("press_front", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON_D)
d.rectangle([1, 1, 15, 15], fill=IRON)
d.rectangle([5, 5, 10, 10], fill=IRON_D)
save("press_bottom", img)


# ══════════════════════════════════════════════════════════════════════
#  MECHANICAL DRILL — scrolling flutes, static top, pointed tip
# ══════════════════════════════════════════════════════════════════════
DRUM = (96, 100, 108, 255)
DRUM_D = (60, 63, 70, 255)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=DRUM)
for x in range(0, 16, 4):
    d.line([(x, 0), (x, 15)], fill=DRUM_D)
frame_ring(d, (40, 42, 48, 255))
save("drill_side", scroll_strip(img))

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=DRUM)
d.ellipse([3, 3, 12, 12], fill=DRUM_D)
d.ellipse([6, 6, 9, 9], fill=DRUM)
save("drill_top", img)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=DRUM)
d.polygon([(8, 1), (3, 8), (13, 8)], fill=STEEL_L)
d.polygon([(7, 3), (5, 8), (11, 8)], fill=(150, 154, 162, 255))
d.rectangle([4, 8, 11, 15], fill=DRUM_D)
save("drill_front", img)


# ══════════════════════════════════════════════════════════════════════
#  MECHANICAL SAW — box body with a toothed blade on the working face
# ══════════════════════════════════════════════════════════════════════
BLADE = (176, 180, 188, 255)
BLADE_D = (120, 124, 132, 255)


def saw_blade_detail(d, a):
    d.ellipse([3, 3, 12, 12], fill=BLADE)
    d.ellipse([5, 5, 10, 10], outline=BLADE_D)
    d.ellipse([7, 7, 9, 9], fill=BLADE_D)
    for i in range(12):
        ang = a + i * 30.0
        x0 = 8 + math.cos(math.radians(ang)) * 5.0
        y0 = 8 + math.sin(math.radians(ang)) * 5.0
        x1 = 8 + math.cos(math.radians(ang)) * 7.5
        y1 = 8 + math.sin(math.radians(ang)) * 7.5
        d.line([(x0, y0), (x1, y1)], fill=BLADE_D)
    d.rectangle([0, 0, 15, 15], outline=BLADE_D)


save("saw_blade", detail_rot_strip(BLADE_D, saw_blade_detail))

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=IRON if y % 3 else IRON_D)
d.rectangle([1, 1, 4, 15], fill=IRON_D)
d.rectangle([12, 1, 15, 15], fill=IRON_D)
d.rectangle([6, 3, 9, 13], fill=IRON_VD)
save("saw_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON)
d.rectangle([2, 2, 14, 14], fill=IRON_D)
d.rectangle([5, 5, 11, 11], fill=IRON)
save("saw_top", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON_D)
d.rectangle([1, 1, 15, 15], fill=IRON)
save("saw_bottom", img)


# ══════════════════════════════════════════════════════════════════════
#  DEPLOYER — box with a gripper face
# ══════════════════════════════════════════════════════════════════════
ARM = (110, 116, 126, 255)
ARM_D = (70, 74, 82, 255)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=ARM)
d.rectangle([0, 0, 3, 16], fill=ARM_D)
d.rectangle([13, 0, 16, 16], fill=ARM_D)
d.rectangle([4, 2, 12, 5], fill=ARM_D)
d.rectangle([4, 11, 12, 14], fill=ARM_D)
save("deployer_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=ARM)
d.rectangle([2, 2, 14, 14], fill=ARM_D)
d.ellipse([5, 5, 10, 10], fill=ARM)
save("deployer_top", img)

img, d = opaque()
GRIP = (44, 46, 50, 255)
for y in range(16):
    d.line([(0, y), (15, y)], fill=ARM)
d.rectangle([6, 0, 9, 4], fill=ARM_D)
d.rectangle([4, 4, 11, 8], fill=GRIP)
d.rectangle([2, 8, 13, 11], fill=ARM_D)
d.rectangle([4, 11, 11, 14], fill=GRIP)
save("deployer_front", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=ARM_D)
d.rectangle([1, 1, 15, 15], fill=ARM)
save("deployer_bottom", img)


# ══════════════════════════════════════════════════════════════════════
#  ITEM VAULT — brass crate with a door on the front
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=BRASS)
frame_ring(d, BRASS_D)
d.rectangle([3, 3, 13, 13], outline=BRASS_D)
d.line([(3, 8), (13, 8)], fill=BRASS_D)
save("vault_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=BRASS_D)
d.rectangle([1, 1, 15, 15], fill=BRASS)
d.rectangle([3, 3, 13, 13], outline=BRASS_L)
save("vault_top", img)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=BRASS)
frame_ring(d, BRASS_D)
d.rectangle([3, 2, 13, 14], fill=BRASS_D)
d.rectangle([4, 3, 12, 13], fill=BRASS)
d.rectangle([7, 7, 9, 9], fill=BRASS_D)
save("vault_front", img)


# ══════════════════════════════════════════════════════════════════════
#  BRASS CASING — brass block with a distinct top/bottom
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=BRASS if y % 4 else BRASS_D)
d.rectangle([0, 0, 3, 16], fill=BRASS_D)
d.rectangle([13, 0, 16, 16], fill=BRASS_D)
save("brass_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=BRASS_D)
d.rectangle([1, 1, 15, 15], fill=BRASS)
d.rectangle([3, 3, 13, 13], outline=BRASS_L)
d.ellipse([6, 6, 9, 9], fill=BRASS_L)
save("brass_top", img)


# ══════════════════════════════════════════════════════════════════════
#  BLAZE BURNER — metal grate bowl (lit + unlit)
# ══════════════════════════════════════════════════════════════════════
def burner_side(lit):
    METAL = (70, 70, 74, 255)
    METAL_L = (110, 110, 116, 255)
    METAL_D = (40, 40, 44, 255)
    img, d = opaque()
    for y in range(16):
        d.line([(0, y), (15, y)], fill=METAL)
    d.rectangle([0, 0, 16, 3], fill=METAL_D)
    d.rectangle([3, 3, 13, 16], fill=METAL_L)
    for y in range(6, 16, 3):
        d.line([(3, y), (13, y)], fill=METAL_D)
    if lit:
        d.rectangle([4, 9, 12, 14], fill=(255, 140, 40, 255))
    return img


def burner_top(lit):
    METAL = (70, 70, 74, 255)
    METAL_L = (110, 110, 116, 255)
    METAL_D = (40, 40, 44, 255)
    img, d = opaque()
    d.rectangle([0, 0, 16, 16], fill=METAL_D)
    d.rectangle([1, 1, 15, 15], fill=METAL)
    d.rectangle([3, 3, 13, 13], fill=METAL_L)
    d.rectangle([4, 4, 12, 12], outline=METAL_D)
    d.rectangle([6, 6, 10, 10], fill=METAL_D)
    d.rectangle([7, 7, 9, 9], fill=METAL)
    if lit:
        d.polygon([(6, 12), (8, 5), (10, 12)], fill=FIRE)
        d.polygon([(7, 12), (8, 8), (9, 12)], fill=FIRE_HOT)
    return img


save("burner_side", burner_side(False))
save("burner_top", burner_top(False))
save("burner_lit_side", burner_side(True))
save("burner_lit_top", burner_top(True))


# ══════════════════════════════════════════════════════════════════════
#  STEAM ENGINE — cylinder on a base (cold + active)
# ══════════════════════════════════════════════════════════════════════
def engine_side(active):
    IRON_ = (150, 150, 155, 255)
    IRON_D_ = (80, 80, 85, 255)
    img, d = opaque()
    d.rectangle([0, 0, 16, 3], fill=IRON_D_)
    for y in range(3, 16):
        d.line([(0, y), (15, y)], fill=IRON_)
    d.rectangle([3, 3, 13, 16], fill=(120, 120, 126, 255))
    d.rectangle([3, 3, 13, 16], outline=IRON_D_)
    d.rectangle([7, 4, 9, 12], fill=IRON_)
    d.ellipse([10, 4, 15, 9], fill=COPPER)
    d.ellipse([11, 5, 14, 8], fill=COPPER_L)
    if active:
        d.rectangle([4, 11, 7, 13], fill=(255, 140, 40, 255))
        d.ellipse([5, 0, 7, 2], fill=(230, 230, 240, 255))
        d.ellipse([9, 0, 12, 2], fill=(230, 230, 240, 255))
    return img


def engine_top(active):
    IRON_ = (150, 150, 155, 255)
    IRON_D_ = (80, 80, 85, 255)
    img, d = opaque()
    d.rectangle([0, 0, 16, 16], fill=IRON_)
    d.rectangle([0, 0, 16, 16], outline=IRON_D_)
    d.rectangle([3, 3, 13, 13], fill=IRON_D_)
    d.ellipse([5, 5, 10, 10], fill=IRON_)
    if active:
        d.ellipse([6, 6, 9, 9], fill=(255, 160, 60, 255))
    return img


save("engine_side", engine_side(False))
save("engine_top", engine_top(False))
save("engine_active_side", engine_side(True))
save("engine_active_top", engine_top(True))


# ══════════════════════════════════════════════════════════════════════
#  WINDMILL BEARING — hub block + axle
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
GRAY = (120, 124, 132, 255)
GRAY_D = (70, 74, 80, 255)
for y in range(16):
    d.line([(0, y), (15, y)], fill=GRAY)
frame_ring(d, GRAY_D)
d.rectangle([7, 0, 9, 16], fill=GRAY_D)
save("bearing_side", img)

img, d = opaque()
GRAY = (120, 124, 132, 255)
GRAY_D = (70, 74, 80, 255)
SHAFT = (60, 62, 66, 255)
d.rectangle([0, 0, 16, 16], fill=GRAY_D)
d.rectangle([1, 1, 15, 15], fill=GRAY)
for r, c in [(6, GRAY_D), (3, SHAFT)]:
    d.ellipse([8 - r, 8 - r, 8 + r, 8 + r], fill=c)
spokes(d, 8, 8, 2, 6, 8, SHAFT)
save("bearing_top", img)


# ══════════════════════════════════════════════════════════════════════
#  WINDMILL SAIL — canvas panel (rotating crossbar shading)
# ══════════════════════════════════════════════════════════════════════
def sail_face_detail(d, a):
    for y in range(2, 14):
        d.line([(2, y), (13, y)], fill=CANVAS if (y // 4) % 2 == 0 else (210, 200, 172, 255))
    for y in range(2, 14, 4):
        d.line([(2, y), (13, y)], fill=CANVAS_BAR)
    # diagonal bracing that visually spins with the rotation angle
    spokes(d, 8, 8, 2, 7, 4, CANVAS_BAR, offset=a)
    frame_ring(d, CANVAS_BAR, width=1)


save("sail_face", detail_rot_strip(CANVAS, sail_face_detail))

img, d = opaque()
d.line([(2, 8), (13, 8)], fill=CANVAS_BAR)
d.rectangle([2, 7, 13, 9], fill=(190, 180, 150, 255))
save("sail_edge", img)


# ══════════════════════════════════════════════════════════════════════
#  BELT CONVEYOR — scrolling tread top, casing sides
# ══════════════════════════════════════════════════════════════════════
img, d = opaque((0, 0, 0, 0))
for y in range(16):
    d.line([(0, y), (15, y)], fill=BELT)
for x in range(0, 16, 2):
    d.line([(x, 0), (x, 15)], fill=TREAD)
d.line([(0, 0), (15, 0)], fill=TREAD)
d.line([(0, 15), (15, 15)], fill=TREAD)
save("belt_top", scroll_strip(img))

img, d = opaque()
for y in range(6, 12):
    d.line([(0, y), (15, y)], fill=IRON)
d.line([(0, 5), (15, 5)], fill=IRON_D)
d.line([(0, 12), (15, 12)], fill=IRON_D)
d.ellipse([1, 4, 5, 12], fill=IRON_D)
d.ellipse([10, 4, 14, 12], fill=IRON_D)
save("belt_side", img)


# ══════════════════════════════════════════════════════════════════════
#  ENCASED FAN — planks casing with a metal fan face
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=WOOD if y % 4 else WOOD_D)
frame_ring(d, WOOD_D)
save("fan_side", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON_D)
d.rectangle([1, 1, 15, 15], fill=IRON)
save("fan_top", img)

img, d = opaque()
for y in range(16):
    d.line([(0, y), (15, y)], fill=IRON)
frame_ring(d, IRON_D)
d.ellipse([3, 3, 12, 12], fill=IRON_D)
spokes(d, 8, 8, 2, 6, 6, STEEL_L, width=2)
d.ellipse([7, 7, 9, 9], fill=IRON_VD)
save("fan_front", img)


# ══════════════════════════════════════════════════════════════════════
#  CLUTCH / GEARSHIFT tops (sides reuse clutch_off/on, gearshift_off/on)
# ══════════════════════════════════════════════════════════════════════
img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON)
d.rectangle([2, 2, 14, 14], fill=IRON_D)
d.rectangle([6, 4, 9, 12], fill=STEEL_L)
d.rectangle([7, 2, 8, 5], fill=BRASS)
save("clutch_top", img)

img, d = opaque()
d.rectangle([0, 0, 16, 16], fill=IRON)
d.rectangle([2, 2, 14, 14], fill=IRON_D)
d.rectangle([3, 3, 13, 13], outline=IRON)
d.rectangle([7, 2, 9, 14], fill=BRASS)
d.ellipse([6, 1, 10, 5], fill=BRASS_L)
save("gearshift_top", img)


# ══════════════════════════════════════════════════════════════════════
#  Model JSONs
# ══════════════════════════════════════════════════════════════════════
def model(name, textures, elements=None):
    out = {"textures": textures}
    if elements:
        out["elements"] = elements
    with open(MOD + name + ".json", "w") as f:
        json.dump(out, f, indent=2)
    print("model", name)


def cube_faces(up, down, north, south, west, east, particle=None):
    return {
        "up": up, "down": down, "north": north,
        "south": south, "west": west, "east": east,
        "particle": particle or up,
    }


model("millstone", cube_faces("millstone_top", "millstone_bottom",
                              "millstone_side", "millstone_side",
                              "millstone_side", "millstone_side"))

model("crushing_wheel", cube_faces("crusher_rim", "crusher_rim",
                                   "crusher_wheel", "crusher_wheel",
                                   "crusher_rim", "crusher_rim"))
model("crushing_wheel_standalone", cube_faces("crusher_rim", "crusher_rim",
                                              "crusher_wheel", "crusher_wheel",
                                              "crusher_rim", "crusher_rim"),
      [{"from": [0, 4, 4], "to": [16, 12, 12]}])

model("mechanical_press", cube_faces("press_top", "press_bottom",
                                     "press_side", "press_side",
                                     "press_side", "press_front"))

model("mechanical_drill", cube_faces("drill_top", "drill_front",
                                     "drill_side", "drill_side",
                                     "drill_side", "drill_front"))

model("mechanical_saw", cube_faces("saw_top", "saw_bottom",
                                   "saw_side", "saw_side",
                                   "saw_side", "saw_blade"))

model("deployer", cube_faces("deployer_top", "deployer_bottom",
                             "deployer_side", "deployer_side",
                             "deployer_side", "deployer_front"))

model("item_vault", cube_faces("vault_top", "vault_top",
                               "vault_side", "vault_side",
                               "vault_side", "vault_front"))

model("brass_casing", cube_faces("brass_top", "brass_top",
                                 "brass_side", "brass_side",
                                 "brass_side", "brass_side"))

model("blaze_burner", cube_faces("burner_top", "burner_top",
                                 "burner_side", "burner_side",
                                 "burner_side", "burner_side"))
model("blaze_burner_lit", cube_faces("burner_lit_top", "burner_lit_top",
                                     "burner_lit_side", "burner_lit_side",
                                     "burner_lit_side", "burner_lit_side"))

model("steam_engine", cube_faces("engine_top", "engine_top",
                                 "engine_side", "engine_side",
                                 "engine_side", "engine_side"))
model("steam_engine_active", cube_faces("engine_active_top", "engine_active_top",
                                        "engine_active_side", "engine_active_side",
                                        "engine_active_side", "engine_active_side"))

model("copper_tank", cube_faces("copper_tank", "copper_tank",
                                "copper_tank_l0", "copper_tank_l0",
                                "copper_tank_l0", "copper_tank_l0"))
for lev in range(1, 6):
    model("copper_tank_%d" % lev,
          cube_faces("copper_tank", "copper_tank",
                     "copper_tank_l%d" % lev, "copper_tank_l%d" % lev,
                     "copper_tank_l%d" % lev, "copper_tank_l%d" % lev))

model("windmill_bearing", cube_faces("bearing_top", "bearing_top",
                                     "bearing_side", "bearing_side",
                                     "bearing_side", "bearing_side"))

model("windmill_sail", {
    "north": "sail_face", "south": "sail_face",
    "up": "sail_edge", "down": "sail_edge",
    "west": "sail_edge", "east": "sail_edge",
    "particle": "sail_face",
}, [{"from": [2, 0, 7], "to": [14, 16, 9]}])

model("belt_conveyor", cube_faces("belt_top", "belt_side",
                                  "belt_side", "belt_side",
                                  "belt_side", "belt_side"),
      [
          {"from": [0, 9, 0], "to": [16, 11, 16]},
          {"from": [0, 5, 0], "to": [2, 9, 16]},
          {"from": [14, 5, 0], "to": [16, 9, 16]},
          {"from": [0, 5, 0], "to": [16, 9, 2]},
          {"from": [0, 5, 14], "to": [16, 9, 16]},
      ])

model("clutch", cube_faces("clutch_top", "clutch_top",
                           "clutch_off", "clutch_off",
                           "clutch_off", "clutch_off"))
model("clutch_on", cube_faces("clutch_top", "clutch_top",
                              "clutch_on", "clutch_on",
                              "clutch_on", "clutch_on"))
model("gearshift", cube_faces("gearshift_top", "gearshift_top",
                              "gearshift_off", "gearshift_off",
                              "gearshift_off", "gearshift_off"))
model("gearshift_on", cube_faces("gearshift_top", "gearshift_top",
                                 "gearshift_on", "gearshift_on",
                                 "gearshift_on", "gearshift_on"))

model("encased_fan", cube_faces("fan_top", "fan_top",
                                "fan_side", "fan_side",
                                "fan_side", "fan_front"))

print("done")
