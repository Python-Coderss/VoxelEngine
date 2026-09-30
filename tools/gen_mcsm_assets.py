#!/usr/bin/env python3
"""Generate the MCSM (Minecraft: Story Mode) content assets.

Creates, with PIL:
  * textures/entity/white_pumpkin_skin.png  (boss skin: pumpkin-headed humanoid)
  * textures/blocks/formidi_bomb.png        (block sides)
  * textures/blocks/white_pumpkin.png       (carved white pumpkin block)
  * textures/items/formidi_bomb.png, white_pumpkin.png, gabriel_sword.png,
    enchant_gauntlet.png                     (inventory icons)
  * models/entity/white_pumpkin.json        (biped model using the new skin)
  * models/block/formidi_bomb.json, white_pumpkin.json

Usage: python tools/gen_mcsm_assets.py
"""
import json
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                    "assets", "minecraft")
TEX_ENT = os.path.join(ROOT, "textures", "entity")
TEX_BLK = os.path.join(ROOT, "textures", "blocks")
TEX_ITM = os.path.join(ROOT, "textures", "items")
MOD_ENT = os.path.join(ROOT, "models", "entity")
MOD_BLK = os.path.join(ROOT, "models", "block")


def pumpkin_face(size, base, outline, eye, mouth):
    """A carved pumpkin face on a size x size canvas."""
    img = Image.new("RGBA", (size, size), base)
    px = img.load()
    s = size
    # stem
    for x in range(s * 3 // 8, s * 5 // 8):
        for y in range(0, max(1, s // 8)):
            px[x, y] = outline
    # eyes (triangles)
    for y in range(s * 3 // 10, s * 11 // 20):
        w = (y - s * 3 // 10)
        for x in range(s * 2 // 10, s * 2 // 10 + w + 1):
            px[x, y] = eye
        for x in range(s * 8 // 10 - w - 1, s * 8 // 10):
            px[x, y] = eye
    # mouth (jagged)
    for x in range(s * 2 // 10, s * 8 // 10):
        top = s * 7 // 10 + (1 if (x // 2) % 2 else 0)
        for y in range(top, min(s, top + max(1, s // 10))):
            px[x, y] = mouth
    return img


def main():
    os.makedirs(TEX_ENT, exist_ok=True)
    os.makedirs(TEX_BLK, exist_ok=True)
    os.makedirs(TEX_ITM, exist_ok=True)
    os.makedirs(MOD_ENT, exist_ok=True)
    os.makedirs(MOD_BLK, exist_ok=True)

    # ── Boss skin: zombie body with a white pumpkin head and dark coat ──
    zombie_path = os.path.join(TEX_ENT, "zombie_skin.png")
    skin = Image.open(zombie_path).convert("RGBA") if os.path.exists(zombie_path) \
        else Image.new("RGBA", (64, 32), (80, 120, 60, 255))
    px = skin.load()
    w, h = skin.size
    head = pumpkin_face(64, (237, 231, 220, 255), (120, 110, 100, 255),
                        (35, 30, 35, 255), (35, 30, 35, 255))
    # Vanilla skin layout: head uvs occupy (0,0)-(32,16).
    skin.paste(head.crop((0, 0, 32, 16)), (0, 0))
    # Dark purple coat over the body/limbs region.
    for y in range(16, min(h, 32)):
        for x in range(0, w):
            r, g, b, a = px[x, y]
            if (r + g + b) < 700:
                px[x, y] = (46, 32, 58, 255)
    skin.save(os.path.join(TEX_ENT, "white_pumpkin_skin.png"))

    # ── White pumpkin block texture (carved face) ──
    block = pumpkin_face(16, (237, 231, 220, 255), (160, 150, 138, 255),
                         (35, 30, 35, 255), (35, 30, 35, 255))
    block.save(os.path.join(TEX_BLK, "white_pumpkin.png"))
    block.save(os.path.join(TEX_ITM, "white_pumpkin.png"))

    # ── Formidi-Bomb block: white clay with a dark red charge pattern ──
    bomb = Image.new("RGBA", (16, 16), (222, 216, 208, 255))
    px = bomb.load()
    for i in range(16):
        px[i, 8] = (120, 30, 30, 255)
        px[8, i] = (120, 30, 30, 255)
    for x in range(6, 10):
        for y in range(6, 10):
            px[x, y] = (60, 20, 25, 255)
    for x in range(1, 15):
        px[x, 0] = (90, 85, 82, 255)
        px[x, 15] = (90, 85, 82, 255)
        px[0, x] = (90, 85, 82, 255)
        px[15, x] = (90, 85, 82, 255)
    bomb.save(os.path.join(TEX_BLK, "formidi_bomb.png"))
    bomb.save(os.path.join(TEX_ITM, "formidi_bomb.png"))

    # ── Gabriel's Sword icon ──
    sword = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = sword.load()
    for i in range(3, 11):
        px[i, 15 - i] = (235, 235, 245, 255)
        px[i + 1, 15 - i] = (180, 190, 210, 255)
    for i in range(2, 6):
        px[i, i] = (140, 95, 50, 255)
    px[6, 7] = (210, 180, 80, 255)
    px[5, 6] = (210, 180, 80, 255)
    sword.save(os.path.join(TEX_ITM, "gabriel_sword.png"))

    # ── Enchanted Gauntlet icon ──
    glove = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = glove.load()
    for x in range(4, 12):
        for y in range(5, 13):
            px[x, y] = (120, 90, 200, 255)
    for x in range(5, 11):
        for y in range(2, 5):
            px[x, y] = (160, 130, 235, 255)
    for x in range(4, 12):
        px[x, 13] = (80, 60, 150, 255)
    glove.save(os.path.join(TEX_ITM, "enchant_gauntlet.png"))

    # ── Boss entity model: biped with the new skin ──
    zombie_model = os.path.join(MOD_ENT, "zombie.json")
    with open(zombie_model, "r", encoding="utf-8") as f:
        model = json.load(f)
    def swap(obj):
        if isinstance(obj, dict):
            for k, v in obj.items():
                if k == "texture" and v == "zombie_skin":
                    obj[k] = "white_pumpkin_skin"
                else:
                    swap(v)
        elif isinstance(obj, list):
            for v in obj:
                swap(v)
    swap(model)
    with open(os.path.join(MOD_ENT, "white_pumpkin.json"), "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)
        f.write("\n")

    # ── Block models ──
    with open(os.path.join(MOD_BLK, "formidi_bomb.json"), "w", encoding="utf-8") as f:
        json.dump({"textures": {"bottom": "formidi_bomb", "top": "formidi_bomb",
                                "side": "formidi_bomb", "particle": "formidi_bomb"}},
                  f, indent=2)
        f.write("\n")
    with open(os.path.join(MOD_BLK, "white_pumpkin.json"), "w", encoding="utf-8") as f:
        json.dump({"textures": {"all": "white_pumpkin", "particle": "white_pumpkin"}},
                  f, indent=2)
        f.write("\n")

    print("MCSM assets generated.")


if __name__ == "__main__":
    main()
