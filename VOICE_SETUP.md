# Java Villager Voice

VoxelEngine includes the Java villager voice runtime. Right-clicking a villager
selects profession- and time-aware dialogue. Synthesis runs on a background
worker, while OpenAL playback is serviced by the render thread.

The voice is entirely in Java 8: the natural **Coqui VCTK VITS** base
(`coqui-vctk-vits.onnx`) is converted to Dan Lloyd's Element Animation villager
timbre by the **RVC v2** model (`rvc-villager.onnx`). No Python, eSpeak,
subprocess, or network access runs at runtime.

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
bundle is approximately 630 MB and is never committed. Delete
`dev/voice-models/` to force a verified rebuild. The small frontend assets
(`cmudict.dict`, `coqui-vctk-vocab.json`, `coqui-vctk-config.json`,
`coqui-vctk-speaker_ids.json`) are copied into the same runtime directory.

Start the engine with `VoxelEngine` as the working directory so the default
`models/java` source path resolves correctly. If the parts are moved, pass the
source directory through the normal model-directory option; override the
runtime cache with `-Dvoxel.voice.runtime.models=/path/to/runtime-models`.

If you are preparing or replacing model assets, run:

```bash
python tools/split_model_assets.py models/java
```

That tool is one-time repository prep only; the game itself never runs Python.

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
known lines use the recorded clip, with speed, mood, and volume controls
applied; unseen lines raise a clear error, so the CLI doubles as a corpus
coverage check. The normal default is `neural` (Coqui); `reference` must be
selected explicitly. Set `-Dvoxel.voice.reference=/path/to/clip-directory` to
use another licensed corpus.

## Voice expression controls

The neural (Coqui) backend accepts the full editable voice profile. Reference
mode continues to use its recorded clip behavior and applies the compatible
timing, mood, and volume controls. Existing speed, pitch, volume, mood, and
natural-source settings remain compatible; the additional controls are:

- `--emotion neutral|happy|sad|angry|scared` — changes delivery energy, pitch,
  timing, loudness, and pitch color without replacing the explicit controls.
- `--singing 0.0..1.0` — adds musical pitch movement/vibrato to the detected voice
  contour. `0` is normal speech; `1` is the strongest singing expression. This is
  expressive singing/vibrato, not a note-by-note melody sequencer. Singing disables
  the spoken natural-source layer so it does not fight the sung RVC pitch contour.
- `--pitch VALUE` — static pitch offset in semitones.
- `--sarcasm VALUE` — dry/deadpan delivery from `0.0` sincere to `1.0` strongly sarcastic. It flattens prosody, lowers pitch, and reduces the natural carrier.
- `--question` — adds a rising interrogative ending. Dialogue metadata can set `question`; when omitted, text ending in `?` is detected automatically.
- `--tone VALUE` — delivery mood from `-1.0` serious/weighty through `0.0` neutral to `+1.0` joking/playful. It no longer means EQ brightness.

Examples:

```bash
java -cp <classpath> villager.voice.Main --emotion happy \
  "I am haggling you" -o happy.wav
java -cp <classpath> villager.voice.Main --emotion angry --pitch 2 \
  "No!!!" -o angry-no.wav
java -cp <classpath> villager.voice.Main --singing 0.75 --pitch -2 \
  "Aha that seems like a deal that will work for both of us" -o sung-aha.wav
```

The standalone `--editor` exposes emotion, mood, sarcasm, and question delivery alongside
speed, pitch, volume, natural-source, and singing controls. Hover each editor control
for its units, endpoints, and audible effect. Presets and dialogue catalogs persist
all of these values in JSON files.

## Villager News intro arrangement

The VNN TV channel uses the editable score asset at
`src/main/resources/voice/villager_news_intro.json`. The current version uses
the user-supplied transcription of GingerGuru's community arrangement:

- Score: <https://musescore.com/user/39216960/scores/6905213>
- Source file: `THE SVG.SVG`
- Key signature: C major / A minor
- Time signature: 4/4
- Tempo: 130 BPM
- Fidelity: user-supplied transcription, editable and subject to later correction

This is **not official sheet music**. The supplied transcription describes a
five-measure piano excerpt with “Play like a Villager,” “Think like a Villager,”
and “Become a Villager” lyrics. The current Java voice render uses the treble
melody only; the bass accompaniment/chords are intentionally omitted from this
first JSON version because the runtime voice is monophonic. Rests are represented
explicitly, so the render preserves the measure timing. This excerpt does not
contain the separate “Da-da...” / “Villager News!” title intro.
The game uses `src/main/resources/voice/villager_news_intro.mid` as the
runtime-authoritative asset. The older JSON transcription remains as a human-
readable fallback/reference. MIDI edits are fingerprinted into the voice cache,
so changing pitches, timing, or velocities regenerates the intro automatically.
Open the editor with `--midi-editor`; double-click to add notes, drag notes to
change pitch/time, edit duration/velocity/syllable, save MIDI, and use Preview
Villager WAV to hear the actual neural villager rendering.

When entering the VNN channel, the game queues the intro asynchronously through
the existing Java voice worker. The first generation is CPU-intensive because
each note is synthesized separately; later plays use the normal persistent voice
cache. The intro is only requested when opening a TV already on VNN or cycling
into VNN.
