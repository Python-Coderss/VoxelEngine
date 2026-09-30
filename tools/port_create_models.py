#!/usr/bin/env python3
"""Port Create mod block/item models into the engine's model format.

Reads the sparsely-cloned original Create mod (mc1.16/dev) from ./Create and
produces, under src/main/resources:

  * assets/minecraft/models/block/create_<name>.json   (flattened engine models)
  * assets/minecraft/models/item/create_<name>.json    (inventory icons)
  * assets/minecraft/textures/blocks/create_*.png      (copied textures)
  * create_content.json                                (registration manifest:
    stable block IDs, display names, icon textures, full-block flags)

Model flattening walks each model's parent chain, merges texture maps (child
wins), rewrites texture references to the copied create_* basenames, and bakes
element rotations in 90-degree steps into axis-aligned boxes.  Non-90-degree
rotations are left unrotated and reported (the engine's AABB renderer cannot
express them).

The manifest keeps IDs stable across runs: existing entries keep their IDs and
new blocks are appended at the next free ID from 5000.

Usage: python tools/port_create_models.py
"""
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
CREATE = os.path.join(ROOT, "Create", "src")
CREATE_GEN = os.path.join(CREATE, "generated", "resources", "assets", "create")
CREATE_MAIN = os.path.join(CREATE, "main", "resources", "assets", "create")
ENG = os.path.join(ROOT, "src", "main", "resources")
ENG_MODELS = os.path.join(ENG, "assets", "minecraft", "models")
ENG_TEX = os.path.join(ENG, "assets", "minecraft", "textures", "blocks")
MANIFEST = os.path.join(ENG, "create_content.json")

FIRST_BLOCK_ID = 5000
FACES = ("down", "up", "north", "south", "west", "east")

# Vanilla template parents that contribute only texture keys (the engine draws
# a full cube for models without elements). Values: set of texture keys.
TEMPLATES = {
    "cube_all": ["all"],
    "cube_bottom_top": ["bottom", "top", "side"],
    "cube_column": ["end", "side"],
    "cube": ["down", "up", "north", "south", "west", "east"],
    "orientable": ["top", "front", "side"],
    "orientable_with_bottom": ["top", "bottom", "front", "side"],
    "cross": ["cross"],
    "button": ["texture"],
    "pressure_plate_up": ["texture"],
    "pressure_plate_down": ["texture"],
    "torch": ["torch"],
    "leaves": ["all"],
}

report = {"approximated_rotations": [], "unresolved_models": [], "unresolved_textures": []}

# Vanilla textures the Create models borrow, mapped to the engine's shipped
# 1.12-style texture basenames.
ALIASES = {
    "block/acacia_planks": "planks_acacia",
    "block/birch_planks": "planks_birch",
    "block/jungle_planks": "planks_jungle",
    "block/spruce_planks": "planks_spruce",
    "block/dark_oak_planks": "planks_big_oak",
    "block/crimson_planks": "planks_oak",
    "block/warped_planks": "planks_oak",
    "block/anvil": "anvil_base",
    "block/campfire_fire": "fire_layer_0",
    "block/piston_top": "piston_top_normal",
    "block/polished_andesite": "stone_andesite_smooth",
    "block/smooth_stone": "stone_slab_top",
    "block/spruce_log": "log_spruce",
    "block/spruce_log_top": "log_spruce_top",
    "block/stripped_oak_log": "log_oak",
    "block/stripped_spruce_log": "log_spruce",
    "block/stripped_spruce_log_top": "log_spruce_top",
    "block/stonecutter_bottom": "stone_slab_top",
    "block/powered_rail": "rail_golden_powered",
    "block/rail": "rail_normal",
}


def read_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def model_path(name):
    """Locate a Create model JSON by resource name (block/... or bare)."""
    name = name.split(":")[-1]
    if name.startswith("block/"):
        name = name[len("block/"):]
    for root in (os.path.join(CREATE_GEN, "models", "block"),
                 os.path.join(CREATE_MAIN, "models", "block")):
        p = os.path.join(root, name + ".json")
        if os.path.exists(p):
            return p
    return None


def find_texture(rel):
    """Locate a Create texture png by relative path under textures/."""
    for base in (os.path.join(CREATE_MAIN, "textures"),
                 os.path.join(CREATE_GEN, "textures")):
        p = os.path.join(base, rel + ".png")
        if os.path.exists(p):
            return p
    return None


copied_textures = {}


