"""The voiceprint: the complete, JSON-serialisable control surface.

A voiceprint is everything that makes *this* villager's voice sound like this
villager, split into the five stages of a source-filter model plus a delivery
layer:

``source``       the glottal waveform (pitch, open quotient, breath, jitter)
``tract``        the vocal-tract filter (formants, bandwidths, tilt, nasality)
``articulation`` how phone targets blend into each other (coarticulation)
``prosody``      timing and pitch movement (measured from the clip index)
``character``    high-level personality knobs that move the layers above

Any parameter can be overridden at three granularities by a performance score:
per line, per word/phone, and per audio frame. ``provenance`` records which
fields were *measured* from recordings by :mod:`analyze` and which are still
seeded from articulatory defaults, so a voiceprint is honest about itself.

Pure standard library; no neural model, no network access.
"""

import copy
import json
import math

VOICEPRINT_FORMAT = "villager-voiceprint"
VOICEPRINT_VERSION = 1

# ---------------------------------------------------------------------------
# Defaults. Numeric values here are *seed* defaults for an adult British-English
# male voice (the Villager News register). Running ``analyze`` against real
# recordings replaces them with measurements and records that in "provenance".
# ---------------------------------------------------------------------------

DEFAULTS = {
    "format": VOICEPRINT_FORMAT,
    "version": VOICEPRINT_VERSION,
    "name": "Villager News anchor",
    "notes": "",
    "provenance": {"kind": "seed", "measured_fields": [], "audio_seconds": 0.0,
                   "clips": 0, "reference": []},

    "output": {
        "sample_rate": 24000,
        "gain_db": 0.0,
        "peak_ceiling": 0.95,
        # Two-sided peak normalisation: bring the render *up* to peak_ceiling as
        # well as limiting it down. Without this, a line with no stop bursts or
        # fricatives (a sustained vowel, "Hrmm") renders ~22 dB quieter than a
        # full sentence, because only the transients were ever hitting the
        # ceiling. Matches wavio.normalize_peak and the Java ClipVoice
        # convention of rendering to a fixed peak.
        "normalize_peak": True,
        "highpass_hz": 70.0,
        "lowpass_hz": 9500.0,
        "room_mix": 0.0,
        "room_decay_ms": 0.0,
        "fade_ms": 12.0,
    },

    "source": {
        "model": "rosenberg",
        "f0_mean_hz": 116.0,
        "f0_semitone_range": 7.0,
        "f0_floor_hz": 68.0,
        "f0_ceiling_hz": 330.0,
        "open_quotient": 0.62,
        "speed_ratio": 0.42,
        "source_tilt_db_per_oct": -9.0,
        # Calibrated so breathiness spans a usable range: -62 dB measures at the
        # ~20 dB HNR floor (a clean voice) and 1.0 breathiness reaches -32 dB
        # (HNR near 0 dB, a whisper). At the old -33 dB default every voice
        # measured as breathy regardless of the knob.
        "aspiration_db": -62.0,
        "aspiration_tilt_db_per_oct": -5.0,
        "breathiness": 0.22,
        "jitter_percent": 0.85,
        "shimmer_percent": 3.5,
        "subharmonic_mix": 0.0,
        "creak_percent": 0.0,
        "voicing_hysteresis": 0.5,
        "vibrato_rate_hz": 5.1,
        "vibrato_depth_cents": 24.0,
        "vibrato_onset_ms": 220.0,
        "pitch_drift_cents_per_sec": -3.0,
    },

    "tract": {
        "length_scale": 1.0,
        "formant_scale": {"f1": 1.0, "f2": 1.0, "f3": 1.0, "f4": 1.0, "f5": 1.0},
        "bandwidth_scale": 1.0,
        "extra_bandwidth_hz": 25.0,
        "cascade_order": 5,
        "spectral_tilt_db_per_oct": -3.0,
        "radiation_db_per_oct": 6.0,
        "hf_loss_hz": 6800.0,
        "nasality": 0.0,
        "nasal_formant_hz": 285.0,
        "nasal_bandwidth_hz": 95.0,
        "nasal_zero_hz": 950.0,
        "nasal_zero_bandwidth_hz": 130.0,
        "frication_gain_db": 0.0,
        "sibilance_center_hz": 5400.0,
        "sibilance_boost_db": 2.5,
    },

    "articulation": {
        "coarticulation": 0.55,
        "transition_ms": 40.0,
        "undershoot": 0.18,
        "vowel_reduction": 0.5,
        "duration_scale": 1.0,
        "vowel_duration_scale": 1.0,
        "consonant_duration_scale": 1.0,
        "stop_burst_db": -13.0,
        "stop_aspiration_ms": 42.0,
        "release_noise_db": -22.0,
        "lateral_release": True,
    },

    "prosody": {
        # Measured from clips_index.json by corpus.load_profile(); the analyzer
        # overwrites these when it runs against a real index.
        "rate_syllables_per_sec": 3.31,
        "rate_words_per_sec": 2.56,
        "rate_stdev": 0.9,
        "declination_semitones_per_sec": -0.85,
        "stress_rise_semitones": 2.1,
        "accent_rise_semitones": 3.0,
        "f0_reset_semitones": 1.6,
        "phrase_final_fall_semitones": -3.4,
        "question_rise_semitones": 4.6,
        "exclamation_rise_semitones": 1.8,
        "pause_short_ms": 130.0,
        "pause_comma_ms": 250.0,
        "pause_sentence_ms": 470.0,
        "pause_variability": 0.18,
        "pre_boundary_lengthening": 1.32,
        "rhythm": 0.38,
        "loudness_range_db": 9.0,
        "stress_loudness_db": 2.4,
        "final_softening_db": -2.0,
    },

    "character": {
        "gender": "male",
        "age_years": 42.0,
        "accent": "rp-british",
        "emotion": "neutral",
        "tone": 0.0,
        "sarcasm": 0.0,
        "tenseness": 0.45,
        "resonance": 0.5,
        "gruffness": 0.12,
        "smile": 0.0,
        "effort": 0.5,
        "size": 1.0,
        "villager_hmm_mix": 0.0,
    },

    # Per-phone trims, e.g. {"AA": {"f1_hz": 700, "dur_scale": 1.1}}
    "phone_overrides": {},

    "random_seed": 20261004,
}

