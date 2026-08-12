#!/usr/bin/env python3
"""
Generate textures for the Nether/Create feature batch:
  - textures/entity/blaze.png        (64x32, cuboid-atlas blaze: head + 4 rods)
  - textures/entity/fireball.png     (64x32, orange ball in standard cube layout)
  - textures/blocks/blaze_burner.png       (16x16 metal burner, cold)
  - textures/blocks/blaze_burner_lit.png   (16x16 burner with flame)
  - textures/blocks/steam_engine.png       (16x16 piston engine, idle)
  - textures/blocks/steam_engine_active.png (16x16 engine, lit port + steam)
  - textures/blocks/copper_tank.png        (16x16 copper tank casing)
  - textures/blocks/tank_fluid.png         (16x16 translucent cyan liquid)
"""
from PIL import Image, ImageDraw

OUT = "src/main/resources/assets/"

def new(w, h):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0)), ImageDraw.Draw(Image.new("RGBA", (w, h)))

# ── Blaze (64x32): head cuboid w16,h16,d16 at uv(0,0); rods w4,h24,d4 at uv(32,0) ──
img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
HEAD = (255, 226, 92, 255)        # pale gold
HEAD_DARK = (120, 90, 30, 255)
EYE = (255, 120, 0, 255)
ROD = (235, 210, 110, 255)
ROD_DARK = (120, 95, 40, 255)

# Head regions (standard cuboid layout, uv(0,0), w=h=d=16):
# TOP(16,0)-(32,16) BOTTOM(32,0)-(48,16) RIGHT(0,16)-(16,32)
# FRONT(16,16)-(32,32) LEFT(32,16)-(48,32) BACK(48,16)-(64,32)
d.rectangle([16, 0, 32, 16], fill=HEAD)          # top
d.rectangle([32, 0, 48, 16], fill=HEAD_DARK)     # bottom
d.rectangle([0, 16, 64, 32], fill=HEAD)          # sides
d.rectangle([16, 16, 32, 32], outline=HEAD_DARK) # front outline
d.rectangle([48, 16, 64, 32], outline=HEAD_DARK) # back outline
# Eyes on front + back faces
for x0 in (16, 48):
    d.rectangle([x0 + 4, 20, x0 + 11, 24], fill=EYE)
    d.rectangle([x0 + 4, 20, x0 + 11, 24], outline=(80, 40, 0, 255))
# Rod regions (uv(32,0), w4,h24,d4): TOP(36,0)-(40,4) BOTTOM(40,0)-(44,4)
# RIGHT(32,4)-(36,28) FRONT(36,4)-(40,28) LEFT(40,4)-(44,28) BACK(44,4)-(48,28)
d.rectangle([32, 0, 48, 32], fill=(0, 0, 0, 0))
d.rectangle([36, 0, 40, 4], fill=ROD)            # rod top
d.rectangle([40, 0, 44, 4], fill=ROD_DARK)       # rod bottom
d.rectangle([32, 4, 36, 28], fill=ROD)           # right sliver
d.rectangle([36, 4, 40, 28], fill=ROD)           # rod front
d.rectangle([40, 4, 44, 28], fill=ROD_DARK)      # left sliver
d.rectangle([44, 4, 48, 28], fill=ROD)           # rod back
# dark bands across the rod front/back every 6px
for y in range(10, 28, 6):
    d.rectangle([36, y, 40, y + 2], fill=ROD_DARK)
    d.rectangle([44, y, 48, y + 2], fill=ROD_DARK)
img.save(OUT + "minecraft/textures/entity/blaze.png")

# ── Fireball (64x32): cube w8,h8,d8 at uv(0,0) ──
img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
FIRE = (255, 150, 40, 255)
FIRE_HOT = (255, 220, 120, 255)
# TOP(8,0)-(16,8) BOTTOM(16,0)-(24,8) RIGHT(0,8)-(8,16)
# FRONT(8,8)-(16,16) LEFT(16,8)-(24,16) BACK(24,8)-(32,16)
for x0 in (8, 16, 24):
    d.rectangle([x0, 0, x0 + 8, 8], fill=FIRE)
    d.ellipse([x0 + 2, 2, x0 + 6, 6], fill=FIRE_HOT)
for x0 in (0, 8, 16, 24):
    d.rectangle([x0, 8, x0 + 8, 16], fill=FIRE)
    d.ellipse([x0 + 2, 10, x0 + 6, 14], fill=FIRE_HOT)
