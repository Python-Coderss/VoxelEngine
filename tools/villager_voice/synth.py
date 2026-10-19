"""Source-filter formant synthesiser -- the villager voice, recreated.

Classic speech synthesis, no models and no network:

1. **Source.** A Rosenberg (or LF-style) glottal flow waveform whose period is
   driven by the F0 contour, perturbed by jitter (pitch), shimmer (amplitude),
   optional creak/subharmonic roughness and vibrato, plus an aspiration noise
   component for breathiness.
2. **Tract.** A five-resonator cascade (Klatt digital resonators) driven to
   per-phone formant targets, smoothed by the coarticulation setting so the
   vowels lean into each other the way real speech does. A parallel branch adds
   frication noise and stop bursts; a nasal pole/zero pair handles nasality.
3. **Output.** Lip radiation (+6 dB/oct), spectral tilt, high-frequency loss,
   band limiting, optional room, gain and fades.

Everything is read from the voiceprint and the score, so every stage is
controllable from JSON. The render is deterministic for a given
``random_seed``, which is what makes the round-trip tests meaningful.
"""

import math
import random

from .phones import (PHONES, DIPHTHONG, SILENCE, STOP, FRICATIVE, AFFRICATE,
                     NASAL, GLIDE, APPROXIMANT, VOCALISATION, is_syllabic,
                     formant_targets)
from .voiceprint import VoicePrint, db_to_linear

BLOCK = 32  # control-rate block, ~1.3 ms at 24 kHz

NEUTRAL = (500.0, 1500.0, 2500.0, 3400.0, 4300.0)
NEUTRAL_BW = (70.0, 100.0, 160.0, 210.0, 270.0)


# ---------------------------------------------------------------------------
# Interpolation helpers
# ---------------------------------------------------------------------------
def interp_points(points, times, default=0.0):
    """Piecewise-linear sample of ``[(t, value), ...]`` at each time."""
    if not points:
        return [default] * len(times)
    pts = sorted(points, key=lambda pair: pair[0])
    out = []
    index = 0
    last = len(pts) - 1
    for t in times:
        while index < last and pts[index + 1][0] < t:
            index += 1
        t0, v0 = pts[index]
        t1, v1 = pts[min(index + 1, last)]
        if t <= t0:
            out.append(v0)
        elif t >= t1 or t1 == t0:
            out.append(v1)
        else:
            fraction = (t - t0) / (t1 - t0)
            out.append(v0 + (v1 - v0) * fraction)
    return out


def smooth_one_pole(values, alpha):
    """Causal one-pole smoother; ``alpha`` is the per-block follow factor."""
    if alpha >= 1.0 or not values:
        return list(values)
    out = [values[0]]
    previous = values[0]
    for value in values[1:]:
        previous = previous + alpha * (value - previous)
        out.append(previous)
    return out


def resonator_coefficients(frequency, bandwidth, sample_rate):
    """Klatt digital resonator: returns ``(a, b1, b2)``."""
    frequency = max(20.0, min(frequency, sample_rate * 0.45))
    bandwidth = max(20.0, min(bandwidth, sample_rate * 0.3))
    radius = math.exp(-math.pi * bandwidth / sample_rate)
    theta = 2.0 * math.pi * frequency / sample_rate
    b1 = 2.0 * radius * math.cos(theta)
    b2 = -(radius * radius)
    a = 1.0 - b1 - b2
    return a, b1, b2


def antiresonator_coefficients(frequency, bandwidth, sample_rate):
    """Klatt anti-resonator (a spectral zero), from the same pole placement."""
    a, b1, b2 = resonator_coefficients(frequency, bandwidth, sample_rate)
    if abs(a) < 1e-9:
        return 1.0, 0.0, 0.0
    return 1.0 / a, -b1 / a, -b2 / a


# ---------------------------------------------------------------------------
# Glottal source shapes. ``u`` is phase in [0,1), ``open`` is the open-phase
# fraction of the period, ``skew`` the rise fraction of the open phase.
# ---------------------------------------------------------------------------
def glottal_rosenberg(u, open_quotient, skew):
    to = max(0.05, open_quotient)
    tr = max(0.02, skew) * to
    tf = max(0.02, to - tr)
    if u >= to:
        return 0.0
    if u < tr:
        return 1.0 - math.cos(math.pi * u / (2.0 * tr))
    return math.cos(math.pi * (u - tr) / (2.0 * tf))


def glottal_lf_style(u, open_quotient, skew):
    """Fast rise with an exponential return phase (LF-like brightness)."""
    to = max(0.05, open_quotient)
    tr = max(0.02, skew) * to * 0.75
    tf = max(0.02, to - tr)
    if u >= to:
        return 0.0
    if u < tr:
        return math.sin(math.pi * u / (2.0 * tr))
    decay = 5.5 * (u - tr) / tf
    tail = math.exp(-decay)
    # Force the flow to reach zero at the end of the open phase.
    end = math.exp(-5.5)
    return max(0.0, (tail - end) / (1.0 - end))