# Inclusive ranges for validation. Anything outside raises a clear error rather
# than producing silence or NaNs -- the same contract SpeechOptions uses in Java.
RANGES = {
    "output.sample_rate": (4000, 96000),
    "output.gain_db": (-48.0, 24.0),
    "output.peak_ceiling": (0.01, 1.0),
    "output.highpass_hz": (0.0, 2000.0),
    "output.lowpass_hz": (500.0, 24000.0),
    "output.room_mix": (0.0, 1.0),
    "output.room_decay_ms": (0.0, 6000.0),
    "output.fade_ms": (0.0, 500.0),

    "source.f0_mean_hz": (40.0, 600.0),
    "source.f0_semitone_range": (0.0, 36.0),
    "source.f0_floor_hz": (30.0, 400.0),
    "source.f0_ceiling_hz": (60.0, 1200.0),
    "source.open_quotient": (0.2, 0.95),
    "source.speed_ratio": (0.05, 0.9),
    "source.source_tilt_db_per_oct": (-30.0, 6.0),
    "source.aspiration_db": (-80.0, -6.0),
    "source.aspiration_tilt_db_per_oct": (-24.0, 12.0),
    "source.breathiness": (0.0, 1.0),
    "source.jitter_percent": (0.0, 8.0),
    "source.shimmer_percent": (0.0, 30.0),
    "source.subharmonic_mix": (0.0, 1.0),
    "source.creak_percent": (0.0, 1.0),
    "source.voicing_hysteresis": (0.0, 1.0),
    "source.vibrato_rate_hz": (0.0, 12.0),
    "source.vibrato_depth_cents": (0.0, 200.0),
    "source.vibrato_onset_ms": (0.0, 2000.0),
    "source.pitch_drift_cents_per_sec": (-60.0, 60.0),

    "tract.length_scale": (0.6, 1.8),
    "tract.bandwidth_scale": (0.4, 3.0),
    "tract.extra_bandwidth_hz": (0.0, 400.0),
    "tract.cascade_order": (1, 6),
    "tract.spectral_tilt_db_per_oct": (-24.0, 12.0),
    "tract.radiation_db_per_oct": (0.0, 12.0),
    "tract.hf_loss_hz": (1000.0, 20000.0),
    # Per-formant vowel-space multipliers. 0.5..2.0 covers any plausible tract
    # reshape (a 2x F1 shift is already beyond a whisper-to-shout range).
    "tract.formant_scale.f1": (0.5, 2.0),
    "tract.formant_scale.f2": (0.5, 2.0),
    "tract.formant_scale.f3": (0.5, 2.0),
    "tract.formant_scale.f4": (0.5, 2.0),
    "tract.formant_scale.f5": (0.5, 2.0),
    "tract.nasality": (0.0, 1.0),
    "tract.nasal_formant_hz": (150.0, 800.0),
    "tract.nasal_bandwidth_hz": (30.0, 400.0),
    "tract.nasal_zero_hz": (300.0, 3000.0),
    "tract.nasal_zero_bandwidth_hz": (30.0, 600.0),
    "tract.frication_gain_db": (-24.0, 24.0),
    "tract.sibilance_center_hz": (1500.0, 11000.0),
    "tract.sibilance_boost_db": (-12.0, 18.0),

    "articulation.coarticulation": (0.0, 1.0),
    "articulation.transition_ms": (5.0, 200.0),
    "articulation.undershoot": (0.0, 0.8),
    "articulation.vowel_reduction": (0.0, 1.0),
    "articulation.duration_scale": (0.2, 4.0),
    "articulation.vowel_duration_scale": (0.2, 4.0),
    "articulation.consonant_duration_scale": (0.2, 4.0),
    "articulation.stop_burst_db": (-48.0, 6.0),
    "articulation.stop_aspiration_ms": (0.0, 200.0),
    "articulation.release_noise_db": (-60.0, 6.0),

    "prosody.rate_syllables_per_sec": (0.5, 12.0),
    "prosody.rate_words_per_sec": (0.3, 9.0),
    "prosody.rate_stdev": (0.0, 4.0),
    "prosody.declination_semitones_per_sec": (-8.0, 4.0),
    "prosody.stress_rise_semitones": (-6.0, 12.0),
    "prosody.accent_rise_semitones": (-6.0, 16.0),
    "prosody.f0_reset_semitones": (-8.0, 12.0),
    "prosody.phrase_final_fall_semitones": (-16.0, 8.0),
    "prosody.question_rise_semitones": (-8.0, 16.0),
    "prosody.exclamation_rise_semitones": (-8.0, 12.0),
    "prosody.pause_short_ms": (0.0, 1000.0),
    "prosody.pause_comma_ms": (0.0, 2000.0),
    "prosody.pause_sentence_ms": (0.0, 4000.0),
    "prosody.pause_variability": (0.0, 1.0),
    "prosody.pre_boundary_lengthening": (1.0, 2.5),
    "prosody.rhythm": (0.0, 1.0),
    "prosody.loudness_range_db": (0.0, 30.0),
    "prosody.stress_loudness_db": (0.0, 12.0),
    "prosody.final_softening_db": (-24.0, 6.0),

    "character.age_years": (2.0, 110.0),
    "character.tone": (-1.0, 1.0),
    "character.sarcasm": (0.0, 1.0),
    "character.tenseness": (0.0, 1.0),
    "character.resonance": (0.0, 1.0),
    "character.gruffness": (0.0, 1.0),
    "character.smile": (-1.0, 1.0),
    "character.effort": (0.0, 1.0),
    "character.size": (0.4, 2.5),
    "character.villager_hmm_mix": (0.0, 1.0),

    "random_seed": (0, 2 ** 40),
}

