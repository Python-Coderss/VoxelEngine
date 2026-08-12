"""Generate 4-frame animated strips for the Create machines' spinning textures.

The engine's TextureManager detects animation frames from vertical PNG strips
(16 x 16*N). The raytracer cycles those frames for kinetic blocks when their
network spins (see isKineticBlock in raytracer.comp). Wheel-like textures get
4 rotation poses; the belt gets a horizontal scroll.
"""
from PIL import Image

OUT = "src/main/resources/assets/minecraft/textures/blocks/"

ROTATE = ["windmill_sail", "millstone", "crushing_wheel", "mechanical_drill", "mechanical_saw"]
SCROLL = ["belt_conveyor"]

def already_animated(path):
    """True if the texture is already a multi-frame vertical strip (height > 16).

    Keeps the script idempotent: re-running it must not double the frame count.
    """
    return Image.open(path).size[1] > 16

for name in ROTATE:
    path = OUT + name + ".png"
    if already_animated(path):
        print("skip (already animated): ", name)
        continue
    base = Image.open(path).convert("RGBA")
    w, h = base.size
    frames = [base.rotate(a, resample=Image.NEAREST, expand=False) for a in (0, 45, 90, 135)]
    strip = Image.new("RGBA", (w, h * 4))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    strip.save(path)
    print("rotated ", name, strip.size)

for name in SCROLL:
    path = OUT + name + ".png"
    if already_animated(path):
        print("skip (already animated): ", name)
        continue
    base = Image.open(path).convert("RGBA")
    w, h = base.size
    frames = []
    for s in (0, 4, 8, 12):
        f = Image.new("RGBA", (w, h))
        f.paste(base.crop((s, 0, w, h)), (0, 0))
        f.paste(base.crop((0, 0, s, h)), (w - s, 0))
        frames.append(f)
    strip = Image.new("RGBA", (w, h * 4))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    strip.save(path)
    print("scrolled ", name, strip.size)
