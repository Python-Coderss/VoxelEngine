# Java custom voice model bundle

This directory contains the source assets used by the Java-only clip generator.
The game runtime does not start Python and does not use FreeTTS or Kevin.

## Repository format

The three large ONNX files are split into parts smaller than GitHub's 100 MB
file limit:

- `base-tts.onnx.partNNN` — Piper/VITS base text-to-speech model.
- `vec-768-layer-12.onnx.partNNN` — ContentVec feature extractor for RVC v2.
- `rvc-villager.onnx.partNNN` — exported custom villager RVC v2 timbre model.
- `model-parts.manifest` — part counts, byte counts, and SHA-256 checksums.

At runtime, `ModelAssembler` validates and joins these parts atomically into
`VoxelEngine/dev/voice-models/`, which is ignored by Git. It also copies `tokens.txt`,
`phoneme-map.tsv`, the readable `pronunciation-overrides.tsv`, and
`espeak-ng-data/` into that runtime directory. Delete `dev/voice-models/` to force
reconstruction.

## Preparing or replacing models

From the repository root, prepare the source assets, copy them into the
VoxelEngine source bundle, and split the ONNX files:

```bat
python villager_voice\python\export_java_models.py
copy villager_voice\models\java\base-tts.onnx VoxelEngine\models\java\
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

- `base-tts.onnx` — Piper/VITS base text-to-speech model used only to create
  intelligible source speech.
- `tokens.txt` and `espeak-ng-data/` — Sherpa ONNX text frontend assets.
- `phoneme-map.tsv` — optional display-only IPA names and token descriptions.
- `pronunciation-overrides.tsv` — readable word pronunciations used to build a
  validated Sherpa lexicon. Entries use labels such as `eh`, `uh`, `uh2`, `er`,
  and `ow`, separated by dashes; they are converted to model symbols before
  synthesis.
- `vec-768-layer-12.onnx` — ContentVec feature extractor for RVC v2.
- `rvc-villager.onnx` — exported custom villager RVC v2 timbre model.

The Java process loads all of these through Sherpa ONNX and ONNX Runtime,
then writes the final WAV. There is no Python subprocess or bundled demo voice
in this path.

## Sources and licenses

The Piper/Sherpa base bundle comes from the Sherpa-ONNX `tts-models` release,
using `vits-piper-en_US-glados.tar.bz2`. The ContentVec model comes from the
Hugging Face `NaruseMioShirakana/MoeSS-SUBModel` repository. Check the model
licenses and the source-recording/model terms before redistribution.
