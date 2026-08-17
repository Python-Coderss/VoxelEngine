#!/usr/bin/env python3
"""
Generate the east-west rail texture (textures/blocks/rail_normal_ew.png) from
the north-south rail (rail_normal.png).

rail_normal.png is a N-S straight rail (rails run vertically). Rotating it 90
degrees yields the E-W variant; the engine's model UV system can only flip a
face's texture rect (u/v reversal), not rotate it, so a dedicated texture is
needed for the horizontal rail to display correctly.
"""
from PIL import Image

SRC = "src/main/resources/assets/minecraft/textures/blocks/rail_normal.png"
DST = "src/main/resources/assets/minecraft/textures/blocks/rail_normal_ew.png"

img = Image.open(SRC).convert("RGBA")
ew = img.transpose(Image.Transpose.ROTATE_270)  # vertical strip -> horizontal strip
ew.save(DST)
print("wrote", DST, ew.size)
