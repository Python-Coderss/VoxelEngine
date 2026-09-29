"""For every entity model JSON, report the UV extent the model needs vs the
size of its texture(s). Detects UV-space/texture-space mismatches.

Run: python tools/check_entity_uv_space.py
"""
import json
import glob
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "src", "main", "resources", "assets")
TEX_ROOTS = [
    os.path.join(MODELS, "minecraft", "textures", "entity"),
    os.path.join(MODELS, "aether", "textures", "entity", "mobs"),
]


def resolve(name):
    for root in TEX_ROOTS:
        p = os.path.join(root, *name.split("/")) + ".png"
        if os.path.exists(p):
            return p
    return None


def main():
    for mf in sorted(glob.glob(os.path.join(MODELS, "*", "models", "entity", "*.json"))):
        doc = json.load(open(mf, encoding="utf-8"))
        ext = {}
        for p in doc.get("parts", []):
            t = p.get("texture", "?")
            uv = p.get("uv", [0, 0])
            sz = p.get("uv_size", p.get("size", [0, 0, 0]))
            w, h, d = sz
            umax = uv[0] + 2 * d + w
            vmax = uv[1] + d + h
            e = ext.setdefault(t, [0, 0])
            e[0] = max(e[0], umax)
            e[1] = max(e[1], vmax)
        for t, (umax, vmax) in sorted(ext.items()):
            tp = resolve(t)
            size = Image.open(tp).size if tp else None
            flag = ""
            if size:
                if umax > size[0] or vmax > size[1]:
                    flag = "  <-- UV EXTENT EXCEEDS TEXTURE"
                elif size[0] == 128 and umax <= 64 and size[1] == 64 and vmax <= 32:
                    flag = "  <-- 2x texture, classic UVs (needs uniform 0.5 downscale)"
            print("%-34s %-28s uv_extent=(%d,%d) tex=%s%s"
                  % (os.path.basename(mf), t, umax, vmax, size, flag))


if __name__ == "__main__":
    main()