def convert_texture(ref):
    """Rewrite a model texture reference to an engine texture basename."""
    if ref is None or ref.startswith("#"):
        return ref
    ref = ref.split(":")[-1]  # strip domain (create:/minecraft:)
    base = ref.split("/")[-1]
    if ref in ALIASES:
        alias = ALIASES[ref]
        copied_textures[ref] = alias
        return alias
    # Already-copied or existing engine texture?
    dest_name = "create_" + ref.replace("/", "_")
    if ref in copied_textures:
        return copied_textures[ref]
    if os.path.exists(os.path.join(ENG_TEX, base + ".png")) and not ref.startswith("block/") \
            and not ref.startswith("item/"):
        # vanilla-name texture that the engine already ships
        copied_textures[ref] = base
        return base
    src = find_texture(ref)
    if src is None:
        # maybe a vanilla texture name that the engine ships under the basename
        if os.path.exists(os.path.join(ENG_TEX, base + ".png")):
            copied_textures[ref] = base
            return base
        report["unresolved_textures"].append(ref)
        copied_textures[ref] = base
        return base
    dest = os.path.join(ENG_TEX, dest_name + ".png")
    if not os.path.exists(dest):
        with open(src, "rb") as f:
            data = f.read()
        with open(dest, "wb") as f:
            f.write(data)
    copied_textures[ref] = dest_name
    return dest_name