img.save(OUT + "minecraft/textures/entity/fireball.png")

# ── Blaze burner (16x16) ──
def burner(lit):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    METAL = (70, 70, 74, 255)
    METAL_L = (110, 110, 116, 255)
    METAL_D = (40, 40, 44, 255)
    d.rectangle([0, 0, 16, 16], fill=METAL)
    d.rectangle([0, 0, 16, 3], fill=METAL_D)       # dark rim
    d.rectangle([3, 3, 13, 13], fill=METAL_L)      # inner plate
    d.rectangle([4, 4, 12, 12], outline=METAL_D)   # grate
    d.rectangle([6, 6, 10, 10], fill=METAL_D)
    d.rectangle([7, 7, 9, 9], fill=METAL)          # center vent
    if lit:
        # flame in the vent
        d.polygon([(6, 12), (8, 5), (10, 12)], fill=(255, 200, 60, 255))
        d.polygon([(7, 12), (8, 8), (9, 12)], fill=(255, 120, 30, 255))
        # glow on the plate
        d.rectangle([2, 2, 14, 14], outline=(255, 140, 40, 120))
    return img

burner(False).save(OUT + "minecraft/textures/blocks/blaze_burner.png")
burner(True).save(OUT + "minecraft/textures/blocks/blaze_burner_lit.png")

# ── Steam engine (16x16) ──
def engine(active):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    IRON = (150, 150, 155, 255)
    IRON_D = (80, 80, 85, 255)
    COPPER = (200, 110, 60, 255)
    d.rectangle([1, 1, 15, 15], fill=IRON)
    d.rectangle([1, 1, 15, 15], outline=IRON_D)
    # cylinder block
    d.rectangle([3, 4, 13, 12], fill=(120, 120, 126, 255))
    d.rectangle([3, 4, 13, 12], outline=IRON_D)
    # piston rod
    d.rectangle([7, 8, 9, 12], fill=IRON)
    # flywheel
    d.ellipse([10, 4, 15, 9], fill=COPPER)
    d.ellipse([11, 5, 14, 8], fill=(230, 150, 90, 255))
    if active:
        # fire port + steam wisps
        d.rectangle([3, 11, 6, 13], fill=(255, 140, 40, 255))
        d.ellipse([4, 2, 6, 4], fill=(230, 230, 240, 200))
        d.ellipse([8, 1, 11, 3], fill=(230, 230, 240, 160))
    return img

engine(False).save(OUT + "minecraft/textures/blocks/steam_engine.png")
engine(True).save(OUT + "minecraft/textures/blocks/steam_engine_active.png")

# ── Copper tank casing (16x16) ──
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
CU = (200, 110, 60, 255)
CU_D = (120, 62, 30, 255)
CU_L = (235, 160, 105, 255)
d.rectangle([0, 0, 16, 16], fill=CU)
d.rectangle([0, 0, 16, 16], outline=CU_D)
d.rectangle([1, 1, 15, 15], outline=CU_L)
d.rectangle([3, 3, 13, 13], outline=CU_D)   # window frame
d.rectangle([4, 4, 12, 12], fill=(70, 90, 110, 255))  # dim glass
img.save(OUT + "minecraft/textures/blocks/copper_tank.png")

# ── Tank level textures (16x16 each): copper casing + side window with fill ──
# Window occupies rows y=3..13 inside the casing; fill rises from the window bottom.
def tank_level(fill_frac):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    CU = (200, 110, 60, 255)
    CU_D = (120, 62, 30, 255)
    CU_L = (235, 160, 105, 255)
    WIN = (60, 78, 96, 255)
    FL = (90, 200, 230, 235)
    FL_L = (150, 230, 245, 235)
    d.rectangle([0, 0, 16, 16], fill=CU)
    d.rectangle([0, 0, 16, 16], outline=CU_D)
    d.rectangle([1, 1, 15, 15], outline=CU_L)
    # window frame (3,3)-(13,13)
    d.rectangle([3, 3, 13, 13], fill=WIN)
    d.rectangle([3, 3, 13, 13], outline=CU_D)
    # fluid inside the window
    win_bottom = 13
    top = round(win_bottom - fill_frac * 10)
    if top < win_bottom:
        d.rectangle([4, top, 12, win_bottom - 1], fill=FL)
        d.ellipse([5, top + 1, 8, top + 4], fill=FL_L)
    return img

