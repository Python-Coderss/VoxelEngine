# Villager Voice Engine — JSON schemas

Generated from the code by `python3 -m villager_voice.schema_doc`, so it
cannot drift from the control surface it describes. Do not edit by hand:
edit `voiceprint.py`, `prosody.py` or `transform.py` and regenerate.

```bash
cd tools
python3 -m villager_voice.schema_doc --write        # update SCHEMA.md
python3 -m villager_voice.schema_doc > /tmp/out.md  # or print to stdout
```

Three JSON files drive the engine, and they compose:

| File | Question it answers | Produced by | Consumed by |
|---|---|---|---|
| **voiceprint** | what does the voice sound like? | `init`, `analyze --voiceprint-out`, by hand | `render`, `convert` |
| **score** | what is said, when, and how? | `prosody.build_score` from text, or by hand | `synth.render_score` |
| **transform spec** | how is an existing recording re-voiced? | by hand | `transform.apply_spec` |

Every field in every schema is optional. An empty voiceprint is the seed
voice; an empty transform spec returns its input unchanged.

---

## 1. Voiceprint

102 knobs. Every scalar one is bounded by a declared range or enum (87 ranges, 4 enums), and `selftest` fails if a knob is added without one.

`character` is a *macro* layer: it resolves into `source`, `tract`,
`prosody` and `articulation` so a caller can say "female, happy, gruff"
without knowing what open quotient that implies. An explicitly set
low-level value always wins over a macro — see
`VoicePrint.explicit_paths()`.

