#!/usr/bin/env python3
"""Generate 8-frame spinning animation strips for the Create kinetic blocks.

Each output is a 16x128 vertical strip (8 frames of 16x16) which the game's
TextureManager auto-detects as an animated texture and the raytracer shader
cycles at ~20 fps, giving the cogwheels / water wheel a spinning look.

Sources: the Create mod mc1.16 branch textures (cogwheel, large_cogwheel, wheel).
"""
from PIL import Image

BASE = "src/main/resources/assets/minecraft/textures/blocks"
FRAMES = 8

def build_animated(source_name, out_name):
    src = Image.open(f"{BASE}/{source_name}.png").convert("RGBA")
    # Downscale to the engine's native 16x16 texture size (nearest for crisp pixels)
    base = src.resize((16, 16), Image.NEAREST)

    # Center the opaque sprite in the 16x16 frame so the rotation reads as a
    # gear spinning around its own center (Create's textures are off-center).
    alpha = base.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is not None:
        sprite = base.crop(bbox)
        canvas = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        ox = (16 - (bbox[2] - bbox[0])) // 2
        oy = (16 - (bbox[3] - bbox[1])) // 2
        canvas.paste(sprite, (ox, oy))
        base = canvas

    frames = []
    for k in range(FRAMES):
        # Rotate in place by -45deg steps; corners stay transparent
        frame = base.rotate(-45 * k, resample=Image.NEAREST, expand=False)
        frames.append(frame)

    strip = Image.new("RGBA", (16, 16 * FRAMES))
    for k, f in enumerate(frames):
        strip.paste(f, (0, 16 * k))
    strip.save(f"{BASE}/{out_name}.png")
    print(f"wrote {out_name}.png {strip.size}")

if __name__ == "__main__":
    build_animated("cogwheel", "cogwheel")
    build_animated("large_cogwheel", "large_cogwheel")
    build_animated("wheel", "wheel")
