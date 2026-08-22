#!/usr/bin/env python3
"""Offline chest visual harness.

This is intentionally deterministic and uses the same chest dimensions and vanilla
atlas regions as raytracer.comp. It does not replace a GPU boot test; it catches
asset/model/hinge regressions without requiring an OpenGL context.
"""
from __future__ import annotations

import json
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "src/main/resources/assets/minecraft/models/block/chest.json"
TEXTURE_PATH = ROOT / "src/main/resources/assets/minecraft/textures/entity/chest/normal.png"
SHADER_PATH = ROOT / "src/main/resources/shaders/raytracer.comp"
OUT_DIR = ROOT / "screenshots/chest_harness"

# face order used by BlockDataManager/raytracer: down, up, north, south, west, east
FACE_NAMES = ("down", "up", "north", "south", "west", "east")
# Vanilla ModelChest cuboid atlas regions in normal.png (pixel coordinates).
REGIONS = {
    "body": {
        "down": (28, 19, 42, 33), "up": (14, 19, 28, 33),
        "north": (28, 33, 42, 43), "south": (14, 33, 28, 43),
        "west": (42, 33, 56, 43), "east": (0, 33, 14, 43),
    },
    "lid": {
        "down": (28, 0, 42, 14), "up": (14, 0, 28, 14),
        "north": (28, 14, 42, 19), "south": (14, 14, 28, 19),
        "west": (42, 14, 56, 19), "east": (0, 14, 14, 19),
    },
}

BODY_MIN = (1.0, 0.0, 1.0)
BODY_MAX = (15.0, 10.0, 15.0)
LID_MIN = (1.0, 10.0, 0.0)
LID_MAX = (15.0, 14.0, 16.0)
HINGE_ANGLE = -1.92


def assert_shader_contract(shader: str) -> dict:
    declarations = re.findall(
        r"layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s+uniform\s+\w+\s+(\w+)",
        shader,
    )
    by_location: dict[int, str] = {}
    for location_text, name in declarations:
        location = int(location_text)
        if location in by_location:
            raise AssertionError(
                f"duplicate explicit uniform location {location}: "
                f"{by_location[location]} and {name}"
            )
        by_location[location] = name
    expected = {49: "u_HingePos", 50: "u_HingeAngle", 51: "u_HingeOn", 52: "u_ChestTextureLayer"}
    for location, name in expected.items():
        if by_location.get(location) != name:
            raise AssertionError(f"missing/wrong chest uniform at {location}: {by_location.get(location)}")
    if "chestEntitySample" not in shader or "u_EntityTextures" not in shader:
        raise AssertionError("shader has no vanilla chest entity-atlas sampling path")
    if "hinfo.x / 2" not in shader:
        raise AssertionError("shader does not convert packed AABB offsets to element indices")
    return {str(location): name for location, name in sorted(by_location.items())}


def assert_orientation_contract(shader: str) -> dict:
    """Check the four stored facings map the canonical +Z front correctly."""
    # This mirrors rotateY() in raytracer.comp (standard right-handed Y rotation).
    expected = {"north": (0.0, -1.0), "south": (0.0, 1.0),
                "west": (-1.0, 0.0), "east": (1.0, 0.0)}
    yaws = {"north": math.pi, "south": 0.0,
            "west": -math.pi / 2.0, "east": math.pi / 2.0}
    actual = {}
    for name, yaw in yaws.items():
        # RY(yaw) * (0, 0, 1) => (sin(yaw), 0, cos(yaw)).
        front = (round(math.sin(yaw), 6), round(math.cos(yaw), 6))
        assert front == expected[name], f"{name} facing maps chest front to {front}"
        actual[name] = {"yaw_radians": round(yaw, 6), "front_xz": list(front)}

    for needle in ("chestFacing == 2 ? 3.14159265",
                   "chestFacing == 4 ? -1.57079633",
                   "chestFacing == 5 ? 1.57079633"):
        assert needle in shader, f"shader is missing orientation mapping: {needle}"
    return actual


def assert_model(model: dict) -> None:
    assert len(model.get("elements", [])) == 2, "chest must have exactly body + lid elements"
    assert model["elements"][0]["from"] == [1, 0, 1]
    assert model["elements"][0]["to"] == [15, 10, 15]
    assert model["elements"][1]["from"] == [1, 10, 0]
    assert model["elements"][1]["to"] == [15, 14, 16]
    assert all("#chest" == e["faces"][face]["texture"]
               for e in model["elements"] for face in FACE_NAMES)


