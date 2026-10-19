# Villager Voice Engine

An **analysis–synthesis** voice engine for the Villager News corpus. It measures a
voice, recreates it from a parametric source-filter model, and re-voices existing
recordings — all driven by JSON.

**No neural network, no zero-shot cloning model, no network access, no
dependencies.** Standard library only (Python 3.8+): no numpy, no scipy, no
soundfile, no ffmpeg. That matches the repository's own stated position that the
engine "ships no neural model and calls no network service", and it is what makes
the engine portable to the Java voice package.

```
6,772 lines   ·   102 voiceprint knobs   ·   48 phones   ·   377 dictionary entries
17/17 self-tests passing   ·   ~10x real time in pure Python
```

---

## Why analysis–synthesis

There are two ways to "recreate a voice". The first is a pretrained cloning model
that maps audio to audio through learned weights. The second — the one used here —
is the classical speech-production model that predates machine learning by
forty years:

> **voice = glottal source  ×  vocal-tract filter**

A glottal source (a periodic airflow pulse with a spectral tilt, plus some
aspiration noise) is pushed through a bank of resonators whose frequencies are the
formants. Everything that makes a voice recognisable is a small set of measurable
numbers: the F0 register, the five formant frequencies, the open quotient of the
glottal cycle, spectral tilt, breathiness, jitter, shimmer, and the timing of the
delivery.

That decomposition is what makes **full control** possible. Every one of those
numbers is a knob, and because they are separate they move independently: you can
drop the pitch a fourth without making the speaker sound smaller, or shorten the
vocal tract without making them sound higher-pitched. A cloning model gives you a
voice; this gives you the *instrument*.

It also makes the engine **verifiable**. A synthesised signal can be fed back
through the analyser and compared against the numbers that produced it. Every
tolerance quoted below was measured this way, not estimated.

---

## Quick start

```bash
cd tools

# speak a line with the seed voice
python3 -m villager_voice render \
    --voiceprint ../src/main/resources/voice/voiceprint_villager_news.json \
    --text "Hrmm, would you like to trade emeralds, fellow villager?" \
    --out /tmp/villager.wav

# change the voice: any knob, on the command line
python3 -m villager_voice render --text "Oh! A fellow villager!" --out /tmp/x.wav \
    --set source.f0_mean_hz=196 --set character.smile=0.6 --set source.breathiness=0.4

# re-voice an existing recording (the voice changer)
python3 -m villager_voice convert --in take.wav \
    --spec villager_voice/examples/transform_deep_villager.json \
    --out deep.wav --report report.json

# measure any WAV file or directory of them
python3 -m villager_voice analyze --in /tmp/villager.wav
python3 -m villager_voice analyze --in ../dev/voice-samples --json > measurements.json

# fit a voiceprint to real recordings
python3 -m villager_voice init --from-audio /path/to/clips --out my_voice.json

# the machine-readable schemas
python3 -m villager_voice schema voiceprint
python3 -m villager_voice schema transform
python3 -m villager_voice schema score

# verify the engine
python3 -m villager_voice selftest
```

---

## The three JSON files

The engine has one control surface and three ways to drive it.

### 1. Voiceprint — *what the voice is*

`src/main/resources/voice/voiceprint_villager_news.json`. 102 knobs in nine
sections; every scalar one is bounded by a declared range or enum
(87 ranges, 4 enums), and `selftest` fails if a knob is added without one.
`SCHEMA.md` documents all of them; `python3 -m villager_voice schema voiceprint`
emits the same thing as JSON.

| Section | What it controls |
|---|---|
| `source` | the glottal source: model, F0 register and range, open quotient, speed ratio, source tilt, aspiration, breathiness, jitter, shimmer, subharmonic mix, creak, vibrato, pitch drift |
| `tract` | the vocal-tract filter: length scale, per-formant scale f1–f5, bandwidth scale, cascade order, spectral tilt, radiation, HF loss, nasality with nasal pole and zero, frication gain, sibilance |
| `articulation` | coarticulation, transition time, undershoot, vowel reduction, duration scales, stop burst and release levels, stop aspiration |
| `prosody` | speaking rate, declination, accent rise, stress rise, F0 reset, phrase-final fall, question and exclamation rise, three pause lengths and their variability, pre-boundary lengthening, loudness range, stress loudness, final softening |
| `character` | the macro layer: gender, age, accent, emotion, tone, sarcasm, tenseness, resonance, gruffness, smile, effort, size, villager hmm mix |
| `output` | sample rate, gain, peak ceiling, two-sided peak normalisation, highpass, lowpass, room mix and decay, fades |
| `phone_overrides` | per-phone formant, bandwidth, duration and amplitude overrides |

