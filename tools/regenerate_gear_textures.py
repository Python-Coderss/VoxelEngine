#!/usr/bin/env python3
"""Regenerate the kinetic gear textures with proper toothed-gear pixel art.

The original generate_kinetic_animations.py read its own animated output back as
input (16x128 strip downscaled to 16x16), producing the noisy garbage the cogs
showed. This script instead draws each gear frame procedurally at its rotation
angle, so every frame is crisp and the 8-frame strip spins cleanly.

Teeth counts are odd (7 / 9) so a 45deg-per-frame rotation yields 8 *distinct*
frames that still loop seamlessly (8 * 45 = 360); an even tooth count would look
static because the gear is symmetric every 360/teeth degrees.

All gears are drawn FULL-BLEED (teeth reach the texture edge) so the raytracer's
flat-face UV `(p - center) / (2 * radius) + 0.5` puts the teeth exactly on the
disc rim instead of leaving a transparent ring.
"""
import math
from PIL import Image, ImageDraw

BASE = "src/main/resources/assets/minecraft/textures/blocks"
SIZE = 16
FRAMES = 8
SS = 4  # supersample factor for clean anti-aliased edges

# Warm oak palette (matches the wooden Create cogwheel aesthetic).
WOOD = (168, 126, 76, 255)
WOOD_LIGHT = (205, 165, 112, 255)
WOOD_DARK = (118, 84, 50, 255)
WOOD_SHADE = (92, 63, 36, 255)
AXLE = (46, 34, 24, 255)


def _canvas():
    """SS*SIZE supersampled RGBA canvas + draw handle (coords in SS-scale)."""
    big = SS * SIZE
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def _finish(img):
    small = img.resize((SIZE, SIZE), Image.LANCZOS)
    # Threshold alpha to a crisp 0/255 silhouette. LANCZOS downscaling from the
    # supersampled canvas smears thin teeth/spokes into the transparent backdrop,
    # leaving semi-transparent haze that the raytracer draws as a ghostly blur
    # instead of a solid gear. Binarising restores the Minecraft pixel-art look.
    px = small.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255 if a >= 128 else 0)
    return small


def _box(c, r):
    return [c - r, c - r, c + r, c + r]


def draw_cog(teeth, tip_r, base_r, hub_r, hole_r, angle_deg):
    """Small cogwheel: solid disc + teeth + axle hole (full bleed)."""
    img, d = _canvas()
    c = SIZE * SS / 2.0
    span = (360.0 / teeth) * 0.5  # tooth == gap angular width

    for i in range(teeth):
        a0 = angle_deg + i * (360.0 / teeth) - span / 2.0
        a1 = a0 + span
        d.pieslice(_box(c, tip_r * SS), a0, a1, fill=WOOD)
    d.ellipse(_box(c, base_r * SS), fill=WOOD)
    d.ellipse(_box(c, base_r * SS), outline=WOOD_LIGHT, width=SS)
    d.ellipse(_box(c, base_r * 0.82 * SS), outline=WOOD_DARK, width=SS)
    d.ellipse(_box(c, hub_r * SS), fill=WOOD_DARK)
    d.ellipse(_box(c, hub_r * SS), outline=WOOD_SHADE, width=SS)
    d.ellipse(_box(c, hole_r * SS), fill=AXLE)
    return _finish(img)


