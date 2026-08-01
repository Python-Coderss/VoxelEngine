#!/usr/bin/env python3
"""Split Java voice ONNX assets into Git-safe parts.

Run from VoxelEngine:
    python tools/split_model_assets.py models/java

Parts are named <model>.onnx.part000, etc. The Java runtime joins them into
an ignored dev/voice-models directory after validating model-parts.manifest.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

PART_SIZE = 45 * 1024 * 1024
MODEL_NAMES = ("base-tts.onnx", "vec-768-layer-12.onnx", "rvc-villager.onnx")
MANIFEST = "model-parts.manifest"


def split_model(directory: Path, name: str, part_size: int) -> tuple[int, int, str]:
    source = directory / name
    if not source.is_file():
        raise SystemExit(f"missing model: {source}")
    for stale in directory.glob(name + ".part*"):
        stale.unlink()
    digest = hashlib.sha256()
    total = 0
    parts = 0
    with source.open("rb") as input_file:
        while True:
            data = input_file.read(part_size)
            if not data:
                break
            part = directory / f"{name}.part{parts:03d}"
            part.write_bytes(data)
            digest.update(data)
            total += len(data)
            parts += 1
    return parts, total, digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, nargs="?", default=Path("models/java"))
    parser.add_argument("--part-size-mib", type=int, default=45,
                        help="part size in MiB; must remain below GitHub's 100 MB limit")
    args = parser.parse_args()
    if args.part_size_mib <= 0 or args.part_size_mib >= 95:
        parser.error("--part-size-mib must be between 1 and 94")
    directory = args.directory.resolve()
    part_size = args.part_size_mib * 1024 * 1024
    rows = []
    for name in MODEL_NAMES:
        parts, total, digest = split_model(directory, name, part_size)
        rows.append(f"{name}\t{parts}\t{total}\t{digest}")
        print(f"{name}: {parts} parts, {total:,} bytes, sha256={digest}")
    (directory / MANIFEST).write_text(
        "# file name\tpart count\tbyte count\tsha256\n"
        + "\n".join(rows) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {directory / MANIFEST}")


if __name__ == "__main__":
    main()
