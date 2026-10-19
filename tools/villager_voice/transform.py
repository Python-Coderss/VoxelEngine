#!/usr/bin/env python3
"""Re-voicing: apply a JSON control spec to an existing recording.

This is the voice-changer half of the engine. ``synth.py`` builds a voice from
nothing; this module takes audio that already exists (a villager clip, a
recording of you, anything) and re-voices it from a JSON spec. No neural
network is involved and nothing is downloaded: every stage is a classical
speech-processing operation whose effect can be measured.

Architecture
------------
The three things that make a voice sound like a particular person are kept
separate so they can be moved independently:

  * **pitch** -- the F0 contour. Shifted by re-spacing the overlap-add marks
    (PSOLA), which leaves the vocal-tract envelope untouched.
  * **duration** -- how fast the timeline is walked. Independent of pitch.
  * **timbre** -- the vocal-tract envelope (formants). Shifted by the
    resample-then-restore trick: resampling moves pitch and formants together,
    then PSOLA puts the pitch back, leaving only the formant shift behind.

On top of those, the *source* character can be remoulded -- spectral tilt,
aspiration noise, jitter and shimmer -- which is what turns a clean recording
into a breathy or gruff one without touching what is being said.

Spec JSON
---------
``python3 -m villager_voice.transform --schema`` prints the full surface. Every
field is optional; an empty spec returns the input unchanged.
"""

import json
import math
import os
import random

from . import analyze, wavio
from .voiceprint import DEFAULTS, RANGES, VoicePrint, db_to_linear, linear_to_db
from .wavio import (apply_gain, clamp, fade_edges, mix_into, normalize_peak,
                    peak, resample_linear, rms)

SPEC_FORMAT = "villager_voice.transform"
SPEC_VERSION = 1

# Frame grid used for pitch analysis and pitch marking.
HOP = 160           # ~6.7 ms at 24 kHz
FRAME = 480         # 20 ms

# Unvoiced regions have no pitch period to lock onto; PSOLA uses a short fixed
# grain there, which reproduces fricatives without imposing a pitch.
UNVOICED_PERIOD_SECONDS = 0.008

DEFAULT_SPEC = {
    "format": SPEC_FORMAT,
    "version": SPEC_VERSION,
    "pitch": {
        "semitones": 0.0,       # fixed shift applied to the whole file
        "contour": [],          # optional [[time_sec, semitones], ...]
        "range_compress": 1.0,  # 1 = keep the speaker's range, 0 = monotone
        "follow_input": True,   # keep the input's F0 contour shape
        "jitter_percent": None, # None = leave the input's jitter alone
        "vibrato_hz": 0.0,
        "vibrato_cents": 0.0,
    },
    "time": {
        "scale": 1.0,           # 2.0 = twice as fast = half the duration
        "keep_pitch": True,
    },
    "timbre": {
        "formant_semitones": 0.0,   # independent of pitch
        "formant_scale": None,      # alternative to semitones (linear factor)
        "vocal_tract_scale": None,  # >1 = longer tract = darker (sets formant_scale = 1/x)
        "bandwidth_scale": 1.0,     # >1 = broader resonances = duller
        "envelope_mix": 0.0,        # 0 = keep the input's envelope, 1 = full target voice
        "nasality": None,
    },
    "source": {
        "tilt_db_per_octave": 0.0,  # extra tilt on top of the input's own
        "aspiration_db_delta": 0.0, # extra breath noise
        "shimmer_percent": None,
        "creak": 0.0,
        "gruff": 0.0,
    },
    "output": {
        "sample_rate": None,        # None = keep the input rate
        "gain_db": 0.0,
        "peak_ceiling": 0.95,
        "highpass_hz": None,
        "fade_ms": 12.0,
    },
    "voiceprint": None,             # optional path: adopt a whole target voice
}


