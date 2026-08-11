#!/usr/bin/env python3
"""Check PIL availability and inspect the downloaded Create kinetic textures."""
import sys

try:
    from PIL import Image
    print("PIL available, version:", Image.__version__ if hasattr(Image, "__version__") else "?")
except ImportError:
    print("PIL NOT available")
    sys.exit(0)

import os

base = "src/main/resources/assets/minecraft/textures/blocks"
for name in ["axis", "axis_top", "cogwheel", "large_cogwheel", "wheel", "wheel_extras"]:
    p = os.path.join(base, name + ".png")
    if not os.path.exists(p):
        print(name, "MISSING")
        continue
    im = Image.open(p)
    print(name, "mode=", im.mode, "size=", im.size, end="")
    if "A" in im.mode:
        a = im.getchannel("A")
        print(" alpha_min=", min(a.getdata()), "alpha_max=", max(a.getdata()))
    else:
        print(" no-alpha-channel")
