#!/usr/bin/env python3
"""Compare exact WAV data between the Java and Python voice pipelines.

Dumps (already produced by the Java StageProbe and the Python reference):

  dev/voice-comparison/{java,python}/            final 24 kHz PCM_16 WAVs
  dev/voice-comparison/java-base/                Java base TTS WAV (22.05 kHz)
  dev/voice-comparison/python-base/              Python base TTS WAV (22.05 kHz)

For every line it reports, per stage:

  * duration / active-span / trailing-silence structure
  * peak and RMS loudness
  * best cross-correlation lag and normalized max absolute difference after
    alignment, so a near-identical pipeline shows high correlation and a tiny
    residual diff, while a misaligned crop shows a large lag.

Python is only used here as an offline comparison reference, never by the game.
"""

from __future__ import annotations

import pathlib
import sys

import numpy as np
import soundfile as sf

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "dev" / "voice-comparison"

LINES = [
    ("hello", "Hello"),
    ("no", "No!!!"),
    ("what_are_you_doing", "What are you doing"),
    ("i_am_haggling_you", "I am haggling you"),
    ("aha_that_seems_like_a_deal_that_will_work_for_both_of_us",
     "Aha that seems like a deal that will work for both of us"),
]


def load(path: pathlib.Path) -> tuple[int, np.ndarray]:
    data, sr = sf.read(str(path), dtype="float32")
    return int(sr), data


def active_span(x: np.ndarray, sr: int, threshold: float = 0.002) -> tuple[int, int, int]:
    magnitude = np.abs(x)
    nonzero = np.where(magnitude > threshold)[0]
    if len(nonzero) == 0:
        return len(x), 0, 0
    lead = int(nonzero[0])
    trail = int(len(x) - nonzero[-1] - 1)
    return len(x), lead, trail


def align(a: np.ndarray, b: np.ndarray, max_lag: int = 4000) -> tuple[int, float]:
    """Return (lag, max-normalized-diff) aligning b onto a by best correlation."""
    best_lag, best_corr = 0, -1.0
    step = 100
    for lag in range(-max_lag, max_lag + 1, step):
        corr = correlate_at(a, b, lag)
        if corr > best_corr:
            best_corr, best_lag = corr, lag
    return best_lag, best_corr


def correlate_at(a: np.ndarray, b: np.ndarray, lag: int) -> float:
    if lag >= 0:
        x, y = a[lag:], b[: len(a) - lag]
    else:
        x, y = a[: len(b) + lag], b[-lag:]
    length = min(len(x), len(y))
    if length < 64:
        return -1.0
    x, y = x[:length], y[:length]
    x = x - x.mean()
    y = y - y.mean()
    denom = np.sqrt((x * x).sum() * (y * y).sum())
    return float((x * y).sum() / denom) if denom > 1e-9 else 0.0


def aligned_diff(a: np.ndarray, b: np.ndarray, lag: int) -> float:
    if lag >= 0:
        x, y = a[lag:], b[: len(a) - lag]
    else:
        x, y = a[: len(b) + lag], b[-lag:]
    length = min(len(x), len(y))
    if length <= 0:
        return float("nan")
    x, y = x[:length], y[:length]
    peak = max(float(np.abs(x).max()), float(np.abs(y).max()), 1e-9)
    return float(np.abs(x - y).max() / peak)


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return x
    from scipy import signal

    g = int(np.gcd(src, dst))
    return signal.resample_poly(x, dst // g, src // g).astype(np.float32)


def compare_line(fid: str, text: str) -> None:
    print(f"### {fid}: {text!r}")
    java_final = BASE / "java" / f"{fid}.wav"
    python_final = BASE / "python" / f"{fid}.wav"
    java_base = BASE / "java-base" / f"{fid}.wav"
    python_base = BASE / "python-base" / f"{fid}.wav"

    for name, jp, pp, expect_sr in [
        ("BASE", java_base, python_base, 22050),
        ("FINAL", java_final, python_final, 24000),
    ]:
        if not jp.is_file() or not pp.is_file():
            print(f"  {name}: missing dump ({jp.name if not jp.is_file() else pp.name})")
            continue
        jsr, jx = load(jp)
        psr, px = load(pp)
        jx = resample(jx, jsr, expect_sr)
        px = resample(px, psr, expect_sr)
        j_total, j_lead, j_trail = active_span(jx, expect_sr)
        p_total, p_lead, p_trail = active_span(px, expect_sr)
        j_rms = float(20 * np.log10(np.sqrt(np.mean(jx * jx)) + 1e-12))
        p_rms = float(20 * np.log10(np.sqrt(np.mean(px * px)) + 1e-12))
        lag, corr = align(jx, px)
        diff = aligned_diff(jx, px, lag)
        print(f"  {name:<5} java {j_total / expect_sr:6.3f}s "
              f"(active {(j_total - j_lead - j_trail) / expect_sr:5.3f}s, "
              f"trail {j_trail / expect_sr:5.3f}s, peak {float(np.abs(jx).max()):.3f}, "
              f"rms {j_rms:5.1f}dB)")
        print(f"       python {p_total / expect_sr:6.3f}s "
              f"(active {(p_total - p_lead - p_trail) / expect_sr:5.3f}s, "
              f"trail {p_trail / expect_sr:5.3f}s, peak {float(np.abs(px).max()):.3f}, "
              f"rms {p_rms:5.1f}dB)")
        print(f"       corr={corr:.3f} lag={lag} aligned_maxdiff={diff:.3f}")
    print()


def main() -> None:
    print("NOTE: Coqui VITS uses a stochastic duration predictor (SDP, "
          "noise_scale_dp=0.8) and decoder noise (noise_scale=0.667).")
    print("Two consecutive Python runs of the same text are NOT sample-identical "
          "(measured self-correlation ~0.12-0.15 with different durations).")
    print("The comparison below therefore validates pipeline equivalence "
          "(model graph, tokens, speaker, scales, silence padding, crop, "
          "sample rates, peak ceiling), not bit-identical output.\n")
    for fid, text in LINES:
        compare_line(fid, text)
    print("done")


if __name__ == "__main__":
    sys.exit(main())