def spec_schema():
    """Describe the transform spec surface (used by --schema)."""
    return {
        "format": SPEC_FORMAT,
        "version": SPEC_VERSION,
        "description": "Re-voicing spec applied to an existing WAV file.",
        "sections": {
            "pitch": {
                "semitones": "fixed pitch shift; +12 is one octave up",
                "contour": "list of [time_sec, semitones] breakpoints added to "
                           "the fixed shift, so the pitch can be animated",
                "range_compress": "1.0 keeps the speaker's own F0 range, 0.0 "
                                  "flattens it to a monotone, 2.0 doubles it",
                "follow_input": "when false the input contour is discarded and "
                                "only the spec's own pitch is used",
                "jitter_percent": "cycle-to-cycle F0 variation to add",
                "vibrato_hz/vibrato_cents": "sinusoidal pitch modulation",
            },
            "time": {
                "scale": "speed factor; 1.5 is 1.5x faster (shorter)",
                "keep_pitch": "when true PSOLA preserves pitch while stretching",
            },
            "timbre": {
                "formant_semitones": "shift the vocal-tract resonances without "
                                     "changing pitch (-12 = an octave darker)",
                "formant_scale": "linear alternative, e.g. 0.9",
                "vocal_tract_scale": "tract length factor; 1.1 = 10% longer, "
                                     "which sets formant_scale to 1/1.1",
                "bandwidth_scale": "resonance damping; >1 dulls the voice",
                "envelope_mix": "blend the input's spectral envelope toward the "
                                "target voiceprint's envelope",
                "nasality": "extra nasal coupling",
            },
            "source": {
                "tilt_db_per_octave": "additional spectral tilt (negative = darker)",
                "aspiration_db_delta": "additional breath noise in dB",
                "shimmer_percent": "cycle-to-cycle amplitude variation to add",
                "creak": "fraction of the file rendered with halved F0",
                "gruff": "adds low-frequency roughness",
            },
            "output": {
                "sample_rate": "resample the result (None keeps the input rate)",
                "gain_db": "make-up gain",
                "peak_ceiling": "normalisation target",
                "highpass_hz": "rumble filter",
                "fade_ms": "edge fades",
            },
            "voiceprint": "path to a voiceprint JSON whose timbre and source "
                          "character are adopted (envelope_mix is then forced "
                          "to 1.0 unless set explicitly)",
        },
        "example": {
            "pitch": {"semitones": -4.0, "range_compress": 0.8},
            "time": {"scale": 0.94},
            "timbre": {"vocal_tract_scale": 1.12, "bandwidth_scale": 1.05},
            "source": {"tilt_db_per_octave": -2.0, "aspiration_db_delta": 3.0},
            "output": {"sample_rate": 24000, "gain_db": 1.0},
        },
    }


def merge_spec(base, overrides):
    """Recursively merge an overrides dict onto a base spec."""
    out = dict(base)
    for key, value in (overrides or {}).items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = merge_spec(out[key], value)
        else:
            out[key] = value
    return out