def region_metrics(texture: Image.Image, region: tuple[int, int, int, int]) -> dict:
    crop = texture.crop(region).convert("RGBA")
    pixels = list(crop.getdata())
    colors = {(r, g, b, a) for r, g, b, a in pixels}
    opaque = sum(a > 8 for _, _, _, a in pixels)
    rgb_mean = tuple(round(sum(p[i] for p in pixels) / len(pixels), 2) for i in range(3))
    return {
        "size": [crop.width, crop.height],
        "unique_colors": len(colors),
        "opaque_pixels": opaque,
        "opaque_fraction": round(opaque / len(pixels), 4),
        "mean_rgb": rgb_mean,
    }


def rotate_lid(point: tuple[float, float, float], angle: float) -> tuple[float, float, float]:
    """World transform inverse to the shader's ray-to-closed-lid transform."""
    px, py, pz = 1.0, 10.0, 0.0
    x, y, z = point[0] - px, point[1] - py, point[2] - pz
    c, s = math.cos(angle), math.sin(angle)
    return (x + px, c * y - s * z + py, s * y + c * z + pz)


def corners(box: tuple[tuple[float, float, float], tuple[float, float, float]],
            angle: float = 0.0) -> list[tuple[float, float, float]]:
    lo, hi = box
    points = [(x, y, z) for x in (lo[0], hi[0])
                       for y in (lo[1], hi[1])
                       for z in (lo[2], hi[2])]
    return [rotate_lid(p, angle) for p in points] if angle else points


def camera_project(point: tuple[float, float, float], width: int, height: int) -> tuple[float, float, float]:
    # Fixed isometric-ish camera: front (+Z), right (+X), and top (+Y) are visible.
    target = (8.0, 9.0, 8.0)
    cam = (24.0, 18.0, 30.0)
    f = tuple(target[i] - cam[i] for i in range(3))
    fl = math.sqrt(sum(v * v for v in f))
    f = tuple(v / fl for v in f)
    up0 = (0.0, 1.0, 0.0)
    # Right-handed camera basis: right = forward × world-up, up = right × forward.
    right = (f[1], -f[0], 0.0)
    rl = math.sqrt(sum(v * v for v in right))
    right = tuple(v / rl for v in right)
    up = (right[1] * f[2] - right[2] * f[1],
          right[2] * f[0] - right[0] * f[2],
          right[0] * f[1] - right[1] * f[0])
    d = tuple(point[i] - target[i] for i in range(3))
    scale = 12.0
    sx = width / 2.0 + sum(d[i] * right[i] for i in range(3)) * scale
    sy = height / 2.0 - sum(d[i] * up[i] for i in range(3)) * scale
    depth = sum(d[i] * f[i] for i in range(3))
    return sx, sy, depth


def draw_textured_face(canvas: Image.Image, texture: Image.Image,
                       polygon3d: list[tuple[float, float, float]],
                       region: tuple[int, int, int, int], label: str) -> None:
    projected = [camera_project(p, canvas.width, canvas.height) for p in polygon3d]
    polygon = [(round(p[0]), round(p[1])) for p in projected]
    min_x = min(x for x, _ in polygon)
    min_y = min(y for _, y in polygon)
    max_x = max(x for x, _ in polygon)
    max_y = max(y for _, y in polygon)
    if max_x <= min_x or max_y <= min_y:
        return
    tile = texture.crop(region).resize((max_x - min_x + 1, max_y - min_y + 1), Image.Resampling.NEAREST)
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).polygon(polygon, fill=255)
    canvas.paste(tile, (min_x, min_y), mask.crop((min_x, min_y, max_x + 1, max_y + 1)))


def box_faces(box: tuple[tuple[float, float, float], tuple[float, float, float]], angle: float):
    lo, hi = box
    def t(p): return rotate_lid(p, angle) if angle else p
    # Only outward-facing faces visible from the fixed camera are drawn.
    return [
        ("up", [t((lo[0], hi[1], lo[2])), t((hi[0], hi[1], lo[2])),
                t((hi[0], hi[1], hi[2])), t((lo[0], hi[1], hi[2]))]),
        ("south", [t((lo[0], lo[1], hi[2])), t((hi[0], lo[1], hi[2])),
                   t((hi[0], hi[1], hi[2])), t((lo[0], hi[1], hi[2]))]),
        ("east", [t((hi[0], lo[1], lo[2])), t((hi[0], hi[1], lo[2])),
                  t((hi[0], hi[1], hi[2])), t((hi[0], lo[1], hi[2]))]),
    ]