```json
[
  {
    "path": "articulation.coarticulation",
    "default": 0.55,
    "range": "0 .. 1",
    "meaning": "0 = crisp phone targets, 1 = heavy smoothing"
  },
  {
    "path": "articulation.consonant_duration_scale",
    "default": 1.0,
    "range": "0.2 .. 4",
    "meaning": ""
  },
  {
    "path": "articulation.duration_scale",
    "default": 1.0,
    "range": "0.2 .. 4",
    "meaning": ""
  },
  {
    "path": "articulation.lateral_release",
    "default": true,
    "range": "-",
    "meaning": ""
  },
  {
    "path": "articulation.release_noise_db",
    "default": -22.0,
    "range": "-60 .. 6",
    "meaning": ""
  },
  {
    "path": "articulation.stop_aspiration_ms",
    "default": 42.0,
    "range": "0 .. 200",
    "meaning": ""
  },
  {
    "path": "articulation.stop_burst_db",
    "default": -13.0,
    "range": "-48 .. 6",
    "meaning": ""
  },
  {
    "path": "articulation.transition_ms",
    "default": 40.0,
    "range": "5 .. 200",
    "meaning": ""
  },
  {
    "path": "articulation.undershoot",
    "default": 0.18,
    "range": "0 .. 0.8",
    "meaning": "how far targets fall short at fast rates"
  },
  {
    "path": "articulation.vowel_duration_scale",
    "default": 1.0,
    "range": "0.2 .. 4",
    "meaning": ""
  },
  {
    "path": "articulation.vowel_reduction",
    "default": 0.5,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.accent",
    "default": "rp-british",
    "range": "one of: rp-british, general-american, australian, irish, neutral",
    "meaning": ""
  },
  {
    "path": "character.age_years",
    "default": 42.0,
    "range": "2 .. 110",
    "meaning": ""
  },
  {
    "path": "character.effort",
    "default": 0.5,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.emotion",
    "default": "neutral",
    "range": "one of: neutral, happy, sad, angry, scared, smug, bored, excited",
    "meaning": ""
  },
  {
    "path": "character.gender",
    "default": "male",
    "range": "one of: male, female, neutral, child, zombie",
    "meaning": ""
  },
  {
    "path": "character.gruffness",
    "default": 0.12,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.resonance",
    "default": 0.5,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.sarcasm",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.size",
    "default": 1.0,
    "range": "0.4 .. 2.5",
    "meaning": ""
  },
  {
    "path": "character.smile",
    "default": 0.0,
    "range": "-1 .. 1",
    "meaning": ""
  },
  {
    "path": "character.tenseness",
    "default": 0.45,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "character.tone",
    "default": 0.0,
    "range": "-1 .. 1",
    "meaning": ""
  },
  {
    "path": "character.villager_hmm_mix",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": "blend of the classic villager 'hmm' vocalisation"
  },
  {
    "path": "format",
    "default": "villager-voiceprint",
    "range": "-",
    "meaning": ""
  },
  {
    "path": "name",
    "default": "Villager News anchor",
    "range": "-",
    "meaning": ""
  },
  {
    "path": "notes",
    "default": "",
    "range": "-",
    "meaning": ""
  },
  {
    "path": "output.fade_ms",
    "default": 12.0,
    "range": "0 .. 500",
    "meaning": ""
  },
  {
    "path": "output.gain_db",
    "default": 0.0,
    "range": "-48 .. 24",
    "meaning": ""
  },
  {
    "path": "output.highpass_hz",
    "default": 70.0,
    "range": "0 .. 2000",
    "meaning": ""
  },
  {
    "path": "output.lowpass_hz",
    "default": 9500.0,
    "range": "500 .. 24000",
    "meaning": ""
  },
  {
    "path": "output.normalize_peak",
    "default": true,
    "range": "-",
    "meaning": ""
  },
  {
    "path": "output.peak_ceiling",
    "default": 0.95,
    "range": "0.01 .. 1",
    "meaning": ""
  },
  {
    "path": "output.room_decay_ms",
    "default": 0.0,
    "range": "0 .. 6000",
    "meaning": ""
  },
  {
    "path": "output.room_mix",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": "wet/dry mix of the simple feedback-delay room"
  },
  {
    "path": "output.sample_rate",
    "default": 24000,
    "range": "4000 .. 96000",
    "meaning": ""
  },
  {
    "path": "prosody.accent_rise_semitones",
    "default": 3.0,
    "range": "-6 .. 16",
    "meaning": ""
  },
  {
    "path": "prosody.declination_semitones_per_sec",
    "default": -0.85,
    "range": "-8 .. 4",
    "meaning": "downward pitch drift across a phrase"
  },
  {
    "path": "prosody.exclamation_rise_semitones",
    "default": 1.8,
    "range": "-8 .. 12",
    "meaning": ""
  },
  {
    "path": "prosody.f0_reset_semitones",
    "default": 1.6,
    "range": "-8 .. 12",
    "meaning": ""
  },
  {
    "path": "prosody.final_softening_db",
    "default": -2.0,
    "range": "-24 .. 6",
    "meaning": ""
  },
  {
    "path": "prosody.loudness_range_db",
    "default": 9.0,
    "range": "0 .. 30",
    "meaning": ""
  },
  {
    "path": "prosody.pause_comma_ms",
    "default": 250.0,
    "range": "0 .. 2000",
    "meaning": ""
  },
  {
    "path": "prosody.pause_sentence_ms",
    "default": 470.0,
    "range": "0 .. 4000",
    "meaning": ""
  },
  {
    "path": "prosody.pause_short_ms",
    "default": 130.0,
    "range": "0 .. 1000",
    "meaning": ""
  },
  {
    "path": "prosody.pause_variability",
    "default": 0.18,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "prosody.phrase_final_fall_semitones",
    "default": -3.4,
    "range": "-16 .. 8",
    "meaning": ""
  },
  {
    "path": "prosody.pre_boundary_lengthening",
    "default": 1.32,
    "range": "1 .. 2.5",
    "meaning": "final-syllable stretch before a boundary"
  },
  {
    "path": "prosody.question_rise_semitones",
    "default": 4.6,
    "range": "-8 .. 16",
    "meaning": ""
  },
  {
    "path": "prosody.rate_stdev",
    "default": 0.9,
    "range": "0 .. 4",
    "meaning": ""
  },
  {
    "path": "prosody.rate_syllables_per_sec",
    "default": 3.31,
    "range": "0.5 .. 12",
    "meaning": "measured from clips_index.json"
  },
  {
    "path": "prosody.rate_words_per_sec",
    "default": 2.56,
    "range": "0.3 .. 9",
    "meaning": ""
  },
  {
    "path": "prosody.rhythm",
    "default": 0.38,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "prosody.stress_loudness_db",
    "default": 2.4,
    "range": "0 .. 12",
    "meaning": ""
  },
  {
    "path": "prosody.stress_rise_semitones",
    "default": 2.1,
    "range": "-6 .. 12",
    "meaning": ""
  },
  {
    "path": "provenance.audio_seconds",
    "default": 0.0,
    "range": "-",
    "meaning": ""
  },
  {
    "path": "provenance.clips",
    "default": 0,
    "range": "-",
    "meaning": ""
  },
  {
    "path": "provenance.kind",
    "default": "seed",
    "range": "-",
    "meaning": ""
  },
  {
    "path": "provenance.measured_fields",
    "default": [],
    "range": "-",
    "meaning": ""
  },
  {
    "path": "provenance.reference",
    "default": [],
    "range": "-",
    "meaning": ""
  },
  {
    "path": "random_seed",
    "default": 20261004,
    "range": "0 .. 1.09951e+12",
    "meaning": ""
  },
  {
    "path": "source.aspiration_db",
    "default": -62.0,
    "range": "-80 .. -6",
    "meaning": "breath noise level relative to the glottal source"
  },
  {
    "path": "source.aspiration_tilt_db_per_oct",
    "default": -5.0,
    "range": "-24 .. 12",
    "meaning": ""
  },
  {
    "path": "source.breathiness",
    "default": 0.22,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "source.creak_percent",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "source.f0_ceiling_hz",
    "default": 330.0,
    "range": "60 .. 1200",
    "meaning": ""
  },
  {
    "path": "source.f0_floor_hz",
    "default": 68.0,
    "range": "30 .. 400",
    "meaning": ""
  },
  {
    "path": "source.f0_mean_hz",
    "default": 116.0,
    "range": "40 .. 600",
    "meaning": ""
  },
  {
    "path": "source.f0_semitone_range",
    "default": 7.0,
    "range": "0 .. 36",
    "meaning": ""
  },
  {
    "path": "source.jitter_percent",
    "default": 0.85,
    "range": "0 .. 8",
    "meaning": "cycle-to-cycle pitch perturbation"
  },
  {
    "path": "source.model",
    "default": "rosenberg",
    "range": "one of: rosenberg, lf_style, pulse, buzz, whisper",
    "meaning": "glottal waveform: rosenberg (smooth), lf_style (sharp return phase), pulse (bright impulse), buzz (sawtooth), whisper (unvoiced source only)"
  },
  {
    "path": "source.open_quotient",
    "default": 0.62,
    "range": "0.2 .. 0.95",
    "meaning": "fraction of each pitch period the glottis stays open"
  },
  {
    "path": "source.pitch_drift_cents_per_sec",
    "default": -3.0,
    "range": "-60 .. 60",
    "meaning": ""
  },
  {
    "path": "source.shimmer_percent",
    "default": 3.5,
    "range": "0 .. 30",
    "meaning": "cycle-to-cycle amplitude perturbation"
  },
  {
    "path": "source.source_tilt_db_per_oct",
    "default": -9.0,
    "range": "-30 .. 6",
    "meaning": ""
  },
  {
    "path": "source.speed_ratio",
    "default": 0.42,
    "range": "0.05 .. 0.9",
    "meaning": "rise time as a fraction of the open phase (skew)"
  },
  {
    "path": "source.subharmonic_mix",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": "period-doubling blend (creaky/rough voice)"
  },
  {
    "path": "source.vibrato_depth_cents",
    "default": 24.0,
    "range": "0 .. 200",
    "meaning": ""
  },
  {
    "path": "source.vibrato_onset_ms",
    "default": 220.0,
    "range": "0 .. 2000",
    "meaning": ""
  },
  {
    "path": "source.vibrato_rate_hz",
    "default": 5.1,
    "range": "0 .. 12",
    "meaning": ""
  },
  {
    "path": "source.voicing_hysteresis",
    "default": 0.5,
    "range": "0 .. 1",
    "meaning": ""
  },
  {
    "path": "tract.bandwidth_scale",
    "default": 1.0,
    "range": "0.4 .. 3",
    "meaning": ""
  },
  {
    "path": "tract.cascade_order",
    "default": 5,
    "range": "1 .. 6",
    "meaning": ""
  },
  {
    "path": "tract.extra_bandwidth_hz",
    "default": 25.0,
    "range": "0 .. 400",
    "meaning": ""
  },
  {
    "path": "tract.formant_scale.f1",
    "default": 1.0,
    "range": "0.5 .. 2",
    "meaning": ""
  },
  {
    "path": "tract.formant_scale.f2",
    "default": 1.0,
    "range": "0.5 .. 2",
    "meaning": ""
  },
  {
    "path": "tract.formant_scale.f3",
    "default": 1.0,
    "range": "0.5 .. 2",
    "meaning": ""
  },
  {
    "path": "tract.formant_scale.f4",
    "default": 1.0,
    "range": "0.5 .. 2",
    "meaning": ""
  },
  {
    "path": "tract.formant_scale.f5",
    "default": 1.0,
    "range": "0.5 .. 2",
    "meaning": ""
  },
  {
    "path": "tract.frication_gain_db",
    "default": 0.0,
    "range": "-24 .. 24",
    "meaning": ""
  },
  {
    "path": "tract.hf_loss_hz",
    "default": 6800.0,
    "range": "1000 .. 20000",
    "meaning": ""
  },
  {
    "path": "tract.length_scale",
    "default": 1.0,
    "range": "0.6 .. 1.8",
    "meaning": "vocal-tract length multiplier; >1 lowers all formants"
  },
  {
    "path": "tract.nasal_bandwidth_hz",
    "default": 95.0,
    "range": "30 .. 400",
    "meaning": ""
  },
  {
    "path": "tract.nasal_formant_hz",
    "default": 285.0,
    "range": "150 .. 800",
    "meaning": ""
  },
  {
    "path": "tract.nasal_zero_bandwidth_hz",
    "default": 130.0,
    "range": "30 .. 600",
    "meaning": ""
  },
  {
    "path": "tract.nasal_zero_hz",
    "default": 950.0,
    "range": "300 .. 3000",
    "meaning": ""
  },
  {
    "path": "tract.nasality",
    "default": 0.0,
    "range": "0 .. 1",
    "meaning": "0 = oral, 1 = fully nasal (adds nasal pole and zero)"
  },
  {
    "path": "tract.radiation_db_per_oct",
    "default": 6.0,
    "range": "0 .. 12",
    "meaning": "lip radiation differentiator (6 dB/oct = ideal)"
  },
  {
    "path": "tract.sibilance_boost_db",
    "default": 2.5,
    "range": "-12 .. 18",
    "meaning": ""
  },
  {
    "path": "tract.sibilance_center_hz",
    "default": 5400.0,
    "range": "1500 .. 11000",
    "meaning": ""
  },
  {
    "path": "tract.spectral_tilt_db_per_oct",
    "default": -3.0,
    "range": "-24 .. 12",
    "meaning": "extra shelf on the tract output"
  },
  {
    "path": "version",
    "default": 1,
    "range": "-",
    "meaning": ""
  }
]
```

