#!/usr/bin/env python3
"""Generate the VoxelEngine top-right loading-popup background.

Run from the VoxelEngine directory:
    python tools/generate_loading_popup_texture.py

A compact dark toast-style panel with a pixel-art border and a faint blocky
inner sheen. The image contains no text; HudUI renders the status text on top.
Pillow is the only dependency.
"""

from pathlib import Path
from PIL import Image, ImageDraw

WIDTH, HEIGHT = 256, 64
RADIUS = 9
OUTPUT = Path(__file__).resolve().parent.parent / "src/main/resources/ui/loading_popup.png"


def main():
    image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Outer dark panel (slightly translucent so the world glows through edges)
    panel = (22, 26, 34, 216)
    draw.rounded_rectangle([1, 1, WIDTH - 2, HEIGHT - 2], radius=RADIUS, fill=panel)

    # Pixel-art border: 1px light top/left, 1px dark bottom/right
    draw.rounded_rectangle([1, 1, WIDTH - 2, HEIGHT - 2], radius=RADIUS,
                           outline=(90, 100, 118, 255), width=1)
    draw.rounded_rectangle([2, 2, WIDTH - 3, HEIGHT - 3], radius=RADIUS - 1,
                           outline=(58, 66, 82, 255), width=1)
    draw.line((3, 3, WIDTH - 4, 3), fill=(116, 128, 148, 255))
    draw.line((3, 4, 3, HEIGHT - 4), fill=(104, 116, 136, 255))
    draw.line((4, HEIGHT - 4, WIDTH - 4, HEIGHT - 4), fill=(10, 12, 18, 255))
    draw.line((WIDTH - 4, 4, WIDTH - 4, HEIGHT - 4), fill=(12, 14, 20, 255))

    # Faint blocky diagonal sheen (pixel-art "glass" shimmer)
    for i in range(0, WIDTH, 8):
        for j in range(0, HEIGHT, 8):
            if ((i + j) // 8) % 3 == 0:
                draw.rectangle((i + 4, j + 4, i + 5, j + 5), fill=(44, 52, 68, 60))

    # Small compass/hourglass glyph on the left, drawn in a warm accent
    gx, gy = 14, 22
    accent = (236, 178, 92, 255)
    dark = (120, 84, 40, 255)
    # Hourglass: top bar, bottom bar, two diagonal sand streams
    draw.rectangle((gx, gy, gx + 22, gy + 3), fill=accent)
    draw.rectangle((gx, gy + 17, gx + 22, gy + 20), fill=accent)
    draw.line((gx + 2, gy + 4, gx + 11, gy + 10), fill=accent)
    draw.line((gx + 20, gy + 4, gx + 11, gy + 10), fill=accent)
    draw.line((gx + 2, gy + 16, gx + 11, gy + 10), fill=dark)
    draw.line((gx + 20, gy + 16, gx + 11, gy + 10), fill=dark)
    draw.rectangle((gx + 9, gy + 8, gx + 13, gy + 12), fill=accent)

    image.save(OUTPUT)
    print(f"Wrote {OUTPUT} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