ENUMS = {
    "source.model": ("rosenberg", "lf_style", "pulse", "buzz", "whisper"),
    # Only rp-british has a measured phone table (see phones.py); the others are
    # accepted so a voiceprint can record its intended accent, and the engine
    # falls back to RP targets. Declaring them keeps typos out of saved files.
    "character.accent": ("rp-british", "general-american", "australian",
                         "irish", "neutral"),
    "character.gender": ("male", "female", "neutral", "child", "zombie"),
    "character.emotion": ("neutral", "happy", "sad", "angry", "scared",
                          "smug", "bored", "excited"),
}

EMOTIONS = ENUMS["character.emotion"]


def _flatten(data, prefix="", out=None):
    """Flatten nested dicts to dotted keys (skips phone_overrides subtrees)."""
    out = {} if out is None else out
    for key, value in data.items():
        path = "%s.%s" % (prefix, key) if prefix else key
        if isinstance(value, dict) and prefix != "phone_overrides" \
                and not path.startswith("phone_overrides."):
            _flatten(value, path, out)
        else:
            out[path] = value
    return out


def deep_merge(base, overrides):
    """Return ``base`` with ``overrides`` merged in, recursing into dicts."""
    result = copy.deepcopy(base)
    for key, value in (overrides or {}).items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