def draw_large_cog(angle_deg):
    """Large cogwheel: open spoked gear (9 teeth, ring rim, 6 spokes, big hub)."""
    img, d = _canvas()
    c = SIZE * SS / 2.0

    teeth = 9
    tip_r = 7.9
    base_r = 6.2
    rim_inner = 4.6
    hub_r = 2.7
    hole_r = 1.4
    span = (360.0 / teeth) * 0.5

    # Teeth (full bleed).
    for i in range(teeth):
        a0 = angle_deg + i * (360.0 / teeth) - span / 2.0
        a1 = a0 + span
        d.pieslice(_box(c, tip_r * SS), a0, a1, fill=WOOD)
    # Rim ring (open center).
    d.ellipse(_box(c, base_r * SS), fill=WOOD)
    d.ellipse(_box(c, base_r * SS), outline=WOOD_LIGHT, width=SS)
    d.ellipse(_box(c, rim_inner * SS), fill=(0, 0, 0, 0))  # cut the inside
    d.ellipse(_box(c, rim_inner * SS), outline=WOOD_DARK, width=SS)
    # Spokes (6, clearly visible gaps between them).
    for i in range(6):
        a = math.radians(angle_deg + i * (360.0 / 6))
        x0 = c + math.cos(a) * (hub_r + 0.2) * SS
        y0 = c + math.sin(a) * (hub_r + 0.2) * SS
        x1 = c + math.cos(a) * (base_r - 0.6) * SS
        y1 = c + math.sin(a) * (base_r - 0.6) * SS
        d.line([(x0, y0), (x1, y1)], fill=WOOD, width=2 * SS)
    # Hub + axle hole.
    d.ellipse(_box(c, hub_r * SS), fill=WOOD_DARK)
    d.ellipse(_box(c, hub_r * SS), outline=WOOD_SHADE, width=SS)
    d.ellipse(_box(c, hole_r * SS), fill=AXLE)
    return _finish(img)


def draw_axis():
    """Shaft rod side texture: vertical wood grain (wraps around the rod)."""
    img, d = _canvas()
    big = SS * SIZE
    for x in range(0, big, SS):
        d.line([(x, 0), (x, big)], fill=WOOD)
    for x in range(3 * SS, big, 4 * SS):
        d.line([(x, 0), (x, big)], fill=WOOD_DARK, width=SS)
    for x in range(1 * SS, big, 4 * SS):
        d.line([(x, 0), (x, big)], fill=WOOD_LIGHT, width=SS)
    return _finish(img)


def draw_axis_top():
    """Shaft end cap: wood disc with a square axle keyway."""
    img, d = _canvas()
    c = SIZE * SS / 2.0
    d.ellipse(_box(c, 4.0 * SS), fill=WOOD)
    d.ellipse(_box(c, 4.0 * SS), outline=WOOD_SHADE, width=SS)
    d.ellipse(_box(c, 2.4 * SS), outline=WOOD_DARK, width=SS)
    d.rectangle([c - 1.2 * SS, c - 1.2 * SS, c + 1.2 * SS, c + 1.2 * SS], fill=AXLE)
    d.rectangle([c - 0.6 * SS, c - 1.6 * SS, c + 0.6 * SS, c + 1.6 * SS], fill=AXLE)
    return _finish(img)


def draw_water_wheel(angle_deg):
    """Water wheel side view: rim + paddles + 4 thick spokes + hub (full bleed,
    with wide open gaps between spokes so it reads as a wheel, not a disc)."""
    img, d = _canvas()
    c = SIZE * SS / 2.0

    rim_outer = 7.9
    rim_inner = 6.2
    hub_r = 1.6
    hole_r = 0.8
    paddles = 8

    # Rim ring (thinner than the cog so the open centre dominates).
    d.ellipse(_box(c, rim_outer * SS), fill=WOOD_DARK)
    d.ellipse(_box(c, rim_outer * SS), outline=WOOD_SHADE, width=SS)
    d.ellipse(_box(c, rim_inner * SS), fill=(0, 0, 0, 0))
    d.ellipse(_box(c, rim_inner * SS), outline=WOOD_DARK, width=SS)

    # Paddle boxes sitting on the outer rim (water-catching buckets).
    for i in range(paddles):
        a = math.radians(angle_deg + i * (360.0 / paddles))
        x = c + math.cos(a) * (rim_outer - 0.4) * SS
        y = c + math.sin(a) * (rim_outer - 0.4) * SS
        half = 1.1 * SS
        d.rectangle([x - half, y - half, x + half, y + half], fill=WOOD_LIGHT)
        d.rectangle([x - half, y - half, x + half, y + half], outline=WOOD_DARK, width=SS)

    # Spokes (4, thick, at 90deg so the gaps stay open after downscale).
    for i in range(4):
        a = math.radians(angle_deg + i * 90.0)
        x0 = c + math.cos(a) * (hole_r + 0.4) * SS
        y0 = c + math.sin(a) * (hole_r + 0.4) * SS
        x1 = c + math.cos(a) * (rim_inner - 0.2) * SS
        y1 = c + math.sin(a) * (rim_inner - 0.2) * SS
        d.line([(x0, y0), (x1, y1)], fill=WOOD, width=3 * SS)

    # Hub + axle hole.
    d.ellipse(_box(c, hub_r * SS), fill=WOOD_DARK)
    d.ellipse(_box(c, hub_r * SS), outline=WOOD_SHADE, width=SS)
    d.ellipse(_box(c, hole_r * SS), fill=AXLE)
    return _finish(img)