def glottal_pulse(u, open_quotient, skew):
    """Bright impulse-like source: sharp attack, fast decay."""
    to = max(0.05, open_quotient) * 0.6
    if u >= to:
        return 0.0
    x = u / to
    return x * math.exp(1.0 - x) * math.exp(-3.0 * x)


def glottal_buzz(u, open_quotient, skew):
    """Band-limited sawtooth (-6 dB/oct harmonics): thin, reedy voice."""
    return 1.0 - 2.0 * u


SOURCE_MODELS = {
    "rosenberg": glottal_rosenberg,
    "lf_style": glottal_lf_style,
    "pulse": glottal_pulse,
    "buzz": glottal_buzz,
}


# ---------------------------------------------------------------------------
# Control tracks
# ---------------------------------------------------------------------------
class _Track(object):
    """Per-block control arrays covering one rendered utterance."""

    def __init__(self, blocks):
        self.blocks = blocks
        self.f0 = [0.0] * blocks
        self.amp = [0.0] * blocks
        self.voice = [0.0] * blocks
        self.noise = [0.0] * blocks
        self.noise_center = [2500.0] * blocks
        self.noise_q = [1.0] * blocks
        self.nasal = [0.0] * blocks
        # Blocks no phone covers (pauses, lead-in, tail) must hold a *neutral*
        # tract shape. Zeroed formants clamp to 20 Hz resonators with Q near
        # 400, which ring for hundreds of milliseconds and dump low-frequency
        # garbage into the next phone's attack.
        self.formants = [list(NEUTRAL) for _ in range(blocks)]
        self.bandwidths = [list(NEUTRAL_BW) for _ in range(blocks)]

    def _slot_blocks(self, slot, sample_rate):
        start = int(math.floor(slot["start"] * sample_rate / BLOCK))
        end = int(math.ceil((slot["start"] + slot["dur"]) * sample_rate / BLOCK))
        return max(0, min(start, self.blocks - 1)), max(0, min(end, self.blocks))