class VoicePrint(object):
    """A validated, layered, JSON-backed voice configuration."""

    def __init__(self, data=None):
        self.data = deep_merge(DEFAULTS, data or {})
        self.validate()

    # -- access -------------------------------------------------------------
    def get(self, path, default=None):
        node = self.data
        for part in path.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def set(self, path, value):
        parts = path.split(".")
        node = self.data
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
        return self

    def section(self, name):
        return self.data.get(name, {})

    def merged(self, overrides):
        """A new voiceprint with ``overrides`` applied (nothing mutated)."""
        return VoicePrint(deep_merge(self.data, overrides or {}))

    # -- validation ---------------------------------------------------------
    def validate(self):
        flat = _flatten(self.data)
        errors = []
        for path, value in flat.items():
            if path.startswith("phone_overrides."):
                continue
            bounds = RANGES.get(path)
            if bounds is not None:
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    errors.append("%s must be a number, got %r" % (path, value))
                elif math.isnan(value) or math.isinf(value):
                    errors.append("%s must be finite" % path)
                elif not (bounds[0] <= value <= bounds[1]):
                    errors.append("%s must be between %g and %g, got %g"
                                  % (path, bounds[0], bounds[1], value))
            allowed = ENUMS.get(path)
            if allowed is not None and value not in allowed:
                errors.append("%s must be one of %s, got %r"
                              % (path, ", ".join(allowed), value))
        for key, scale in (self.get("tract.formant_scale") or {}).items():
            if not isinstance(scale, (int, float)) or not (0.4 <= scale <= 2.5):
                errors.append("tract.formant_scale.%s must be 0.4..2.5, got %r"
                              % (key, scale))
        if self.get("source.f0_floor_hz", 0) >= self.get("source.f0_ceiling_hz", 1):
            errors.append("source.f0_floor_hz must be below source.f0_ceiling_hz")
        if errors:
            raise ValueError("invalid voiceprint:\n  " + "\n  ".join(sorted(errors)))
        return True

    # -- derivation ---------------------------------------------------------
    def explicit_paths(self):
        """Dotted paths whose value differs from DEFAULTS, i.e. set on purpose.

        The ``character`` section is a *macro* layer: it exists so a caller can
        say "female, happy, gruff" without knowing what open quotient that
        implies. But a caller who sets ``source.f0_mean_hz`` directly means it.
        Diffing against DEFAULTS is what distinguishes the two cases, so an
        explicit low-level number is never silently multiplied by a macro.

        Setting a field to exactly its default value is indistinguishable from
        not setting it; that one case resolves in favour of the macro.
        """
        defaults = _flatten(DEFAULTS)
        return {path for path, value in _flatten(self.data).items()
                if path in defaults and value != defaults[path]}

    def effective(self):
        """Resolve the ``character`` layer into source/tract/prosody deltas.

        Returns a *new* voiceprint whose low-level numbers already include the
        personality knobs, so the synthesiser only ever reads concrete values.
        This mirrors ``SpeechOptions.getEffective*`` in the Java engine.
        """
        data = copy.deepcopy(self.data)
        character = data["character"]
        source = data["source"]
        tract = data["tract"]
        prosody = data["prosody"]
        articulation = data["articulation"]

        # Low-level knobs the caller set explicitly; macros must not touch them.
        explicit = self.explicit_paths()

        def allows(path):
            return path not in explicit

        emotion = character.get("emotion", "neutral")
        tenseness = float(character.get("tenseness", 0.5))
        gruff = float(character.get("gruffness", 0.0))
        smile = float(character.get("smile", 0.0))
        effort = float(character.get("effort", 0.5))
        size = float(character.get("size", 1.0))
        age = float(character.get("age_years", 42.0))
        gender = character.get("gender", "male")
        sarcasm = float(character.get("sarcasm", 0.0))
        tone = float(character.get("tone", 0.0))

        # --- register: gender/age/size move the whole instrument -------------
        register = 1.0
        if gender == "female":
            register *= 1.32
        elif gender == "child":
            register *= 1.62
        elif gender == "zombie":
            register *= 0.72
        elif gender == "neutral":
            register *= 1.08
        register *= 1.0 + max(-0.4, min(0.4, (42.0 - age) / 130.0))
        register /= max(0.5, size)
        # Setting 196 Hz with gender=female used to come out at 274 Hz, because
        # the macro multiplied the explicit value. Now the explicit value wins.
        if allows("source.f0_mean_hz"):
            source["f0_mean_hz"] = _clamp(source["f0_mean_hz"] * register,
                                          source["f0_floor_hz"],
                                          source["f0_ceiling_hz"])

        # Tract length tracks size and gender (longer tract = lower formants).
        if allows("tract.length_scale"):
            length = tract["length_scale"]
            if gender == "female":
                length *= 0.93
            elif gender == "child":
                length *= 0.80
            elif gender == "zombie":
                length *= 1.06
            length *= max(0.7, min(1.5, size))
            tract["length_scale"] = _clamp(length, 0.6, 1.8)

        # --- emotion --------------------------------------------------------
        # Multipliers chosen to match the direction SpeechOptions uses in Java
        # (happy faster/brighter, sad slower/darker, angry faster/harsher).
        table = {
            "neutral":  dict(rate=1.00, f0=1.00, range=1.00, tilt=0.00,
                             breath=0.00, jitter=1.00, loud=1.00, fall=1.00),
            "happy":    dict(rate=1.08, f0=1.06, range=1.18, tilt=+0.10,
                             breath=+0.02, jitter=1.05, loud=1.05, fall=0.75),
            "sad":      dict(rate=0.86, f0=0.94, range=0.72, tilt=-0.14,
                             breath=+0.10, jitter=1.10, loud=0.84, fall=1.35),
            "angry":    dict(rate=1.12, f0=1.09, range=1.25, tilt=+0.18,
                             breath=-0.06, jitter=1.35, loud=1.14, fall=0.85),
            "scared":   dict(rate=1.16, f0=1.14, range=1.40, tilt=+0.06,
                             breath=+0.12, jitter=1.80, loud=1.02, fall=0.60),
            "smug":     dict(rate=0.96, f0=1.02, range=1.10, tilt=+0.04,
                             breath=-0.02, jitter=0.95, loud=1.02, fall=0.90),
            "bored":    dict(rate=0.90, f0=0.97, range=0.62, tilt=-0.08,
                             breath=+0.06, jitter=1.05, loud=0.88, fall=1.30),
            "excited":  dict(rate=1.20, f0=1.12, range=1.45, tilt=+0.14,
                             breath=+0.04, jitter=1.25, loud=1.16, fall=0.65),
        }
        row = table.get(emotion, table["neutral"])
        if allows("prosody.rate_syllables_per_sec"):
            prosody["rate_syllables_per_sec"] *= row["rate"]
        if allows("prosody.rate_words_per_sec"):
            prosody["rate_words_per_sec"] *= row["rate"]
        if allows("source.f0_mean_hz"):
            source["f0_mean_hz"] = _clamp(source["f0_mean_hz"] * row["f0"],
                                          source["f0_floor_hz"],
                                          source["f0_ceiling_hz"])
        if allows("source.f0_semitone_range"):
            source["f0_semitone_range"] *= row["range"]
        if allows("tract.spectral_tilt_db_per_oct"):
            tract["spectral_tilt_db_per_oct"] += row["tilt"] * 6.0
        if allows("source.breathiness"):
            source["breathiness"] = _clamp(source["breathiness"] + row["breath"],
                                           0.0, 1.0)
        if allows("source.jitter_percent"):
            source["jitter_percent"] *= row["jitter"]
        if allows("prosody.phrase_final_fall_semitones"):
            prosody["phrase_final_fall_semitones"] *= row["fall"]
        if allows("output.gain_db"):
            data["output"]["gain_db"] += 20.0 * math.log10(max(0.05, row["loud"]))

        # --- tenseness / gruffness / smile ---------------------------------
        # Tense voice: shorter open phase, brighter source, higher F1 floor.
        if allows("source.open_quotient"):
            source["open_quotient"] = _clamp(
                source["open_quotient"] * (1.0 - 0.22 * (tenseness - 0.45)),
                0.2, 0.95)
        if allows("source.source_tilt_db_per_oct"):
            source["source_tilt_db_per_oct"] += -2.5 * (tenseness - 0.45)
        if allows("source.jitter_percent"):
            source["jitter_percent"] *= (1.0 - 0.45 * tenseness) * (1.0 + 1.1 * gruff)
        # Gruff: more aspiration, darker tilt, slight creak.
        if allows("source.aspiration_db"):
            source["aspiration_db"] += 9.0 * gruff
        if allows("source.creak_percent"):
            source["creak_percent"] = _clamp(source["creak_percent"] + 0.35 * gruff,
                                             0.0, 1.0)
        if allows("tract.spectral_tilt_db_per_oct"):
            tract["spectral_tilt_db_per_oct"] -= 3.0 * gruff
        # Smile raises F2/F3 (the classic "smiling voice" brightening).
        spread = 1.0 + 0.16 * (character.get("resonance", 0.5) - 0.5)
        f2_gain = (1.0 + 0.045 * smile) * spread
        if allows("tract.formant_scale.f1"):
            tract["formant_scale"]["f1"] *= 1.0 + (spread - 1.0) * 0.9
        if allows("tract.formant_scale.f2"):
            tract["formant_scale"]["f2"] *= f2_gain
        if allows("tract.formant_scale.f3"):
            tract["formant_scale"]["f3"] *= 1.0 + 0.028 * smile
        # Effort: louder, faster, wider pitch range, less breath.
        if allows("prosody.loudness_range_db"):
            prosody["loudness_range_db"] *= 1.0 + 0.4 * (effort - 0.5)
        if allows("source.f0_semitone_range"):
            source["f0_semitone_range"] *= 1.0 + 0.25 * (effort - 0.5)
        if allows("source.breathiness"):
            source["breathiness"] = _clamp(
                source["breathiness"] - 0.10 * (effort - 0.5), 0.0, 1.0)
        # Breathiness trades source amplitude for aspiration noise. 30 dB of
        # travel across 0..1 was measured to span the whole useful HNR range
        # (see analyze.calibrate_breathiness); 16 dB left the top half of the
        # knob inaudible.
        source["aspiration_db"] += 30.0 * source["breathiness"]
        # Sarcasm/tone: drier, flatter, slightly darker (mirrors Java SpeechOptions).
        if allows("prosody.declination_semitones_per_sec"):
            prosody["declination_semitones_per_sec"] *= 1.0 - 0.35 * sarcasm
        if allows("source.f0_semitone_range"):
            source["f0_semitone_range"] *= 1.0 - 0.28 * sarcasm
        if allows("tract.spectral_tilt_db_per_oct"):
            tract["spectral_tilt_db_per_oct"] += 0.5 * tone - 0.12 * 6.0 * sarcasm
        if allows("articulation.duration_scale"):
            articulation["duration_scale"] *= 1.0 - 0.08 * sarcasm
        if allows("prosody.rate_syllables_per_sec"):
            prosody["rate_syllables_per_sec"] *= 1.0 + 0.08 * tone

        # Zombie villager: lowered register, added creak, narrowed range.
        if gender == "zombie":
            if allows("source.creak_percent"):
                source["creak_percent"] = _clamp(source["creak_percent"] + 0.25,
                                                 0.0, 1.0)
            if allows("source.subharmonic_mix"):
                source["subharmonic_mix"] = _clamp(source["subharmonic_mix"] + 0.30,
                                                   0.0, 1.0)
            if allows("tract.spectral_tilt_db_per_oct"):
                tract["spectral_tilt_db_per_oct"] -= 4.0
            if allows("source.breathiness"):
                source["breathiness"] = _clamp(source["breathiness"] + 0.18,
                                               0.0, 1.0)

        result = VoicePrint(data)
        result.clamp_derived()
        return result

    def clamp_derived(self):
        """Pull derived values back inside their validated ranges in place."""
        for path, bounds in RANGES.items():
            if path.startswith("phone_overrides"):
                continue
            value = self.get(path)
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                continue
            low, high = bounds
            if value < low:
                self.set(path, low)
            elif value > high:
                self.set(path, high)
        floor = self.get("source.f0_floor_hz")
        ceiling = self.get("source.f0_ceiling_hz")
        if floor >= ceiling:
            self.set("source.f0_floor_hz", min(floor, ceiling * 0.5))
        self.set("source.f0_mean_hz",
                 _clamp(self.get("source.f0_mean_hz"), floor, ceiling))
        return self

    # -- io -----------------------------------------------------------------
    def to_json(self, indent=2):
        return json.dumps(self.data, indent=indent, sort_keys=False)

    def save(self, path, indent=2):
        import os

        parent = os.path.dirname(os.path.abspath(str(path)))
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with open(str(path), "w", encoding="utf-8") as handle:
            handle.write(self.to_json(indent) + "\n")
        return path

    @classmethod
    def load(cls, path):
        with open(str(path), "r", encoding="utf-8") as handle:
            return cls(json.load(handle))

    @classmethod
    def default(cls):
        return cls({})

    # -- reporting ----------------------------------------------------------
    def summary(self):
        eff = self.effective()
        lines = [
            "voiceprint: %s (%s)" % (self.get("name"), self.get("provenance", {}).get("kind")),
            "source : %s  F0 %.1f Hz (range %.1f st)  Oq %.2f  breath %.2f  jitter %.2f%%"
            % (eff.get("source.model"), eff.get("source.f0_mean_hz"),
               eff.get("source.f0_semitone_range"), eff.get("source.open_quotient"),
               eff.get("source.breathiness"), eff.get("source.jitter_percent")),
            "tract  : length %.2f  tilt %.1f dB/oct  nasality %.2f  F-scale %s"
            % (eff.get("tract.length_scale"), eff.get("tract.spectral_tilt_db_per_oct"),
               eff.get("tract.nasality"),
               {k: round(v, 3) for k, v in sorted(eff.get("tract.formant_scale").items())}),
            "artic  : coart %.2f  transition %.0f ms  undershoot %.2f  dur x%.2f"
            % (eff.get("articulation.coarticulation"), eff.get("articulation.transition_ms"),
               eff.get("articulation.undershoot"), eff.get("articulation.duration_scale")),
            "prosody: %.2f syl/s  decl %.2f st/s  stress +%.1f st  final %.1f st  Q +%.1f st"
            % (eff.get("prosody.rate_syllables_per_sec"),
               eff.get("prosody.declination_semitones_per_sec"),
               eff.get("prosody.stress_rise_semitones"),
               eff.get("prosody.phrase_final_fall_semitones"),
               eff.get("prosody.question_rise_semitones")),
            "character: %s %s  age %.0f  emotion %s  tone %+.2f  sarcasm %.2f"
            % (eff.get("character.gender"), eff.get("character.accent"),
               eff.get("character.age_years"), eff.get("character.emotion"),
               eff.get("character.tone"), eff.get("character.sarcasm")),
            "output : %d Hz  gain %+.1f dB  ceiling %.2f  hp %.0f Hz  lp %.0f Hz"
            % (eff.get("output.sample_rate"), eff.get("output.gain_db"),
               eff.get("output.peak_ceiling"), eff.get("output.highpass_hz"),
               eff.get("output.lowpass_hz")),
        ]
        measured = self.get("provenance.measured_fields") or []
        lines.append("measured fields: %d%s"
                     % (len(measured), "" if measured else " (seed defaults only)"))
        return "\n".join(lines)


