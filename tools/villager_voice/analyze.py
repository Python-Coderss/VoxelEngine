"""Measurement front-end: recordings in, voiceprint out.

This is the analysis half of analysis-synthesis, and it is what makes the
recreated voice *theirs* rather than a generic default. It measures, with plain
DSP:

* **F0** -- autocorrelation pitch per frame, then mean/median/percentile range
* **formants** -- LPC (autocorrelation + Levinson-Durbin) spectrum peaks with
  bandwidths from the -3 dB width, tracked and median-filtered over voiced frames
* **breathiness** -- harmonics-to-noise ratio from the autocorrelation peak
* **jitter / shimmer** -- cycle-to-cycle period and amplitude perturbation
* **spectral tilt** -- least-squares slope of the log magnitude spectrum
* **voicing ratio, speaking rate, dynamics**

Nothing is learned and nothing is downloaded; every number is computed from the
signal. The same routines double as the verification harness: synthesise from a
voiceprint, measure the result, and the error tells you whether the control
surface is honest (see :func:`round_trip_report`).

Pure standard library. If numpy happens to be importable the heavy loops use it,
but it is never required.
"""

import math
import os
import wave

try:  # optional acceleration; the pure-Python path is authoritative
    import numpy as _np
except Exception:  # pragma: no cover
    _np = None

from .voiceprint import VoicePrint
from .wavio import read_wav

DEFAULT_FRAME = 0.025
DEFAULT_HOP = 0.010
# LPC order must scale with the sample rate: an all-pole model of order p can
# resolve about fs/(p+1) Hz, so a fixed order of 16 at 24 kHz smears formants
# 1.4 kHz apart into one lump. The standard rule (2 + fs/1000) gives one pole
# pair per formant plus a couple for the source.
LPC_ORDER = 16
FORMANT_COUNT = 5


def lpc_order(sample_rate):
    """Order for a formant-resolution-appropriate all-pole model."""
    return int(2 + max(4000, sample_rate) / 1000.0)
FORMANT_MIN = 90.0
FORMANT_MAX = 5500.0


# ---------------------------------------------------------------------------
# Framing and windowing
# ---------------------------------------------------------------------------
def hamming(length):
    return [0.54 - 0.46 * math.cos(2.0 * math.pi * i / max(1, length - 1))
            for i in range(length)]


def frame_signal(samples, sample_rate, frame_seconds=DEFAULT_FRAME,
                 hop_seconds=DEFAULT_HOP):
    """Split into overlapping Hamming-windowed frames."""
    length = max(8, int(frame_seconds * sample_rate))
    hop = max(1, int(hop_seconds * sample_rate))
    window = hamming(length)
    frames = []
    starts = []
    for start in range(0, max(0, len(samples) - length + 1), hop):
        chunk = samples[start:start + length]
        frames.append([chunk[i] * window[i] for i in range(len(chunk))])
        starts.append(start)
    if not frames and samples:
        chunk = list(samples[:length])
        frames.append([chunk[i] * window[i] for i in range(len(chunk))])
        starts.append(0)
    return frames, starts, length, hop


def preemphasis(frame, coefficient=0.97):
    out = [frame[0]]
    for i in range(1, len(frame)):
        out.append(frame[i] - coefficient * frame[i - 1])
    return out


def energy(frame):
    return math.sqrt(sum(value * value for value in frame) / max(1, len(frame)))


# ---------------------------------------------------------------------------
# LPC
# ---------------------------------------------------------------------------
def autocorrelation(frame, order):
    """Biased autocorrelation lags 0..order."""
    n = len(frame)
    out = [0.0] * (order + 1)
    for lag in range(order + 1):
        total = 0.0
        for i in range(lag, n):
            total += frame[i] * frame[i - lag]
        out[lag] = total
    return out


def levinson_durbin(autocorr, order):
    """Solve the Yule-Walker equations; returns (coefficients, gain).

    The reflection update must read the *previous* order's coefficients for
    every j, so they are snapshotted first: updating in place overwrites
    ``coefficients[i-1-j]`` before it is read for larger j and produces
    garbage (verified against a direct Gaussian solve in the test suite).
    """
    if not autocorr or autocorr[0] <= 1e-12:
        return [0.0] * order, 0.0
    coefficients = [0.0] * order
    error = autocorr[0]
    for i in range(order):
        lambda_value = -autocorr[i + 1]
        for j in range(i):
            lambda_value -= coefficients[j] * autocorr[i - j]
        if abs(error) <= 1e-18:
            break
        lambda_value /= error
        previous = coefficients[:i]
        for j in range(i):
            coefficients[j] = previous[j] + lambda_value * previous[i - 1 - j]
        coefficients[i] = lambda_value
        error *= (1.0 - lambda_value * lambda_value)
        if error <= 1e-12:
            break
    return coefficients, math.sqrt(max(0.0, error))


def lpc_for_frame(frame, order=None, sample_rate=24000):
    if order is None:
        order = lpc_order(sample_rate)
    windowed = preemphasis(frame)
    autocorr = autocorrelation(windowed, order)
    coefficients, gain = levinson_durbin(autocorr, order)
    return coefficients, gain, autocorr


def lpc_spectrum_magnitudes(coefficients, gain, sample_rate, bins=512,
                            max_hz=None):
    """Evaluate |H(f)| of the LPC all-pole filter on a linear frequency grid.

    :func:`levinson_durbin` returns coefficients for the prediction-*error*
    filter ``A(z) = 1 + sum(a_i z^-i)``, so the denominator is evaluated with
    that sign. Using ``1 - sum(a_i z^-i)`` inverts the response and turns every
    formant peak into a valley -- verified against a synthetic three-resonator
    signal in ``selftest``.
    """
    ceiling = max_hz or (sample_rate * 0.5)
    order = len(coefficients)
    magnitudes = []
    frequencies = []
    for k in range(bins):
        frequency = ceiling * k / float(bins - 1) if bins > 1 else 0.0
        omega = 2.0 * math.pi * frequency / sample_rate
        real = 1.0
        imag = 0.0
        for i in range(order):
            angle = omega * (i + 1)
            real += coefficients[i] * math.cos(angle)
            imag -= coefficients[i] * math.sin(angle)
        magnitude_squared = real * real + imag * imag
        magnitude = gain / math.sqrt(magnitude_squared) if magnitude_squared > 1e-18 else 0.0
        magnitudes.append(magnitude)
        frequencies.append(frequency)
    return frequencies, magnitudes


