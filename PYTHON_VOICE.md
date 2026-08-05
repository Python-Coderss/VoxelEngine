# Python Villager Voice Pipeline

The authoritative villager voice implementation lives in `../villager_voice/`
(a sibling of this repo — Coqui TTS + RVC v2, Python 3.10–3.13). The Java
implementation that previously mirrored it has been **removed** from this
repository (the game now plays a silent stub and shows the dialogue text in
the HUD). This document records the Python pipeline so it can be reproduced
or reimplemented later with a stronger model.
> **Update (Java 8 runtime restored):** the removed Java villager voice has been restored to this repository - `villager.voice` package, ONNX parts under `models/java/` (Coqui VCTK VITS + ContentVec + RVC), and OpenAL playback via `VillagerAudioManager`. All inference runs in Java (ONNX Runtime, pure-Java cmudict frontend; no Python or eSpeak); this document remains the record of the Python recipe used to produce and tune the RVC timbre model.

## Repository layout (`../villager_voice/`)

| Path | Purpose |
|------|---------|
| `python/villager_line.py` | **The validated "noflat_key3" recipe**, single file |
| `python/villager_pipeline.py` | 3-subprocess variant (releases RAM between stages) |
| `python/villager_tts.py` | Base synthesis: XTTS v2 / YourTTS / plain VCTK p226 |
| `python/villager_rvc.py` | RVC v2 timbre conversion (fairseq-free Hubert loader) |
| `python/villager_vc.py` | FreeVC24 zero-shot voice-conversion alternative |
| `python/monotonize.py` | PSOLA flatten / pitch-shift helpers |
| `python/export_java_models.py` | One-time exporter for the (now removed) Java ONNX bundle |
| `python/villager_voice_colab.ipynb` | Colab GPU notebook |
| `python/bootstrap.sh` / `bootstrap_win.bat` | Environment bootstrap |
| `models/` | Downloaded RVC `.pth`/`.index` + feature models |
| `samples_reference/` | Shared villager reference recordings (`ref_*.wav`, `ref_concat_mono.wav`) |
| `demo/` | Reference and capability-test WAVs |
| `lines.json` | Example batch input: `[{"id": str, "text": str}, ...]` |

## The validated recipe ("noflat_key3")

1. **Base speech** — Coqui `tts_models/en/vctk/vits`, speaker `p226`
   (natural prosody). Sentences are split on `[.!?…] `, each part is rendered
   independently, and parts are joined with a `0.1 s` silence gap at the base
   sample rate (22.05 kHz).
2. **Timbre** — villager RVC v2 model (`Villager-ElementAnimation.pth`, trained
   on ~18 min of Dan Lloyd / Element Animation audio) through `rvc_python`:
   - ContentVec embedder `lengyue233/content-vec-best`, layer-12 features
   - f0 method `rmvpe`, pitch **+3 semitones** (`--f0-up-key 3`)
   - IVF **index rate 0.5**, **protect 0.5** (disables voiceless mixing)
3. **Output** — resample to **24 kHz**, peak-ceiling **0.85**, mono 16-bit PCM.

No monotonize/flattening — natural pitch is deliberately kept (the "flat" pass
is opt-in only).

## Usage

```bash
# single line (optional .ogg sidecar)
python python/villager_line.py "Hmm. What do you want, traveler?" -o line.wav --ogg

# batch — skips lines whose .wav already exists (cheap incremental updates),
# writes an index.json of id -> text
python python/villager_line.py --lines lines.json --outdir voiced_lines --ogg

# deterministic render — same line + seed => byte-identical output
python python/villager_line.py "Hello" -o line.wav --seed 123
```

## Determinism

`--seed N` fixes `torch`, `numpy`, and `random` seeds before model load and
again before each line render, so per-line draws are independent of history and
batch order. Without a seed the output is nondeterministic (the RVC posterior
samples Gaussian noise). The removed Java side could never match this
byte-for-byte because its exported ONNX graph drew its own seeded noise.

## Stage scripts in detail

### `villager_tts.py` — stage 1 (base speech)

- Default model: XTTS v2 (`tts_models/multilingual/multi-dataset/xtts_v2`);
  falls back to YourTTS on low-RAM boxes.
- Sampling: `temperature 0.65`, `top_p 0.9`, `top_k 50`,
  `repetition_penalty 1.05`, `length_penalty 1.0`.
- Text is chunked to ≤ 260 chars per sentence-chunk to avoid long-form drift,
  with a 0.14 s silence gap between chunks.
- `--base` mode = plain TTS (VCTK p226, no reference clips) — used as
  pipeline stage 1 by `villager_pipeline.py`.
- `clean()`: optional PSOLA flatten (`--strength < 1`) and pitch shift
  (`--pitch <semitones>`), then resample to 24 kHz with a 0.89 peak ceiling.
- `--raw` writes the untouched native-rate output (for the subprocess pipeline).

### `villager_rvc.py` — stage 2 (timbre conversion)

- Loads `Villager-ElementAnimation.pth` (auto-downloaded from
  `huggingface.co/SyberGen/MinecraftModels-v2/Villager-ElementAnimation.zip`,
  ~150 MB) and the matching `.index` for retrieval.
- **fairseq-free patch**: `rvc_python` normally needs `fairseq`'s
  `load_hubert()`; on Python 3.13 fairseq won't build, so this replaces it
  with a `transformers.HubertModel` loader. `VILLAGER_EMBEDDER` selects
  `contentvec` (default), `hf_hubert`, or `local` (fairseq checkpoint with
  key remapping).
- Runs `rvc.vc.vc_single(...)` under `torch.inference_mode()` with
  `f0_method="rmvpe"`, `sid=0`, `filter_radius`, `resample_sr`,
  `rms_mix_rate`, `protect`.
- `vc_single` swallows exceptions (returns the traceback as a string) — the
  script treats a `str` result as failure.
- Torch is limited to 2 threads for 2 GB-RAM survival.

### `villager_pipeline.py` — 3-subprocess variant

Runs `villager_tts.py --base --raw` → `villager_rvc.py` (or `villager_vc.py`)
→ optional `monotonize.py --flatten`, as separate subprocesses so RAM is fully
released between stages. Same defaults as the recipe; `--flatten --f0 155.8
--strength 0.2` reproduces the old monotone take.

### `villager_vc.py` — alternative stage 2

Zero-shot VC with Coqui `voice_conversion_models/multilingual/vctk/freevc24`:
keeps the base's prosody/content and swaps the timbre toward
`samples_reference/ref_concat_mono.wav`.

### `monotonize.py`

PSOLA-based `flatten_pitch(wav, target_f0, strength)` and
`shift_pitch(wav, semitones)`. Full flatten is opt-in; the validated recipe
does not use it.

## Model sources

| Model | Source | Size |
|-------|--------|------|
| Villager RVC v2 `.pth` + `.index` | `huggingface.co/SyberGen/MinecraftModels-v2` (zip) | ~150 MB |
| ContentVec embedder | `lengyue233/content-vec-best` (HF transformers) | ~350 MB |
| Base TTS (VCTK VITS / XTTS v2) | Coqui model zoo (`COQUI_TOS_AGREED=1`) | ~2.5 GB total first run |

## Output contract

- Mono, **24 kHz**, 16-bit PCM WAV (`subtype="PCM_16"`).
- Optional `.ogg` (Vorbis) sidecar for resource-pack ingestion.
- Peak ceiling 0.85 (villager_line) / 0.89 (villager_tts) — never clips.

## Licensing

The source recordings and model assets may carry separate copyright/license
terms (the base TTS is Coqui CPML, non-commercial). Check those terms before
distributing generated voices or assets.
