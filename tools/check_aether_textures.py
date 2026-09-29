"""Verify that every `texture` referenced by the aether entity model JSONs
resolves to a real PNG under the roots Main.loadEntityTextures() loads:
  - src/main/resources/assets/minecraft/textures/entity
  - src/main/resources/assets/aether/textures/entity/mobs

Run: python tools/check_aether_textures.py
"""
import json
import os
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AETHER = os.path.join(ROOT, "src", "main", "resources", "assets", "aether")
MODELS = os.path.join(AETHER, "models", "entity")
ROOTS = [
    os.path.join(ROOT, "src", "main", "resources", "assets", "minecraft", "textures", "entity"),
    os.path.join(AETHER, "textures", "entity", "mobs"),
]


def resolve(name):
    for root in ROOTS:
        p = os.path.join(root, *name.split("/")) + ".png"
        if os.path.exists(p):
            return p
    return None


def main():
    bad = 0
    for mf in sorted(glob.glob(os.path.join(MODELS, "*.json"))):
        doc = json.load(open(mf, encoding="utf-8"))
        texs = sorted({p.get("texture") for p in doc.get("parts", []) if p.get("texture")})
        for t in texs:
            if resolve(t) is None:
                bad += 1
                print("UNRESOLVED %-28s %s" % (os.path.basename(mf), t))
    print("unresolved:", bad)


if __name__ == "__main__":
    main()
