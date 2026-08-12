#!/usr/bin/env python3
"""
Generate 16x16 textures for the Create-features port batch:
  - hand_crank        (metal crank with handle)
  - windmill_bearing  (bearing hub, face)
  - windmill_sail     (canvas sail)
  - mechanical_press  (heavy press head + frame)
  - millstone         (stone grinding wheel)
  - crushing_wheel    (ribbed crushing wheel)
  - mechanical_drill  (drill tip)
  - mechanical_saw    (saw blade)
  - deployer          (deployer arm/face)
  - belt_conveyor     (rubber belt with treads)
  - item_vault        (storage crate face)
  - brass_casing      (brass block)
  - brass_ingot       (ingot item)
  - goggles           (goggle item)
  - wrench            (wrench item)
Also writes simple cube models for each block into models/block/.
"""
from PIL import Image, ImageDraw
import json, os

OUT_TEX = "src/main/resources/assets/minecraft/textures/blocks/"
OUT_MOD = "src/main/resources/assets/minecraft/models/block/"


def new_tex(name, pixels):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for (x, y), c in pixels.items():
        img.putpixel((x, y), c)
    img.save(OUT_TEX + name + ".png")


def hline(d, y, c, x0=0, x1=16):
    d.line([(x0, y), (x1, y)], fill=c)


def vline(d, x, c, y0=0, y1=16):
    d.line([(x, y0), (x, y1)], fill=c)


# ── Hand crank: dark iron base + gold handle ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
IRON = (90, 92, 98, 255); IRON_D = (58, 60, 66, 255); BRASS = (214, 168, 84, 255); BRASS_D = (150, 112, 48, 255)
for y in range(0, 16):
    hline(d, y, IRON_D if y % 3 == 0 else IRON)
d.rectangle([6, 0, 9, 5], fill=BRASS)          # handle stem
d.rectangle([5, 0, 10, 2], fill=BRASS_D)       # handle top
d.rectangle([7, 5, 8, 16], fill=IRON_D)        # spindle
d.rectangle([2, 12, 13, 16], fill=IRON)        # base plate
img.save(OUT_TEX + "hand_crank.png")

# ── Windmill bearing: circular hub ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
GRAY = (120, 124, 132, 255); GRAY_D = (70, 74, 80, 255); SHAFT = (60, 62, 66, 255)
for r, c in [(8, GRAY_D), (6, GRAY), (3, SHAFT)]:
    d.ellipse([8 - r, 8 - r, 8 + r, 8 + r], fill=c)
for a in range(0, 360, 30):
    import math
    x0 = 8 + math.cos(math.radians(a)) * 2
    y0 = 8 + math.sin(math.radians(a)) * 2
    x1 = 8 + math.cos(math.radians(a)) * 7
    y1 = 8 + math.sin(math.radians(a)) * 7
    d.line([(x0, y0), (x1, y1)], fill=SHAFT)
d.rectangle([0, 0, 16, 1], fill=GRAY_D)
d.rectangle([0, 15, 16, 16], fill=GRAY_D)
d.rectangle([0, 0, 1, 16], fill=GRAY_D)
d.rectangle([15, 0, 16, 16], fill=GRAY_D)
img.save(OUT_TEX + "windmill_bearing.png")

# ── Windmill sail: canvas with cross bars ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
CANVAS = (226, 218, 192, 255); BAR = (110, 74, 40, 255)
for y in range(16):
    hline(d, y, CANVAS)
for y in range(0, 16, 4):
    hline(d, y, BAR)
vline(d, 4, BAR); vline(d, 11, BAR)
img.save(OUT_TEX + "windmill_sail.png")

# ── Mechanical press: heavy frame + piston ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
STEEL = (132, 136, 144, 255); STEEL_D = (88, 92, 98, 255); PISTON = (46, 48, 52, 255)
for y in range(16):
    hline(d, y, STEEL if y not in (0, 1, 14, 15) else STEEL_D)
