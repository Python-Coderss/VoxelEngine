# Villager Voice (recorded clips only)

VoxelEngine's villager voice plays **recorded clips**. There is no speech
synthesis anywhere in the project: no neural models, no ONNX runtime, no Python
TTS pipeline. Right-clicking a villager selects a profession- and time-aware
line, `ClipIndex` picks the recorded clip whose transcript best matches that
line, and that clip is played. If no clip matches, the line falls back to a
vocalization — nothing is ever invented.

The clip corpus is:

| Source | Contents | Lookup |
|--------|----------|--------|
| Villager News addon voice (Oreville Studios & Element Animation) | 2,229 transcribed clips, `.ogg` decoded via stb_vorbis | `voxel.voice.addon` → addon zip or extracted resource-pack folder |
| TEAVSRP corpus (transcript-renamed) | 17 transcript-named WAVs | `voxel.voice.corpus` → `voice/corpus/assets/minecraft/sounds/mob/villager/` |

`src/main/resources/voice/clips_index.json` is the transcript index the runtime
loads. Rebuild it from the addon zip with:

```bash
python tools/build_voice_clip_index.py
```

## Runtime configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `voxel.voice.mode` | `clip` | `clip` (best-matching recorded clip) or `reference` (exact transcript-named corpus replay) |
| `voxel.voice.index` | packaged `voice/clips_index.json` | Alternative clip index |
| `voxel.voice.addon` | auto-discovered | Addon zip or extracted resource-pack directory holding the `.ogg` clips |
| `voxel.voice.corpus` | `voice/corpus/...` | Folder holding the TEAVSRP reference WAVs |
| `voxel.voice.cache` | `dev/voice-cache` | Cache for shaped clips (versioned key: text/id + playback profile) |
| `voxel.voice.dialogue` | packaged catalog | Editable dialogue metadata catalog |

`neural`, `rvc`, `coqui`, and `kokoro` are rejected by `VoiceMode.parse` with an
explicit "synthesis has been removed" error, so an old launch script fails
loudly rather than silently rendering nothing.

## Build

Run Maven from the `VoxelEngine` directory:

```bash
./mvnw test
```

On Windows, use `mvnw.cmd test`.

## Audio behavior

- LWJGL OpenAL is initialized after the GLFW/OpenGL context is created.
- Clip selection, decode, and playback shaping run on the `VillagerVoiceClips`
  worker thread and never block the game loop.
- Completed clips are uploaded as mono 16-bit PCM buffers and played by OpenAL
  on the render thread; `update()` must be pumped from that thread.
- Live captions (`LiveCaptions`, HUD bottom-center) reveal the clip's exact
  transcript word by word, publisher-side, so the caption always matches the
  audio — and still shows the line when no audio device exists.
- If OpenAL cannot open an audio device, the engine continues without voice
  playback and logs the failure to stderr.

## Standalone CLI

```bash
java -cp <classpath> villager.voice.Main "I am haggling you" -o line.wav
java -cp <classpath> villager.voice.Main --lines lines.json --outdir voiced_lines
java -cp <classpath> villager.voice.Main --editor            # voice profile editor
java -cp <classpath> villager.voice.Main --midi-editor       # VNN intro piano roll
java -cp <classpath> villager.voice.Main --dialogue-editor   # dialogue catalog editor
```

Playback controls that shape a clip: `--speed`, `--pitch` (semitones),
`--volume`, `--tone` (-1 serious … +1 joking), `--emotion`, `--sarcasm`,
`--question`.

Reference mode replays exact transcript-named clips and raises a clear error
for lines the corpus does not contain, so it doubles as a corpus coverage
check:

```bash
java -cp <classpath> villager.voice.Main --mode reference \
  "I am haggling you" -o haggling.wav
```

## Villager News intro arrangement

The VNN TV channel uses the editable score asset at
`src/main/resources/voice/villager_news_intro.json` and the
runtime-authoritative `src/main/resources/voice/villager_news_intro.mid`.
Notes are rendered by fitting recorded syllable clips to each note's pitch and
duration, then mixed; nothing is synthesized. MIDI edits are fingerprinted into
the voice cache, so changing pitches, timing, or velocities renders a fresh
intro automatically. Open the editor with `--midi-editor`.

- Score: <https://musescore.com/user/39216960/scores/6905213>
- Source file: `THE SVG.SVG` — key C major / A minor, 4/4, 130 BPM
- Fidelity: user-supplied transcription, editable and subject to correction.
  This is **not official sheet music**.

## Licensing

The addon voice and the TEAVSRP corpus are third-party recordings and may carry
their own terms; check them before redistributing the clips themselves. The
engine only references them at runtime.
