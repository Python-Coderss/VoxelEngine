#!/usr/bin/env python3
"""Per-window diagnostic probe for villager voice WAVs.

For each 50 ms window prints: time, RMS(dB), dominant pitch (FFT peak in the
voicing range), and spectral centroid. Also prints harmonic amplitudes of the
steadiest voiced window so the waveform texture (pure sine vs rich harmonics)
can be compared between generated and reference clips.

Usage:
    python tools/probe_wav.py file.wav [window_ms]
"""
import sys
import wave
import numpy as np


def load_wav(path):
    with wave.open(path, "rb") as w:
        channels = w.getnchannels()
        rate = w.getframerate()
        width = w.getsampwidth()
        frames = w.readframes(w.getnframes())
    if width == 2:
        data = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    else:
        raise ValueError("unsupported sample width %d" % width)
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return rate, data


def fft_pitch(seg, rate):
    n = len(seg)
    windowed = seg * np.hanning(n)
    spec = np.abs(np.fft.rfft(windowed))
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    lo, hi = 60, 500
    band = (freqs >= lo) & (freqs <= hi)
    if not band.any():
        return 0.0
    return float(freqs[band][np.argmax(spec[band])])


def centroid(seg, rate):
    n = len(seg)
    spec = np.abs(np.fft.rfft(seg * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    total = spec.sum()
    if total <= 0:
        return 0.0
    return float((freqs * spec).sum() / total)


def harmonics(seg, rate, f0, count=12):
    n = len(seg)
    spec = np.abs(np.fft.rfft(seg * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    out = []
    for k in range(1, count + 1):
        fk = k * f0
        band = (freqs >= fk * 0.92) & (freqs <= fk * 1.08)
        peak = spec[band].max() if band.any() else 0.0
        out.append(peak)
    if max(out) <= 0:
        return out
    db = [20.0 * np.log10(v / max(out) + 1e-9) for v in out]
    return db


def main():
    path = sys.argv[1]
    win_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    rate, data = load_wav(path)
    win = int(rate * win_ms / 1000)
    hop = win // 2

    print("=== %s  (%.2fs, sr=%d) ===" % (path, len(data) / rate, rate))
    print("  %6s %7s %7s %7s" % ("time", "rmsdB", "pitch", "centr"))
    best = None
    best_energy = -1
    for start in range(0, len(data) - win, hop):
        seg = data[start:start + win]
        rms = 20.0 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-12)
        p = fft_pitch(seg, rate)
        c = centroid(seg, rate)
        if rms > best_energy and p > 60:
            best_energy = rms
            best = (start, p)
        print("  %6.2f %7.1f %7.1f %7.0f" % (start / rate, rms, p, c))

    if best:
        start, p = best
        seg = data[start:start + win]
        db = harmonics(seg, rate, p)
        label = ", ".join("%dh:%+.0f" % (k, v) for k, v in enumerate(db[1:6], 1))
        print("  steadiest voiced @ %.2fs pitch %.1fHz: harmonics %s ..." %
              (start / rate, p, label))


if __name__ == "__main__":
    main()
