#!/usr/bin/env python3
"""
Generate the main-menu panorama backgrounds:
  - src/main/resources/ui/menu_background.png        (light: bright sky, sun, clouds, hills)
  - src/main/resources/ui/menu_background_dark.png   (dark: night sky, stars, moon, hills)
Both are 1280x720 RGBA rendered as a stylized Beta-era panorama.
"""
from PIL import Image, ImageDraw, ImageFilter
import math
import random

W, H = 1280, 720
OUT = "src/main/resources/ui/"

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def sky_gradient(d, top, bottom):
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        t = y / H
        px[0, y] = lerp(top, bottom, t)  # column seeds
    for y in range(H):
        t = y / H
        col = lerp(top, bottom, t)
        for x in range(W):
            px[x, y] = col
    return img

def draw_sun(img, d, cx, cy, r, color, glow_r=None):
    glow_r = glow_r or r * 2.2
    # soft glow
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for rr in range(int(glow_r), 0, -2):
        a = int(40 * (1 - rr / glow_r))
        gd.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=color + (a,))
    img.alpha_composite(glow)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color + (255,))

def draw_stars(d, rng, n=160):
    for _ in range(n):
        x = rng.uniform(0, W)
        y = rng.uniform(0, H * 0.55)
        r = rng.uniform(0.6, 2.0)
        a = rng.randint(120, 255)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))

def draw_clouds(d, rng, base_y, n=14, color=(255, 255, 255, 190), y_range=(0, H * 0.35)):
    for _ in range(n):
        x = rng.uniform(-80, W)
        y = rng.uniform(y_range[0], y_range[1])
        w = rng.uniform(90, 220)
        h = rng.uniform(14, 30)
        d.ellipse([x - w, y - h, x + w, y + h], fill=color)
        d.ellipse([x - w * 0.6, y - h * 1.4, x + w * 0.6, y + h * 0.2], fill=color)

def draw_hills(d, horizon_y, color_near, color_far, rng, amp=70, base_amp=40):
    # Far hill band
    pts = []
    for x in range(-10, W + 20, 12):
        y = horizon_y - base_amp * abs(math.sin(x * 0.004 + 1.3)) - rng.uniform(0, 18)
        pts.append((x, y))
    pts.extend([(W + 10, H), (-10, H)])
    d.polygon(pts, fill=color_far)
    # Near hill band
    pts = []
    for x in range(-10, W + 20, 8):
        y = horizon_y + 18 - (amp + 40) * abs(math.sin(x * 0.0022 + 0.4)) - rng.uniform(0, 26)
        pts.append((x, y))
    pts.extend([(W + 10, H), (-10, H)])
    d.polygon(pts, fill=color_near)

def build(light=True):
    rng = random.Random(7 if light else 13)
    if light:
        sky_top = (78, 158, 222)
        sky_bot = (196, 226, 246)
        far_hill = (112, 140, 120)
        near_hill = (86, 118, 92)
        sun_color = (255, 240, 190)
        cloud_color = (255, 255, 255, 210)
        horizon = 330
    else:
        sky_top = (8, 12, 32)
        sky_bot = (40, 58, 96)
        far_hill = (30, 40, 52)
        near_hill = (18, 26, 36)
        sun_color = (235, 238, 248)
        cloud_color = (60, 70, 96, 140)
        horizon = 320

    img = sky_gradient(None, sky_top, sky_bot)
    img = img.convert("RGBA")
    d = ImageDraw.Draw(img)

    if light:
        draw_sun(img, d, W * 0.74, 118, 42, sun_color)
        draw_clouds(d, rng, 0, n=16, color=cloud_color)
    else:
        draw_stars(d, rng)
        draw_sun(img, d, W * 0.74, 118, 30, sun_color, glow_r=70)
        draw_clouds(d, rng, 0, n=8, color=cloud_color)

    draw_hills(d, horizon, near_hill, far_hill, rng)

    # Soft vignette for depth
    vign = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vign)
    vd.ellipse([-W * 0.4, -H * 0.5, W * 1.4, H * 1.5], fill=(0, 0, 0, 90 if light else 110))
    vign = vign.filter(ImageFilter.GaussianBlur(80))
    img = Image.alpha_composite(img, vign)
    return img

build(True).save(OUT + "menu_background.png")
build(False).save(OUT + "menu_background_dark.png")
print("menu panoramas written to " + OUT)