def find_formants(frequencies, magnitudes, count=FORMANT_COUNT,
                  f_min=FORMANT_MIN, f_max=FORMANT_MAX):
    """Peak-pick the LPC spectrum; returns [(freq, bandwidth, magnitude)].

    Bandwidth comes from the -3 dB width around each peak, which is the
    standard cheap estimate and is stable enough to drive a voiceprint.
    """
    peaks = []
    n = len(magnitudes)
    peak_magnitude = max(magnitudes) if magnitudes else 0.0
    floor = peak_magnitude * 0.02
    for i in range(2, n - 2):
        frequency = frequencies[i]
        if frequency < f_min or frequency > f_max:
            continue
        value = magnitudes[i]
        if value < floor:
            continue
        if value >= magnitudes[i - 1] and value > magnitudes[i + 1] \
                and value >= magnitudes[i - 2] and value >= magnitudes[i + 2]:
            # -3 dB width, capped so a broad lump cannot report a nonsense
            # bandwidth when the peak sits on a shallow skirt.
            target = value * 0.7071
            span_limit = 900.0
            low = frequency
            j = i - 1
            while j > 0 and magnitudes[j] > target \
                    and frequency - frequencies[j] < span_limit:
                low = frequencies[j]
                j -= 1
            high = frequency
            j = i + 1
            while j < n - 1 and magnitudes[j] > target \
                    and frequencies[j] - frequency < span_limit:
                high = frequencies[j]
                j += 1
            bandwidth = max(40.0, min(900.0, high - low))
            peaks.append((frequency, bandwidth, value))

    # Enforce a minimum spacing and monotonic ordering, keeping the strongest.
    peaks.sort(key=lambda item: -item[2])
    chosen = []
    for peak in peaks:
        if all(abs(peak[0] - existing[0]) > 130.0 for existing in chosen):
            chosen.append(peak)
        if len(chosen) >= count * 2:
            break
    chosen.sort(key=lambda item: item[0])
    # Keep the ``count`` lowest, which is where the articulatory information is.
    return chosen[:count]


# ---------------------------------------------------------------------------
# Pitch and voice quality
# ---------------------------------------------------------------------------
def estimate_f0(frame, sample_rate, f_min=60.0, f_max=400.0):
    """Autocorrelation pitch with parabolic interpolation.

    Returns ``(f0, confidence)``; confidence is the normalised autocorrelation
    peak, which doubles as a voicing decision and an HNR proxy.
    """
    n = len(frame)
    if n < 32:
        return 0.0, 0.0
    mean = sum(frame) / n
    centered = [value - mean for value in frame]
    lag_min = max(2, int(sample_rate / f_max))
    lag_max = min(n - 1, int(sample_rate / f_min))
    if lag_max <= lag_min:
        return 0.0, 0.0

    zero_lag = sum(value * value for value in centered)
    if zero_lag <= 1e-9:
        return 0.0, 0.0

    best_lag = -1
    best_value = -1.0
    correlations = {}
    for lag in range(lag_min, lag_max + 1):
        total = 0.0
        for i in range(lag, n):
            total += centered[i] * centered[i - lag]
        normalised = total / (zero_lag * (1.0 - lag / float(n)))
        correlations[lag] = normalised
        if normalised > best_value:
            best_value = normalised
            best_lag = lag

    if best_lag < 0 or best_value < 0.25:
        return 0.0, max(0.0, best_value)

    # Octave protection: breathy or noisy voiced frames often peak at 2x F0
    # (a 116 Hz voice measured as 238 Hz). Take the shortest lag whose
    # correlation is within 10% of the best, which is the fundamental.
    candidate = best_lag
    for lag in range(lag_min + 1, best_lag - 1):
        value = correlations.get(lag, -1.0)
        if value < 0.92 * best_value:
            continue
        # Require a genuine local peak, otherwise noise on a short lag can win
        # and halve the period (a 116 Hz voice reading as 275 Hz).
        if value >= correlations.get(lag - 1, -1.0) \
                and value >= correlations.get(lag + 1, -1.0):
            candidate = lag
            break
    if candidate != best_lag and candidate > 0:
        best_lag = candidate
        best_value = correlations.get(candidate, best_value)

    # Parabolic interpolation around the peak for sub-sample accuracy.
    left = correlations.get(best_lag - 1, best_value)
    right = correlations.get(best_lag + 1, best_value)
    denominator = left - 2.0 * best_value + right
    offset = 0.0
    if abs(denominator) > 1e-12:
        offset = 0.5 * (left - right) / denominator
        offset = max(-1.0, min(1.0, offset))
    period = best_lag + offset
    if period <= 0:
        return 0.0, 0.0
    return sample_rate / period, min(1.0, best_value)


def harmonic_to_noise(confidence):
    """Approximate HNR in dB from the normalised autocorrelation peak."""
    value = max(0.0, min(0.995, confidence))
    if value <= 0.01:
        return 0.0
    return 10.0 * math.log10(value * value / max(1e-6, 1.0 - value * value))


def voiced_runs(f0_values, hop, total_samples):
    """Group voiced frames into (first_sample, last_sample) runs."""
    runs = []
    current = None
    for index, f0 in enumerate(f0_values):
        if f0 > 0:
            start = index * hop
            end = min(total_samples, start + hop)
            if current is None:
                current = [start, end]
            else:
                current[1] = max(current[1], end)
        elif current is not None:
            runs.append(tuple(current))
            current = None
    if current is not None:
        runs.append(tuple(current))
    return [(a, b) for a, b in runs if b - a > 4]