### Declared ranges

| Path | Range | Default |
|---|---|---|
| `articulation.coarticulation` | 0.0 … 1.0 | `0.55` |
| `articulation.consonant_duration_scale` | 0.2 … 4.0 | `1.0` |
| `articulation.duration_scale` | 0.2 … 4.0 | `1.0` |
| `articulation.release_noise_db` | -60.0 … 6.0 | `-22.0` |
| `articulation.stop_aspiration_ms` | 0.0 … 200.0 | `42.0` |
| `articulation.stop_burst_db` | -48.0 … 6.0 | `-13.0` |
| `articulation.transition_ms` | 5.0 … 200.0 | `40.0` |
| `articulation.undershoot` | 0.0 … 0.8 | `0.18` |
| `articulation.vowel_duration_scale` | 0.2 … 4.0 | `1.0` |
| `articulation.vowel_reduction` | 0.0 … 1.0 | `0.5` |
| `character.age_years` | 2.0 … 110.0 | `42.0` |
| `character.effort` | 0.0 … 1.0 | `0.5` |
| `character.gruffness` | 0.0 … 1.0 | `0.12` |
| `character.resonance` | 0.0 … 1.0 | `0.5` |
| `character.sarcasm` | 0.0 … 1.0 | `0.0` |
| `character.size` | 0.4 … 2.5 | `1.0` |
| `character.smile` | -1.0 … 1.0 | `0.0` |
| `character.tenseness` | 0.0 … 1.0 | `0.45` |
| `character.tone` | -1.0 … 1.0 | `0.0` |
| `character.villager_hmm_mix` | 0.0 … 1.0 | `0.0` |
| `character.accent` | enum | `rp-british`, `general-american`, `australian`, `irish`, `neutral` |
| `character.emotion` | enum | `neutral`, `happy`, `sad`, `angry`, `scared`, `smug`, `bored`, `excited` |
| `character.gender` | enum | `male`, `female`, `neutral`, `child`, `zombie` |
| `output.fade_ms` | 0.0 … 500.0 | `12.0` |
| `output.gain_db` | -48.0 … 24.0 | `0.0` |
| `output.highpass_hz` | 0.0 … 2000.0 | `70.0` |
| `output.lowpass_hz` | 500.0 … 24000.0 | `9500.0` |
| `output.peak_ceiling` | 0.01 … 1.0 | `0.95` |
| `output.room_decay_ms` | 0.0 … 6000.0 | `0.0` |
| `output.room_mix` | 0.0 … 1.0 | `0.0` |
| `output.sample_rate` | 4000 … 96000 | `24000` |
| `prosody.accent_rise_semitones` | -6.0 … 16.0 | `3.0` |
| `prosody.declination_semitones_per_sec` | -8.0 … 4.0 | `-0.85` |
| `prosody.exclamation_rise_semitones` | -8.0 … 12.0 | `1.8` |
| `prosody.f0_reset_semitones` | -8.0 … 12.0 | `1.6` |
| `prosody.final_softening_db` | -24.0 … 6.0 | `-2.0` |
| `prosody.loudness_range_db` | 0.0 … 30.0 | `9.0` |
| `prosody.pause_comma_ms` | 0.0 … 2000.0 | `250.0` |
| `prosody.pause_sentence_ms` | 0.0 … 4000.0 | `470.0` |
| `prosody.pause_short_ms` | 0.0 … 1000.0 | `130.0` |
| `prosody.pause_variability` | 0.0 … 1.0 | `0.18` |
| `prosody.phrase_final_fall_semitones` | -16.0 … 8.0 | `-3.4` |
| `prosody.pre_boundary_lengthening` | 1.0 … 2.5 | `1.32` |
| `prosody.question_rise_semitones` | -8.0 … 16.0 | `4.6` |
| `prosody.rate_stdev` | 0.0 … 4.0 | `0.9` |
| `prosody.rate_syllables_per_sec` | 0.5 … 12.0 | `3.31` |
| `prosody.rate_words_per_sec` | 0.3 … 9.0 | `2.56` |
| `prosody.rhythm` | 0.0 … 1.0 | `0.38` |
| `prosody.stress_loudness_db` | 0.0 … 12.0 | `2.4` |
| `prosody.stress_rise_semitones` | -6.0 … 12.0 | `2.1` |
| `random_seed` | 0 … 1099511627776 | `20261004` |
| `source.aspiration_db` | -80.0 … -6.0 | `-62.0` |
| `source.aspiration_tilt_db_per_oct` | -24.0 … 12.0 | `-5.0` |
| `source.breathiness` | 0.0 … 1.0 | `0.22` |
| `source.creak_percent` | 0.0 … 1.0 | `0.0` |
| `source.f0_ceiling_hz` | 60.0 … 1200.0 | `330.0` |
| `source.f0_floor_hz` | 30.0 … 400.0 | `68.0` |
| `source.f0_mean_hz` | 40.0 … 600.0 | `116.0` |
| `source.f0_semitone_range` | 0.0 … 36.0 | `7.0` |
| `source.jitter_percent` | 0.0 … 8.0 | `0.85` |
| `source.open_quotient` | 0.2 … 0.95 | `0.62` |
| `source.pitch_drift_cents_per_sec` | -60.0 … 60.0 | `-3.0` |
| `source.shimmer_percent` | 0.0 … 30.0 | `3.5` |
| `source.source_tilt_db_per_oct` | -30.0 … 6.0 | `-9.0` |
| `source.speed_ratio` | 0.05 … 0.9 | `0.42` |
| `source.subharmonic_mix` | 0.0 … 1.0 | `0.0` |
| `source.vibrato_depth_cents` | 0.0 … 200.0 | `24.0` |
| `source.vibrato_onset_ms` | 0.0 … 2000.0 | `220.0` |
| `source.vibrato_rate_hz` | 0.0 … 12.0 | `5.1` |
| `source.voicing_hysteresis` | 0.0 … 1.0 | `0.5` |
| `source.model` | enum | `rosenberg`, `lf_style`, `pulse`, `buzz`, `whisper` |
| `tract.bandwidth_scale` | 0.4 … 3.0 | `1.0` |
| `tract.cascade_order` | 1 … 6 | `5` |
| `tract.extra_bandwidth_hz` | 0.0 … 400.0 | `25.0` |
| `tract.formant_scale.f1` | 0.5 … 2.0 | `1.0` |
| `tract.formant_scale.f2` | 0.5 … 2.0 | `1.0` |
| `tract.formant_scale.f3` | 0.5 … 2.0 | `1.0` |
| `tract.formant_scale.f4` | 0.5 … 2.0 | `1.0` |
| `tract.formant_scale.f5` | 0.5 … 2.0 | `1.0` |
| `tract.frication_gain_db` | -24.0 … 24.0 | `0.0` |
| `tract.hf_loss_hz` | 1000.0 … 20000.0 | `6800.0` |
| `tract.length_scale` | 0.6 … 1.8 | `1.0` |
| `tract.nasal_bandwidth_hz` | 30.0 … 400.0 | `95.0` |
| `tract.nasal_formant_hz` | 150.0 … 800.0 | `285.0` |
| `tract.nasal_zero_bandwidth_hz` | 30.0 … 600.0 | `130.0` |
| `tract.nasal_zero_hz` | 300.0 … 3000.0 | `950.0` |
| `tract.nasality` | 0.0 … 1.0 | `0.0` |
| `tract.radiation_db_per_oct` | 0.0 … 12.0 | `6.0` |
| `tract.sibilance_boost_db` | -12.0 … 18.0 | `2.5` |
| `tract.sibilance_center_hz` | 1500.0 … 11000.0 | `5400.0` |
| `tract.spectral_tilt_db_per_oct` | -24.0 … 12.0 | `-3.0` |

