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
`dev/voice-models/` to force a verified rebuild. The small `cmudict.dict` and
`homographs.en` assets are copied into the same runtime directory for the
VCTK/ESPnet frontend. Legacy Piper/eSpeak assets may also be copied when
present, but are not required by the neutral VCTK path.

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

Files are named after the dialogue text so the cache is directly inspectable
while debugging: `hmm_what_do_you_want_traveler.wav` for the default profile,
with the few differing voice parameters appended for custom profiles
(`hmm_what_do_you_want_traveler_emo=happy.wav`). Writes are atomic. The cache is
disposable — it is simply deleted on game updates — so it carries no versioning
or hashing. It is covered by the repository's `dev/` ignore rule, so clips
persist between runs on the C: drive without being committed. On a cache hit,
the engine plays the WAV directly and does not initialize or run the neural
voice models for that line. Delete `dev/voice-cache/` if you want to regenerate
all dialogue.

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

## Debugging the voice

`bash tools/render_voice_samples.sh` renders a set of samples and builds
`dev/voice-samples/index.html`: a self-contained spectrogram comparison page
(unchanged reference corpus recordings vs generated neural output for the same
lines, with pitch-contour overlays, raw/aligned timing metrics, and click-to-seek).
It refreshes neural samples by default (`--reuse` deliberately keeps old renders),
creates comparison-only `*_neural_aligned.wav` copies with
`tools/align_neural_to_reference.py`, and never writes to the reference corpus.
The comparison page shows both the full 40–8,000 Hz view and an expanded
40–1,000 Hz low-band view so voice body is not visually compressed. The aligner
maps each generated active speech span onto the reference span for comparison;
it does not change runtime clips or claim phoneme-level DTW. The page reports
an aligned-span PASS when the timing error is at most 5%. It inlines
every WAV as base64 so it works from `file://`. `python tools/analyze_wav.py <wav...>` prints
numeric stats (duration, RMS, peak, centroid, pitch);
`python tools/probe_wav.py <wav>` prints a per-window table.

## Transcript reference backend

The standalone CLI also has a read-only `reference` mode for the supplied,
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
known lines return the recording content unchanged (resampling only when the
runtime playback rate requires it); reference mode ignores neural voice controls.
A missing transcript is an error in reference mode rather than
a synthesized fallback, so the corpus remains an honest, unchanged comparison
source. The normal generated mode is `neural`; the neutral neural path currently matches
the Python baseline only. Emotion, singing, speed, pitch, tone, source mixing,
and denoise controls remain deferred until that baseline is measured. `reference`
is read-only corpus playback. The former Java formant mode is no longer exposed or used. Set
`-Dvoxel.voice.reference=/path/to/clip-directory` to use another licensed corpus.

## Voice expression controls

The neural backend accepts the full editable voice profile. Reference mode
returns the recorded clip content unchanged (apart from the runtime sample-rate
conversion) and ignores neural voice controls. The additional neural controls are:

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