for i, frac in enumerate([0.0, 0.2, 0.4, 0.6, 0.8, 1.0]):
    tank_level(frac).save(OUT + "minecraft/textures/blocks/copper_tank_l%d.png" % i)
# legacy plain casing name used by the frame-only item
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, 16, 16], fill=(200, 110, 60, 255))
d.rectangle([0, 0, 16, 16], outline=(120, 62, 30, 255))
d.rectangle([1, 1, 15, 15], outline=(235, 160, 105, 255))
img.save(OUT + "minecraft/textures/blocks/copper_tank.png")

# ── Zombie pigman (64x64, classic pre-1.16 skin + golden sword at uv(48,48)) ──
img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
PIG = (229, 165, 158, 255)      # zombie pigman pink flesh
PIG_D = (180, 120, 115, 255)
PIG_L = (242, 195, 185, 255)
PANTS = (120, 130, 60, 255)     # olive green pants
PANTS_D = (80, 90, 40, 255)
GOLD = (235, 190, 80, 255)
GOLD_D = (160, 120, 40, 255)
EYE = (40, 30, 30, 255)

# Head cuboid w8,h8,d8 at uv(0,0): TOP(8,0)-(16,8) BOTTOM(16,0)-(24,8)
# RIGHT(0,8)-(8,16) FRONT(8,8)-(16,16) LEFT(16,8)-(24,16) BACK(24,8)-(32,16)
d.rectangle([8, 0, 16, 8], fill=PIG_L)     # top
d.rectangle([16, 0, 24, 8], fill=PIG_D)    # bottom
d.rectangle([0, 8, 32, 16], fill=PIG)      # sides
d.rectangle([8, 8, 16, 16], outline=PIG_D) # front outline
d.rectangle([24, 8, 32, 16], outline=PIG_D) # back outline
# pig snout + eyes on front face (8,8)-(16,16)
d.rectangle([10, 11, 14, 14], fill=PIG_D)  # snout
for ex in (10, 13):
    d.rectangle([ex, 9, ex + 1, 10], fill=EYE)

# Body cuboid w8,h12,d4 at uv(16,16): TOP(24,16)-(32,16) BOTTOM(32,16)-(40,16)
# RIGHT(16,20)-(20,32) FRONT(20,20)-(28,32) LEFT(28,20)-(32,32) BACK(32,20)-(40,32)
d.rectangle([24, 16, 32, 16], fill=PIG_D)  # top
d.rectangle([32, 16, 40, 16], fill=PIG_D)  # bottom
d.rectangle([16, 20, 20, 32], fill=PIG_D)  # right
d.rectangle([20, 20, 28, 32], fill=PIG)    # front
d.rectangle([28, 20, 32, 32], fill=PIG_D)  # left
d.rectangle([32, 20, 40, 32], fill=PIG)    # back

# Right arm cuboid w4,h12,d4 at uv(40,16): TOP(44,16)-(48,16) BOTTOM(48,16)-(52,16)
# RIGHT(40,20)-(44,32) FRONT(44,20)-(48,32) LEFT(48,20)-(52,32) BACK(52,20)-(56,32)
d.rectangle([44, 16, 48, 16], fill=PIG_D)
d.rectangle([48, 16, 52, 16], fill=PIG_D)
d.rectangle([40, 20, 44, 32], fill=PIG_D)
d.rectangle([44, 20, 48, 32], fill=PIG)    # right arm front

# Left arm cuboid w4,h12,d4 at uv(32,48): TOP(36,48)-(40,48) BOTTOM(40,48)-(44,48)
# RIGHT(32,52)-(36,64) FRONT(36,52)-(40,64) LEFT(40,52)-(44,64) BACK(44,52)-(48,64)
d.rectangle([36, 48, 40, 48], fill=PIG_D)
d.rectangle([40, 48, 44, 48], fill=PIG_D)
d.rectangle([32, 52, 36, 64], fill=PIG_D)
d.rectangle([36, 52, 40, 64], fill=PIG)    # left arm front