**Precedence rule.** `character` is a *macro* layer that resolves into the
low-level sections, so `character.gender = "female"` implies a register around
153 Hz and a 7 % shorter tract. But an explicitly set low-level value always
wins: `character.gender = "female"` together with `source.f0_mean_hz = 196`
gives 196 Hz, not 274 Hz. `VoicePrint.explicit_paths()` is what distinguishes the
two cases, by diffing against `DEFAULTS`. (The single ambiguity: setting a field
to exactly its default value is indistinguishable from not setting it.)

### 2. Score — *what to say, and how*

A timed performance score. `prosody.build_score()` produces one from plain text;
`synth.render_score()` consumes one. **The synthesiser cannot tell the difference
between a generated score and a hand-written one**, which is the point: the score
is the voice-changer's input contract. See
`examples/score_handwritten_hmmm.json` for a complete hand-written example.

Top level: `text`, `sample_rate`, `duration`, `phones`, `pauses`, `words`,
`phrases`, `contours`, `events`, `random_seed`, `voiceprint`.

Each phone slot carries `sym`, `start`, `dur`, `stress`, `amp`, `word`, `syll`,
`manner`, `voiced`, `f[5]`, `bw[5]`, `phrase`, and optionally `f0_semitones` and
`override`.

The four `contours` are the per-frame animation layer, each a sorted list of
`[time_sec, value]` breakpoints that are linearly interpolated:

- `f0` — semitones relative to `source.f0_mean_hz`
- `amp` — dB relative to nominal
- `formant_scale` — linear multiplier on all formants
- `breathiness` — 0…1, overriding `source.breathiness` for those blocks

`events` overlays non-speech vocalisations (`laugh`, `sigh`, `gasp`, `hmm`) at
timestamps, mixed in *before* the output stage so they get the same EQ, room and
normalisation as the speech.

### 3. Transform spec — *how to re-voice an existing recording*

The voice changer. Applied to a WAV that already exists. See
`examples/transform_deep_villager.json`, `examples/transform_breathy_child.json`
and `examples/transform_slow_creak.json`.

The three things that make a voice sound like a particular person are kept on
separate axes so they can be moved independently:

| Axis | Mechanism | Spec keys |
|---|---|---|
| **pitch** | PSOLA — re-spaces the overlap-add marks; grains are *not* resampled, so the tract envelope survives | `pitch.semitones`, `pitch.contour`, `pitch.range_compress`, `pitch.follow_input`, `pitch.jitter_percent`, `pitch.vibrato_*` |
| **duration** | walks the input timeline faster or slower | `time.scale`, `time.keep_pitch` |
| **timbre** | resample-then-restore: resampling moves pitch *and* formants together, then PSOLA puts the pitch back, leaving only the formant shift | `timbre.formant_semitones`, `timbre.formant_scale`, `timbre.vocal_tract_scale`, `timbre.bandwidth_scale`, `timbre.envelope_mix`, `timbre.nasality` |

On top of those, `source.*` remoulds the glottal character (extra tilt,
aspiration, shimmer, creak, gruff) without touching what is being said, and
`voiceprint` adopts a whole target voice.

Raising pitch alone sounds like a pitch-shifted adult. Raising pitch *and*
formants together is what changes the apparent size of the speaker — which is why
the axes are separate rather than fused into one "pitch" control.

---

### Output loudness

`output.normalize_peak` (default on) normalises two-sided to
`output.peak_ceiling`, so a line is brought *up* to the ceiling as well as
limited down. Without it a line whose loudest moment is a vowel rather than a
stop burst renders about 22 dB quieter than a full sentence, because only the
transients ever reached the ceiling: `"Hrmm."` measured peak 0.047 / −44.7 dB
with limiting only, and 0.950 / −18.7 dB with normalisation. This matches
`wavio.normalize_peak` and the Java `ClipVoice` convention of rendering to a
fixed peak.

---

## Verification

`python3 -m villager_voice.selftest` runs 17 checks. Every number was measured,
not assumed. The tests are built around signals whose ground truth is known by
construction, because a synthesiser and an analyser can agree with each other
while both being wrong.

