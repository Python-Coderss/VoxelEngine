#!/usr/bin/env python3
"""Generate assets for the redstone expansion:
- 16 colored redstone lamp textures (off/on) tinted from the vanilla lamp
- Per-direction repeater / comparator textures (rotated art + subtract variants)
- Model JSONs for lamps, repeaters, comparators, clutches, gearshifts, dyes, quartz
All models follow the engine's simplified JSON format (6 face keys + optional elements).
"""
import json
import os
from PIL import Image, ImageEnhance

BLOCK_TEX = "src/main/resources/assets/minecraft/textures/blocks"
ITEM_TEX = "src/main/resources/assets/minecraft/textures/items"
MODELS = "src/main/resources/assets/minecraft/models/block"

# MC 1.8 dye order: (item id, texture, lamp color RGB)
COLORS = [
    ("white",      "dye_powder_white",   (240, 240, 240)),
    ("orange",     "dye_powder_orange",  (235, 136,  68)),
    ("magenta",    "dye_powder_magenta", (195,  84, 205)),
    ("light_blue", "dye_powder_light_blue", (102, 137, 211)),
    ("yellow",     "dye_powder_yellow",  (222, 207,  42)),
    ("lime",       "dye_powder_lime",    ( 65, 205,  52)),
    ("pink",       "dye_powder_pink",    (235, 138, 166)),
    ("gray",       "dye_powder_gray",    ( 67,  67,  67)),
    ("light_gray", "dye_powder_silver",  (171, 171, 171)),
    ("cyan",       "dye_powder_cyan",    ( 40, 118, 151)),
    ("purple",     "dye_powder_purple",  (123,  47, 190)),
    ("blue",       "dye_powder_blue",    ( 53,  57, 157)),
    ("brown",      "dye_powder_brown",   (114,  71,  40)),
    ("green",      "dye_powder_green",   ( 84, 109,  27)),
    ("red",        "dye_powder_red",     (179,  49,  44)),
    ("black",      "dye_powder_black",   ( 30,  27,  27)),
]

def write_model(name, textures, elements=None):
    model = {"textures": textures}
    if elements:
        model["elements"] = elements
    with open(f"{MODELS}/{name}.json", "w") as f:
        json.dump(model, f, indent=2)
    print(f"model {name}.json")

