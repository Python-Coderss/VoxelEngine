"""Prepare the direct Java ESPnet VITS base-TTS assets.

The model is downloaded manually into dev/voice-models/source-vctk. This script
copies it and tokenizer data into models/java, where split_model_assets.py
creates Git-safe parts. It never modifies reference clips or RVC checkpoints.
"""
from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "dev" / "voice-models" / "source-vctk"
TARGET = ROOT / "models" / "java"


def copy_asset(source_name: str, target_name: str) -> None:
    source = SOURCE / source_name
    if not source.is_file():
        raise SystemExit(f"Missing downloaded VCTK asset: {source}")
    target = TARGET / target_name
    if not target.exists() or target.stat().st_size != source.stat().st_size:
        shutil.copy2(source, target)
        print(f"copied {target} ({target.stat().st_size:,} bytes)")
    else:
        print(f"using {target}")


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    copy_asset("model.onnx", "vctk-vits.onnx")
    copy_asset("cmudict.dict", "cmudict.dict")
    copy_asset("homographs.en", "homographs.en")
    print("VCTK source assets are ready; the checked-in VCTK parts and manifest are used at runtime.")


if __name__ == "__main__":
    main()