def build_tracks(score, effective, sample_rate):
    """Turn a score's phone slots into per-block synthesis parameters."""
    duration = max(0.05, float(score.get("duration") or 0.0))
    for slot in score.get("phones", []):
        duration = max(duration, slot["start"] + slot["dur"])
    for pause in score.get("pauses", []):
        duration = max(duration, float(pause.get("at", 0.0)) + float(pause.get("dur", 0.0)))
    total_samples = int(math.ceil(duration * sample_rate)) + BLOCK * 4
    blocks = max(2, (total_samples + BLOCK - 1) // BLOCK)

    track = _Track(blocks)
    block_times = [(i + 0.5) * BLOCK / float(sample_rate) for i in range(blocks)]

    contours = score.get("contours") or {}
    f0_contour = interp_points(contours.get("f0") or [], block_times, 0.0)
    amp_contour = interp_points(contours.get("amp") or [], block_times, 0.0)
    scale_contour = interp_points(contours.get("formant_scale") or [],
                                  block_times, 1.0)
    breath_contour = interp_points(contours.get("breathiness") or [],
                                   block_times, -1.0)

    source = effective.section("source")
    tract = effective.section("tract")
    articulation = effective.section("articulation")

    f0_mean = float(source.get("f0_mean_hz", 116.0))
    f0_floor = float(source.get("f0_floor_hz", 68.0))
    f0_ceiling = float(source.get("f0_ceiling_hz", 330.0))
    length = float(tract.get("length_scale", 1.0))
    formant_scale = tract.get("formant_scale") or {}
    bandwidth_scale = float(tract.get("bandwidth_scale", 1.0))
    extra_bandwidth = float(tract.get("extra_bandwidth_hz", 25.0))
    coarticulation = float(articulation.get("coarticulation", 0.55))
    transition_ms = float(articulation.get("transition_ms", 40.0))
    undershoot = float(articulation.get("undershoot", 0.18))
    burst_db = float(articulation.get("stop_burst_db", -13.0))
    aspiration_ms = float(articulation.get("stop_aspiration_ms", 42.0))
    release_db = float(articulation.get("release_noise_db", -22.0))
    nasality_base = float(tract.get("nasality", 0.0))
    frication_gain = db_to_linear(float(tract.get("frication_gain_db", 0.0)))
    sibilance_boost = db_to_linear(float(tract.get("sibilance_boost_db", 0.0)))

    # --- fill per-phone targets -------------------------------------------
    for slot in score.get("phones", []):
        symbol = slot.get("sym", "IX")
        spec = PHONES.get(symbol) or PHONES["IX"]
        phone_manner = spec.get("manner", SILENCE)
        start_block, end_block = track._slot_blocks(slot, sample_rate)
        if end_block <= start_block:
            end_block = min(blocks, start_block + 1)
        count = max(1, end_block - start_block)

        # Formant trajectory: diphthongs glide between two targets.
        if phone_manner == DIPHTHONG and isinstance(spec.get("f"), list) \
                and spec["f"] and isinstance(spec["f"][0], list):
            start_targets = list(spec["f"][0])
            end_targets = list(spec["f"][1])
        else:
            start_targets = list(slot.get("f") or formant_targets(symbol)[0])
            end_targets = list(start_targets)

        # Undershoot pulls short phones toward the neutral tract shape.
        phone_duration = max(0.005, float(slot["dur"]))
        short = max(0.0, min(1.0, (0.12 - phone_duration) / 0.12))
        pull = undershoot * short
        for i in range(5):
            start_targets[i] = NEUTRAL[i] + (start_targets[i] - NEUTRAL[i]) * (1.0 - pull)
            end_targets[i] = NEUTRAL[i] + (end_targets[i] - NEUTRAL[i]) * (1.0 - pull)

        amplitude = float(slot.get("amp", spec.get("amp", 1.0)))
        voiced_phone = bool(slot.get("voiced", spec.get("voiced", False)))
        phone_f0_offset = float(slot.get("f0_semitones", 0.0) or 0.0)

        # Sub-segment plan inside the slot (stops and affricates need one).
        closure = float(spec.get("closure", 0.0) or 0.0)
        burst = spec.get("burst")
        noise = spec.get("noise")
        aspiration = float(spec.get("aspiration", 0.0) or 0.0)
        voicing_in_closure = float(spec.get("voicing_during_closure", 0.0) or 0.0)

        for offset in range(count):
            index = start_block + offset
            if index >= blocks:
                break
            progress = (offset + 0.5) / count
            time_in_slot = progress * phone_duration

            f1 = start_targets[0] + (end_targets[0] - start_targets[0]) * progress
            f2 = start_targets[1] + (end_targets[1] - start_targets[1]) * progress
            f3 = start_targets[2] + (end_targets[2] - start_targets[2]) * progress
            f4 = start_targets[3] + (end_targets[3] - start_targets[3]) * progress
            f5 = start_targets[4] + (end_targets[4] - start_targets[4]) * progress

            for i, value in enumerate((f1, f2, f3, f4, f5)):
                key = "f%d" % (i + 1)
                scaled = value * length * float(formant_scale.get(key, 1.0)) \
                    * scale_contour[index]
                track.formants[index][i] = scaled
            phone_bw = slot.get("bw") or spec.get("bw") or list(NEUTRAL_BW)
            for i in range(5):
                value = float(phone_bw[i]) if i < len(phone_bw) else NEUTRAL_BW[i]
                track.bandwidths[index][i] = value * bandwidth_scale + extra_bandwidth

            # --- excitation for this block --------------------------------
            voice_gain = 0.0
            noise_gain = 0.0
            center = 2500.0
            q = 1.0

            if phone_manner == SILENCE:
                voice_gain = 0.0
            elif phone_manner in (STOP, AFFRICATE):
                closure_end = min(phone_duration * 0.62, closure) if closure else \
                    phone_duration * 0.45
                burst_window = 0.014
                aspiration_window = max(0.012, aspiration)
                if time_in_slot < closure_end:
                    # Closure: silence, or a weak buzz for voiced stops.
                    voice_gain = amplitude * voicing_in_closure if voiced_phone else 0.0
                    noise_gain = 0.0
                    center, q = 700.0, 1.0
                elif time_in_slot < closure_end + burst_window:
                    # Burst: short broadband pop at the place of articulation.
                    decay = 1.0 - (time_in_slot - closure_end) / burst_window
                    noise_gain = db_to_linear(burst_db) * max(0.0, decay)
                    if burst:
                        center = float(burst.get("center", 2500.0))
                        q = float(burst.get("q", 1.2))
                        noise_gain *= float(burst.get("amp", 1.0)) / 0.25
                    voice_gain = amplitude * 0.15 if voiced_phone else 0.0
                else:
                    # Aspiration into the release: decaying breath.
                    elapsed = time_in_slot - closure_end - burst_window
                    decay = max(0.0, 1.0 - elapsed / aspiration_window)
                    noise_gain = (db_to_linear(release_db) * decay
                                  + db_to_linear(burst_db) * 0.25 * decay)
                    if burst:
                        center = float(burst.get("center", 2500.0)) * 1.25
                        q = float(burst.get("q", 1.2))
                    voice_gain = amplitude * decay * (0.55 if voiced_phone else 0.0)
            elif phone_manner == FRICATIVE:
                if noise:
                    center = float(noise.get("center", 4000.0))
                    q = float(noise.get("q", 1.0))
                    noise_gain = float(noise.get("amp", 0.25)) * frication_gain
                    if center > 2400.0:
                        noise_gain *= sibilance_boost
                else:
                    center, noise_gain = 4000.0, 0.2 * frication_gain
                # Frication ramps in and out so it does not click.
                edge = min(1.0, progress / 0.18, (1.0 - progress) / 0.18)
                noise_gain *= max(0.0, edge)
                voice_gain = amplitude if voiced_phone else 0.0
                if voiced_phone:
                    noise_gain *= 0.75
            elif phone_manner == NASAL:
                voice_gain = amplitude
                noise_gain = 0.0
            elif phone_manner == VOCALISATION:
                voice_gain = amplitude
                noise_gain = 0.0
            else:
                voice_gain = amplitude
                if symbol == "HH":
                    noise_gain = 0.30 * frication_gain
                    center = float((noise or {}).get("center", 1500.0))
                    q = 0.7
                    voice_gain *= 0.35

            # F0 and loudness from the contours.
            semitones = f0_contour[index] + phone_f0_offset
            f0 = f0_mean * (2.0 ** (semitones / 12.0))
            track.f0[index] = max(f0_floor, min(f0_ceiling, f0))
            loudness = db_to_linear(amp_contour[index])
            track.amp[index] = loudness
            track.voice[index] = voice_gain * loudness
            track.noise[index] = noise_gain * loudness
            track.noise_center[index] = center
            track.noise_q[index] = q

            nasal_amount = float(spec.get("nasal", 0.0) or 0.0)
            track.nasal[index] = max(nasality_base, min(1.0, nasal_amount))

        # Breathiness contour can override the voiceprint value per block.
        if breath_contour:
            for index in range(start_block, min(end_block, blocks)):
                if breath_contour[index] >= 0.0:
                    track.breath_override = getattr(track, "breath_override", {})
                    track.breath_override[index] = breath_contour[index]

    # --- coarticulation smoothing -----------------------------------------
    # The smoother's follow factor comes from the transition time: a longer
    # transition (or heavier coarticulation) means the tract lags more.
    block_seconds = BLOCK / float(sample_rate)
    transition = max(0.004, (transition_ms / 1000.0) *
                     (0.45 + 1.1 * coarticulation))
    alpha = min(1.0, block_seconds / transition)
    for i in range(5):
        column = [track.formants[b][i] for b in range(blocks)]
        smoothed = smooth_one_pole(column, alpha)
        for b in range(blocks):
            track.formants[b][i] = smoothed[b]
        column = [track.bandwidths[b][i] for b in range(blocks)]
        smoothed = smooth_one_pole(column, min(1.0, alpha * 1.6))
        for b in range(blocks):
            track.bandwidths[b][i] = smoothed[b]
    for name in ("f0", "voice", "noise", "nasal"):
        column = getattr(track, name)
        setattr(track, name, smooth_one_pole(column, min(1.0, alpha * 2.2)))

    track.total_samples = total_samples
    track.block_times = block_times
    return track


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------
def render_score(score, voiceprint=None, seed=None):
    """Render a performance score to ``(sample_rate, samples)``."""
    if voiceprint is None:
        embedded = score.get("voiceprint")
        voiceprint = VoicePrint(embedded) if embedded else VoicePrint.default()
    effective = voiceprint.effective()

    source = effective.section("source")
    tract = effective.section("tract")
    output = effective.section("output")

    sample_rate = int(output.get("sample_rate", 24000))
    rng = random.Random(int(seed if seed is not None
                            else effective.get("random_seed", 20261004)))

    track = build_tracks(score, effective, sample_rate)
    blocks = track.blocks
    total_samples = track.total_samples

    model = SOURCE_MODELS.get(str(source.get("model", "rosenberg")),
                              glottal_rosenberg)
    whisper_only = str(source.get("model")) == "whisper"
    open_quotient = float(source.get("open_quotient", 0.62))
    skew = float(source.get("speed_ratio", 0.42))
    # Jitter/shimmer are specified as the conventional *local* measures,
    # mean|T_i - T_i-1| / mean(T). A per-period Gaussian of standard deviation
    # sigma produces a local measure of sigma * sqrt(2) * sqrt(2/pi) = 1.128
    # sigma, so the perturbation is scaled by 1/1.128 to make the knob mean
    # what it says. Verified by analyze.round_trip_report.
    PERTURBATION_TO_LOCAL = 1.0 / (math.sqrt(2.0) * math.sqrt(2.0 / math.pi))
    jitter = float(source.get("jitter_percent", 0.85)) / 100.0 * PERTURBATION_TO_LOCAL
    shimmer = float(source.get("shimmer_percent", 3.5)) / 100.0 * PERTURBATION_TO_LOCAL
    aspiration_db = float(source.get("aspiration_db", -33.0))
    breathiness = float(source.get("breathiness", 0.22))
    creak = float(source.get("creak_percent", 0.0))
    subharmonic = float(source.get("subharmonic_mix", 0.0))
    vibrato_rate = float(source.get("vibrato_rate_hz", 5.1))
    vibrato_depth = float(source.get("vibrato_depth_cents", 24.0)) / 1200.0
    vibrato_onset = float(source.get("vibrato_onset_ms", 220.0)) / 1000.0
    drift = float(source.get("pitch_drift_cents_per_sec", -3.0)) / 100.0

    nasal_formant = float(tract.get("nasal_formant_hz", 285.0))
    nasal_bandwidth = float(tract.get("nasal_bandwidth_hz", 95.0))
    nasal_zero = float(tract.get("nasal_zero_hz", 950.0))
    nasal_zero_bw = float(tract.get("nasal_zero_bandwidth_hz", 130.0))
    radiation = float(tract.get("radiation_db_per_oct", 6.0)) / 6.0
    tilt = float(tract.get("spectral_tilt_db_per_oct", -3.0))
    source_tilt = float(source.get("source_tilt_db_per_oct", -9.0))
    hf_loss = float(tract.get("hf_loss_hz", 6800.0))
    order = int(tract.get("cascade_order", 5))

    # --- per-block filter coefficients ------------------------------------
    coefficients = []
    for b in range(blocks):
        bank = []
        for i in range(min(5, max(1, order))):
            bank.append(resonator_coefficients(
                track.formants[b][i], track.bandwidths[b][i], sample_rate))
        coefficients.append(bank)
    nasal_pole = resonator_coefficients(nasal_formant, nasal_bandwidth, sample_rate)
    nasal_zero_coefficients = antiresonator_coefficients(nasal_zero, nasal_zero_bw,
                                                         sample_rate)
    # One-pole lowpass for the high-frequency loss.
    hf_coefficient = 1.0 - math.exp(-2.0 * math.pi * hf_loss / sample_rate)

    # --- sample loop -------------------------------------------------------
    samples = [0.0] * total_samples
    phase = 0.0
    sub_phase = 0.0
    period_gain = 1.0
    # A pitch period (~207 samples at 116 Hz) spans about six control blocks
    # (32 samples), so the per-cycle perturbation must live *outside* the block
    # loop. Re-deriving it from the contour each block overwrote the jitter
    # before it could affect most of the cycle -- the knob measured as no-op.
    period_multiplier = 1.0
    voiced_time = 0.0
    previous_source = 0.0

    state = [[0.0, 0.0] for _ in range(5)]
    nasal_state = [0.0, 0.0]
    zero_state = [0.0, 0.0]
    noise_state = [0.0, 0.0]
    tilt_low = 0.0
    source_low = 0.0
    hf_low = 0.0
    previous_output = 0.0

    noise_a, noise_b1, noise_b2 = 1.0, 0.0, 0.0
    noise_noise = 0.0
    breath_override = getattr(track, "breath_override", None) or {}

    for b in range(blocks):
        base_f0 = track.f0[b]
        voice_gain = track.voice[b]
        noise_gain = track.noise[b]
        amp = track.amp[b]
        nasal_mix = track.nasal[b]
        center = track.noise_center[b]
        q = max(0.25, track.noise_q[b])
        block_start = b * BLOCK
        block_end = min(total_samples, block_start + BLOCK)
        if block_start >= total_samples:
            break

        noise_a, noise_b1, noise_b2 = resonator_coefficients(
            center, max(80.0, center / q), sample_rate)
        bank = coefficients[b]
        a0, b1, b2 = bank[0]
        a1, b3, b4 = bank[1] if len(bank) > 1 else (1.0, 0.0, 0.0)
        a2, b5, b6 = bank[2] if len(bank) > 2 else (1.0, 0.0, 0.0)
        a3, b7, b8 = bank[3] if len(bank) > 3 else (1.0, 0.0, 0.0)
        a4, b9, b10 = bank[4] if len(bank) > 4 else (1.0, 0.0, 0.0)
        n_a, n_b1, n_b2 = nasal_pole
        z_a, z_b1, z_b2 = nasal_zero_coefficients

        if voice_gain > 0.0 and not whisper_only:
            voiced_time += (block_end - block_start) / float(sample_rate)
        else:
            voiced_time = 0.0

        vibrato = 0.0
        if vibrato_depth > 0.0 and vibrato_rate > 0.0 and voiced_time > vibrato_onset:
            ramp = min(1.0, (voiced_time - vibrato_onset) / 0.35)
            vibrato = vibrato_depth * ramp

        time_seconds = block_start / float(sample_rate)
        drift_factor = 1.0 + drift * time_seconds * 0.01

        for n in range(block_start, block_end):
            # --- source ----------------------------------------------------
            if whisper_only:
                source_value = 0.0
            elif voice_gain > 1e-5 and base_f0 > 1.0:
                # Jitter perturbs around the block's contour value, so it can
                # never random-walk into a pitch drift.
                instant = base_f0 * period_multiplier * drift_factor
                if vibrato:
                    instant *= 1.0 + vibrato * math.sin(
                        2.0 * math.pi * vibrato_rate * (n / float(sample_rate)))
                if instant < 1.0:
                    instant = 1.0
                step = instant / sample_rate
                phase += step
                if phase >= 1.0:
                    phase -= 1.0
                    # New glottal period: apply jitter, shimmer and creak.
                    period_multiplier = 1.0
                    if jitter > 0.0:
                        period_multiplier += jitter * rng.gauss(0.0, 1.0)
                        period_multiplier = max(0.5, min(2.0, period_multiplier))
                    period_gain = 1.0 + (shimmer * rng.gauss(0.0, 1.0)
                                         if shimmer > 0.0 else 0.0)
                    if creak > 0.0 and rng.random() < creak * 0.25:
                        period_gain *= 0.55
                        period_multiplier *= 0.5
                source_value = model(phase, open_quotient, skew) * period_gain
                if subharmonic > 0.0:
                    sub_phase += (base_f0 * period_multiplier * 0.5) / sample_rate
                    if sub_phase >= 1.0:
                        sub_phase -= 1.0
                    source_value = (source_value * (1.0 - subharmonic)
                                    + subharmonic * glottal_buzz(sub_phase,
                                                                  open_quotient, skew))
                source_value *= voice_gain
            else:
                source_value = 0.0

            # Aspiration (breath) rides on the source path. ``aspiration_db``
            # already carries the breathiness contribution -- effective() adds
            # 30 dB per unit -- so it must not be added a second time here.
            #
            # A score's ``contours.breathiness`` can override that value per
            # block (build_tracks collects it into track.breath_override). The
            # override is converted back to dB with the same 30 dB/unit rule
            # effective() uses, so the contour and the knob mean the same thing.
            block_breathiness = breathiness
            block_aspiration_db = aspiration_db
            if breath_override and b in breath_override:
                block_breathiness = breath_override[b]
                block_aspiration_db = aspiration_db + 30.0 * (
                    block_breathiness - breathiness)
            aspiration_level = db_to_linear(block_aspiration_db)
            white = rng.random() * 2.0 - 1.0

            # Source spectral tilt: a leaky integrator darkens the excitation.
            source_low += (source_value - source_low) * 0.08
            if source_tilt < 0.0:
                amount = max(-0.9, source_tilt / 24.0)
                source_value = source_value + amount * (source_value - source_low)

            excitation = source_value + white * aspiration_level * max(
                voice_gain, 0.25 * block_breathiness)

            # --- frication / burst noise (parallel branch) -----------------
            noise_out = 0.0
            if noise_gain > 1e-5:
                noise_y1, noise_y2 = noise_state
                value = noise_a * white + noise_b1 * noise_y1 + noise_b2 * noise_y2
                noise_state = [value, noise_y1]
                noise_out = value * noise_gain * 2.2

            # --- cascade tract --------------------------------------------
            y1, y2 = state[0]
            value = a0 * excitation + b1 * y1 + b2 * y2
            state[0] = [value, y1]
            if order > 1:
                y1, y2 = state[1]
                value = a1 * value + b3 * y1 + b4 * y2
                state[1] = [value, y1]
            if order > 2:
                y1, y2 = state[2]
                value = a2 * value + b5 * y1 + b6 * y2
                state[2] = [value, y1]
            if order > 3:
                y1, y2 = state[3]
                value = a3 * value + b7 * y1 + b8 * y2
                state[3] = [value, y1]
            if order > 4:
                y1, y2 = state[4]
                value = a4 * value + b9 * y1 + b10 * y2
                state[4] = [value, y1]

            # --- nasal coupling -------------------------------------------
            if nasal_mix > 0.001:
                y1, y2 = nasal_state
                pole = n_a * value + n_b1 * y1 + n_b2 * y2
                nasal_state = [pole, y1]
                y1, y2 = zero_state
                zeroed = z_a * pole + z_b1 * y1 + z_b2 * y2
                zero_state = [pole, y1]
                value = value * (1.0 - nasal_mix) + zeroed * nasal_mix

            value += noise_out

            # --- radiation, tilt, HF loss ---------------------------------
            # Lip radiation is a first difference (+6 dB/oct). It is what turns
            # the glottal *flow* into the radiated pressure wave, and it is the
            # only thing offsetting the -12 dB/oct source rolloff plus the
            # cascade's unity DC gain. Blending it (radiation 0..1) interpolates
            # between no differentiation and the ideal differentiator.
            if radiation > 0.0:
                difference = value - previous_output
                previous_output = value
                value = value * (1.0 - radiation) + difference * radiation
            tilt_low += (value - tilt_low) * 0.03
            if tilt != 0.0:
                amount = max(-0.9, min(0.9, tilt * 0.14))
                value = value + amount * (value - tilt_low)
            hf_low += (value - hf_low) * hf_coefficient
            value = hf_low

            samples[n] = value * amp

    # Non-speech vocalisations from the score's ``events`` list (laugh, sigh,
    # gasp, hmm) are mixed in *before* the output stage so they receive the same
    # EQ, room and normalisation as the speech they sit on top of.
    if score.get("events"):
        samples = overlay_events(samples, score, effective, sample_rate)

    _output_stage(samples, effective, sample_rate, rng)
    return sample_rate, samples


def _output_stage(samples, effective, sample_rate, rng):
    """Band limiting, room, gain, fades and peak normalisation."""
    output = effective.section("output")
    gain = db_to_linear(float(output.get("gain_db", 0.0)))
    ceiling = float(output.get("peak_ceiling", 0.95))
    highpass = float(output.get("highpass_hz", 70.0))
    lowpass = float(output.get("lowpass_hz", 9500.0))
    room_mix = float(output.get("room_mix", 0.0))
    room_decay = float(output.get("room_decay_ms", 0.0))
    fade_ms = float(output.get("fade_ms", 12.0))

    if highpass > 0.0:
        coefficient = math.exp(-2.0 * math.pi * highpass / sample_rate)
        previous_in = 0.0
        previous_out = 0.0
        for i, value in enumerate(samples):
            previous_out = coefficient * (previous_out + value - previous_in)
            previous_in = value
            samples[i] = previous_out
    if lowpass < sample_rate * 0.45:
        coefficient = 1.0 - math.exp(-2.0 * math.pi * lowpass / sample_rate)
        state = 0.0
        for i, value in enumerate(samples):
            state += coefficient * (value - state)
            samples[i] = state

    if room_mix > 0.0 and room_decay > 0.0:
        _simple_room(samples, sample_rate, room_mix, room_decay)

    if gain != 1.0:
        for i, value in enumerate(samples):
            samples[i] = value * gain

    peak_value = 0.0
    for value in samples:
        magnitude = -value if value < 0.0 else value
        if magnitude > peak_value:
            peak_value = magnitude

    # Two-sided normalisation when enabled: a render whose loudest moment is a
    # vowel rather than a stop burst would otherwise stay ~22 dB below the
    # ceiling. The 1e-4 floor stops genuine silence from being amplified into
    # noise. Limiting-only behaviour is kept when the knob is off.
    normalize = bool(output.get("normalize_peak", True))
    if peak_value > 0.0 and (normalize and peak_value > 1e-4
                             or peak_value > ceiling):
        factor = ceiling / peak_value
        for i, value in enumerate(samples):
            samples[i] = value * factor

    # Soft clip so nothing ever leaves the renderer out of range.
    for i, value in enumerate(samples):
        if value > 1.0:
            samples[i] = 1.0
        elif value < -1.0:
            samples[i] = -1.0

    fade = int(fade_ms / 1000.0 * sample_rate)
    if fade > 0 and len(samples) > fade * 2:
        for i in range(fade):
            window = 0.5 - 0.5 * math.cos(math.pi * (i + 1.0) / (fade + 1.0))
            samples[i] *= window
            samples[len(samples) - 1 - i] *= window
    return samples


def _simple_room(samples, sample_rate, mix, decay_ms):
    """Two comb filters and one allpass -- enough for a small interior."""
    length = len(samples)
    if length == 0:
        return
    feedback = max(0.0, min(0.92, 1.0 - 3.0 / max(1.0, decay_ms / 10.0)))
    wet = [0.0] * length
    for delay_ms in (29.0, 37.0):
        delay = max(1, int(delay_ms / 1000.0 * sample_rate))
        buffer = [0.0] * delay
        index = 0
        for i in range(length):
            delayed = buffer[index]
            buffer[index] = samples[i] + delayed * feedback
            index = (index + 1) % delay
            wet[i] += delayed * 0.5
    # Allpass to smear the comb colouration.
    delay = max(1, int(5.0 / 1000.0 * sample_rate))
    buffer = [0.0] * delay
    index = 0
    gain = 0.5
    for i in range(length):
        delayed = buffer[index]
        value = wet[i] + gain * delayed
        buffer[index] = value - gain * wet[i]
        index = (index + 1) % delay
        wet[i] = delayed + gain * value
    for i in range(length):
        samples[i] = samples[i] * (1.0 - mix) + wet[i] * mix
    return samples


# ---------------------------------------------------------------------------
# Events: short vocalisations overlaid on top of the spoken line
# ---------------------------------------------------------------------------
def render_event(kind, effective, sample_rate, duration=None, seed=0):
    """Render a standalone vocalisation ("laugh", "hmm", "sigh", "gasp")."""
    rng = random.Random(seed)
    source = effective.section("source")
    f0_mean = float(source.get("f0_mean_hz", 116.0))
    if kind == "laugh":
        pulses = max(2, int((duration or 0.6) / 0.16))
        score = {"phones": [], "pauses": [], "contours": {"f0": [], "amp": []},
                 "duration": 0.0}
        t = 0.0
        f0_points = []
        amp_points = []
        for i in range(pulses):
            score["phones"].append({
                "sym": "AX", "start": round(t, 4), "dur": 0.10, "stress": 0,
                "amp": 0.9 if i % 2 == 0 else 0.6, "voiced": True,
                "f": list(formant_targets("AX")[0]),
                "bw": list(formant_targets("AX")[1]), "manner": "vowel",
                "word": 0, "syll": i})
            f0_points.append([round(t, 4), 3.0 - i * 0.6])
            amp_points.append([round(t, 4), 2.0])
            f0_points.append([round(t + 0.1, 4), -1.0])
            amp_points.append([round(t + 0.1, 4), -14.0])
            t += 0.16
        score["duration"] = round(t, 4)
        score["contours"]["f0"] = f0_points
        score["contours"]["amp"] = amp_points
        return render_score(score, effective, seed)[1]

    if kind in ("hmm", "murmur"):
        length = int((duration or 0.5) * sample_rate)
        return _tone_burst(length, sample_rate, f0_mean, effective, rng,
                           contour="hmm")
    if kind == "sigh":
        length = int((duration or 0.8) * sample_rate)
        return _tone_burst(length, sample_rate, f0_mean * 0.9, effective, rng,
                           contour="falling", breathy=1.0)
    if kind == "gasp":
        length = int((duration or 0.3) * sample_rate)
        return _tone_burst(length, sample_rate, f0_mean, effective, rng,
                           contour="rising", breathy=1.4, unvoiced=True)
    return []


def _tone_burst(length, sample_rate, f0, effective, rng, contour="flat",
                breathy=0.0, unvoiced=False):
    """A single sustained vocalisation used by the event renderer."""
    source = effective.section("source")
    tract = effective.section("tract")
    open_quotient = float(source.get("open_quotient", 0.62))
    skew = float(source.get("speed_ratio", 0.42))
    symbol = "AX" if contour != "hmm" else "HMM"
    targets, bandwidths = formant_targets(symbol)
    length_scale = float(tract.get("length_scale", 1.0))
    targets = [t * length_scale for t in targets]
    coefficients = [resonator_coefficients(targets[i], bandwidths[i], sample_rate)
                    for i in range(5)]
    out = [0.0] * length
    state = [[0.0, 0.0] for _ in range(5)]
    phase = 0.0
    aspiration = db_to_linear(float(source.get("aspiration_db", -33.0))
                              + 26.0 * max(breathy, float(source.get("breathiness", 0.2))))
    for n in range(length):
        progress = n / float(max(1, length - 1))
        if contour == "falling":
            instant = f0 * (1.0 - 0.28 * progress)
        elif contour == "rising":
            instant = f0 * (1.0 + 0.22 * progress)
        elif contour == "hmm":
            instant = f0 * (1.0 + 0.03 * math.sin(2.0 * math.pi * 3.1 * progress))
        else:
            instant = f0
        envelope = math.sin(math.pi * min(1.0, max(0.0, progress)))
        if unvoiced:
            value = (rng.random() * 2.0 - 1.0) * envelope
        else:
            phase += instant / sample_rate
            if phase >= 1.0:
                phase -= 1.0
            value = glottal_rosenberg(phase, open_quotient, skew) * envelope
            value += (rng.random() * 2.0 - 1.0) * aspiration * envelope
        for i in range(5):
            a, b1, b2 = coefficients[i]
            y1, y2 = state[i]
            value = a * value + b1 * y1 + b2 * y2
            state[i] = [value, y1]
        out[n] = value * 0.5
    peak_value = max((abs(v) for v in out), default=0.0)
    if peak_value > 0.0:
        factor = 0.85 / peak_value
        out = [v * factor for v in out]
    return out


def overlay_events(samples, score, effective, sample_rate):
    """Mix score ``events`` into an existing buffer at their timestamps."""
    events = score.get("events") or []
    if not events:
        return samples
    for event in events:
        kind = str(event.get("kind", "")).lower()
        at = float(event.get("at", 0.0))
        duration = float(event.get("dur", 0.0) or 0.0) or None
        gain = float(event.get("gain", 1.0))
        clip = render_event(kind, effective, sample_rate, duration,
                            seed=int(event.get("seed", 0)))
        if not clip:
            continue
        offset = int(round(at * sample_rate))
        end = offset + len(clip)
        if end > len(samples):
            samples.extend([0.0] * (end - len(samples)))
        for i, value in enumerate(clip):
            samples[offset + i] += value * gain
    peak_value = max((abs(v) for v in samples), default=0.0)
    if peak_value > 1.0:
        factor = 1.0 / peak_value
        for i, value in enumerate(samples):
            samples[i] = value * factor
    return samples


def render_text(text, voiceprint=None, corpus=None, delivery=None, seed=None):
    """Convenience: text -> score -> audio."""
    from .prosody import build_score

    voiceprint = voiceprint or VoicePrint.default()
    score = build_score(text, voiceprint, corpus, seed, delivery)
    effective = voiceprint.effective()
    sample_rate = int(effective.get("output.sample_rate", 24000))
    samples = render_score(score, voiceprint, seed)
    return sample_rate, samples[1], score


def mix_into(destination, source, offset, gain=1.0):
    end = offset + len(source)
    if end > len(destination):
        destination.extend([0.0] * (end - len(destination)))
    for i, value in enumerate(source):
        destination[offset + i] += value * gain
    return destination
