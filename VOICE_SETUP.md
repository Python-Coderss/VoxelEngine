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
`dev/voice-models/` to force a verified rebuild. The small `tokens.txt`, readable
`pronunciation-overrides.tsv`, display-only `phoneme-map.tsv`, and `espeak-ng-data`
assets are copied into the same runtime directory. The readable overrides are
compiled into a validated Sherpa token lexicon; the display map is not passed to
ONNX synthesis.

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

## Explicit formant phonemes

The formant backend can render hand-authored readable phonemes without
spelling inference, eSpeak, or IPA/token injection. Use braces around a phrase,
hyphens between phonemes in a word, and spaces between words:

```text
{dh-schwa f-ah-m-er}
{ay ae-m h-ae-g-l-ih-ng y-oo}
{n-oa}
```

The pure-Java inventory contains the 24 consonants and 20 vowels from the
project's English 44-sound reference. Notation is strict: hyphens separate
phonemes, spaces separate words, and plus signs group a connected blend:

```text
{b+l-ae  g+l-ih  s+t+r-ee-t}
```

Here `b+l`, `g+l`, and `s+t+r` are blends. `--phonemes` is available in the
voice CLI; batch files may put the same brace notation in each entry's `text`
field.

## Transcript reference backend

The standalone CLI also has a Java-only `reference` mode for the supplied,
transcript-renamed TEAVSRP clips:

```bash
java -cp <classpath> villager.voice.Main --mode reference \
  "I am haggling you" -o haggling.wav
```

The backend reads WAV files from:

```text
voice/corpus/assets/minecraft/sounds/mob/villager/
```

It normalizes the caller text and matches it to a transcript filename. Exact
known lines use the recorded clip, with speed, tone, and volume controls applied;
unseen lines fall back to the formant renderer. This is corpus replay/concatenative
coverage, not a claim of in-Java neural training. The normal default is now `neural`; `formant` and `reference` must be selected explicitly. Set
`-Dvoxel.voice.reference=/path/to/clip-directory` to use another licensed corpus.

## Voice expression controls

The neural and formant backends accept the full editable voice profile. Reference
mode continues to use its recorded clip behavior and applies the compatible timing,
tone, and volume controls. Existing speed, pitch, volume, tone, and natural-source
settings remain compatible; the additional controls are:

- `--emotion neutral|happy|sad|angry|scared` — changes delivery energy, pitch,
  timing, loudness, and spectral tilt without replacing the explicit controls.
- `--singing 0.0..1.0` — adds musical pitch movement/vibrato to the detected voice
  contour. `0` is normal speech; `1` is the strongest singing expression. This is
  expressive singing/vibrato, not a note-by-note melody sequencer. Singing disables
  the spoken natural-source layer so it does not fight the sung RVC pitch contour.
- `--pitch VALUE` — static pitch offset in semitones.
- `--tone VALUE` — warm/dark through bright, from `-1.0` to `+1.0`.

Examples:

```bash
java -cp <classpath> villager.voice.Main --emotion happy \
  "I am haggling you" -o happy.wav
java -cp <classpath> villager.voice.Main --emotion angry --pitch 2 \
  "No!!!" -o angry-no.wav
java -cp <classpath> villager.voice.Main --singing 0.75 --pitch -2 \
  "Aha that seems like a deal that will work for both of us" -o sung-aha.wav
```

The standalone `--editor` exposes emotion and singing alongside the existing
sliders and saves both values in voice preset JSON files.