```
levinson_durbin_matches_gaussian_solve   PASS  max coefficient error 8.59e-14
wav_roundtrip_16bit                      PASS  max sample error 1.52e-05 (half a quantum)
voiceprint_save_load                     PASS  102 fields
voiceprint_rejects_out_of_range          PASS  f0_mean_hz=5000 rejected
voiceprint_clamp_derived                 PASS  breathiness 0.9 -> aspiration_db -33.9
schema_covers_every_knob                 PASS  102 knobs bounded (87 ranges, 4 enums)
lexicon_g2p                              PASS  19/19 words exact; 48 phones
corpus_fallback_matches_index            PASS  17 fields agree
formant_recovery_from_resonators         PASS  want [500,1500,2500] got [517,1505,2494]
sustained_vowel_round_trip               PASS  F0 116.0 -> 115.4 (0.6%); F1-F3 9.5/0.6/1.3%
clean_voice_hnr_floor                    PASS  HNR 19.1 dB, jitter 0.71%, 46 cycles
prosody_rate_matches_corpus              PASS  3.04 syl/s vs corpus 3.31 (8.2%)
sentence_round_trip_f0_f2_f3             PASS  F0 1.0%; F2 2.6%; F3 0.6%
psola_pitch_preserves_timbre_and_length  PASS  +7 st: F0 error 0.0%, dur 0.0%, F2/F3 drift 0.0/1.9%
psola_time_preserves_pitch_and_timbre    PASS  x1.50: duration error 0.01%, F0 drift 0.3%
formant_shift_independent_of_pitch       PASS  +3 st: F2 2.3%, F3 0.3%, F0 drift 0.5%
breathiness_calibration_monotonic        PASS  clean 17.2 dB, span 17.4 dB over 0..0.50
```

Highlights:

- **PSOLA pitch shift is exact.** +7 semitones on a 115.4 Hz vowel measured
  172.8 Hz against a target of 172.8 Hz — 0.0 % error — with F2/F3 drift under
  2 % and duration unchanged. Pitch and timbre really are independent.
- **Time scaling is exact.** ×1.5 gave a duration error of 0.01 % with 0.3 %
  pitch drift.
- **The analyser recovers what it is fed.** An impulse train through resonators
  at 500/1500/2500 Hz comes back as 517/1505/2494 Hz.
- **Levinson–Durbin agrees with a direct Gaussian solve** of the same
  Yule–Walker system to 8.59e-14.
- **Timing matches the corpus.** 3.04 syllables/s generated against 3.31
  measured from 2,252 real clips (1.75 h) — 8.2 % slow.

### Known limitations

Stated plainly, because a control surface is only useful if you know where it
stops being trustworthy.

- **F1 is biased low.** LPC formant estimation reads F1 about 9–19 % low when
  the fundamental sits close to the first formant; F2/F3 hold to 0.6–2.6 %. This
  is inherent to LPC peak-picking (Praat shows the same bias), not a bug in the
  synthesiser — the sustained-vowel test recovers F2/F3 to 0.6 %/1.3 % on a
  signal whose targets are known exactly. The self-test therefore allows F1 12 %
  and F2/F3 5 %.
- **HNR has a floor of about 19 dB.** Set by residual peak-position accuracy in
  the pitch-synchronous average, mitigated by parabolic sub-sample refinement and
  two passes of template alignment. A cleaner signal than that measures as clean.
- **`breathiness` is verifiable over 0…0.6 only.** Measured HNR sweeps
  monotonically from 19.1 dB (0.0) to −1.2 dB (0.6); beyond that the pitch
  tracker starts losing cycles in the noise. The knob still works audibly above
  0.6, it just cannot be checked. `analyze.CALIBRATION` stores the measured table
  and `analyze.calibrate_breathiness()` regenerates it.
- **Vibrato must be off for stationary calibration.** A 24-cent 5.1 Hz
  modulation makes successive cycles differ in length by up to 4 samples, which
  smears any pitch-synchronous average. `calibrate_breathiness()` disables it,
  vibrato, jitter, shimmer and pitch drift for exactly this reason.