def tint_lamp(src_path, out_path, rgb, on):
    im = Image.open(src_path).convert("RGBA")
    r, g, b = rgb
    px = im.load()
    out = Image.new("RGBA", im.size)
    opx = out.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            pr, pg, pb, pa = px[x, y]
            if pa == 0:
                opx[x, y] = (0, 0, 0, 0)
                continue
            # Base tint: multiply toward the color, keep the lamp's highlight pattern
            lr = pr * r // 255
            lg = pg * g // 255
            lb = pb * b // 255
            if on:
                # Bright saturated glow
                opx[x, y] = (min(255, lr + 40), min(255, lg + 40), min(255, lb + 40), pa)
            else:
                # Muted, darker
                opx[x, y] = (lr * 3 // 5, lg * 3 // 5, lb * 3 // 5, pa)
    out.save(out_path)

def rotate_tex(src, out, angle):
    im = Image.open(src).convert("RGBA")
    im.rotate(angle, resample=Image.NEAREST, expand=False).save(out)

def make_subtract(src, out):
    """Paint the front torch lit on a comparator texture (bottom-center 4x6 patch)."""
    im = Image.open(src).convert("RGBA")
    torch = Image.open(f"{BLOCK_TEX}/redstone_torch_on.png").convert("RGBA").crop((0, 4, 16, 16))
    torch = torch.resize((4, 6), Image.NEAREST)
    im.paste(torch, (6, 10), torch)
    im.save(out)

def lamp_tex(on):
    return "redstone_lamp_on" if on else "redstone_lamp_off"

def main():
    os.makedirs(BLOCK_TEX, exist_ok=True)
    os.makedirs(ITEM_TEX, exist_ok=True)
    os.makedirs(MODELS, exist_ok=True)

    # ---- Colored lamps: textures + models ----
    for name, tex, rgb in COLORS:
        off_src = f"{BLOCK_TEX}/{lamp_tex(False)}.png"
        on_src = f"{BLOCK_TEX}/{lamp_tex(True)}.png"
        tint_lamp(off_src, f"{BLOCK_TEX}/lamp_{name}_off.png", rgb, False)
        tint_lamp(on_src, f"{BLOCK_TEX}/lamp_{name}_on.png", rgb, True)
        write_model(f"lamp_{name}_off", {"all": f"lamp_{name}_off"})
        write_model(f"lamp_{name}_on", {"all": f"lamp_{name}_on"})

    # ---- Repeaters: rotate vanilla art for the 4 horizontal directions ----
    # Base art faces south (output toward +Z / bottom of the top-down art).
    for state in ("off", "on"):
        base = f"{BLOCK_TEX}/repeater_{state}.png"
        for d in ("south", "east", "north", "west"):
            rotate_tex(base, f"{BLOCK_TEX}/repeater_{state}_{d}.png", {"south": 0, "east": -90, "north": 180, "west": 90}[d])

    # ---- Comparators: 4 dirs x (off/on) x (compare/subtract) ----
    for state in ("off", "on"):
        base = f"{BLOCK_TEX}/comparator_{state}.png"
        sub = f"{BLOCK_TEX}/comparator_{state}_sub_base.png"
        make_subtract(base, sub)
        for d in ("south", "east", "north", "west"):
            ang = {"south": 0, "east": -90, "north": 180, "west": 90}[d]
            rotate_tex(base, f"{BLOCK_TEX}/comparator_{state}_{d}.png", ang)
            rotate_tex(sub, f"{BLOCK_TEX}/comparator_{state}_sub_{d}.png", ang)
        os.remove(sub)

    # ---- Repeater models (flat plate, art on top) ----
    for d in ("north", "south", "west", "east"):
        for st in ("", "_on"):
            write_model(f"repeater_{d}{st}", {
                "up": f"repeater_off_{d}" if st == "" else f"repeater_on_{d}",
                "down": "stone_slab_top",
                "north": "stone_slab_side", "south": "stone_slab_side",
                "west": "stone_slab_side", "east": "stone_slab_side",
                "particle": f"repeater_off_{d}" if st == "" else f"repeater_on_{d}",
            }, [{"from": [0, 0, 0], "to": [16, 2, 16]}])

    # ---- Comparator models ----
    for d in ("north", "south", "west", "east"):
        for mode in ("", "_sub"):
            for st in ("", "_on"):
                st_tex = "_on" if st else "_off"
                write_model(f"comparator_{d}{mode}{st}", {
                    "up": f"comparator{st_tex}{mode}_{d}",
                    "down": "stone_slab_top",
                    "north": "stone_slab_side", "south": "stone_slab_side",
                    "west": "stone_slab_side", "east": "stone_slab_side",
                    "particle": f"comparator{st_tex}{mode}_{d}",
                }, [{"from": [0, 0, 0], "to": [16, 2, 16]}])

    # ---- Clutch / gearshift models (full cubes with the Create textures) ----
    for name, tex in (("clutch", "clutch_off"), ("clutch_on", "clutch_on"),
                      ("gearshift", "gearshift_off"), ("gearshift_on", "gearshift_on")):
        write_model(name, {"all": tex})

    # ---- Dye items: flat plate + vertical drop plane ----
    for name, tex, _ in COLORS:
        write_model(f"item_{name}_dye", {"all": tex},
                    [{"from": [2, 0, 2], "to": [14, 3, 14],
                      "faces": {"up": {"uv": [0, 0, 16, 16]}, "down": {"uv": [0, 0, 16, 16]},
                                "north": {"uv": [0, 0, 16, 3]}, "south": {"uv": [0, 0, 16, 3]},
                                "east": {"uv": [0, 0, 16, 3]}, "west": {"uv": [0, 0, 16, 3]}}}])
        write_model(f"item_drop_{name}_dye", {"all": tex},
                    [{"from": [2, 0, 6.5], "to": [14, 16, 9.5],
                      "faces": {"north": {"uv": [0, 0, 16, 16]}, "south": {"uv": [0, 0, 16, 16]},
                                "east": {"uv": [0, 0, 3, 16]}, "west": {"uv": [0, 0, 3, 16]},
                                "up": {"uv": [0, 0, 16, 3]}, "down": {"uv": [0, 0, 16, 3]}}}])

    # ---- Nether quartz item (for the comparator recipe) ----
    write_model("item_quartz", {"all": "quartz"},
                [{"from": [2, 0, 2], "to": [14, 3, 14],
                  "faces": {"up": {"uv": [0, 0, 16, 16]}, "down": {"uv": [0, 0, 16, 16]},
                            "north": {"uv": [0, 0, 16, 3]}, "south": {"uv": [0, 0, 16, 3]},
                            "east": {"uv": [0, 0, 16, 3]}, "west": {"uv": [0, 0, 16, 3]}}}])
    write_model("item_drop_quartz", {"all": "quartz"},
                [{"from": [2, 0, 6.5], "to": [14, 16, 9.5],
                  "faces": {"north": {"uv": [0, 0, 16, 16]}, "south": {"uv": [0, 0, 16, 16]},
                            "east": {"uv": [0, 0, 3, 16]}, "west": {"uv": [0, 0, 3, 16]},
                            "up": {"uv": [0, 0, 16, 3]}, "down": {"uv": [0, 0, 16, 3]}}}])

    print("done")

if __name__ == "__main__":
    main()
