#!/usr/bin/env python3
"""One-time dev tool: generates the End Update's custom block textures.

Writes 16x16 RGBA PNGs into src/main/resources/assets/minecraft/textures/blocks:
  - end_glass.png   pale translucent violet glass
  - void_steel.png  dark indigo metal plate with beveled edges

Pure stdlib (zlib + struct PNG writer) so it runs anywhere.

Run with:  python tools/gen_end_textures.py
"""
import struct, zlib, os

OUT_DIR = os.path.join("src", "main", "resources", "assets", "minecraft", "textures", "blocks")


def write_png(path, w, h, rgba_rows):
    """rgba_rows: list of h rows, each a list of w (r,g,b,a) tuples."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row) for row in rgba_rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", path)


def clamp(v):
    return max(0, min(255, int(v)))


def _rgba(c):
    """Normalise a colour to a 4-tuple."""
    if len(c) == 3:
        return (c[0], c[1], c[2], 255)
    return tuple(c)


def gen_end_glass():
    """Pale translucent glass with a brighter frame and two diagonal streaks."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            edge = x == 0 or y == 0 or x == 15 or y == 15
            corner = (x <= 1 or x >= 14) and (y <= 1 or y >= 14)
            if corner:
                c = (222, 214, 244, 210)
            elif edge:
                c = (206, 198, 234, 185)
            else:
                # Body: faint violet tint, slightly varied per pixel.
                n = ((x * 7 + y * 13) % 5) - 2
                body = (188 + n * 3, 180 + n * 3, 226 + n * 2, 120)
                c = body
                # Two highlight streaks.
                if (x - y) % 16 in (10, 11) or (x - y) % 16 == 4:
                    c = (240, 236, 252, 165)
            row.append(_rgba(c))
        rows.append(row)
    write_png(os.path.join(OUT_DIR, "end_glass.png"), 16, 16, rows)


def gen_void_steel():
    """Dark indigo metal: beveled light TL / dark BR edges, teal rivets, subtle grain."""
    base = (26, 26, 38)
    light = (58, 58, 82)
    dark = (14, 14, 22)
    rivet = (96, 148, 148)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x == 0 or y == 0:
                c = light
            elif x == 15 or y == 15:
                c = dark
            else:
                n = ((x * 31 + y * 17) % 7) - 3
                c = (clamp(base[0] + n), clamp(base[1] + n), clamp(base[2] + n + 1), 255)
                # Plate seam cross in the middle.
                if x == 8 or y == 8:
                    c = dark if (x + y) % 2 == 0 else (20, 20, 30)
            row.append(_rgba(c))
        rows.append(row)
    # Rivets near the corners of each quadrant seam.
    for rx, ry in [(3, 3), (12, 3), (3, 12), (12, 12)]:
        rows[ry][rx] = (96, 148, 148, 255)
        rows[ry][rx + 1 if rx < 15 else rx - 1] = (60, 92, 92, 255)
    write_png(os.path.join(OUT_DIR, "void_steel.png"), 16, 16, rows)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    gen_end_glass()
    gen_void_steel()