d.rectangle([1, 6, 14, 10], fill=STEEL_D)   # press head band
d.rectangle([6, 2, 9, 6], fill=PISTON)      # piston
d.rectangle([5, 10, 10, 14], fill=PISTON)   # die
img.save(OUT_TEX + "mechanical_press.png")

# ── Millstone: stone wheel with grooves ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
STONE = (150, 148, 140, 255); STONE_D = (104, 102, 96, 255)
for y in range(16):
    hline(d, y, STONE)
for y in range(0, 16, 2):
    hline(d, y, STONE_D)
for x in range(0, 16, 4):
    vline(d, x, STONE_D)
d.ellipse([6, 6, 9, 9], fill=STONE_D)
img.save(OUT_TEX + "millstone.png")

# ── Crushing wheel: ribbed heavy wheel ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
WHEEL = (118, 112, 104, 255); WHEEL_D = (78, 72, 66, 255); RIB = (52, 50, 46, 255)
for y in range(16):
    hline(d, y, WHEEL)
d.ellipse([2, 2, 13, 13], fill=WHEEL)
d.ellipse([4, 4, 11, 11], outline=WHEEL_D)
for a in range(0, 360, 45):
    import math
    x0 = 8 + math.cos(math.radians(a)) * 2
    y0 = 8 + math.sin(math.radians(a)) * 2
    x1 = 8 + math.cos(math.radians(a)) * 6
    y1 = 8 + math.sin(math.radians(a)) * 6
    d.line([(x0, y0), (x1, y1)], fill=RIB)
img.save(OUT_TEX + "crushing_wheel.png")

# ── Mechanical drill: drill tip pointing out ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
DRUM = (96, 100, 108, 255); TIP = (188, 192, 200, 255)
for y in range(16):
    hline(d, y, DRUM)
d.polygon([(8, 2), (4, 8), (12, 8)], fill=TIP)   # pointed tip
d.polygon([(7, 3), (5, 8), (9, 8)], fill=(140, 144, 152, 255))
img.save(OUT_TEX + "mechanical_drill.png")

# ── Mechanical saw: round blade with teeth ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
BLADE = (176, 180, 188, 255); BLADE_D = (120, 124, 132, 255)
d.ellipse([3, 3, 12, 12], fill=BLADE)
d.ellipse([6, 6, 9, 9], fill=BLADE_D)
import math
for a in range(0, 360, 30):
    x0 = 8 + math.cos(math.radians(a)) * 5.5
    y0 = 8 + math.sin(math.radians(a)) * 5.5
    x1 = 8 + math.cos(math.radians(a)) * 8
    y1 = 8 + math.sin(math.radians(a)) * 8
    d.line([(x0, y0), (x1, y1)], fill=BLADE_D)
img.save(OUT_TEX + "mechanical_saw.png")

# ── Deployer: arm face with gripper ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
ARM = (110, 116, 126, 255); ARM_D = (70, 74, 82, 255); GRIP = (44, 46, 50, 255)
for y in range(16):
    hline(d, y, ARM)
d.rectangle([6, 0, 9, 5], fill=ARM_D)    # arm
d.rectangle([4, 5, 11, 9], fill=GRIP)    # gripper head
d.rectangle([2, 9, 13, 12], fill=ARM_D)  # base
img.save(OUT_TEX + "deployer.png")

# ── Belt conveyor: rubber treads ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
BELT = (58, 54, 52, 255); TREAD = (92, 86, 82, 255)
for y in range(16):
    hline(d, y, BELT)
for x in range(0, 16, 2):
    vline(d, x, TREAD)
hline(d, 0, TREAD); hline(d, 15, TREAD)
img.save(OUT_TEX + "belt_conveyor.png")

# ── Item vault: brass crate face ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
BRASS2 = (196, 154, 78, 255); BRASS2_D = (130, 98, 44, 255)
for y in range(16):
    hline(d, y, BRASS2)
d.rectangle([1, 1, 14, 14], outline=BRASS2_D, width=2)
d.rectangle([3, 3, 12, 12], outline=BRASS2_D)
d.rectangle([6, 6, 9, 9], fill=BRASS2_D)
img.save(OUT_TEX + "item_vault.png")

