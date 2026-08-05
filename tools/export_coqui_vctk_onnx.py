"""Export the cached Coqui tts_models/en/vctk/vits model to ONNX for Java.

This is an offline preparation step run manually during development/setup,
never by the game:

  python tools/export_coqui_vctk_onnx.py

It loads the model checkpoint already cached by Coqui under
%USERPROFILE%/AppData/Local/tts/tts_models--en--vctk--vits, exports the graph
through the installed TTS Vits.export_onnx path, copies the ONNX plus its
config/speaker-id metadata into dev/voice-models, and writes a golden
tokenizer fixture used by the Java parity test. No Python is invoked at
runtime by the game.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "dev" / "voice-models"


def cached_model_dir() -> Path:
    candidates = [
        Path(os.environ.get("USERPROFILE", "")) / "AppData" / "Local" / "tts"
        / "tts_models--en--vctk--vits",
        Path(os.environ.get("HOME", "")) / ".local" / "share" / "tts"
        / "tts_models--en--vctk--vits",
    ]
    for candidate in candidates:
        if (candidate / "model.pth").is_file() and (candidate / "config.json").is_file():
            return candidate
    raise SystemExit(
        "Cached Coqui VCTK model not found. Run Python once with "
        "TTS('tts_models/en/vctk/vits') to download it."
    )


def export_onnx(source: Path) -> None:
    import torch

    from TTS.config import load_config
    from TTS.tts.models.vits import Vits

    config_path = source / "config.json"
    checkpoint_path = source / "model.pth"

    print(f"loading config: {config_path}")
    config = load_config(str(config_path))
    model_args = getattr(config, "model_args", config)
    num_chars = getattr(model_args, "num_chars", "?")
    num_speakers = getattr(model_args, "num_speakers", "?")
    print(f"building Vits model (chars={num_chars}, speakers={num_speakers})")
    model = Vits.init_from_config(config)
    print(f"loading checkpoint: {checkpoint_path}")
    model.load_checkpoint(config, str(checkpoint_path), eval=True)

    target = RUNTIME / "coqui-vctk-vits.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"exporting ONNX -> {target}")

    # Replicate Vits.export_onnx but force the legacy TorchScript tracer
    # (dynamo=False). torch>=2.6 defaults torch.onnx.export to the dynamo
    # exporter, which fails on the data-dependent spline guard in
    # TTS/tts/layers/vits/transforms.py.
    _forward = model.forward
    disc = getattr(model, "disc", None)
    training = model.training
    model.disc = None
    model.eval()

    def onnx_inference(text, text_lengths, scales, sid=None, langid=None):
        model.noise_scale = scales[0]
        model.length_scale = scales[1]
        model.noise_scale_dp = scales[2]
        return model.inference(
            text,
            aux_input={
                "x_lengths": text_lengths,
                "d_vectors": None,
                "speaker_ids": sid,
                "language_ids": langid,
                "durations": None,
            },
        )["model_outputs"]

    model.forward = onnx_inference

    dummy_input_length = 100
    sequences = torch.randint(low=0, high=2, size=(1, dummy_input_length), dtype=torch.long)
    sequence_lengths = torch.LongTensor([sequences.size(1)])
    scales = torch.FloatTensor(
        [model.inference_noise_scale, model.length_scale, model.inference_noise_scale_dp]
    )
    dummy_input = (sequences, sequence_lengths, scales)
    input_names = ["input", "input_lengths", "scales"]

    if model.num_speakers > 0:
        speaker_id = torch.LongTensor([0])
        dummy_input += (speaker_id,)
        input_names.append("sid")

    if getattr(model, "num_languages", 0) > 0 and getattr(model, "embedded_language_dim", 0) > 0:
        language_id = torch.LongTensor([0])
        dummy_input += (language_id,)
        input_names.append("langid")

    torch.onnx.export(
        model=model,
        args=dummy_input,
        opset_version=15,
        f=str(target),
        verbose=True,
        dynamo=False,
        input_names=input_names,
        output_names=["output"],
        dynamic_axes={
            "input": {0: "batch_size", 1: "phonemes"},
            "input_lengths": {0: "batch_size"},
            "output": {0: "batch_size", 1: "time1", 2: "time2"},
        },
    )

    model.forward = _forward
    if training:
        model.train()
    if disc is not None:
        model.disc = disc

    if not target.is_file():
        raise SystemExit(f"export failed; no file at {target}")

    # Keep the exact vocabulary/speaker metadata next to the graph so Java can
    # validate its tokenizer and speaker id without any Python at runtime.
    shutil.copy2(config_path, RUNTIME / "coqui-vctk-config.json")
    speakers = source / "speaker_ids.json"
    if speakers.is_file():
        shutil.copy2(speakers, RUNTIME / "coqui-vctk-speaker_ids.json")
        ids = json.loads(speakers.read_text(encoding="utf-8"))
        p226 = ids.get("p226")
        print(f"p226 speaker id = {p226}")
    print(f"wrote {target} ({target.stat().st_size:,} bytes)")


def dump_golden_fixture(source: Path) -> None:
    """Write ground-truth Python token ids for the Java parity test lines."""
    from TTS.config import load_config
    from TTS.tts.utils.text.tokenizer import TTSTokenizer

    config = load_config(str(source / "config.json"))
    tokenizer, _ = TTSTokenizer.init_from_config(config)
    lines = [
        "Hello",
        "No!!!",
        "What are you doing",
        "I am haggling you",
        "Aha that seems like a deal that will work for both of us",
    ]
    fixture = {text: tokenizer.text_to_ids(text) for text in lines}
    output = RUNTIME / "coqui-vctk-golden-tokens.json"
    output.write_text(json.dumps(fixture, indent=1) + "\n", encoding="utf-8")
    print(f"wrote golden token fixture: {output}")
    print("vocab size:", len(tokenizer.characters.vocab))
    print("blank id:", tokenizer.characters.blank_id)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    source = cached_model_dir()
    print(f"using cached Coqui model: {source}")
    export_onnx(source)
    dump_golden_fixture(source)
    print("Coqui VCTK ONNX export complete. Java runtime files are in dev/voice-models.")


if __name__ == "__main__":
    main()