---

## 2. Score

The performance contract, and the voice changer's input.
`synth.render_score` consumes a hand-written score exactly as it consumes
one generated from text; the synthesiser cannot tell the difference. See
`examples/score_handwritten_hmmm.json`.

```json
{
  "format": "villager-voice-score",
  "version": 1,
  "description": "A timed performance score: what to say, when, and how.",
  "top_level": {
    "text": "the source line (informational; phones drive the audio)",
    "sample_rate": "int, output rate in Hz",
    "duration": "float, total seconds",
    "phones": "list of phone slots, in time order",
    "pauses": "list of {at, dur} silence insertions in seconds",
    "words": "list of {text, start, end} for alignment/subtitles",
    "phrases": "list of {index, end, punctuation, kind}",
    "contours": "time-varying control tracks (see below)",
    "events": "list of {at, kind, dur} non-speech overlays",
    "random_seed": "int, makes a score reproducible",
    "voiceprint": "optional embedded voiceprint overrides"
  },
  "phone_slot": {
    "sym": "ARPAbet-ASCII symbol, e.g. 'AX', 'SH', 'EY1'",
    "start": "float, onset in seconds from the start of the line",
    "dur": "float, duration in seconds",
    "stress": "int 0/1/2 (2 = primary)",
    "amp": "float, per-phone amplitude scale",
    "word": "int, index into 'words'",
    "syll": "int, syllable index within the word",
    "manner": "vowel|diphthong|fricative|stop|nasal|glide|approximant|affricate|silence|vocalisation",
    "voiced": "bool",
    "f": "[F1..F5] target formant frequencies in Hz",
    "bw": "[B1..B5] formant bandwidths in Hz",
    "phrase": "int, index into 'phrases'",
    "f0_semitones": "optional fixed pitch offset for this phone",
    "override": "optional dict of voiceprint overrides active here"
  },
  "contours": {
    "f0": "[[time_sec, semitones_relative_to_f0_mean], ...]",
    "amp": "[[time_sec, dB], ...]",
    "formant_scale": "[[time_sec, linear_factor], ...]",
    "breathiness": "[[time_sec, 0..1], ...]"
  },
  "notes": [
    "All times are seconds from the start of the line.",
    "Contour lists are linearly interpolated between breakpoints and must be sorted by time.",
    "A hand-written score needs at minimum: phones (with sym, start, dur, f, bw, voiced), contours.f0, contours.amp and duration."
  ]
}
```