# ── Brass casing / brass ingot ──
img = Image.new("RGBA", (16, 16), (196, 154, 78, 255))
d = ImageDraw.Draw(img)
for y in range(0, 16, 4):
    hline(d, y, (130, 98, 44, 255))
img.save(OUT_TEX + "brass_casing.png")

img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
for y in range(4, 13):
    d.line([(4, y), (11, y)], fill=(196, 154, 78, 255))
d.line([(3, 5), (3, 11)], fill=(130, 98, 44, 255))
d.line([(12, 5), (12, 11)], fill=(244, 210, 130, 255))
img.save(OUT_TEX + "brass_ingot.png")

# ── Goggles: lens pair ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.ellipse([1, 3, 6, 8], fill=(120, 180, 220, 255))
d.ellipse([9, 3, 14, 8], fill=(120, 180, 220, 255))
d.ellipse([1, 3, 6, 8], outline=(60, 62, 66, 255), width=1)
d.ellipse([9, 3, 14, 8], outline=(60, 62, 66, 255), width=1)
d.line([(6, 5), (9, 5)], fill=(60, 62, 66, 255))
d.line([(3, 8), (8, 12)], fill=(80, 82, 88, 255))
d.line([(8, 12), (13, 8)], fill=(80, 82, 88, 255))
img.save(OUT_TEX + "goggles.png")

# ── Wrench: drop wrench ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.line([(4, 3), (11, 12)], fill=(120, 124, 132, 255), width=2)
d.line([(4, 3), (11, 12)], fill=(80, 82, 88, 255), width=1)
d.ellipse([2, 1, 6, 5], outline=(120, 124, 132, 255), width=1)
d.rectangle([9, 10, 14, 13], fill=(120, 124, 132, 255))
img.save(OUT_TEX + "wrench.png")

# ── Simple cube models ──
def cube_model(name, tex):
    with open(OUT_MOD + name + ".json", "w") as f:
        json.dump({
            "textures": {
                "up": tex, "down": tex, "north": tex,
                "south": tex, "west": tex, "east": tex,
                "particle": tex
            }
        }, f)

for name, tex in [
    ("windmill_bearing", "windmill_bearing"),
    ("windmill_sail", "windmill_sail"),
    ("mechanical_press", "mechanical_press"),
    ("millstone", "millstone"),
    ("crushing_wheel", "crushing_wheel"),
    ("mechanical_drill", "mechanical_drill"),
    ("mechanical_saw", "mechanical_saw"),
    ("deployer", "deployer"),
    ("belt_conveyor", "belt_conveyor"),
    ("item_vault", "item_vault"),
    ("brass_casing", "brass_casing"),
]:
    cube_model(name, tex)

# Hand crank: slim block with handle column
with open(OUT_MOD + "hand_crank.json", "w") as f:
    json.dump({
        "textures": {
            "up": "hand_crank", "down": "axis_top", "north": "hand_crank",
            "south": "hand_crank", "west": "hand_crank", "east": "hand_crank",
            "particle": "hand_crank"
        },
        "elements": [
            {"from": [7, 0, 7], "to": [9, 16, 9]},
            {"from": [2, 12, 2], "to": [14, 16, 14]}
        ]
    }, f)

# Crushing wheel: wide flat wheel (horizontal axis) + standalone variant
with open(OUT_MOD + "crushing_wheel_standalone.json", "w") as f:
    json.dump({
        "textures": {
            "up": "crushing_wheel", "down": "crushing_wheel",
            "north": "crushing_wheel", "south": "crushing_wheel",
            "west": "crushing_wheel", "east": "crushing_wheel",
            "particle": "crushing_wheel"
        },
        "elements": [
            {"from": [0, 5, 5], "to": [16, 11, 11]}
        ]
    }, f)

print("Create-features textures + models written to " + OUT_TEX + " and " + OUT_MOD)