# Right leg cuboid w4,h12,d4 at uv(0,16): TOP(4,16)-(8,16) BOTTOM(8,16)-(12,16)
# RIGHT(0,20)-(4,32) FRONT(4,20)-(8,32) LEFT(8,20)-(12,32) BACK(12,20)-(16,32)
d.rectangle([4, 16, 8, 16], fill=PANTS_D)
d.rectangle([8, 16, 12, 16], fill=PANTS_D)
d.rectangle([0, 20, 4, 32], fill=PANTS_D)
d.rectangle([4, 20, 8, 32], fill=PANTS)    # right leg front

# Left leg cuboid w4,h12,d4 at uv(16,48): TOP(20,48)-(24,48) BOTTOM(24,48)-(28,48)
# RIGHT(16,52)-(20,64) FRONT(20,52)-(24,64) LEFT(24,52)-(28,64) BACK(28,52)-(32,64)
d.rectangle([20, 48, 24, 48], fill=PANTS_D)
d.rectangle([24, 48, 28, 48], fill=PANTS_D)
d.rectangle([16, 52, 20, 64], fill=PANTS_D)
d.rectangle([20, 52, 24, 64], fill=PANTS)  # left leg front

# Sword cuboid w2,h12,d2 at uv(48,48): TOP(50,48)-(52,52) BOTTOM(52,48)-(54,52)
# RIGHT(48,52)-(50,64) FRONT(50,52)-(52,64) LEFT(52,52)-(54,64) BACK(54,52)-(56,64)
d.rectangle([50, 48, 52, 52], fill=GOLD_D)
d.rectangle([52, 48, 54, 52], fill=GOLD_D)
d.rectangle([48, 52, 50, 64], fill=GOLD_D)  # right sliver
d.rectangle([50, 52, 52, 64], fill=GOLD)    # sword front (blade)
d.rectangle([52, 52, 54, 64], fill=GOLD_D)  # left sliver
d.rectangle([54, 52, 56, 64], fill=GOLD)    # sword back
# guard band near the top of the blade
d.rectangle([50, 53, 52, 55], fill=GOLD_D)
img.save(OUT + "minecraft/textures/entity/zombie_pigman.png")

# ── Item textures (16×16 each) ──

def gen_item(filename, color, highlight=None, shape="square"):
    """Simple 16×16 item sprite."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shape == "square":
        d.rectangle([2, 2, 14, 14], fill=color)
        d.rectangle([2, 2, 14, 14], outline=(color[0]//2, color[1]//2, color[2]//2, 255))
        if highlight:
            d.rectangle([4, 4, 8, 8], fill=highlight)
    elif shape == "rod":
        d.rectangle([5, 1, 11, 15], fill=color)
        d.rectangle([5, 1, 11, 15], outline=(color[0]//2, color[1]//2, color[2]//2, 255))
        if highlight:
            d.rectangle([7, 2, 9, 14], fill=highlight)
    elif shape == "powder":
        # Speckled/irregular powder
        import random as _r
        _r.seed(hash(filename))
        for _ in range(30):
            px, py = _r.randint(1, 14), _r.randint(1, 14)
            s = _r.randint(1, 3)
            shade = (min(255, color[0]+_r.randint(-30,30)),
                     min(255, color[1]+_r.randint(-30,30)),
                     min(255, color[2]+_r.randint(-30,30)),
                     255)
            d.ellipse([px, py, px+s, py+s], fill=shade)
    elif shape == "fireball":
        d.ellipse([2, 2, 14, 14], fill=color)
        if highlight:
            d.ellipse([5, 5, 10, 10], fill=highlight)
    img.save(OUT + "minecraft/textures/items/" + filename)

GEN = (150, 150, 155, 255)  # gunpowder gray
GEN_HL = (190, 190, 195, 255)
BR = (220, 170, 65, 255)    # blaze rod gold
BR_HL = (250, 200, 100, 255)
BP = (255, 140, 30, 255)   # blaze powder orange
BP_HL = (255, 190, 80, 255)
FC = (255, 100, 30, 255)   # fire charge
FC_HL = (255, 210, 100, 255)

gen_item("gunpowder.png", GEN, GEN_HL, "powder")
gen_item("blaze_rod.png", BR, BR_HL, "rod")
gen_item("blaze_powder.png", BP, BP_HL, "powder")
gen_item("fire_charge.png", FC, FC_HL, "fireball")

print("generated: blaze.png, fireball.png, blaze_burner*.png, steam_engine*.png, copper_tank*.png, zombie_pigman.png, item textures")