def lpc_residual(samples, sample_rate, f0_values=None, hop=None,
                 frame_seconds=DEFAULT_FRAME, hop_seconds=DEFAULT_HOP,
                 order=None):
    """Inverse-filter voiced segments; returns (residual, positions).

    One LPC filter is fitted per *voiced segment*, not per frame. Fitting each
    25 ms frame separately gives every frame a slightly different inverse
    filter, which moves the residual pulses around by several samples and
    invents 9% jitter on a perfectly periodic synthetic signal. A segment-level
    filter is stationary, so what is left is real cycle-to-cycle perturbation.
    """
    _frames, starts, length, hop_samples = frame_signal(
        samples, sample_rate, frame_seconds, hop_seconds)
    hop = hop or hop_samples
    order = order or lpc_order(sample_rate)
    if f0_values is None:
        f0_values = [1.0] * max(1, len(samples) // hop)

    residual = []
    positions = []
    for first, last in voiced_runs(f0_values, hop, len(samples)):
        if last - first < order + 32:
            continue
        # Average the autocorrelation over windows inside the segment, then
        # solve once: a single stationary filter for the whole run.
        window = min(length, max(order + 8, (last - first) // 3))
        accumulated = None
        count = 0
        stride = max(1, window // 2)
        for start in range(first, max(first + 1, last - window), stride):
            chunk = samples[start:start + window]
            if len(chunk) < order + 8:
                break
            windowed = preemphasis([chunk[i] * hamming(len(chunk))[i]
                                    for i in range(len(chunk))])
            autocorr = autocorrelation(windowed, order)
            accumulated = autocorr if accumulated is None else [
                a + b for a, b in zip(accumulated, autocorr)]
            count += 1
        if not accumulated or count == 0:
            continue
        accumulated = [value / count for value in accumulated]
        coefficients, _gain = levinson_durbin(accumulated, order)
        for position in range(first + order, last):
            if position >= len(samples):
                break
            value = samples[position]
            for i in range(order):
                value += coefficients[i] * samples[position - 1 - i]
            residual.append(value)
            positions.append(position)
    return residual, positions


def measure_hnr(samples, sample_rate, peaks, template_length=160):
    """Harmonics-to-noise ratio by pitch-synchronous averaging.

    The waveform between successive glottal closures is resampled onto a fixed
    grid and averaged: the average is the deterministic (harmonic) component
    and what each cycle leaves over is noise. That is a far more trustworthy
    HNR than the height of the autocorrelation peak, which formant structure
    drags down even on a perfectly clean synthetic vowel.
    """
    if len(peaks) < 4:
        return 0.0, 0
    gaps = [peaks[i + 1] - peaks[i] for i in range(len(peaks) - 1)]
    typical = median(gaps)
    if typical < 12:
        return 0.0, 0
    # Limit the analysis bandwidth. Cycle-to-cycle period error misaligns high
    # harmonics when they are resampled onto a common grid, and that alignment
    # error is counted as noise -- Praat limits HNR to about 4.5 kHz for the
    # same reason. Without it a perfectly clean synthetic vowel measures ~17 dB.
    coefficient = 1.0 - math.exp(-2.0 * math.pi * 4500.0 / sample_rate)
    smoothed = []
    state = 0.0
    for value in samples:
        state += coefficient * (value - state)
        smoothed.append(state)

    cycles = []
    for i in range(len(peaks) - 1):
        first = float(peaks[i])
        span = float(peaks[i + 1]) - first
        if span < typical * 0.6 or span > typical * 1.6:
            continue
        resampled = []
        for k in range(template_length):
            position = first + span * k / float(template_length)
            index = int(position)
            if index + 1 >= len(smoothed):
                break
            fraction = position - index
            resampled.append(smoothed[index] * (1.0 - fraction)
                             + smoothed[index + 1] * fraction)
        if len(resampled) == template_length:
            cycles.append(resampled)
    if len(cycles) < 3:
        return 0.0, len(cycles)

    template = [sum(cycle[k] for cycle in cycles) / len(cycles)
                for k in range(template_length)]

    # Re-align each cycle to the template before averaging. Residual peak
    # positions still carry a fraction of a sample of error; letting each cycle
    # choose the integer shift that best matches the template removes the phase
    # smearing that otherwise caps HNR at a couple of dB no matter how clean
    # the source is.
    for _iteration in range(2):
        aligned = []
        for cycle in cycles:
            best_shift = 0
            best_score = None
            for shift in range(-3, 4):
                score = 0.0
                for k in range(template_length):
                    index = k + shift
                    if 0 <= index < template_length:
                        score += cycle[index] * template[k]
                if best_score is None or score > best_score:
                    best_score = score
                    best_shift = shift
            if best_shift == 0:
                aligned.append(cycle)
                continue
            shifted = []
            for k in range(template_length):
                index = k + best_shift
                shifted.append(cycle[index] if 0 <= index < template_length else 0.0)
            aligned.append(shifted)
        cycles = aligned
        template = [sum(cycle[k] for cycle in cycles) / len(cycles)
                    for k in range(template_length)]

    harmonic_power = sum(value * value for value in template) / template_length
    noise_power = 0.0
    for cycle in cycles:
        noise_power += sum((cycle[k] - template[k]) ** 2
                           for k in range(template_length)) / template_length
    noise_power /= len(cycles)
    if noise_power <= 1e-12 or harmonic_power <= 1e-12:
        return (60.0 if noise_power <= 1e-12 else 0.0), len(cycles)
    return 10.0 * math.log10(harmonic_power / noise_power), len(cycles)


def _refine_peak(position, residual, positions):
    """Parabolic sub-sample refinement of a residual peak.

    Integer-sample peak positions carry about +/-1.5 samples of error, which is
    a ~100 degree phase error at 4 kHz -- enough to smear a pitch-synchronous
    average and floor every HNR reading near 0 dB.
    """
    if not positions:
        return position
    try:
        index = positions.index(position)
    except ValueError:
        return position
    if index <= 0 or index + 1 >= len(residual):
        return position
    left = abs(residual[index - 1])
    centre = abs(residual[index])
    right = abs(residual[index + 1])
    denominator = left - 2.0 * centre + right
    if abs(denominator) < 1e-15:
        return position
    offset = 0.5 * (left - right) / denominator
    return position + max(-1.0, min(1.0, offset))


def _contiguous_runs(positions, max_gap=2):
    """Split absolute sample positions into runs of consecutive samples.

    ``lpc_residual`` skips unvoiced frames, so the stream is a set of disjoint
    chunks. Gap and refractory logic must never compare positions across a
    chunk boundary -- that is what silently discarded nearly every detection.
    """
    runs = []
    current = []
    for index, position in enumerate(positions):
        if current and position - positions[index - 1] > max_gap:
            runs.append(current)
            current = []
        current.append(index)
    if current:
        runs.append(current)
    return runs


def glottal_periods(residual, positions, sample_rate, f0_hint=0.0,
                    f_min=55.0, f_max=420.0):
    """Locate glottal closure instants in the LPC residual.

    Two robustness requirements learned the hard way:

    * the threshold comes from the 90th percentile of the rectified residual,
      not its maximum -- ``max`` is set by stop bursts and phone-boundary
      transients, which puts the threshold far above every voiced pulse;
    * detection runs per contiguous chunk, so a pause or a consonant cannot
      make the neighbouring pulses look like isolated outliers.
    """
    if len(residual) < 8:
        return []
    rectified = sorted(abs(value) for value in residual)
    robust = percentile(rectified, 0.90)
    if robust <= 1e-9:
        return []
    threshold = robust * 0.45

    shortest = sample_rate / f_max
    longest = sample_rate / f_min
    if f0_hint > 0:
        expected = sample_rate / f0_hint
        shortest = min(shortest, expected * 0.60)
        longest = max(longest, expected * 1.60)

    accepted = []
    for run in _contiguous_runs(positions):
        if len(run) < 4:
            continue
        # The radiated waveform is a differentiated glottal flow, so a closure
        # appears as a bipolar doublet; rectified local maxima catch either
        # polarity.
        # A local maximum over a window of about a fifth of the pitch period,
        # not over immediate neighbours: the residual is smooth, and the
        # radiated closure is a bipolar doublet whose two lobes would both
        # pass a 1-sample test.
        lobe = max(2, int((sample_rate / f0_hint * 0.20) if f0_hint > 0 else 6))
        candidates = []
        for k in range(len(run)):
            i = run[k]
            value = abs(residual[i])
            if value < threshold:
                continue
            lo = max(0, k - lobe)
            hi = min(len(run) - 1, k + lobe)
            if all(value >= abs(residual[run[m]]) for m in range(lo, hi + 1)):
                candidates.append((positions[i], value))
        candidates.sort(key=lambda item: -item[1])
        # Refractory period just under one pitch period: rejects the second
        # lobe of a doublet while still admitting every genuine cycle.
        refractory = max(shortest * 0.6,
                         (sample_rate / f0_hint * 0.72) if f0_hint > 0 else 0.0)
        chosen = []
        for position, _value in candidates:
            if all(abs(position - other) >= refractory for other in chosen):
                chosen.append(_refine_peak(position, residual, positions))
        chosen.sort()
        # Within one chunk, drop detections whose neighbour spacing is
        # physiologically impossible.
        accepted.extend(chosen)

    accepted = sorted(set(accepted))
    refractory = max(shortest * 0.6,
                     (sample_rate / f0_hint * 0.72) if f0_hint > 0 else 0.0)
    merged = []
    for position in accepted:
        if merged and position - merged[-1] < refractory:
            continue
        merged.append(position)
    return merged


def measure_jitter_shimmer(samples, sample_rate, f0_values, hop,
                           residual=None, positions=None, peaks=None):
    """Cycle-to-cycle period (jitter) and amplitude (shimmer) perturbation.

    Jitter and shimmer are *per glottal cycle* measures. Deriving them from
    frame-wise F0 estimates instead measures how much the estimate moves every
    10 ms, which is several times larger than the real perturbation -- so the
    periods are taken from the LPC residual's glottal pulses.
    """
    voiced = [value for value in f0_values if value > 0]
    hint = median(voiced) if voiced else 0.0
    if peaks is None:
        if residual is None:
            residual, positions = lpc_residual(samples, sample_rate, f0_values)
        peaks = glottal_periods(residual, positions, sample_rate, hint)
    if len(peaks) < 6:
        return 0.0, 0.0, len(peaks)

    # Group the detected closures into runs. Peaks on either side of a pause or
    # an unvoiced consonant are not consecutive cycles, and treating the gap
    # between them as a period inflates jitter to nonsense (49% measured where
    # 0.77% was configured).
    gaps = [peaks[i + 1] - peaks[i] for i in range(len(peaks) - 1)]
    typical = median(gaps) if gaps else 0.0
    runs = []
    current = [peaks[0]]
    for i in range(1, len(peaks)):
        if typical > 0 and peaks[i] - peaks[i - 1] > typical * 1.8:
            runs.append(current)
            current = []
        current.append(peaks[i])
    runs.append(current)

    usable = []
    for run in runs:
        for i in range(len(run) - 1):
            period = run[i + 1] - run[i]
            if sample_rate / 500.0 < period < sample_rate / 50.0:
                usable.append(period)
    if len(usable) < 4:
        return 0.0, 0.0, len(peaks)
    mean_period = sum(usable) / len(usable)
    differences = []
    for run in runs:
        run_periods = [run[i + 1] - run[i] for i in range(len(run) - 1)
                       if sample_rate / 500.0 < run[i + 1] - run[i]
                       < sample_rate / 50.0]
        for i in range(len(run_periods) - 1):
            differences.append(abs(run_periods[i + 1] - run_periods[i]))
    if not differences:
        return 0.0, 0.0, len(peaks)
    jitter = 100.0 * (sum(differences) / len(differences)) / mean_period \
        if mean_period > 0 else 0.0

    # Shimmer: peak-to-peak signal amplitude measured once per glottal cycle.
    amplitudes = []
    for position in peaks:
        position = int(round(position))
        window = int(mean_period)
        first = max(0, position - window // 3)
        last = min(len(samples), position + window)
        if last - first < 4:
            continue
        amplitudes.append((position, max(abs(value) for value in samples[first:last])))
    shimmer = 0.0
    if len(amplitudes) >= 4:
        by_position = dict(amplitudes)
        deltas = []
        all_amplitudes = []
        for run in runs:
            values = [by_position[int(round(position))] for position in run
                      if int(round(position)) in by_position]
            all_amplitudes.extend(values)
            for i in range(len(values) - 1):
                deltas.append(abs(values[i + 1] - values[i]))
        if deltas and all_amplitudes:
            mean_amplitude = sum(all_amplitudes) / len(all_amplitudes)
            shimmer = 100.0 * (sum(deltas) / len(deltas)) / mean_amplitude \
                if mean_amplitude > 1e-9 else 0.0
    return jitter, shimmer, len(peaks)


def measure_spectral_tilt(samples, sample_rate, bins=1024):
    """Least-squares slope of log magnitude vs log frequency, in dB/octave."""
    if len(samples) < bins:
        return 0.0
    # Use the strongest window so the tilt reflects voiced speech, not pauses.
    window_length = bins
    best_start = 0
    best_energy = -1.0
    step = max(1, len(samples) // 8)
    for start in range(0, max(1, len(samples) - window_length), step):
        chunk = samples[start:start + window_length]
        value = sum(x * x for x in chunk)
        if value > best_energy:
            best_energy = value
            best_start = start
    chunk = samples[best_start:best_start + window_length]
    spectrum = _fft_magnitudes(chunk)
    points = []
    for i in range(2, len(spectrum) // 2):
        frequency = i * sample_rate / float(window_length)
        if frequency < 150 or frequency > min(6000, sample_rate * 0.4):
            continue
        magnitude = spectrum[i]
        if magnitude <= 1e-8:
            continue
        points.append((math.log2(frequency), 20.0 * math.log10(magnitude)))
    if len(points) < 8:
        return 0.0
    n = len(points)
    mean_x = sum(p[0] for p in points) / n
    mean_y = sum(p[1] for p in points) / n
    numerator = sum((p[0] - mean_x) * (p[1] - mean_y) for p in points)
    denominator = sum((p[0] - mean_x) ** 2 for p in points)
    if abs(denominator) < 1e-9:
        return 0.0
    return numerator / denominator


def _fft_magnitudes(chunk):
    """Radix-2 FFT magnitude spectrum (zero-padded to a power of two)."""
    length = 1
    while length < len(chunk):
        length <<= 1
    real = [0.0] * length
    imag = [0.0] * length
    window = hamming(len(chunk))
    for i, value in enumerate(chunk):
        real[i] = value * window[i]

    # Bit-reversal permutation.
    j = 0
    for i in range(1, length):
        bit = length >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            real[i], real[j] = real[j], real[i]
            imag[i], imag[j] = imag[j], imag[i]

    size = 2
    while size <= length:
        half = size // 2
        step = -2.0 * math.pi / size
        for i in range(0, length, size):
            for k in range(half):
                angle = step * k
                wr = math.cos(angle)
                wi = math.sin(angle)
                tr = wr * real[i + k + half] - wi * imag[i + k + half]
                ti = wr * imag[i + k + half] + wi * real[i + k + half]
                real[i + k + half] = real[i + k] - tr
                imag[i + k + half] = imag[i + k] - ti
                real[i + k] += tr
                imag[i + k] += ti
        size <<= 1
    return [math.sqrt(real[i] ** 2 + imag[i] ** 2) for i in range(length)]


def median(values):
    if not values:
        return 0.0
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return 0.5 * (ordered[middle - 1] + ordered[middle])


def percentile(values, fraction):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round(fraction * (len(ordered) - 1)))
    return ordered[max(0, min(len(ordered) - 1, index))]


# ---------------------------------------------------------------------------
# Whole-file measurement
# ---------------------------------------------------------------------------
def analyze_samples(samples, sample_rate, frame_seconds=DEFAULT_FRAME,
                    hop_seconds=DEFAULT_HOP, verbose=False):
    """Measure everything this module knows how to measure."""
    frames, starts, length, hop = frame_signal(samples, sample_rate,
                                               frame_seconds, hop_seconds)
    if not frames:
        raise ValueError("signal too short to analyze")

    energies = [energy(frame) for frame in frames]
    voice_threshold = percentile(energies, 0.55) * 0.55
    f0_values = []
    confidences = []
    formant_tracks = [[] for _ in range(FORMANT_COUNT)]
    bandwidth_tracks = [[] for _ in range(FORMANT_COUNT)]
    voiced = 0

    for index, frame in enumerate(frames):
        if energies[index] < voice_threshold:
            f0_values.append(0.0)
            confidences.append(0.0)
            continue
        f0, confidence = estimate_f0(frame, sample_rate)
        f0_values.append(f0 if confidence > 0.35 else 0.0)
        confidences.append(confidence)
        if f0 > 0 and confidence > 0.35:
            voiced += 1
            coefficients, gain, _autocorr = lpc_for_frame(
                frame, sample_rate=sample_rate)
            if gain > 1e-6:
                frequencies, magnitudes = lpc_spectrum_magnitudes(
                    coefficients, gain, sample_rate, bins=256)
                peaks = find_formants(frequencies, magnitudes)
                for i in range(FORMANT_COUNT):
                    if i < len(peaks):
                        formant_tracks[i].append(peaks[i][0])
                        bandwidth_tracks[i].append(peaks[i][1])
        if verbose and index % 50 == 0:
            print("  frame %d/%d f0=%.1f conf=%.2f"
                  % (index, len(frames), f0_values[-1], confidences[-1]))

    voiced_f0 = [value for value in f0_values if value > 0]
    hint = median(voiced_f0) if voiced_f0 else 0.0
    residual, residual_positions = lpc_residual(samples, sample_rate, f0_values)
    peaks = glottal_periods(residual, residual_positions, sample_rate, hint)
    hnr, cycles_used = measure_hnr(samples, sample_rate, peaks)
    jitter, shimmer, glottal_cycles = measure_jitter_shimmer(
        samples, sample_rate, f0_values, hop, residual, residual_positions,
        peaks=peaks)
    voiced_confidence = [value for index, value in enumerate(confidences)
                         if f0_values[index] > 0]
    tilt = measure_spectral_tilt(samples, sample_rate)

    peak_value = max((abs(value) for value in samples), default=0.0)
    rms_value = math.sqrt(sum(value * value for value in samples) /
                          max(1, len(samples)))

    measurements = {
        "sample_rate": int(sample_rate),
        "duration_seconds": round(len(samples) / float(sample_rate), 4),
        "frames": len(frames),
        "voiced_frames": voiced,
        "voicing_ratio": round(voiced / float(len(frames)), 4),
        "peak": round(peak_value, 5),
        "rms": round(rms_value, 5),
        "rms_db": round(20.0 * math.log10(rms_value) if rms_value > 1e-9 else -120.0, 2),
        "f0_mean_hz": round(sum(voiced_f0) / len(voiced_f0), 2) if voiced_f0 else 0.0,
        "f0_median_hz": round(median(voiced_f0), 2),
        "f0_p5_hz": round(percentile(voiced_f0, 0.05), 2),
        "f0_p95_hz": round(percentile(voiced_f0, 0.95), 2),
        "f0_min_hz": round(min(voiced_f0), 2) if voiced_f0 else 0.0,
        "f0_max_hz": round(max(voiced_f0), 2) if voiced_f0 else 0.0,
        "f0_semitone_range": round(
            12.0 * math.log2(percentile(voiced_f0, 0.95) /
                             max(1.0, percentile(voiced_f0, 0.05)))
            if len(voiced_f0) > 8 and percentile(voiced_f0, 0.05) > 0 else 0.0, 2),
        "hnr_db": round(hnr, 2),
        "hnr_autocorrelation_db": round(
            median([harmonic_to_noise(value) for value in voiced_confidence]), 2),
        "pitch_sync_cycles": cycles_used,
        "glottal_cycles": glottal_cycles,
        "jitter_percent": round(jitter, 3),
        "shimmer_percent": round(shimmer, 3),
        "spectral_tilt_db_per_oct": round(tilt, 2),
        "formants_hz": [round(median(track), 1) for track in formant_tracks],
        "formant_bandwidths_hz": [round(median(track), 1) for track in bandwidth_tracks],
        "formant_samples": [len(track) for track in formant_tracks],
    }
    return measurements


def analyze_file(path, **kwargs):
    """Read a WAV file and measure it."""
    sample_rate, samples = read_wav(path)
    measurements = analyze_samples(samples, sample_rate, **kwargs)
    measurements["file"] = str(path)
    return measurements


def analyze_directory(directory, pattern="*.wav", limit=None, **kwargs):
    """Measure every matching file in a directory and aggregate the results."""
    import glob

    paths = sorted(glob.glob(os.path.join(str(directory), pattern)))
    if limit:
        paths = paths[:limit]
    if not paths:
        raise ValueError("no %s files in %s" % (pattern, directory))
    per_file = []
    for path in paths:
        try:
            per_file.append(analyze_file(path, **kwargs))
        except Exception as error:  # keep going over a bad file
            per_file.append({"file": path, "error": str(error)})
    return aggregate(per_file), per_file


def format_measurements(measurements):
    """Human-readable summary of one file's measurements."""
    if "error" in measurements:
        return "%s: error %s" % (measurements.get("file", "?"), measurements["error"])
    formants = measurements.get("formants_hz") or []
    bandwidths = measurements.get("formant_bandwidths_hz") or []
    lines = [
        "%s" % measurements.get("file", "<buffer>"),
        "  %.2f s at %d Hz   peak %.2f   rms %.1f dB" % (
            measurements.get("duration_seconds", 0.0),
            measurements.get("sample_rate", 0),
            measurements.get("peak", 0.0),
            measurements.get("rms_db", -120.0)),
        "  F0        mean %.1f Hz   median %.1f Hz   p5-p95 %.1f-%.1f Hz   range %.1f st" % (
            measurements.get("f0_mean_hz", 0.0),
            measurements.get("f0_median_hz", 0.0),
            measurements.get("f0_p5_hz", 0.0),
            measurements.get("f0_p95_hz", 0.0),
            measurements.get("f0_semitone_range", 0.0)),
        "  voicing   %.2f of frames   %d voiced frames   %d glottal cycles" % (
            measurements.get("voicing_ratio", 0.0),
            measurements.get("voiced_frames", 0),
            measurements.get("glottal_cycles", 0)),
        "  formants  %s Hz" % " / ".join("%.0f" % value for value in formants),
        "  bandwidth %s Hz" % " / ".join("%.0f" % value for value in bandwidths),
        "  source    HNR %.1f dB   jitter %.2f%%   shimmer %.2f%%   tilt %.1f dB/oct" % (
            measurements.get("hnr_db", 0.0),
            measurements.get("jitter_percent", 0.0),
            measurements.get("shimmer_percent", 0.0),
            measurements.get("spectral_tilt_db_per_oct", 0.0)),
        "  derived   breathiness %.2f (from HNR)" % hnr_to_breathiness(
            measurements.get("hnr_db", 0.0)),
    ]
    return "\n".join(lines)


def format_aggregate(aggregate_result, per_file=None, show_files=8):
    """Human-readable summary of a directory of measurements."""
    if not aggregate_result.get("usable"):
        return "no usable files (%d scanned)" % aggregate_result.get("files", 0)
    formants = aggregate_result.get("formants_hz") or []
    bandwidths = aggregate_result.get("formant_bandwidths_hz") or []
    lines = [
        "%d files, %d usable, %.1f s of audio" % (
            aggregate_result["files"], aggregate_result["usable"],
            aggregate_result.get("audio_seconds", 0.0)),
        "  F0        mean %.1f Hz   median %.1f Hz   floor %.1f   ceiling %.1f   range %.1f st" % (
            aggregate_result.get("f0_mean_hz", 0.0),
            aggregate_result.get("f0_median_hz", 0.0),
            aggregate_result.get("f0_floor_hz", 0.0),
            aggregate_result.get("f0_ceiling_hz", 0.0),
            aggregate_result.get("f0_semitone_range", 0.0)),
        "  voicing   %.2f" % aggregate_result.get("voicing_ratio", 0.0),
        "  formants  %s Hz (median)" % " / ".join("%.0f" % v for v in formants),
        "  bandwidth %s Hz" % " / ".join("%.0f" % v for v in bandwidths),
        "  source    HNR %.1f dB   jitter %.2f%%   shimmer %.2f%%   tilt %.1f dB/oct" % (
            aggregate_result.get("hnr_db", 0.0),
            aggregate_result.get("jitter_percent", 0.0),
            aggregate_result.get("shimmer_percent", 0.0),
            aggregate_result.get("spectral_tilt_db_per_oct", 0.0)),
        "  level     rms %.1f dB" % aggregate_result.get("rms_db", -120.0),
        "  derived   breathiness %.2f" % hnr_to_breathiness(
            aggregate_result.get("hnr_db", 0.0)),
    ]
    if per_file:
        lines.append("  worst files:")
        ranked = sorted(
            (item for item in per_file if "error" not in item),
            key=lambda item: item.get("voicing_ratio", 0.0))[:show_files]
        for item in ranked:
            lines.append("    %-44s voicing %.2f  F0 %.1f Hz" % (
                os.path.basename(str(item.get("file", "?")))[:44],
                item.get("voicing_ratio", 0.0), item.get("f0_mean_hz", 0.0)))
    return "\n".join(lines)


def aggregate(measurements):
    """Combine per-file measurements into corpus-level statistics."""
    usable = [item for item in measurements if "error" not in item
              and item.get("f0_mean_hz", 0) > 0]
    if not usable:
        return {"files": len(measurements), "usable": 0}

    def collect(key):
        return [item[key] for item in usable if isinstance(item.get(key), (int, float))]

    f0_values = collect("f0_mean_hz")
    formants = []
    bandwidths = []
    for i in range(FORMANT_COUNT):
        column = [item["formants_hz"][i] for item in usable
                  if len(item.get("formants_hz", [])) > i and item["formants_hz"][i] > 0]
        formants.append(round(median(column), 1) if column else 0.0)
        column = [item["formant_bandwidths_hz"][i] for item in usable
                  if len(item.get("formant_bandwidths_hz", [])) > i
                  and item["formant_bandwidths_hz"][i] > 0]
        bandwidths.append(round(median(column), 1) if column else 0.0)

    return {
        "files": len(measurements),
        "usable": len(usable),
        "audio_seconds": round(sum(collect("duration_seconds")), 3),
        "voicing_ratio": round(sum(collect("voicing_ratio")) / len(usable), 4),
        "f0_mean_hz": round(sum(f0_values) / len(f0_values), 2),
        "f0_median_hz": round(median(f0_values), 2),
        "f0_p5_hz": round(median(collect("f0_p5_hz")), 2),
        "f0_p95_hz": round(median(collect("f0_p95_hz")), 2),
        "f0_floor_hz": round(percentile(collect("f0_min_hz"), 0.05), 2),
        "f0_ceiling_hz": round(percentile(collect("f0_max_hz"), 0.95), 2),
        "f0_semitone_range": round(median(collect("f0_semitone_range")), 2),
        "hnr_db": round(median(collect("hnr_db")), 2),
        "jitter_percent": round(median(collect("jitter_percent")), 3),
        "shimmer_percent": round(median(collect("shimmer_percent")), 3),
        "spectral_tilt_db_per_oct": round(median(collect("spectral_tilt_db_per_oct")), 2),
        "rms_db": round(median(collect("rms_db")), 2),
        "formants_hz": formants,
        "formant_bandwidths_hz": bandwidths,
    }


# ---------------------------------------------------------------------------
# Measurements -> voiceprint
# ---------------------------------------------------------------------------
# The breathiness knob reaches the signal through aspiration_db, the cascade and
# the radiation differentiator, so the knob-to-HNR relation is not the simple
# 20 dB rule of thumb. CALIBRATION is measured by rendering the knob sweep and
# measuring the result -- the same round trip that validates every other field.
# Measured with calibrate_breathiness() on this engine at 24 kHz: a sustained
# AX with vibrato, jitter, shimmer and pitch drift disabled, so the reading is
# stationary. Below ~0.22 the signal is at the measurement floor (~19 dB HNR,
# set by residual peak-position error); above ~0.6 the pitch tracker starts
# losing cycles in the noise and the reading is no longer trustworthy, so the
# usable, verifiable span of the knob is 0..0.6.
CALIBRATION = {
    "breathiness_hnr": [(0.0, 19.1), (0.22, 19.0), (0.35, 9.1), (0.5, 0.2),
                        (0.6, -1.2)],
    "measured_by": "analyze.calibrate_breathiness (sustained AX, 24 kHz)",
    "floor_db": 19.1,
    "usable_max": 0.6,
}


def hnr_to_breathiness(hnr_db, table=None):
    """Map a measured HNR onto the breathiness knob using the measured curve.

    Interpolates :data:`CALIBRATION` and clamps, so a measurement can never push
    the knob outside its validated range. Pass a fresh table (from
    :func:`calibrate_breathiness`) to recalibrate for a different sample rate or
    a modified source model.
    """
    table = table or CALIBRATION["breathiness_hnr"]
    ordered = sorted(table, key=lambda pair: -pair[1])  # descending HNR
    if hnr_db >= ordered[0][1]:
        return ordered[0][0]
    if hnr_db <= ordered[-1][1]:
        return ordered[-1][0]
    for i in range(len(ordered) - 1):
        knob_high, hnr_high = ordered[i]
        knob_low, hnr_low = ordered[i + 1]
        if hnr_low <= hnr_db <= hnr_high:
            if hnr_high == hnr_low:
                return knob_high
            fraction = (hnr_high - hnr_db) / (hnr_high - hnr_low)
            value = knob_high + fraction * (knob_low - knob_high)
            return max(0.0, min(1.0, value))
    return 0.0


def calibrate_breathiness(seconds=0.5, sample_rate=None, verbose=False):
    """Render a breathiness sweep and measure the HNR each setting produces.

    Returns ``[(breathiness, hnr_db), ...]`` -- a calibration table for
    :func:`hnr_to_breathiness`. This is how the knob is made to mean something:
    the relationship is measured from this engine, not assumed from a textbook.
    """
    from .synth import render_score
    from .phones import formant_targets
    from .voiceprint import VoicePrint

    settings = [0.0, 0.1, 0.22, 0.35, 0.5, 0.7, 0.85, 1.0]
    targets, bandwidths = formant_targets("AX")
    table = []
    for value in settings:
        # Vibrato must be off for a stationary measurement: a 24-cent, 5.1 Hz
        # modulation makes successive cycles differ in length by up to 4
        # samples, which smears any pitch-synchronous average and caps the
        # measurable HNR near 18 dB no matter how clean the source is.
        voiceprint = VoicePrint.default().merged({
            "source": {"breathiness": value, "jitter_percent": 0.0,
                       "shimmer_percent": 0.0, "vibrato_depth_cents": 0.0,
                       "pitch_drift_cents_per_sec": 0.0},
            "output": {"sample_rate": sample_rate} if sample_rate else {},
            "random_seed": 7,
        })
        score = {
            "phones": [{"sym": "AX", "start": 0.05, "dur": seconds, "stress": 1,
                        "amp": 1.0, "voiced": True, "f": list(targets),
                        "bw": list(bandwidths), "manner": "vowel",
                        "word": 0, "syll": 0}],
            "pauses": [],
            "contours": {"f0": [[0.0, 0.0], [seconds + 0.2, 0.0]],
                         "amp": [[0.0, 0.0], [seconds + 0.2, 0.0]]},
            "duration": seconds + 0.15,
        }
        rendered_rate, samples = render_score(score, voiceprint)
        measurements = analyze_samples(samples, rendered_rate)
        table.append((value, round(measurements["hnr_db"], 2)))
        if verbose:
            print("  breathiness %.2f -> HNR %6.1f dB (f0 %.1f, cycles %d)"
                  % (value, measurements["hnr_db"], measurements["f0_mean_hz"],
                     measurements["pitch_sync_cycles"]))
    return table


def tilt_from_spectrum(tilt_db, radiation_db=6.0):
    """Separate the measured overall slope into source and tract tilt.

    The measured slope includes the -12 dB/oct-ish glottal rolloff and the
    +6 dB/oct radiation. What is left over is attributed to the tract shelf,
    clamped to the voiceprint range.
    """
    residual = tilt_db + 12.0 - radiation_db
    return max(-24.0, min(12.0, residual * 0.5))


def voiceprint_from_measurements(measurements, base=None, name=None,
                                 reference=None):
    """Build a voiceprint whose acoustic fields come from real measurements."""
    voiceprint = VoicePrint(base.data if isinstance(base, VoicePrint) else (base or {}))
    data = voiceprint.data
    measured = []

    def put(path, value, condition=True):
        if condition and value is not None:
            voiceprint.set(path, value)
            measured.append(path)

    f0 = measurements.get("f0_mean_hz") or measurements.get("f0_median_hz")
    put("source.f0_mean_hz", float(f0), bool(f0))
    put("source.f0_floor_hz", float(measurements["f0_floor_hz"]),
        measurements.get("f0_floor_hz"))
    put("source.f0_ceiling_hz", float(measurements["f0_ceiling_hz"]),
        measurements.get("f0_ceiling_hz"))
    put("source.f0_semitone_range", float(measurements["f0_semitone_range"]),
        measurements.get("f0_semitone_range"))
    put("source.jitter_percent", float(measurements["jitter_percent"]),
        measurements.get("jitter_percent") is not None)
    put("source.shimmer_percent", float(measurements["shimmer_percent"]),
        measurements.get("shimmer_percent") is not None)
    hnr = measurements.get("hnr_db")
    if hnr is not None:
        put("source.breathiness", round(hnr_to_breathiness(float(hnr)), 3))
        # Breathier voices carry more aspiration noise in the source model.
        put("source.aspiration_db",
            round(max(-80.0, min(-6.0, -33.0 + 22.0 * (1.0 - hnr_to_breathiness(float(hnr))) * -1
                        + 14.0 * hnr_to_breathiness(float(hnr)))), 2))
    tilt = measurements.get("spectral_tilt_db_per_oct")
    if tilt is not None:
        put("tract.spectral_tilt_db_per_oct",
            round(tilt_from_spectrum(float(tilt)), 2))

    formants = measurements.get("formants_hz") or []
    bandwidths = measurements.get("formant_bandwidths_hz") or []
    if len(formants) >= 3 and all(formants[:3]):
        # Express the measured vowel space as a scale on the articulatory
        # defaults: that keeps the phone table meaningful while matching the
        # speaker's tract length and vowel-space size.
        from .phones import PHONES

        reference_f = [PHONES["AX"]["f"][i] for i in range(min(5, len(formants)))]
        scales = {}
        for i, value in enumerate(formants[:5]):
            if i < len(reference_f) and reference_f[i] > 0 and value > 0:
                scales["f%d" % (i + 1)] = round(
                    max(0.4, min(2.5, value / reference_f[i])), 4)
        if scales:
            data["tract"]["formant_scale"] = scales
            measured.append("tract.formant_scale")
        average_scale = sum(scales.values()) / len(scales) if scales else 1.0
        put("tract.length_scale", round(max(0.6, min(1.8, average_scale)), 4))
    if len(bandwidths) >= 3 and all(bandwidths[:3]):
        reference_bw = [100.0, 120.0, 160.0]
        ratios = [bandwidths[i] / reference_bw[i] for i in range(3)
                  if bandwidths[i] > 0]
        if ratios:
            put("tract.bandwidth_scale", round(max(0.4, min(3.0,
                sum(ratios) / len(ratios))), 3))

    if measurements.get("voicing_ratio") is not None:
        put("source.voicing_hysteresis",
            round(max(0.0, min(1.0, 1.0 - float(measurements["voicing_ratio"]))), 3))

    data["provenance"] = {
        "kind": "measured" if measured else "seed",
        "measured_fields": sorted(measured),
        "audio_seconds": float(measurements.get("audio_seconds")
                               or measurements.get("duration_seconds") or 0.0),
        "clips": int(measurements.get("files") or 1),
        "reference": reference or ([measurements["file"]]
                                   if measurements.get("file") else []),
        "measurements": {key: value for key, value in measurements.items()
                         if key != "file"},
    }
    if name:
        data["name"] = name
    voiceprint.clamp_derived()
    voiceprint.validate()
    return voiceprint


# ---------------------------------------------------------------------------
# Verification: does the synthesiser honour its own voiceprint?
# ---------------------------------------------------------------------------
CALIBRATION_LINE = "Oh, a fellow villager! Hmm. Would you like to trade emeralds?"


def round_trip_report(voiceprint=None, line=CALIBRATION_LINE, sample_rate=None):
    """Synthesise a calibration line, measure it, and report the error.

    This is the honest check on the control surface: if a knob says F0 is
    116 Hz and the measurement says 118 Hz, the surface is telling the truth.
    """
    from .prosody import build_score
    from .synth import render_score

    voiceprint = voiceprint or VoicePrint.default()
    effective = voiceprint.effective()
    score = build_score(line, voiceprint)
    if sample_rate:
        score["sample_rate"] = int(sample_rate)
    rendered_rate, samples = render_score(score, voiceprint)
    measurements = analyze_samples(samples, rendered_rate)

    wanted_f0 = float(effective.get("source.f0_mean_hz"))
    got_f0 = float(measurements.get("f0_mean_hz") or 0.0)
    wanted_jitter = float(effective.get("source.jitter_percent"))
    got_jitter = float(measurements.get("jitter_percent") or 0.0)
    wanted_breath = float(effective.get("source.breathiness"))
    got_breath = hnr_to_breathiness(float(measurements.get("hnr_db") or 20.0))
    wanted_tilt = float(effective.get("tract.spectral_tilt_db_per_oct"))
    got_tilt = float(measurements.get("spectral_tilt_db_per_oct") or 0.0)

    scale = effective.get("tract.formant_scale") or {}
    length = float(effective.get("tract.length_scale", 1.0))
    formant_error = []
    for i, measured_value in enumerate(measurements.get("formants_hz") or []):
        if i >= 3 or not measured_value:
            continue
        factor = length * float(scale.get("f%d" % (i + 1), 1.0))
        from .phones import PHONES

        wanted_value = PHONES["AX"]["f"][i] * factor
        formant_error.append((wanted_value, measured_value,
                              100.0 * abs(measured_value - wanted_value) /
                              max(1.0, wanted_value)))

    return {
        "line": line,
        "sample_rate": rendered_rate,
        "seconds": round(len(samples) / float(rendered_rate), 3),
        "measurements": measurements,
        "comparison": {
            "f0_mean_hz": {"voiceprint": round(wanted_f0, 2),
                           "measured": round(got_f0, 2),
                           "error_percent": round(
                               100.0 * abs(got_f0 - wanted_f0) / max(1.0, wanted_f0), 2)},
            "jitter_percent": {"voiceprint": round(wanted_jitter, 3),
                               "measured": round(got_jitter, 3)},
            "breathiness": {"voiceprint": round(wanted_breath, 3),
                            "measured": round(got_breath, 3)},
            "spectral_tilt_db_per_oct": {"voiceprint": round(wanted_tilt, 2),
                                         "measured": round(got_tilt, 2)},
            "formants_hz": [{"index": i + 1, "expected": round(a, 1),
                             "measured": round(b, 1), "error_percent": round(c, 1)}
                            for i, (a, b, c) in enumerate(formant_error)],
        },
    }


def format_report(report):
    comparison = report["comparison"]
    lines = [
        "round trip: %r" % report["line"],
        "  rendered %.2f s at %d Hz" % (report["seconds"], report["sample_rate"]),
        "  F0      voiceprint %.1f Hz   measured %.1f Hz   error %.1f%%"
        % (comparison["f0_mean_hz"]["voiceprint"],
           comparison["f0_mean_hz"]["measured"],
           comparison["f0_mean_hz"]["error_percent"]),
        "  jitter  voiceprint %.2f%%      measured %.2f%%"
        % (comparison["jitter_percent"]["voiceprint"],
           comparison["jitter_percent"]["measured"]),
        "  breath  voiceprint %.2f        measured %.2f"
        % (comparison["breathiness"]["voiceprint"],
           comparison["breathiness"]["measured"]),
        "  tilt    voiceprint %.1f dB/oct measured %.1f dB/oct"
        % (comparison["spectral_tilt_db_per_oct"]["voiceprint"],
           comparison["spectral_tilt_db_per_oct"]["measured"]),
    ]
    for row in comparison["formants_hz"]:
        lines.append("  F%d      expected %7.1f Hz   measured %7.1f Hz   error %4.1f%%"
                     % (row["index"], row["expected"], row["measured"],
                        row["error_percent"]))
    measurements = report["measurements"]
    lines.append("  voicing ratio %.2f   HNR %.1f dB   shimmer %.1f%%   rms %.1f dB"
                 % (measurements["voicing_ratio"], measurements["hnr_db"],
                    measurements["shimmer_percent"], measurements["rms_db"]))
    return "\n".join(lines)


if __name__ == "__main__":
    print(format_report(round_trip_report()))