def render_preview(texture: Image.Image, open_lid: bool) -> Image.Image:
    image = Image.new("RGBA", (640, 560), (24, 28, 36, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 470, 640, 560), fill=(42, 48, 58, 255))
    angle = HINGE_ANGLE if open_lid else 0.0
    faces = []
    for element, box in (("body", (BODY_MIN, BODY_MAX)), ("lid", (LID_MIN, LID_MAX))):
        for face, poly in box_faces(box, angle if element == "lid" else 0.0):
            depth = sum(camera_project(p, image.width, image.height)[2] for p in poly) / 4.0
            faces.append((depth, element, face, poly))
    # Painter's order: farther faces first.
    for _, element, face, poly in sorted(faces, reverse=True):
        draw_textured_face(image, texture, poly, REGIONS[element][face], f"{element}:{face}")
    title = "OPEN LID" if open_lid else "CLOSED LID"
    draw.text((18, 18), f"CHEST HARNESS — {title}", fill=(240, 244, 255, 255))
    return image


def image_metrics(image: Image.Image) -> dict:
    px = image.convert("RGBA")
    # Chest atlas pixels are warm brown; exclude the cool background/ground and
    # white title so the geometry metrics describe the rendered chest only.
    def chest_pixel(x: int, y: int) -> bool:
        r, g, b, a = px.getpixel((x, y))
        return a > 8 and r > 45 and r > g * 1.12 and g > b * 1.15 and r - b > 18

    points = [(x, y) for y in range(px.height) for x in range(px.width)
              if chest_pixel(x, y)]
    if not points:
        return {"chest_pixels": 0}
    xs, ys = zip(*points)
    return {
        "chest_pixels": len(points),
        "bbox": [min(xs), min(ys), max(xs), max(ys)],
        "centroid": [round(sum(xs) / len(ys), 2), round(sum(ys) / len(ys), 2)],
    }


def main() -> None:
    model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    assert_model(model)
    shader = SHADER_PATH.read_text(encoding="utf-8")
    shader_locations = assert_shader_contract(shader)
    orientations = assert_orientation_contract(shader)
    texture = Image.open(TEXTURE_PATH).convert("RGBA")
    assert texture.size == (64, 64), f"vanilla chest atlas must be 64x64, got {texture.size}"

    report = {
        "model": str(MODEL_PATH.relative_to(ROOT)),
        "texture": str(TEXTURE_PATH.relative_to(ROOT)),
        "texture_size": list(texture.size),
        "shader_chest_uniforms": {location: shader_locations[location]
                                  for location in ("49", "50", "51", "52")},
        "orientations": orientations,
        "regions": {element: {face: region_metrics(texture, region)
                              for face, region in faces.items()}
                    for element, faces in REGIONS.items()},
        "hinge_angle_radians": HINGE_ANGLE,
        "closed": {},
        "open": {},
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for open_lid, key, filename in ((False, "closed", "chest_closed.png"),
                                    (True, "open", "chest_open.png")):
        preview = render_preview(texture, open_lid)
        preview.save(OUT_DIR / filename)
        report[key] = image_metrics(preview)
        if report[key].get("chest_pixels", 0) < 1000:
            raise AssertionError(f"{key} preview contains too few chest pixels")
        x0, y0, x1, y1 = report[key]["bbox"]
        if min(x0, y0) < 4 or x1 >= preview.width - 4 or y1 >= preview.height - 4:
            raise AssertionError(f"{key} preview is clipped at {report[key]['bbox']}")

    if report["closed"]["bbox"] == report["open"]["bbox"]:
        raise AssertionError("open and closed previews have identical silhouettes")

    (OUT_DIR / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Wrote {OUT_DIR / 'chest_closed.png'}")
    print(f"Wrote {OUT_DIR / 'chest_open.png'}")
    print(f"Wrote {OUT_DIR / 'report.json'}")


if __name__ == "__main__":
    main()
