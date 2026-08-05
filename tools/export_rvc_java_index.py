"""Export the RVC FAISS IndexIVFFlat into a portable Java sidecar.

This is an offline preparation step. The game runtime never imports FAISS or
starts Python. The resulting file is consumed by villager.voice.RvcIndex.

Usage:
  python tools/export_rvc_java_index.py \
      villager_voice/models/Villager-ElementAnimation.index \
      models/java/rvc-villager.index.bin
"""
from __future__ import annotations

import argparse
import struct
from pathlib import Path

import faiss
import numpy as np

MAGIC = 0x52564349  # RVCI
VERSION = 1


def write(path: Path, index_path: Path) -> None:
    index = faiss.read_index(str(index_path))
    if not isinstance(index, faiss.IndexIVFFlat):
        raise SystemExit(f"expected IndexIVFFlat, got {type(index).__name__}")
    if index.metric_type != faiss.METRIC_L2:
        raise SystemExit(f"expected squared-L2 metric, got {index.metric_type}")

    dimension = int(index.d)
    list_count = int(index.nlist)
    sizes = [int(index.invlists.list_size(i)) for i in range(list_count)]
    total = sum(sizes)
    centroids = index.quantizer.reconstruct_n(0, list_count).astype(np.float32, copy=False)

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as output:
        # Java DataInputStream is big-endian.
        output.write(struct.pack(">5I", MAGIC, VERSION, dimension, list_count, total))
        output.write(struct.pack(">%dI" % list_count, *sizes))
        output.write(centroids.astype(">f4", copy=False).tobytes(order="C"))
        for list_id, size in enumerate(sizes):
            if size == 0:
                continue
            # IndexIVFFlat stores each vector directly in its inverted-list
            # codes. DirectMap is not initialized in the shipped RVC index, so
            # index.reconstruct(id) is unavailable; reading the codes also
            # preserves the exact per-list ordering used by FAISS search.
            raw = faiss.rev_swig_ptr(index.invlists.get_codes(list_id),
                                     size * index.code_size)
            vectors = np.frombuffer(raw, dtype=np.float32).reshape(size, dimension)
            output.write(vectors.astype(">f4", copy=False).tobytes(order="C"))

    print(f"wrote {path} ({path.stat().st_size:,} bytes)")
    print(f"dimension={dimension} lists={list_count} vectors={total}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    write(args.output, args.index)


if __name__ == "__main__":
    main()