def _clamp(value, low, high):
    return max(low, min(high, value))


def semitones_to_ratio(semitones):
    return 2.0 ** (semitones / 12.0)


def ratio_to_semitones(ratio):
    if ratio <= 0:
        return -96.0
    return 12.0 * math.log2(ratio)


def db_to_linear(db):
    return 10.0 ** (db / 20.0)


def linear_to_db(value):
    if value <= 1e-9:
        return -180.0
    return 20.0 * math.log10(value)


def schema_document():
    """Human-readable schema: every knob, its range, default and meaning."""
    notes = {
        "source.model": "glottal waveform: rosenberg (smooth), lf_style (sharp "
                        "return phase), pulse (bright impulse), buzz (sawtooth), "
                        "whisper (unvoiced source only)",
        "source.open_quotient": "fraction of each pitch period the glottis stays open",
        "source.speed_ratio": "rise time as a fraction of the open phase (skew)",
        "source.aspiration_db": "breath noise level relative to the glottal source",
        "source.jitter_percent": "cycle-to-cycle pitch perturbation",
        "source.shimmer_percent": "cycle-to-cycle amplitude perturbation",
        "source.subharmonic_mix": "period-doubling blend (creaky/rough voice)",
        "tract.length_scale": "vocal-tract length multiplier; >1 lowers all formants",
        "tract.formant_scale": "per-formant multipliers f1..f5 (vowel-space shaping)",
        "tract.spectral_tilt_db_per_oct": "extra shelf on the tract output",
        "tract.radiation_db_per_oct": "lip radiation differentiator (6 dB/oct = ideal)",
        "tract.nasality": "0 = oral, 1 = fully nasal (adds nasal pole and zero)",
        "articulation.coarticulation": "0 = crisp phone targets, 1 = heavy smoothing",
        "articulation.undershoot": "how far targets fall short at fast rates",
        "prosody.rate_syllables_per_sec": "measured from clips_index.json",
        "prosody.declination_semitones_per_sec": "downward pitch drift across a phrase",
        "prosody.pre_boundary_lengthening": "final-syllable stretch before a boundary",
        "character.villager_hmm_mix": "blend of the classic villager 'hmm' vocalisation",
        "output.room_mix": "wet/dry mix of the simple feedback-delay room",
    }
    rows = []
    flat = _flatten(DEFAULTS)
    for path in sorted(flat):
        if path.startswith("phone_overrides"):
            continue
        default = flat[path]
        bounds = RANGES.get(path)
        allowed = ENUMS.get(path)
        if allowed:
            constraint = "one of: " + ", ".join(str(a) for a in allowed)
        elif bounds:
            constraint = "%g .. %g" % bounds
        else:
            constraint = "-"
        rows.append({
            "path": path,
            "default": default,
            "range": constraint,
            "meaning": notes.get(path, ""),
        })
    return rows


if __name__ == "__main__":
    print(VoicePrint.default().summary())
    print("\nknobs: %d" % len(schema_document()))
