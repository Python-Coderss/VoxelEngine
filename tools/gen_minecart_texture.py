#!/usr/bin/env python3
"""
Generate the minecart entity texture (textures/entity/minecart.png).

The engine's cuboid-atlas UV mapper (raytracer.comp, entityCuboidAtlasUv)
uses the standard skin layout: for a part with pixel dims (w, h, d) and
uv origin (uvU, uvV) the faces map to:

    TOP    = (uvU + d,     uvV    ) size (w, d)
    BOTTOM = (uvU + d + w, uvV    ) size (w, d)
    RIGHT  = (uvU,         uvV + d) size (d, h)
    FRONT  = (uvU + d,     uvV + d) size (w, h)
    LEFT   = (uvU + d + w, uvV + d) size (d, h)
    BACK   = (uvU + 2d + w,uvV + d) size (w, h)

The minecart model (models/entity/minecart.json) uses 5 parts whose uv
origins were chosen so every OUTER face lands on a drawn region and every
hidden face reads the flat base grey:
    base      size [16, 2,16] uv ( 0, 0)  -> top/bottom/2px skirt faces
    wall_north size [16, 8, 2] uv (14,16) -> outer FRONT at (16,18)-(32,26)
    wall_south size [16, 8, 2] uv (28,16) -> outer BACK  at (48,18)-(64,26)
    wall_west  size [ 2, 8,12] uv (14,16) -> hidden faces only
    wall_east  size [ 2, 8,12] uv ( 0,12) -> hidden/2px sliver faces only
"""
from PIL import Image, ImageDraw

W, H = 64, 32
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

BODY = (158, 158, 158, 255)      # side panel grey
BODY_DARK = (96, 96, 96, 255)    # inner/hidden face grey (base fill)
RIM = (46, 46, 46, 255)          # dark outline / skirt
TOP = (206, 206, 206, 255)       # top face
BOTTOM = (72, 72, 72, 255)       # underside
STRUT = (70, 70, 70, 255)        # panel struts / wheels

# 1. Base fill: every hidden face reads this
d.rectangle([0, 0, W, H], fill=BODY_DARK)

# 2. Top faces (16,0)-(32,16) and (48,0)-(64,16), bottom face (32,0)-(48,16)
for (x0, y0) in [(16, 0), (48, 0)]:
    d.rectangle([x0, y0, x0 + 16, y0 + 16], fill=TOP)
    d.rectangle([x0, y0, x0 + 16, y0 + 16], outline=RIM)
    d.rectangle([x0 + 3, y0 + 3, x0 + 13, y0 + 13], outline=(150, 150, 150, 255))
d.rectangle([32, 0, 48, 16], fill=BOTTOM)
d.rectangle([32, 0, 48, 16], outline=RIM)

# 3. Side band (0,16)-(64,26): cart body colour with rim lines
d.rectangle([0, 16, 64, 26], fill=BODY)
d.line([(0, 16), (64, 16)], fill=RIM)   # skirt top edge
d.line([(0, 26), (64, 26)], fill=RIM)   # skirt bottom edge
for x in (16, 32, 48):
    d.line([(x, 16), (x, 26)], fill=RIM)

# 4. Outer faces: wall_north FRONT (16,18)-(32,26), wall_south BACK (48,18)-(64,26)
def panel(x0):
    # cart panel: two vertical struts + a window band
    d.rectangle([x0, 18, x0 + 16, 26], fill=BODY)
    d.rectangle([x0 + 1, 18, x0 + 2, 26], fill=STRUT)
    d.rectangle([x0 + 13, 18, x0 + 14, 26], fill=STRUT)
    # window
    d.rectangle([x0 + 4, 19, x0 + 11, 24], fill=(215, 215, 215, 255))
    d.rectangle([x0 + 4, 19, x0 + 11, 24], outline=RIM)
    # wheels under the panel
    d.rectangle([x0 + 2, 23, x0 + 6, 26], fill=(30, 30, 30, 255))
    d.rectangle([x0 + 9, 23, x0 + 13, 26], fill=(30, 30, 30, 255))

panel(16)
panel(48)

img.save("src/main/resources/assets/minecraft/textures/entity/minecart.png")
print("wrote textures/entity/minecart.png", img.size)
