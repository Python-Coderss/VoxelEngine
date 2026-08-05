# Java custom voice model bundle

This directory contains the source assets used by the Java-only villager voice.
The runtime never starts Python and does not use eSpeak, Piper/Sherpa, or any
web API.

## Pipeline

Dialogue is synthesized entirely in Java 8 with ONNX Runtime:

1. `coqui-vctk-vits.onnx` — natural Coqui VCTK VITS base TTS (speaker p226),
   driven by a pure-Java CMU-dictionary G2P frontend (`CoquiFrontend` ->
   `CoquiVitsTts`). No eSpeak, no subprocess, no network.
2. `vec-768-layer-12.onnx` — ContentVec feature extractor for RVC v2.
3. `rvc-villager.onnx` — exported custom villager RVC v2 timbre model
   (Dan Lloyd / Element Animation villager voice).

## Repository format

The large ONNX files are split into parts smaller than GitHub's 100 MB limit:

- `coqui-vctk-vits.onnx.partNNN` — Coqui VCTK VITS base TTS graph.
- `vec-768-layer-12.onnx.partNNN` — ContentVec feature extractor for RVC v2.
- `rvc-villager.onnx.partNNN` — exported custom villager RVC v2 timbre model.
- `model-parts.manifest` — part counts, byte counts, and SHA-256 checksums.

Small frontend assets shipped next to the parts:

- `cmudict.dict` — CMU pronunciation dictionary (134k words) for the Java G2P.
- `coqui-vctk-vocab.json` — the model's symbol vocabulary (id = index, blank 178).
- `coqui-vctk-config.json` / `coqui-vctk-speaker_ids.json` — model metadata.

At runtime, `ModelAssembler` validates and joins these parts atomically into
`VoxelEngine/dev/voice-models/`, which is ignored by Git, and copies the small
frontend assets into that runtime directory. Delete `dev/voice-models/` to
force reconstruction.

## Preparing or replacing models

The Coqui VCTK graph is exported once in the `villager_voice` pipeline
(`tools/export_coqui_vctk_onnx.py`, Python prep only — the game never runs it).
From the repository root, stage the assets and split the ONNX files:

```bat
copy villager_voice\models\java\coqui-vctk-vits.onnx VoxelEngine\models\java\
copy villager_voice\models\java\vec-768-layer-12.onnx VoxelEngine\models\java\
copy villager_voice\models\java\rvc-villager.onnx VoxelEngine\models\java\
python VoxelEngine\tools\split_model_assets.py VoxelEngine\models\java
```

The splitter leaves the originals available locally for verification, but they
are ignored by Git. The Java runtime uses only the checked-in parts and rebuilds
the ignored copies automatically. The runtime can also use a complete external
model directory passed through `--models` or `voxel.voice.models`; only the
default `models/java` source bundle uses part reconstruction.

## Runtime files

The assembled runtime directory contains:

- `coqui-vctk-vits.onnx` — natural Coqui VCTK VITS base TTS graph.
- `cmudict.dict` — CMU dictionary used by the pure-Java frontend.
- `coqui-vctk-vocab.json` — symbol vocabulary used by `CoquiFrontend`.
- `vec-768-layer-12.onnx` — ContentVec feature extractor for RVC v2.
- `rvc-villager.onnx` — exported custom villager RVC v2 timbre model.

The Java process loads the graphs through ONNX Runtime and writes the final
WAV. There is no Python subprocess, eSpeak, or network access in this path.

## Sources and licenses

The Coqui VCTK VITS graph is exported from Coqui TTS `tts_models/en/vctk/vits`
(MIT), speaker p226. The RVC timbre model is derived from the Element
Animation villager voice (Dan Lloyd). The ContentVec model comes from the
Hugging Face `NaruseMioShirakana/MoeSS-SUBModel` repository. Check the model
licenses and the source-recording/model terms before redistribution.
