#!/usr/bin/env python3
"""Diagnostic tool for the villager voice WAVs.

Prints duration / RMS / peak / zero-crossing / spectral stats for each input
WAV and writes a spectrogram PNG next to it (foo.wav -> foo.spectrogram.png).

Usage:
    python tools/analyze_wav.py file1.wav file2.wav ...
"""
import sys
import wave
import numpy as np

try:
    from scipy import signal
except ImportError:
    signal = None


def load_wav(path):
    with wave.open(path, "rb") as w:
        channels = w.getnchannels()
        rate = w.getframerate()
        width = w.getsampwidth()
        frames = w.readframes(w.getnframes())
    if width == 2:
        data = np.frombuffer(frames, dtype=np.int16)
    elif width == 1:
        data = (np.frombuffer(frames, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
        return rate, data, channels, width
    else:
        raise ValueError("unsupported sample width %d" % width)
    samples = data.astype(np.float32) / 32768.0
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    return rate, samples, channels, width


def rms_db(samples):
    rms = np.sqrt(np.mean(samples ** 2)) if len(samples) else 0.0
    return 20.0 * np.log10(rms + 1e-12)


def zero_crossing_rate(samples, rate):
    if len(samples) < 2:
        return 0.0
    crossings = np.sum(np.abs(np.diff(np.signbit(samples).astype(np.int8))))
    return crossings / max(1.0, len(samples)) * rate  # per second


def pitch_estimate(samples, rate):
    """Median autocorrelation pitch over voiced windows (rough diagnostic)."""
    if len(samples) < rate // 20:
        return 0.0
    window = int(rate * 0.03)
    hop = window // 2
    pitches = []
    min_lag, max_lag = rate // 500, rate // 60
    for start in range(0, len(samples) - window, hop):
        seg = samples[start:start + window]
        seg = seg - seg.mean()
        energy = np.sqrt(np.sum(seg ** 2))
        if energy < 0.01:
            continue
        corr = np.correlate(seg, seg, "full")[len(seg) - 1:]
        corr /= corr[0] + 1e-12
        lag = min_lag + int(np.argmax(corr[min_lag:max_lag]))
        if corr[lag] > 0.6:
            pitches.append(rate / lag)
    if not pitches:
        return 0.0
    return float(np.median(pitches))


def spectral_stats(samples, rate):
    if len(samples) < 512:
        return 0.0, 0.0
    window = int(rate * 0.03)
    window = max(512, window)
    if signal is None:
        return 0.0, 0.0
    freqs, times, spec = signal.spectrogram(
        samples, rate, window=signal.windows.hann(window), nperseg=window,
        noverlap=window // 2)
    power = np.abs(spec)
    total = power.sum(axis=0).sum()
    if total <= 0:
        return 0.0, 0.0
    centroid = float((freqs[:, None] * power).sum() / total)
    # Fraction of energy below 500 Hz (body vs buzzy top)
    low = float(power[freqs < 500].sum() / total)
    return centroid, low


def spectrogram_png(path, rate, samples, out_path):
    if signal is None:
        return
    fig, ax = plt.subplots(figsize=(10, 3.2), dpi=110)
    window = int(rate * 0.03)
    window = max(512, window)
    freqs, times, spec = signal.spectrogram(
        samples, rate, window=signal.windows.hann(window), nperseg=window,
        noverlap=window // 2, mode="magnitude")
    db = 20.0 * np.log10(np.abs(spec) + 1e-9)
    db = np.clip(db, -90, 0)
    ax.pcolormesh(times, freqs, db, shading="auto", cmap="magma", vmin=-90, vmax=-10)
    ax.set_ylim(0, 6000)
    ax.set_xlabel("time (s)")
    ax.set_ylabel("Hz")
    ax.set_title("%s  (%ds, sr=%d)" % (path, len(samples) / rate, rate))
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)


def main(args):
    if not args:
        print(__doc__)
        return 1
    global plt
    from matplotlib import pyplot as plt
    for path in args:
        rate, samples, channels, width = load_wav(path)
        if len(samples) == 0:
            print("%s: EMPTY" % path)
            continue
        centroid, low_energy = spectral_stats(samples, rate)
        print("=== %s ===" % path)
        print("  duration %.2fs  rate %d  channels %d  width %dB" %
              (len(samples) / rate, rate, channels, width))
        print("  peak %.3f  rms %.1fdB  zcr %.0f/s" %
              (np.max(np.abs(samples)), rms_db(samples), zero_crossing_rate(samples, rate)))
        print("  pitch ~%.1fHz  centroid ~%.0fHz  energy<500Hz %.0f%%" %
              (pitch_estimate(samples, rate), centroid, low_energy * 100.0))
        out_png = path.rsplit(".", 1)[0] + ".spectrogram.png"
        try:
            spectrogram_png(path, rate, samples, out_png)
            print("  spectrogram -> %s" % out_png)
        except Exception as exc:
            print("  spectrogram failed: %s" % exc)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
