# Java custom voice model bundle

This directory contains the source assets used by the Java-only neural villager
voice. The game runtime does not start Python and does not use the old formant
backend.

## Repository format

Large ONNX files are split into Git-safe parts:

- `vctk-vits.onnx.partNNN` — exported VCTK/ESPnet VITS base model, 22.05 kHz,
  with the matching CMU dictionary and homograph data.
- `vec-768-layer-12.onnx.partNNN` — ContentVec feature extractor for RVC v2.
- `rvc-villager.onnx.partNNN` — exported custom villager RVC v2 timbre model.
- `model-parts.manifest` — part counts, byte counts, and SHA-256 checksums.

The Java runtime assembles the parts atomically into the ignored
`dev/voice-models/` directory. It also copies the frontend assets and bundled
eSpeak runtime there. Delete `dev/voice-models/` to force reconstruction.

## Self-contained neural runtime

The Java path is:1. Normalize text with the bundled CMU dictionary and homograph table.
2. Convert words to the ESPnet VCTK phoneme IDs, preserving supported punctuation.
3. Run `vctk-vits.onnx` with speaker `p226` (speaker ID 1).
4. For sentence-delimited input, synthesize each sentence separately and insert
   100 ms of silence between sentences, matching the neutral Python runner.
5. Feed the resulting 22.05 kHz waveform into the Java RVC model.

`espeak-ng.exe`, `libespeak-ng.dll`, and `espeak-ng-data/` are copied into the
same runtime folder, so no Python, system PATH dependency, JNI bridge, or
separate installation is needed.

## Runtime files

The assembled folder contains:

- `vctk-vits.onnx` — VCTK/ESPnet VITS graph.
- `cmudict.dict` and `homographs.en` — the Java-side VCTK frontend assets.
- `vec-768-layer-12.onnx` and `rvc-villager.onnx` — neural RVC conversion assets.
- Optional `rmvpe.onnx` and `rvc-villager.index.bin` — parity sidecars.
- Optional `rmvpe.onnx` and `rvc-villager.index.bin` sidecars.

The reference corpus remains read-only and is not used to create or overwrite
these assets.

## Sources and licenses

The VCTK/ESPnet graph and frontend assets are distributed under their upstream
model/data terms. The ContentVec and RVC model licenses and the source-recording
terms must be checked before redistribution.
