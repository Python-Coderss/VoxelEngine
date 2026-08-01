#!/usr/bin/env python3
"""Generate the VoxelEngine spawn loading-screen background.

Run from the VoxelEngine directory:
    python tools/generate_loading_texture.py

The image intentionally contains no text; HudUI renders localized/dynamic
loading text above it. Pillow is the only dependency.
"""

from pathlib import Path
from PIL import Image, ImageDraw

WIDTH, HEIGHT = 480, 270
OUTPUT = Path(__file__).resolve().parent.parent / "src/main/resources/ui/loading.png"


def lerp(a, b, t):
    return int(a + (b - a) * t)


def shade(color, amount):
    return tuple(max(0, min(255, channel + amount)) for channel in color)


def draw_pixel_mountain(draw, points, base_y, palette):
    """Draw a stepped mountain silhouette with a blocky snow/highlight edge."""
    draw.polygon(points + [(points[-1][0], base_y), (points[0][0], base_y)], fill=palette[0])
    for index in range(0, len(points) - 1, 2):
        x1, y1 = points[index]
        x2, y2 = points[min(index + 1, len(points) - 1)]
        draw.line((x1, y1, x2, y2), fill=palette[1], width=3)


def main():
    image = Image.new("RGB", (WIDTH, HEIGHT), (135, 205, 255))
    draw = ImageDraw.Draw(image)

    # Banded daytime gradient: deliberately stepped for a low-resolution,
    # pixel-art feel when the texture is scaled to the game window.
    top = (88, 175, 245)
    bottom = (218, 244, 255)
    for y in range(HEIGHT):
        band_y = (y // 3) * 3
        t = band_y / float(HEIGHT - 1)
        color = tuple(lerp(top[i], bottom[i], t) for i in range(3))
        draw.line((0, y, WIDTH, y), fill=color)

    # Warm daytime sun with chunky rays.
    sun = (255, 239, 133)
    sun_hi = (255, 250, 196)
    draw.ellipse((365, 24, 411, 70), fill=sun)
    draw.rectangle((356, 44, 420, 50), fill=sun)
    draw.rectangle((385, 14, 391, 80), fill=sun)
    draw.rectangle((360, 28, 366, 34), fill=sun_hi)
    draw.rectangle((399, 58, 405, 64), fill=sun_hi)

    # Bright block clouds.
    cloud = (248, 252, 255)
    cloud_shadow = (207, 231, 246)
    for x, y, width in ((27, 78, 66), (338, 96, 72), (151, 89, 42)):
        draw.rectangle((x, y, x + width, y + 7), fill=cloud_shadow)
        draw.rectangle((x + 10, y - 5, x + width - 14, y + 7), fill=cloud)
        top_left = x + width // 3
        top_right = x + width - width // 3
        draw.rectangle((top_left, y - 10, top_right, y + 2), fill=cloud)
        draw.line((x + 10, y + 7, x + width - 10, y + 7), fill=(177, 211, 231), width=2)
    for x, y, width in ((27, 78, 66), (338, 96, 72), (151, 89, 42)):
        draw.rectangle((x, y, x + width, y + 7), fill=cloud)
        draw.rectangle((x + 10, y - 5, x + width - 14, y + 7), fill=cloud)
        top_left = x + width // 3
        top_right = x + width - width // 3
        draw.rectangle((top_left, y - 10, top_right, y + 2), fill=cloud)

    # Layered voxel mountain horizon.
    draw_pixel_mountain(
        draw,
        [(0, 169), (44, 126), (77, 153), (119, 111), (157, 151),
         (203, 121), (247, 160), (290, 114), (333, 150), (382, 119),
         (432, 156), (480, 132)],
        220,
        ((93, 157, 170), (174, 215, 190)),
    )
    draw_pixel_mountain(
        draw,
        [(0, 192), (57, 151), (99, 181), (151, 140), (204, 184),
         (256, 146), (311, 185), (364, 143), (424, 181), (480, 151)],
        225,
        ((50, 119, 109), (123, 190, 142)),
    )

    # Floating spawn island, the focal point behind the loading copy.
    island_top = [(178, 177), (194, 170), (226, 170), (244, 177),
                  (270, 177), (291, 170), (317, 173), (335, 183),
                  (317, 190), (201, 190)]
    draw.polygon(island_top, fill=(107, 181, 108))
    draw.line((181, 178, 333, 182), fill=(224, 246, 152), width=4)
    draw.rectangle((199, 190, 318, 196), fill=(92, 148, 87))
    draw.polygon([(205, 196), (313, 196), (296, 212), (283, 212),
                  (275, 224), (255, 224), (245, 214), (225, 214)],
                 fill=(95, 145, 108))
    draw.polygon([(219, 196), (301, 196), (286, 210), (239, 210)], fill=(142, 194, 127))

    # Tiny blocky tree silhouette on the island.
    draw.rectangle((249, 140, 257, 174), fill=(111, 79, 47))
    for box in ((235, 137, 269, 151), (241, 127, 264, 144), (248, 119, 258, 135)):
        draw.rectangle(box, fill=(54, 157, 88))
    draw.rectangle((240, 139, 247, 145), fill=(117, 208, 105))

    # Subtle central glass panel leaves visual breathing room for dynamic text.
    panel = (255, 249, 219)
    panel_edge = (235, 184, 83)
    draw.rectangle((102, 83, 378, 159), fill=panel)
    draw.rectangle((103, 84, 377, 158), outline=(255, 255, 245), width=2)
    draw.line((119, 87, 361, 87), fill=panel_edge, width=2)
    draw.line((119, 155, 361, 155), fill=(225, 172, 71), width=2)
    for x in range(128, 357, 32):
        draw.rectangle((x, 86, x + 5, 88), fill=(245, 198, 83))
        draw.rectangle((x + 16, 153, x + 21, 155), fill=(235, 183, 71))

    # Bottom foreground blocks and small cyan guide lights.
    draw.rectangle((0, 226, WIDTH, HEIGHT), fill=(39, 119, 81))
    for x in range(-8, WIDTH + 8, 24):
        draw.rectangle((x, 226, x + 17, 230), fill=(80, 164, 91))
        draw.rectangle((x + 5, 241, x + 9, 244), fill=(255, 224, 104))
    draw.rectangle((0, 226, WIDTH, 228), fill=(154, 213, 103))

    # A few foreground grass pixels break up the lower edge without competing
    # with the status line rendered by HudUI.
    for x in (22, 67, 421, 459):
        draw.rectangle((x, 217, x + 3, 226), fill=(44, 113, 64))
        draw.rectangle((x - 4, 214, x + 1, 218), fill=(88, 177, 78))
        draw.rectangle((x + 2, 211, x + 6, 218), fill=(66, 151, 70))

    image.save(OUTPUT, "PNG", optimize=True)
    print(f"Generated {OUTPUT} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
