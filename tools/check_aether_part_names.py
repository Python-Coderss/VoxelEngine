"""Cross-check aether entity model JSON part names against the part names
the entity Java classes animate (setPartRotation / Animation keyframes / getChild).

Run: python tools/check_aether_part_names.py
"""
import json
import os
import re
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "aether", "models", "entity")
ENTITY_DIR = os.path.join(ROOT, "src", "main", "java", "com", "voxel", "entity")

# model json -> entity java (heuristic pairing by prefix)
PAIRS = {
    "moa.json": ["MoaEntity.java"],
    "aerbunny.json": ["AerbunnyEntity.java"],
    "aerwhale.json": ["AerwhaleEntity.java"],
    "aechor_plant.json": ["AechorPlantEntity.java"],
    "cockatrice.json": ["CockatriceEntity.java"],
    "flying_cow.json": ["FlyingCowEntity.java"],
    "phyg.json": ["PhygEntity.java"],
    "sheepuff.json": ["SheepuffEntity.java"],
    "sentry.json": ["SentryEntity.java"],
    "slider.json": ["SliderEntity.java"],
    "sun_spirit.json": ["SunSpiritEntity.java"],
    "swet.json": ["SwetEntity.java"],
    "valkyrie.json": ["ValkyrieEntity.java"],
    "valkyrie_queen.json": ["ValkyrieQueenEntity.java"],
    "mimic.json": ["MimicEntity.java"],
    "chest_mimic_closed.json": ["MimicEntity.java"],
    "zephyr.json": ["ZephyrEntity.java"],
    "whirlwind.json": ["WhirlwindEntity.java"],
}

STRING_RE = re.compile(r'"([a-z][a-z0-9_]{2,})"')


def model_parts(path):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    names = [p["name"] for p in doc.get("parts", [])]
    parent = doc.get("parent")
    return names, parent


def java_part_refs(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    return set(STRING_RE.findall(src))


def main():
    for model, javas in sorted(PAIRS.items()):
        mpath = os.path.join(MODEL_DIR, model)
        if not os.path.exists(mpath):
            print("MISSING MODEL", model)
            continue
        names, parent = model_parts(mpath)
        if parent:
            pnames, _ = model_parts(os.path.join(MODEL_DIR, parent))
            names = names + pnames
        name_set = set(names)
        refs = set()
        for j in javas:
            jp = os.path.join(ENTITY_DIR, j)
            if not os.path.exists(jp):
                print("MISSING JAVA", j)
                continue
            refs |= java_part_refs(jp)
        # strings in java that look like part names but aren't in the model
        suspicious = sorted(r for r in refs
                            if re.match(r"^[a-z][a-z0-9]*(_[a-z0-9]+)+$", r)
                            and r not in name_set
                            and any(tok in r for tok in
                                    ("leg", "arm", "head", "body", "wing", "tail",
                                     "jaw", "neck", "eye", "ear", "puff", "hat",
                                     "crown", "lid", "knob", "snout", "beak",
                                     "foot", "feather", "tooth", "claw", "mouth",
                                     "nose", "horn", "shell", "core", "ring")))
        unused = sorted(n for n in name_set if n not in refs)
        print("==", model)
        if suspicious:
            print("   java refs NOT in model:", suspicious)
        if unused:
            print("   model parts never referenced by java:", unused)


if __name__ == "__main__":
    main()
