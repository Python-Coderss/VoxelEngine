"""Prepare optional Java parity assets from the already-installed local models.

This command is run manually during development/setup, never by the game:

  python tools/prepare_java_voice_assets.py

It copies the local rvc_python RMVPE ONNX model into dev/voice-models and
exports the existing villager FAISS IndexIVFFlat into the portable sidecar
consumed by RvcIndex. It does not modify references, checkpoints, or the
checked-in model parts.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from export_rvc_java_index import write

ROOT = Path(__file__).resolve().parents[1]
SIBLING = ROOT.parent / "villager_voice"
RUNTIME = ROOT / "dev" / "voice-models"
RMVPE = SIBLING / "python" / ".venv" / "Lib" / "site-packages" / "rvc_python" / "base_model" / "rmvpe.onnx"
FAISS_INDEX = SIBLING / "models" / "Villager-ElementAnimation.index"


def main() -> None:
    if not RMVPE.is_file():
        raise SystemExit(f"Missing local RMVPE ONNX asset: {RMVPE}")
    if not FAISS_INDEX.is_file():
        raise SystemExit(f"Missing local FAISS index: {FAISS_INDEX}")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    target = RUNTIME / "rmvpe.onnx"
    if not target.exists() or target.stat().st_size != RMVPE.stat().st_size:
        shutil.copy2(RMVPE, target)
        print(f"copied {target} ({target.stat().st_size:,} bytes)")
    else:
        print(f"using {target}")
    write(RUNTIME / "rvc-villager.index.bin", FAISS_INDEX)


if __name__ == "__main__":
    main()