---

## 3. Transform spec

Re-voicing an existing WAV. Pitch, duration and timbre are separate axes
by design so they can be moved independently: PSOLA re-spaces the
overlap-add marks to change pitch without touching the vocal-tract
envelope, and the formant shift uses resample-then-restore so timbre
moves without the pitch following. See `examples/transform_*.json`.

```json
{
  "format": "villager_voice.transform",
  "version": 1,
  "description": "Re-voicing spec applied to an existing WAV file.",
  "sections": {
    "pitch": {
      "semitones": "fixed pitch shift; +12 is one octave up",
      "contour": "list of [time_sec, semitones] breakpoints added to the fixed shift, so the pitch can be animated",
      "range_compress": "1.0 keeps the speaker's own F0 range, 0.0 flattens it to a monotone, 2.0 doubles it",
      "follow_input": "when false the input contour is discarded and only the spec's own pitch is used",
      "jitter_percent": "cycle-to-cycle F0 variation to add",
      "vibrato_hz/vibrato_cents": "sinusoidal pitch modulation"
    },
    "time": {
      "scale": "speed factor; 1.5 is 1.5x faster (shorter)",
      "keep_pitch": "when true PSOLA preserves pitch while stretching"
    },
    "timbre": {
      "formant_semitones": "shift the vocal-tract resonances without changing pitch (-12 = an octave darker)",
      "formant_scale": "linear alternative, e.g. 0.9",
      "vocal_tract_scale": "tract length factor; 1.1 = 10% longer, which sets formant_scale to 1/1.1",
      "bandwidth_scale": "resonance damping; >1 dulls the voice",
      "envelope_mix": "blend the input's spectral envelope toward the target voiceprint's envelope",
      "nasality": "extra nasal coupling"
    },
    "source": {
      "tilt_db_per_octave": "additional spectral tilt (negative = darker)",
      "aspiration_db_delta": "additional breath noise in dB",
      "shimmer_percent": "cycle-to-cycle amplitude variation to add",
      "creak": "fraction of the file rendered with halved F0",
      "gruff": "adds low-frequency roughness"
    },
    "output": {
      "sample_rate": "resample the result (None keeps the input rate)",
      "gain_db": "make-up gain",
      "peak_ceiling": "normalisation target",
      "highpass_hz": "rumble filter",
      "fade_ms": "edge fades"
    },
    "voiceprint": "path to a voiceprint JSON whose timbre and source character are adopted (envelope_mix is then forced to 1.0 unless set explicitly)"
  },
  "example": {
    "pitch": {
      "semitones": -4.0,
      "range_compress": 0.8
    },
    "time": {
      "scale": 0.94
    },
    "timbre": {
      "vocal_tract_scale": 1.12,
      "bandwidth_scale": 1.05
    },
    "source": {
      "tilt_db_per_octave": -2.0,
      "aspiration_db_delta": 3.0
    },
    "output": {
      "sample_rate": 24000,
      "gain_db": 1.0
    }
  }
}
```

