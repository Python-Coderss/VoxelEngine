# Java Villager Voice

VoxelEngine now includes the Java villager voice runtime from `villager_voice`.
Right-clicking a villager selects profession- and time-aware dialogue. Synthesis
runs on a background worker, while OpenAL playback is serviced by the render thread.

## Build

Run Maven from the `VoxelEngine` directory:

```bash
./mvnw test
```

On Windows, use `mvnw.cmd test`.

## Model assets

The repository stores the large ONNX files as GitHub-safe parts under:

```text
VoxelEngine/models/java/*.onnx.partNNN
```

On first startup, Java verifies the manifest and parts, then atomically rebuilds
the complete models into the ignored runtime directory:

```text
VoxelEngine/dev/voice-models/
```

The source parts are each below GitHub's 100 MB file limit. The reconstructed
bundle is approximately 545 MB and is never committed. Delete
`dev/voice-models/` to force a verified rebuild. The small `tokens.txt` and
`espeak-ng-data` assets are copied into the same runtime directory.

Start the engine with `VoxelEngine` as the working directory so the default
`models/java` source path resolves correctly. If the parts are moved, pass the
source directory through the normal model-directory option; override the
runtime cache with `-Dvoxel.voice.runtime.models=/path/to/runtime-models`.

If you are preparing or replacing model assets, run:

```bash
python tools/split_model_assets.py models/java
```

If the models are moved, override the source path at launch:

```bash
./mvnw -Dvoxel.voice.models=/path/to/models/java exec:java
```

On Windows, use `mvnw.cmd` and a Windows path. Code that embeds the manager can
also construct `VillagerAudioManager` with a replacement model directory.

## Persistent cache

Generated clips are saved as WAV files under:

```text
VoxelEngine/dev/voice-cache/
```

The cache uses versioned SHA-256 keys of the exact dialogue text and writes files
atomically. It is covered by the repository's `dev/` ignore rule, so clips persist
between runs on the C: drive without being committed. On a cache hit, the engine
plays the WAV directly and does not initialize or run the neural voice models for
that line. Delete `dev/voice-cache/` if you want to regenerate all dialogue.

## Audio behavior

- LWJGL OpenAL is initialized after the GLFW/OpenGL context is created.
- Voice model loading and inference happen on the `VillagerVoiceSynthesis`
  worker thread and do not block the game loop.
- Completed clips are uploaded as mono 16-bit PCM buffers and played by OpenAL
  on the render thread.
- OpenAL buffers, the worker, native voice models, context, and device are
  released during engine shutdown.

If OpenAL cannot open an audio device, the engine continues without voice
playback and logs the failure to stderr.