def load_spec(path_or_dict):
    """Load a transform spec from a path, a JSON string, or a dict."""
    if isinstance(path_or_dict, dict):
        return merge_spec(DEFAULT_SPEC, path_or_dict)
    text = path_or_dict
    if os.path.exists(text):
        with open(text, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    else:
        data = json.loads(text)
    return merge_spec(DEFAULT_SPEC, data)


# --------------------------------------------------------------------------- #
# pitch analysis
# --------------------------------------------------------------------------- #

def f0_track(samples, sample_rate, hop=HOP, frame=FRAME, f_min=60.0, f_max=400.0):
    """F0 and voicing decision on a fixed hop grid.

    Returns ``(times, f0, voiced)``. Unvoiced frames get ``f0 = 0``.
    """
    times = []
    values = []
    voiced = []
    for start in range(0, max(1, len(samples) - frame), hop):
        block = samples[start:start + frame]
        f0, confidence = analyze.estimate_f0(block, sample_rate, f_min, f_max)
        times.append((start + frame * 0.5) / sample_rate)
        values.append(f0)
        voiced.append(f0 > 0.0 and confidence >= 0.40)
    return times, values, voiced


def _smooth_track(values, voiced, window=5):
    """Median-smooth the F0 track inside voiced runs only.

    Averaging across an octave error would put the pitch somewhere between the
    two candidates, which is worse than either.
    """
    out = list(values)
    half = window // 2
    for i in range(len(values)):
        if not voiced[i]:
            continue
        lo = max(0, i - half)
        hi = min(len(values), i + half + 1)
        neighbours = sorted(values[j] for j in range(lo, hi)
                            if voiced[j] and values[j] > 0)
        if neighbours:
            out[i] = neighbors_median(neighbours)
    return out


def neighbors_median(sorted_values):
    n = len(sorted_values)
    return sorted_values[n // 2] if n % 2 else 0.5 * (
        sorted_values[n // 2 - 1] + sorted_values[n // 2])


def pitch_marks(samples, sample_rate, f0, voiced, unvoiced_period=None):
    """Pitch-synchronous analysis marks.

    Inside a voiced run the marks step by the local period and snap to the
    nearest energy maximum, which keeps the overlap-add grains phase-aligned --
    without the snapping, PSOLA produces audible roughness. Unvoiced regions
    get evenly spaced marks.
    """
    unvoiced_period = unvoiced_period or int(
        UNVOICED_PERIOD_SECONDS * sample_rate)
    marks = []
    periods = []
    position = 0
    total = len(samples)
    while position < total:
        frame_index = int(position / HOP)
        frame_index = max(0, min(len(f0) - 1, frame_index))
        is_voiced = voiced[frame_index] if frame_index < len(voiced) else False
        period = (sample_rate / f0[frame_index]
                  if is_voiced and f0[frame_index] > 0 else unvoiced_period)
        period = max(20, min(int(sample_rate / 50.0), int(round(period))))
        # Snap to the local energy maximum within a third of a period.
        search = max(2, period // 3)
        best = position
        best_energy = -1.0
        for offset in range(-search, search + 1):
            candidate = position + offset
            if 0 <= candidate < total:
                energy = abs(samples[candidate])
                if energy > best_energy:
                    best_energy = energy
                    best = candidate
        marks.append(best)
        periods.append(period)
        position = best + period
    return marks, periods


def _hann(length):
    if length <= 1:
        return [1.0]
    return [0.5 - 0.5 * math.cos(2.0 * math.pi * i / (length - 1))
            for i in range(length)]


# --------------------------------------------------------------------------- #
# PSOLA
# --------------------------------------------------------------------------- #

def psola(samples, sample_rate, marks, periods, pitch_factor=1.0,
          time_factor=1.0, pitch_contour=None, jitter_percent=0.0,
          shimmer_percent=0.0, rng=None):
    """Overlap-add resynthesis with independent pitch and time control.

    ``pitch_factor`` re-spaces the output marks; the grains themselves are not
    resampled, so the vocal-tract envelope (and therefore the timbre) survives
    a pitch shift unchanged. ``time_factor`` is a speed factor: 2.0 walks the
    input timeline twice as fast and halves the duration.

    ``pitch_contour`` is a list of ``(time_sec, factor)`` breakpoints applied on
    top of ``pitch_factor``, which is how the spec's animated pitch and the
    input's own contour both get expressed.
    """
    rng = rng or random.Random(12345)
    if not marks:
        return list(samples), sample_rate
    if time_factor <= 0:
        time_factor = 1.0

    duration_in = len(samples) / sample_rate
    duration_out = duration_in / time_factor
    out_length = int(math.ceil(duration_out * sample_rate)) + 4096
    out = [0.0] * out_length

    window_cache = {}
    position = 0.0
    guard = 0
    max_iterations = int(out_length * 4) + 100000
    while position < duration_out * sample_rate and guard < max_iterations:
        guard += 1
        time_out = position / sample_rate
        time_in = time_out * time_factor

        # Nearest analysis mark to time_in.
        target = int(round(time_in * sample_rate))
        index = _nearest_index(marks, target)
        mark = marks[index]
        period_in = periods[index]

        factor = pitch_factor
        if pitch_contour:
            factor *= _interp_breakpoints(pitch_contour, time_out)
        factor = max(0.25, min(4.0, factor))
        period_out = max(8, int(round(period_in / factor)))

        # Grain: one period either side of the mark, Hann-windowed.
        half = period_in
        first = mark - half
        last = mark + half + 1
        length = last - first
        if length < 4:
            position += period_out
            continue
        window = window_cache.get(length)
        if window is None:
            window = _hann(length)
            window_cache[length] = window

        gain = 1.0
        if shimmer_percent > 0.0:
            gain = 1.0 + rng.uniform(-1.0, 1.0) * (
                shimmer_percent * 0.01 / 0.7979)
            gain = max(0.2, min(2.5, gain))
        extra = 0.0
        if jitter_percent > 0.0:
            extra = rng.uniform(-1.0, 1.0) * (
                period_out * jitter_percent * 0.01 / 1.7725)

        centre = int(round(position))
        for offset in range(length):
            source_index = first + offset
            if source_index < 0 or source_index >= len(samples):
                continue
            value = samples[source_index] * window[offset] * gain
            target_index = centre - half + offset
            if 0 <= target_index < out_length:
                out[target_index] += value

        step = max(4, int(round(period_out + extra)))
        position += step

    return out[:int(duration_out * sample_rate) + 1], sample_rate


def _nearest_index(sorted_values, target):
    lo = 0
    hi = len(sorted_values) - 1
    if hi < 0:
        return 0
    if target <= sorted_values[0]:
        return 0
    if target >= sorted_values[hi]:
        return hi
    while lo < hi - 1:
        middle = (lo + hi) // 2
        if sorted_values[middle] <= target:
            lo = middle
        else:
            hi = middle
    return lo if abs(sorted_values[lo] - target) <= abs(sorted_values[hi] - target) else hi


def _interp_breakpoints(points, time):
    if not points:
        return 1.0
    if time <= points[0][0]:
        return points[0][1]
    if time >= points[-1][0]:
        return points[-1][1]
    for i in range(len(points) - 1):
        left, right = points[i], points[i + 1]
        if left[0] <= time <= right[0]:
            span = right[0] - left[0]
            if span <= 1e-9:
                return right[1]
            fraction = (time - left[0]) / span
            return left[1] + (right[1] - left[1]) * fraction
    return points[-1][1]


# --------------------------------------------------------------------------- #
# timbre
# --------------------------------------------------------------------------- #

def formant_shift(samples, sample_rate, scale, f0_hint=None):
    """Shift the vocal-tract resonances without changing pitch or duration.

    Resampling moves pitch and formants together by ``scale``; PSOLA then puts
    the pitch and the duration back, leaving only the formant shift behind. This
    is the classical formant shifter, and it reuses the same overlap-add code
    that the pitch shift uses, so the two operations compose exactly.
    """
    if abs(scale - 1.0) < 1e-3 or not samples:
        return list(samples), sample_rate

    # out[i] = in[i * scale]: the array shrinks by ``scale``, so played at the
    # original rate it is scale-times faster and scale-times higher in both
    # pitch and formants.
    resampled = resample_linear(samples, sample_rate / scale, sample_rate)
    if len(resampled) < 64:
        return list(samples), sample_rate

    hint_min, hint_max = 60.0, 400.0
    if f0_hint and f0_hint > 0:
        shifted = f0_hint * scale
        hint_min = max(45.0, shifted * 0.55)
        hint_max = min(sample_rate / 6.0, shifted * 1.8)
    times, f0, voiced = f0_track(resampled, sample_rate, f_min=hint_min,
                                 f_max=hint_max)
    f0 = _smooth_track(f0, voiced)
    marks, periods = pitch_marks(resampled, sample_rate, f0, voiced)
    # Undo the pitch change (1/scale) and the duration change (1/scale).
    out, _ = psola(resampled, sample_rate, marks, periods,
                   pitch_factor=1.0 / scale, time_factor=1.0 / scale)
    return out, sample_rate


def envelope_shape(samples, sample_rate, tilt_db=0.0, bandwidth_scale=1.0,
                   highpass_hz=None, nasality=0.0):
    """Remould the source spectrum with cheap one-pole stages.

    Tilt is a first-order lowpass (positive ``tilt_db`` brightens); bandwidth
    scaling is approximated by an extra lowpass whose corner tracks the
    requested damping; nasality adds a broad low-mid boost. These are applied
    sample-by-sample so they stay exact at any sample rate.
    """
    out = list(samples)
    if not out:
        return out

    if highpass_hz and highpass_hz > 0:
        coefficient = math.exp(-2.0 * math.pi * highpass_hz / sample_rate)
        previous_in = 0.0
        previous_out = 0.0
        for i, value in enumerate(out):
            out[i] = coefficient * (previous_out + value - previous_in)
            previous_in = value
            previous_out = out[i]

    if abs(tilt_db) > 1e-3:
        # A one-pole lowpass at a corner that produces roughly ``tilt_db`` of
        # change across the speech band; negative tilt darkens.
        corner = 4200.0 * (2.0 ** (tilt_db / 12.0))
        corner = max(300.0, min(sample_rate * 0.45, corner))
        coefficient = math.exp(-2.0 * math.pi * corner / sample_rate)
        if tilt_db < 0:
            state = 0.0
            mix = min(1.0, abs(tilt_db) / 12.0)
            for i, value in enumerate(out):
                state = coefficient * state + (1.0 - coefficient) * value
                out[i] = value * (1.0 - mix) + state * mix
        else:
            state = 0.0
            previous = 0.0
            mix = min(1.0, tilt_db / 12.0)
            for i, value in enumerate(out):
                state = coefficient * state + (1.0 - coefficient) * value
                out[i] = value * (1.0 + mix) - state * mix
                previous = value

    if bandwidth_scale > 1.001:
        corner = max(600.0, sample_rate * 0.5 / bandwidth_scale)
        coefficient = math.exp(-2.0 * math.pi * corner / sample_rate)
        state = 0.0
        for i, value in enumerate(out):
            state = coefficient * state + (1.0 - coefficient) * value
            out[i] = state

    if nasality > 0.0:
        # A broad 500-1200 Hz boost approximates the extra nasal coupling.
        low = _resonator_coefficients(900.0, 260.0, sample_rate)
        state = [0.0, 0.0]
        nasal = [0.0] * len(out)
        for i, value in enumerate(out):
            sample = (value + low[1] * state[0] + low[2] * state[1])
            state[1] = state[0]
            state[0] = sample
            nasal[i] = sample
        blend = min(0.6, nasality * 0.5)
        nasal_peak = peak(nasal) or 1.0
        scale = (peak(out) or 1.0) / nasal_peak
        for i in range(len(out)):
            out[i] = out[i] * (1.0 - blend) + nasal[i] * scale * blend * 0.5
    return out


def _resonator_coefficients(frequency, bandwidth, sample_rate):
    r = math.exp(-math.pi * bandwidth / sample_rate)
    theta = 2.0 * math.pi * frequency / sample_rate
    a1 = 2.0 * r * math.cos(theta)
    a2 = -(r * r)
    return (1.0 - a1 - a2, a1, a2)


def add_aspiration(samples, sample_rate, delta_db, rng=None):
    """Add breath noise shaped by the signal's own envelope.

    Aspiration is loud where the voice is loud, so the noise is amplitude
    modulated by a smoothed copy of the input; that keeps consonants from
    turning into a constant hiss bed.
    """
    if delta_db <= 0.01 or not samples:
        return list(samples)
    rng = rng or random.Random(97531)
    reference = peak(samples) or 1.0
    # ``delta_db`` reads as "dB below the signal peak": 0 is as loud as the
    # voice, -20 is a soft breath bed, -40 is inaudible.
    level = reference * db_to_linear(delta_db - 40.0)
    envelope_state = 0.0
    coefficient = math.exp(-2.0 * math.pi * 60.0 / sample_rate)
    out = []
    for value in samples:
        envelope_state = (coefficient * envelope_state
                          + (1.0 - coefficient) * abs(value))
        # x3 because a smoothed |x| runs about a third of the peak value.
        out.append(value + rng.uniform(-1.0, 1.0) * level * 3.0
                   * (envelope_state / reference))
    return out


# --------------------------------------------------------------------------- #
# orchestration
# --------------------------------------------------------------------------- #

def apply_spec(samples, sample_rate, spec, voiceprint=None, rng_seed=None):
    """Apply a transform spec to a sample buffer.

    Returns ``(sample_rate, samples, report)`` where the report records what
    each stage actually did, so a spec can be checked against its result.
    """
    spec = load_spec(spec) if not isinstance(spec, dict) else merge_spec(DEFAULT_SPEC, spec)
    report = {}
    work = list(samples)
    rate = sample_rate
    rng = random.Random(rng_seed if rng_seed is not None else 4242)

    pitch = spec.get("pitch") or {}
    time_spec = spec.get("time") or {}
    timbre = spec.get("timbre") or {}
    source = spec.get("source") or {}
    output = spec.get("output") or {}

    # ---- optional target voice -------------------------------------------
    target = voiceprint
    if spec.get("voiceprint"):
        path = spec["voiceprint"]
        if os.path.exists(path):
            target = VoicePrint.load(path)
            report["voiceprint"] = path
    if target is not None and timbre.get("envelope_mix", 0.0) <= 0.0:
        timbre = dict(timbre)
        timbre["envelope_mix"] = 1.0

    # ---- pitch analysis of the input -------------------------------------
    times, f0, voiced = f0_track(work, rate)
    f0 = _smooth_track(f0, voiced)
    voiced_values = [value for value, flag in zip(f0, voiced) if flag and value > 0]
    input_f0 = analyze.median(voiced_values) if voiced_values else 0.0
    report["input_f0_mean_hz"] = input_f0
    report["voiced_fraction"] = (sum(1 for flag in voiced if flag) / len(voiced)
                                 if voiced else 0.0)

    # ---- pitch factor ----------------------------------------------------
    semitones = float(pitch.get("semitones") or 0.0)
    if target is not None:
        tract = target.effective()["tract"]
        source_section = target.effective()["source"]
        # Adopting a target voice means adopting its tract length (timbre) and
        # its pitch centre, unless the spec overrides them explicitly.
        if timbre.get("vocal_tract_scale") is None and timbre.get("formant_scale") is None \
                and timbre.get("formant_semitones", 0.0) == 0.0:
            timbre["vocal_tract_scale"] = tract.get("length_scale", 1.0)
        if pitch.get("semitones") == 0.0 and input_f0 > 0:
            target_f0 = source_section.get("f0_mean_hz", input_f0)
            semitones = 12.0 * math.log2(target_f0 / input_f0)
            report["pitch_from_voiceprint_semitones"] = semitones
    pitch_factor = 2.0 ** (semitones / 12.0) if semitones else 1.0

    # Range compression about the input's own mean pitch.
    range_compress = float(pitch.get("range_compress", 1.0) or 1.0)
    contour = []
    if input_f0 > 0 and abs(range_compress - 1.0) > 1e-3 and pitch.get("follow_input", True):
        for time, value, flag in zip(times, f0, voiced):
            if flag and value > 0:
                semitone_offset = 12.0 * math.log2(value / input_f0)
                contour.append([time / (float(time_spec.get("scale") or 1.0)),
                                2.0 ** ((semitone_offset * (range_compress - 1.0)) / 12.0)])
    if not pitch.get("follow_input", True) and input_f0 > 0:
        # Discard the input's contour by cancelling it.
        contour = []
        for time, value, flag in zip(times, f0, voiced):
            if flag and value > 0:
                semitone_offset = 12.0 * math.log2(value / input_f0)
                contour.append([time / (float(time_spec.get("scale") or 1.0)),
                                2.0 ** (-semitone_offset / 12.0)])

    for point in (pitch.get("contour") or []):
        try:
            contour.append([float(point[0]), 2.0 ** (float(point[1]) / 12.0)])
        except (TypeError, ValueError, IndexError):
            continue
    contour.sort(key=lambda point: point[0])

    vibrato_hz = float(pitch.get("vibrato_hz") or 0.0)
    vibrato_cents = float(pitch.get("vibrato_cents") or 0.0)
    if vibrato_hz > 0 and vibrato_cents:
        duration = len(work) / rate
        steps = max(2, int(duration * 40))
        for i in range(steps + 1):
            time = duration * i / steps
            cents = vibrato_cents * math.sin(2.0 * math.pi * vibrato_hz * time)
            contour.append([time, 2.0 ** (cents / 1200.0)])
        contour.sort(key=lambda point: point[0])

    # ---- timbre first, so the pitch marks are taken from the final signal --
    scale = timbre.get("formant_scale")
    if scale is None:
        tract_scale = timbre.get("vocal_tract_scale")
        if tract_scale:
            scale = 1.0 / float(tract_scale)
        else:
            semitone_shift = float(timbre.get("formant_semitones") or 0.0)
            scale = 2.0 ** (semitone_shift / 12.0) if semitone_shift else 1.0
    scale = float(scale)
    if abs(scale - 1.0) > 1e-3:
        work, rate = formant_shift(work, rate, scale, f0_hint=input_f0)
        report["formant_scale"] = scale
        # The formant shift re-ran PSOLA, so re-derive the pitch track.
        times, f0, voiced = f0_track(work, rate)
        f0 = _smooth_track(f0, voiced)
        report["post_formant_f0_mean_hz"] = analyze.median(
            [value for value, flag in zip(f0, voiced) if flag and value > 0]) or 0.0

    # ---- pitch + time via PSOLA ------------------------------------------
    time_factor = float(time_spec.get("scale") or 1.0)
    if time_factor <= 0:
        time_factor = 1.0
    marks, periods = pitch_marks(work, rate, f0, voiced)
    report["pitch_marks"] = len(marks)
    work, rate = psola(
        work, rate, marks, periods,
        pitch_factor=pitch_factor if time_spec.get("keep_pitch", True) else pitch_factor,
        time_factor=time_factor,
        pitch_contour=contour or None,
        jitter_percent=float(pitch.get("jitter_percent") or 0.0),
        shimmer_percent=float(source.get("shimmer_percent") or 0.0),
        rng=rng,
    )
    report["pitch_factor"] = pitch_factor
    report["time_factor"] = time_factor

    if not time_spec.get("keep_pitch", True):
        # Stretching without pitch preservation is just resampling.
        pass

    # ---- creak -----------------------------------------------------------
    creak = float(source.get("creak") or 0.0)
    if creak > 0.0 and work:
        region = int(len(work) * min(1.0, creak))
        times, f0, voiced = f0_track(work[:region], rate)
        marks, periods = pitch_marks(work[:region], rate, f0, voiced)
        head, _ = psola(work[:region], rate, marks, periods, pitch_factor=0.5)
        work = head + work[region:]

    # ---- source character ------------------------------------------------
    work = envelope_shape(
        work, rate,
        tilt_db=float(source.get("tilt_db_per_octave") or 0.0),
        bandwidth_scale=float(timbre.get("bandwidth_scale") or 1.0),
        highpass_hz=output.get("highpass_hz"),
        nasality=float(timbre.get("nasality") or 0.0),
    )
    aspiration = float(source.get("aspiration_db_delta") or 0.0)
    if target is not None:
        effective = target.effective()
        aspiration += max(0.0, effective["source"]["aspiration_db"] + 62.0)
        if source.get("tilt_db_per_octave", 0.0) == 0.0:
            work = envelope_shape(work, rate,
                                  tilt_db=effective["source"]["source_tilt_db_per_octave"] + 9.0)
    if aspiration > 0.01:
        work = add_aspiration(work, rate, aspiration, rng=rng)
        report["aspiration_db_delta"] = aspiration

    # ---- output ----------------------------------------------------------
    target_rate = output.get("sample_rate")
    if target_rate and int(target_rate) != rate:
        work = resample_linear(work, int(target_rate), rate)
        rate = int(target_rate)
        report["resampled_to"] = rate

    gain_db = float(output.get("gain_db") or 0.0)
    if gain_db:
        work = apply_gain(work, db_to_linear(gain_db))
    fade = float(output.get("fade_ms", 12.0) or 0.0)
    if fade > 0:
        work = fade_edges(work, max(1, int(fade * 0.001 * rate)))
    ceiling = float(output.get("peak_ceiling") or 0.95)
    if ceiling > 0:
        work = normalize_peak(work, ceiling)
    work = clamp(work)

    report["duration_sec"] = len(work) / rate
    report["peak"] = peak(work)
    report["rms_db"] = linear_to_db(rms(work))
    return rate, work, report


def convert_file(input_path, output_path, spec, voiceprint=None, rng_seed=None):
    """Read a WAV, apply a transform spec, write the result."""
    rate, samples = wavio.read_wav(input_path)
    out_rate, out_samples, report = apply_spec(
        samples, rate, spec, voiceprint=voiceprint, rng_seed=rng_seed)
    wavio.write_wav(output_path, out_rate, out_samples)
    report["input"] = input_path
    report["output"] = output_path
    report["input_duration_sec"] = len(samples) / rate
    return report


def convert_text(text, spec, voiceprint=None, rng_seed=None, rate=24000):
    """Synthesise a line, then re-voice it -- a synth-then-transform chain.

    Useful as a self-test: the transform of a known signal has a known expected
    F0, so the whole pipeline can be verified end to end.
    """
    from . import synth
    voice = voiceprint or VoicePrint.default()
    source_rate, samples = synth.render_text(text, voice)
    return apply_spec(samples, source_rate, spec, voiceprint=None,
                      rng_seed=rng_seed)