- **The G2P rule path is approximate on the long tail.** English tense/lax vowel
  choice is lexical, not derivable from spelling. Correctness on frequent words is
  bought by curating `lexicon.DICT` (377 entries; the 72 most frequent words
  cover 50 % of the corpus's 16,146 tokens). Accepted imperfections include
  `queue → K W Y UW IY1` and `thank → TH AE1 NG K`.
- **Tilt is not apples-to-apples in the sentence round trip.** The synthesiser's
  `spectral_tilt_db_per_octave` is a source-stage tilt applied before the tract
  and the 6 dB/oct radiation characteristic; `analyze.measure_spectral_tilt`
  measures the radiated output. Use `analyze.tilt_from_spectrum()` to convert.

---

## The corpus, and why the acoustics are not measured from it

`src/main/resources/voice/clips_index.json` (423 KB) indexes **2,252 clips**
(2,235 addon, 17 teavrsp) totalling **1.75 hours**, with 3,758 timed cues and
16,146 word tokens (2,509 distinct).

The **timing and vocabulary in this engine are measured from that index**, and
`selftest` verifies the hard-coded fallbacks still agree with a fresh measurement
of it (17 fields). What could *not* be measured is the acoustics, because the
audio itself is not in the repository. Four independent searches confirmed this:

```
wav  0     ·  ogg  146 (73 The-Aether/, 73 mirrored in src/)  ·  mp3  463 (all music)
zip / mcpack / mcaddon  0     ·  voice/corpus/  absent     ·  nothing in git history
```

So the acoustic fields of the seed voiceprint are **RP male reference values**,
and the file says so: `provenance.measured_fields` lists what came from the
index, `provenance.assumed_fields` lists what did not. When the recordings become
available, one command re-fits them:

```bash
python3 -m villager_voice init --from-audio /path/to/clips --out voiceprint_villager_news.json
```

---

## Modules

| Module | Lines | Role |
|---|---|---|
| `wavio.py` | 185 | stdlib mono WAV I/O (8/16/24/32-bit read, 16-bit write), resample, gain, fades, mixing |
| `corpus.py` | 219 | measures `clips_index.json` into a `CorpusProfile`; documented fallbacks |
| `phones.py` | 289 | 48 ARPAbet-ASCII phones with RP formants, bandwidths, manner, voicing, duration, amplitude, plus noise/burst/closure/aspiration/nasal/place features |
| `lexicon.py` | 781 | grapheme-to-phoneme: 377 curated entries, suffix-stress tables, syllabification, tense/lax choice |
| `voiceprint.py` | 707 | the control surface: `DEFAULTS`, `RANGES`, `ENUMS`, dotted get/set, validation, macro resolution, schema |
| `prosody.py` | 612 | text → timed score: F0 contour, amplitude contour, pauses, stress, phrase boundaries |
| `synth.py` | 889 | the source-filter synthesiser: glottal models, aspiration, 5-resonator cascade, nasal pole/zero, radiation, output stage, events |
| `analyze.py` | 1,297 | the analyser: LPC, Levinson–Durbin, formant peak-picking, autocorrelation F0, pitch-synchronous HNR, jitter/shimmer from the LPC residual, spectral tilt, calibration, round trip |
| `transform.py` | 746 | the voice changer: pitch marks, PSOLA, formant shift, envelope remoulding, aspiration, spec orchestration |
| `cli.py` | 434 | `render`, `convert`, `analyze`, `calibrate`, `selftest`, `schema`, `init`, `describe`, `phones`, `pronounce` |
| `selftest.py` | 574 | the 17-check verification suite |

### Synthesis chain

```
score JSON
   │
   ├─ glottal source ──── Rosenberg rise/fall (or lf_style, pulse, buzz, whisper)
   │                      open quotient · speed ratio · subharmonic mix · creak
   │                      jitter & shimmer applied per glottal period
   │                    + aspiration noise (breathiness, 30 dB of travel)
   │                    × source spectral tilt
   │
   ├─ vocal tract ─────── 5-resonator cascade, per-formant frequency & bandwidth
   │                      nasal pole + zero when nasality > 0
   │                      frication / burst noise on a parallel branch
   │                    × radiation (+6 dB/oct) × HF loss
   │
   └─ output ──────────── highpass · lowpass · room (2 comb + allpass) · gain
                          peak ceiling · soft clip · edge fades
```

Parameter changes are applied per 32-sample block as keyframes and smoothed by a
causal one-pole filter, which is what produces coarticulation without the cost of
per-sample coefficient interpolation.

### Analysis chain

```
WAV → framing (25 ms / 10 ms hop) → preemphasis → Hamming
    → autocorrelation → Levinson–Durbin → LPC spectrum → formant peak-picking
    → autocorrelation F0 (with octave protection)
    → voiced runs → one LPC filter per run → residual
    → glottal periods (percentile threshold, windowed maxima, sub-sample
      refinement, pitch refractory)
    → jitter / shimmer (within contiguous runs only)
    → pitch-synchronous HNR (160-point grid, two alignment passes, 4.5 kHz limit)
    → spectral tilt (FFT regression)
```

Two of these choices are load-bearing and were found the hard way:

- **One LPC filter per *voiced run*, not per frame.** Per-frame inverse
  filtering gives each 25 ms frame a different filter, which moves the residual
  pulses by tens of samples and manufactures jitter: a perfectly periodic signal
  measured 9.1 % jitter. Run-level filtering brought the floor to 0.71 %.
- **Jitter and shimmer are measured only within contiguous runs.** The residual
  skips unvoiced frames, so the position list is disjoint; naively comparing
  neighbours across a gap discarded nearly every cycle (345 → 5).

---

## Samples

`../dev/voice-samples/` (gitignored — generated artifacts, regenerate with the
commands below) holds 20 renders covering the control surface: the seed voice,
emotion macros, gender and age, breathiness, creak, nasality, tract length, two
alternative glottal models, a hand-written score, and four PSOLA re-voicings.

---

## Java port

`src/main/java/villager/voice/synth/` — 12 files, 9,320 lines, Java 8, no new
dependencies (uses the `org.json` the pom already declares). It plugs into the
existing package rather than replacing it: `VillagerSynthVoice` implements
`TextToSpeech`, so it can be handed to any caller that already works with the
SAPI voice, and also exposes `render(String, SpeechOptions)` matching
`ReferenceCorpusVoice` so the two are interchangeable at the call site.

| Class | Lines | Mirrors |
|---|---|---|
| `VoicePrint` | 700 | `voiceprint.py` — dotted access, validation, `explicitPaths()`, `effective()` macro resolution, JSON I/O |
| `VoicePrintTables` | 289 | generated: 102 defaults, 87 ranges, 4 enums |
| `VillagerPhones` | 1,451 | generated: the 48-phone inventory |
| `VillagerDictionary` | 437 | generated: 377 curated pronunciations |
| `VillagerCorpusLexicon` | 2,515 | generated: pronunciations for all 2,455 distinct corpus words |
| `VillagerLexicon` | 558 | `lexicon.py` — tokenising, normalising, contractions, suffix machinery |
| `ProsodyScore` | 653 | `prosody.py` — text → timed score, all four passes |
| `FormantSynthesizer` | 1,198 | `synth.py` — source, cascade tract, nasal coupling, radiation, output stage, events |
| `PsolaVoiceTransform` | 767 | `transform.py` — pitch marks, PSOLA, formant shift, envelope, aspiration |
| `SpeechAnalysis` | 265 | `analyze.estimate_f0` — autocorrelation F0 with octave protection |
| `VillagerSynthVoice` | 190 | the bridge into the existing voice package |
| `SynthCli` | 297 | `render`, `convert`, `schema`, `phones`, `pronounce`, `stats` |

### Generated data, not transcribed data

The phone inventory, voiceprint tables and pronunciation dictionaries are
**emitted from the verified Python tables** by
`python3 -m villager_voice.java_port`. A hand-copied table of 377 pronunciations
is exactly the kind of thing that drifts silently and then makes the Java voice
disagree with the Python one it is supposed to match, so `selftest` fails if the
committed Java no longer matches what the generator produces
(`java_tables_in_sync`).

This also drove a better answer than porting the G2P rules. Rather than
transliterate several hundred lines of order-dependent, position-sensitive rules
that cannot be compile-checked here, the generator pre-computes their *output*
over the corpus vocabulary: all 2,455 distinct words the corpus uses carry their
Python-computed pronunciation. The Java engine therefore agrees with Python
exactly on every word the corpus contains, and only reaches its own simplified
rules for words the corpus never used. That simplification is documented in
`VillagerLexicon`'s Javadoc rather than hidden.

### Verification status, stated plainly

The Java port is **syntax-validated but not compiled**. No JDK is obtainable in
this sandbox: there is no `javac`/`mvn` on the machine, the `jdk4py` PyPI wheels
ship a JRE with `jdk.compiler` stripped out (so `java -m jdk.compiler/...` fails
with "Module jdk.compiler not found"), and the Adoptium download hosts are not
reachable. Every file was parsed with `javalang`, which also parses all 23
pre-existing files in the package cleanly — so the grammar is right and no
existing file was broken, but **type errors would not be caught**. Run
`mvn -q compile` before trusting it.

The Python implementation is the verified reference: 18/18 self-tests, and its
round trip recovers F0 to 1.0% and F2/F3 to 2.6%/0.6%.

One correction to carry across: `WavAudio.java` reads 16-bit PCM with ÷32768
(line 44) but writes with ×32767 (line 64). That asymmetry injects a systematic
`v/32768` error into every round trip — 4.3e-05 at 0.9 full scale. It is fixed in
`wavio.write_wav` here and should be fixed in Java to match.