def rot90(origin, axis, angle, x, y, z):
    """Rotate a point around origin by angle degrees (multiples of 90) about axis."""
    ox, oy, oz = origin
    x, y, z = x - ox, y - oy, z - oz
    steps = (angle // 90) % 4
    for _ in range(steps):
        if axis == "y":
            x, z = z, -x
        elif axis == "x":
            y, z = -z, y
        else:  # z
            x, y = -y, x
    return x + ox, y + oy, z + oz


FACE_ROT_Y = {"north": "west", "west": "south", "south": "east", "east": "north"}
FACE_ROT_X = {"up": "south", "south": "down", "down": "north", "north": "up"}
FACE_ROT_Z = {"up": "west", "west": "down", "down": "east", "east": "up"}


def rotate_face_dir(d, axis, angle):
    table = {"x": FACE_ROT_X, "y": FACE_ROT_Y, "z": FACE_ROT_Z}[axis]
    for _ in range((angle // 90) % 4):
        d = table[d]
    return d


def bake_element(el):
    """Return a copy of the element with its rotation baked in (90-degree only)."""
    rot = el.get("rotation")
    frm, to = el["from"], el["to"]
    faces = {k: dict(v) for k, v in el.get("faces", {}).items()}
    if not rot:
        return {"from": frm, "to": to, "faces": faces} if faces else {"from": frm, "to": to}
    axis = rot["axis"]
    angle = int(rot["angle"])
    origin = rot.get("origin", [8, 8, 8])
    if angle % 90 != 0:
        report["approximated_rotations"].append(
            "%s around %s by %s" % (el.get("from"), axis, angle))
        return {"from": frm, "to": to, "faces": faces} if faces else {"from": frm, "to": to}
    corners = []
    for cx in (frm[0], to[0]):
        for cy in (frm[1], to[1]):
            for cz in (frm[2], to[2]):
                corners.append(rot90(origin, axis, angle, cx, cy, cz))
    xs = [c[0] for c in corners]
    ys = [c[1] for c in corners]
    zs = [c[2] for c in corners]
    new_frm = [min(xs), min(ys), min(zs)]
    new_to = [max(xs), max(ys), max(zs)]
    new_faces = {}
    for d, face in faces.items():
        new_faces[rotate_face_dir(d, axis, angle)] = face
    return {"from": new_frm, "to": new_to, "faces": new_faces} if new_faces \
        else {"from": new_frm, "to": new_to}


def flatten_model(name, depth=0):
    """Flatten a model chain into (textures, elements)."""
    if depth > 10:
        return {}, None
    path = model_path(name)
    bare = name.split(":")[-1]
    if bare.startswith("block/"):
        bare = bare[len("block/"):]
    if path is None:
        # vanilla template: contributes no elements, keys only matter to callers
        return {}, None
    data = read_json(path)
    textures = {}
    for k, v in data.get("textures", {}).items():
        if not v.startswith("#"):
            textures[k] = convert_texture(v)
    elements = None
    if "elements" in data:
        elements = [bake_element(e) for e in data["elements"]]
    parent = data.get("parent")
    if parent:
        p_textures, p_elements = flatten_model(parent, depth + 1)
        for k, v in p_textures.items():
            textures.setdefault(k, v)
        if elements is None:
            elements = p_elements
    return textures, elements


def resolve_refs(textures):
    """Resolve #key references inside the merged texture map."""
    for _ in range(10):
        changed = False
        for k, v in textures.items():
            if v.startswith("#") and v[1:] in textures:
                textures[k] = textures[v[1:]]
                changed = True
        if not changed:
            break
    return textures


def first_icon(textures):
    for k in ("all", "top", "front", "up", "side", "end", "cross", "texture",
              "particle", "layer0"):
        if k in textures and textures[k] and not textures[k].startswith("#"):
            return textures[k]
    for v in textures.values():
        if v and not v.startswith("#"):
            return v
    return None


def pick_variant_model(blockstate):
    if "variants" in blockstate:
        variants = blockstate["variants"]
        if "" in variants:
            entry = variants[""]
        else:
            entry = next(iter(variants.values()))
        if isinstance(entry, list):
            entry = entry[0]
        return entry.get("model")
    if "multipart" in blockstate:
        part = blockstate["multipart"][0]
        apply = part.get("apply", {})
        if isinstance(apply, list):
            apply = apply[0]
        return apply.get("model")
    return None


def is_full_block(elements):
    if elements is None:
        return True
    if len(elements) != 1:
        return False
    e = elements[0]
    return e["from"] == [0, 0, 0] and e["to"] == [16, 16, 16] and len(e.get("faces", {})) == 6


def main():
    lang_path = os.path.join(CREATE_GEN, "lang", "en_us.json")
    lang = read_json(lang_path) if os.path.exists(lang_path) else {}

    manifest = []
    if os.path.exists(MANIFEST):
        manifest = read_json(MANIFEST)
    by_name = {e["name"]: e for e in manifest}
    next_id = max([e["id"] for e in manifest if "id" in e] + [FIRST_BLOCK_ID - 1]) + 1

    bs_dir = os.path.join(CREATE_GEN, "blockstates")
    names = sorted(f[:-5] for f in os.listdir(bs_dir) if f.endswith(".json"))
    ported = 0
    for name in names:
        bs = read_json(os.path.join(bs_dir, name + ".json"))
        model = pick_variant_model(bs)
        if not model:
            report["unresolved_models"].append(name + " (no variant model)")
            continue
        textures, elements = flatten_model(model)
        textures = resolve_refs(textures)
        if not textures:
            report["unresolved_models"].append(name + " (no textures: %s)" % model)
            continue

        eng_name = "create_" + name
        out = {"textures": textures}
        if elements is not None:
            out["elements"] = elements
        write_json(os.path.join(ENG_MODELS, "block", eng_name + ".json"), out)

        icon = first_icon(textures)
        if icon:
            write_json(os.path.join(ENG_MODELS, "item", eng_name + ".json"),
                       {"parent": "item/generated", "textures": {"layer0": icon}})

        if eng_name not in by_name:
            by_name[eng_name] = {
                "name": eng_name,
                "id": next_id,
                "displayName": lang.get("block.create." + name,
                                        name.replace("_", " ").title()),
                "icon": icon or textures.get("all", ""),
                "fullBlock": is_full_block(elements),
            }
            next_id += 1
        ported += 1

    # Item-only models (wrench, goggles, ingredients, ...) for the inventory.
    item_dir = os.path.join(CREATE_GEN, "models", "item")
    items_ported = 0
    if os.path.isdir(item_dir):
        for fname in sorted(f for f in os.listdir(item_dir) if f.endswith(".json")):
            iname = fname[:-5]
            eng_name = "create_" + iname
            if os.path.exists(os.path.join(ENG_MODELS, "item", eng_name + ".json")):
                continue
            data = read_json(os.path.join(item_dir, fname))
            textures = {}
            for k, v in data.get("textures", {}).items():
                if not v.startswith("#"):
                    textures[k] = convert_texture(v)
            if not textures and data.get("parent"):
                textures, _ = flatten_model(data["parent"])
            textures = resolve_refs(textures)
            icon = first_icon(textures)
            if not icon:
                continue
            write_json(os.path.join(ENG_MODELS, "item", eng_name + ".json"),
                       {"parent": "item/generated", "textures": {"layer0": icon}})
            items_ported += 1

    write_json(MANIFEST, sorted(by_name.values(), key=lambda e: e.get("id", 0)))

    print("ported %d blocks + %d item-only models, %d textures copied"
          % (ported, items_ported, len(copied_textures)))
    print("manifest: %d entries (%d..%d)"
          % (len(by_name), min(e["id"] for e in by_name.values()),
             max(e["id"] for e in by_name.values())))
    if report["approximated_rotations"]:
        print("approximated non-90 rotations: %d" % len(report["approximated_rotations"]))
    if report["unresolved_models"]:
        print("unresolved models (%d):" % len(report["unresolved_models"]))
        for m in report["unresolved_models"][:20]:
            print("   -", m)
    if report["unresolved_textures"]:
        uniq = sorted(set(report["unresolved_textures"]))
        print("unresolved textures (%d):" % len(uniq))
        for t in uniq[:20]:
            print("   -", t)
    return 0


if __name__ == "__main__":
    sys.exit(main())