def draw_cog_side():
    """Cogwheel rim texture: repeating teeth around the circumference.

    The raytracer wraps the rim texture once around the gear (uv.x = angle), so
    the tooth pattern runs along X; Y is the 6px disc thickness (sampled near the
    middle). Alternating light/dark bands read as teeth on the rim edge.
    """
    img, d = _canvas()
    teeth = 8
    for i in range(teeth):
        x0 = i * (SIZE * SS) // teeth
        x1 = (i + 1) * (SIZE * SS) // teeth
        col = WOOD if i % 2 == 0 else WOOD_DARK
        d.rectangle([x0, 0, x1 - SS, SS * SIZE], fill=col)
    # Soft vertical shading so the teeth read as a serrated rim, not a barcode.
    for x in range(0, SS * SIZE, SS):
        d.line([(x, 0), (x, SS * SIZE)], fill=WOOD_SHADE, width=SS // 2)
    return _finish(img)


def draw_wheel_side():
    """Water wheel rim edge: a clean wooden band (the wheel's thickness)."""
    img, d = _canvas()
    d.rectangle([0, 0, SS * SIZE, SS * SIZE], fill=WOOD)
    d.line([(0, 0), (SS * SIZE, 0)], fill=WOOD_LIGHT, width=SS)
    d.line([(0, SS * SIZE), (SS * SIZE, SS * SIZE)], fill=WOOD_SHADE, width=SS)
    for y in range(0, SS * SIZE, 2 * SS):
        d.line([(0, y), (SS * SIZE, y)], fill=WOOD_DARK, width=SS // 2)
    return _finish(img)


def strip(frames):
    out = Image.new("RGBA", (SIZE, SIZE * len(frames)))
    for i, f in enumerate(frames):
        out.paste(f, (0, SIZE * i))
    return out


def save(name, frames):
    strip(frames).save(f"{BASE}/{name}.png")
    print(f"wrote {name}.png {SIZE}x{SIZE * len(frames)}")


if __name__ == "__main__":
    save("cogwheel", [draw_cog(7, 7.9, 5.6, 2.4, 1.1, 45 * k) for k in range(FRAMES)])
    save("large_cogwheel", [draw_large_cog(45 * k) for k in range(FRAMES)])
    save("wheel", [draw_water_wheel(45 * k) for k in range(FRAMES)])
    draw_axis().save(f"{BASE}/axis.png")
    print(f"wrote axis.png {SIZE}x{SIZE}")
    draw_axis_top().save(f"{BASE}/axis_top.png")
    print(f"wrote axis_top.png {SIZE}x{SIZE}")
    draw_cog_side().save(f"{BASE}/cog_side.png")
    print(f"wrote cog_side.png {SIZE}x{SIZE}")
    draw_wheel_side().save(f"{BASE}/wheel_side.png")
    print(f"wrote wheel_side.png {SIZE}x{SIZE}")
    # Fully transparent texture for the invisible large-cog multiblock parts
    # (kept as a fallback; the parts now render as gear slices via the shader).
    Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0)).save(f"{BASE}/invisible.png")
    print(f"wrote invisible.png {SIZE}x{SIZE}")
