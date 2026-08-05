#!/usr/bin/env python3
"""Build dev/voice-samples/index.html from the rendered WAV files.

The page inlines every WAV as base64 (no fetch -> works from file://), draws a
log-frequency spectrogram for each clip with an F0 (pitch) contour overlay, and
syncs a playhead + click-to-seek.

In the "same line, two voices" section the unchanged reference / neural
spectrograms are aligned: both share the same seconds-per-pixel, their speech
onsets are lined up (leading/trailing silence is only cropped in the view),
actual duration and onset deltas are reported, and a Sync-play button starts
both at their aligned positions.

Run after tools/render_voice_samples.sh has produced the WAVs.
"""
import base64
import glob
import json
import os
import sys
import wave

import numpy as np

OUT = "dev/voice-samples"


def b64(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


def read_mono_wav(path):
    with wave.open(path, "rb") as wav:
        channels = wav.getnchannels()
        rate = wav.getframerate()
        if wav.getsampwidth() != 2:
            raise ValueError("expected 16-bit WAV: " + path)
        data = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.int16)
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return rate, data.astype(np.float32) / 32768.0


def active_span(samples, rate):
    if len(samples) == 0:
        return 0.0, 0.0
    window = max(1, rate // 100)
    energy = np.convolve(samples * samples, np.ones(window, dtype=np.float32), mode="same")
    peak = float(energy.max())
    if peak <= 1.0e-10:
        return 0.0, len(samples) / rate
    active = np.flatnonzero(energy > peak * 0.10)
    if len(active) == 0:
        return 0.0, len(samples) / rate
    start = max(0, int(active[0]) - window) / rate
    end = min(len(samples), int(active[-1]) + window) / rate
    return start, end


def low_band_metrics(reference_path, neural_path):
    """Compare normalized low-band energy over active speech, not silence."""
    if not (os.path.exists(reference_path) and os.path.exists(neural_path)):
        return None

    def band_fractions(samples, rate):
        start, end = active_span(samples, rate)
        first = max(0, int(round(start * rate)))
        last = min(len(samples), int(round(end * rate)))
        active = samples[first:last]
        if len(active) < 32:
            return [0.0] * 4
        nperseg = min(2048, len(active))
        bands = ((40, 150), (150, 300), (300, 500), (500, 1000))
        accumulated = np.zeros(len(bands), dtype=np.float64)
        total = 0.0
        hop = max(1, nperseg // 2)
        window = np.hanning(nperseg)
        for start in range(0, max(1, len(active) - nperseg + 1), hop):
            frame = active[start:start + nperseg]
            if len(frame) < nperseg:
                frame = np.pad(frame, (0, nperseg - len(frame)))
            freqs = np.fft.rfftfreq(nperseg, 1.0 / rate)
            power = np.abs(np.fft.rfft(frame * window)) ** 2
            total += float(power.sum())
            for index, (low, high) in enumerate(bands):
                accumulated[index] += float(power[(freqs >= low) & (freqs < high)].sum())
        total = max(total, 1.0e-12)
        return [float(value / total) for value in accumulated]

    rr, ref = read_mono_wav(reference_path)
    nr, neural = read_mono_wav(neural_path)
    reference_bands = band_fractions(ref, rr)
    neural_bands = band_fractions(neural, nr)
    labels = ("40-150 Hz", "150-300 Hz", "300-500 Hz", "500-1000 Hz")
    return [{
        "range": label,
        "reference": reference_bands[index],
        "neural": neural_bands[index],
        "relativePercent": abs(neural_bands[index] - reference_bands[index])
            / max(reference_bands[index], 1.0e-12) * 100.0,
    } for index, label in enumerate(labels)]


def timing_metrics(reference_path, neural_path, aligned_path):
    if not (os.path.exists(reference_path) and os.path.exists(aligned_path)):
        return None
    rr, ref = read_mono_wav(reference_path)
    nr, neural = read_mono_wav(neural_path)
    ar, aligned = read_mono_wav(aligned_path)
    ref_start, ref_end = active_span(ref, rr)
    raw_start, raw_end = active_span(neural, nr)
    aligned_start, aligned_end = active_span(aligned, ar)
    ref_duration = max(1.0e-6, ref_end - ref_start)
    aligned_span = aligned_end - aligned_start
    error = max(abs(aligned_start - ref_start) / ref_duration,
                abs(aligned_span - ref_duration) / ref_duration)
    return {
        "rawOnsetDelta": raw_start - ref_start,
        "rawSpanDelta": (raw_end - raw_start) - ref_duration,
        "alignedOnsetDelta": aligned_start - ref_start,
        "alignedSpanDelta": aligned_span - ref_duration,
        "alignedError": error,
        "alignedPercent": error * 100.0,
        "status": "PASS ≤5%" if error <= 0.05 else "CHECK >5%",
    }


def main():
    compare, presets, corpus = [], [], []

    for gen in sorted(glob.glob(os.path.join(OUT, "gen", "*_neural.wav"))):
        stem = os.path.basename(gen)[: -len("_neural.wav")]
        ref = os.path.join(OUT, "ref", stem + ".wav")
        aligned = os.path.join(OUT, "gen", stem + "_neural_aligned.wav")
        item = {
            "id": stem,
            "label": stem.replace("_", " "),
            "neural": b64(gen),
        }
        if os.path.exists(ref):
            item["ref"] = b64(ref)
        if os.path.exists(aligned):
            item["aligned"] = b64(aligned)
        item["metrics"] = timing_metrics(ref, gen, aligned)
        item["lowBands"] = low_band_metrics(ref, gen)
        compare.append(item)

    preset_meta = {
        "01_default_spoken": (
            "New default: neutral spoken, no vibrato, 36% natural body",
            'villager.voice.Main "The shop is open. Let us make a fair deal."'),
        "02_daytime_happy": (
            "Daytime friendly (context emotion: happy)",
            'villager.voice.Main "A good morning for planting, hmm." --emotion happy'),
        "03_night_neutral": (
            "Night calm (context emotion: neutral)",
            'villager.voice.Main "It is late. You should find a bed, hmm."'),
        "04_angry_no": (
            "Angry delivery",
            'villager.voice.Main "No!!!" --emotion angry'),
        "05_singing_profile": (
            "Singing profile: happy + 100% singing (subtle +-7-cent vibrato)",
            'villager.voice.Main "Aha that seems like a deal that will work for both of us" '
            "--emotion happy --singing 1.0"),
    }
    for wav in sorted(glob.glob(os.path.join(OUT, "[0-9]*.wav"))):
        base = os.path.basename(wav)[:-4]
        label, cmd = preset_meta.get(base, (base.replace("_", " "), ""))
        presets.append({"id": base, "label": label, "cmd": cmd, "src": b64(wav)})

    for wav in sorted(glob.glob(os.path.join(OUT, "ref", "*.wav"))):
        stem = os.path.basename(wav)[:-4]
        corpus.append({"id": stem, "label": stem.replace("_", " "), "src": b64(wav)})

    data = {"compare": compare, "presets": presets, "corpus": corpus}
    page = TEMPLATE.replace("__DATA__", json.dumps(data))
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)
    print("index.html: %d compare, %d presets, %d corpus clips (%.1f MB)" %
          (len(compare), len(presets), len(corpus), os.path.getsize(
              os.path.join(OUT, "index.html")) / 1e6))
    return 0


TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Villager voice samples — spectrogram comparison</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 1080px; margin: 2rem auto;
         padding: 0 1rem; color: #222; background: #faf8f4; }
  h1 { font-size: 1.35rem; margin-bottom: 4px; }
  p.note { color: #666; font-size: 0.85rem; margin-top: 4px; }
  h2 { font-size: 1.05rem; margin: 2.4rem 0 0.6rem; border-bottom: 1px solid #ddd; padding-bottom: 4px; }
  .card { background: #fff; border: 1px solid #e2ddd3; border-radius: 12px;
          padding: 14px 16px; margin: 14px 0; }
  .card .head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
  .card .head h3 { font-size: 0.98rem; margin: 0; }
  .card .cmd { color: #666; font-family: ui-monospace, monospace; font-size: 0.74rem;
               margin-bottom: 10px; word-break: break-all; }
  button.sync { font-size: 0.8rem; padding: 4px 12px; border-radius: 8px; border: 1px solid #b7c8a0;
                background: #eef5e4; color: #2c4a12; cursor: pointer; }
  button.sync:hover { background: #dcebc9; }
  .timing { color: #666; font-size: 0.76rem; font-family: ui-monospace, monospace; }
  .rows { display: flex; flex-direction: column; gap: 10px; }
  .aligned-row { display: grid; grid-template-columns: 135px 1fr; gap: 10px; align-items: start; }
  .aligned-row h4 { margin: 0; font-size: 0.8rem; line-height: 1.35; padding-top: 2px; }
  .aligned-row h4.ref { color: #1a6f34; }
  .aligned-row h4.neural { color: #1a5fb4; }
  .aligned-row h4.aligned { color: #6b4bb3; }
  .aligned-row .body audio { width: 100%; margin-bottom: 4px; }
  .spec-label { color: #777; font-size: 0.68rem; margin: 2px 0 3px; }
  canvas.spec { width: 100%; height: 170px; border: 1px solid #e2ddd3;
                border-radius: 6px; background: #0b0b12; cursor: crosshair; display: block; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
  .cmp { background: #f4f1ea; border-radius: 10px; padding: 10px; }
  .cmp h4 { margin: 0 0 6px; font-size: 0.85rem; }
  .cmp h4.ref { color: #1a6f34; }
  .cmp h4.neural { color: #1a5fb4; }
  .cmp h4.aligned { color: #6b4bb3; }
  .legend { font-size: 0.78rem; color: #555; margin: 4px 0 0; }
  .legend b.ref { color: #1a6f34; } .legend b.neural { color: #1a5fb4; }
</style>
</head>
<body>
<h1>Villager voice — spectrogram comparison</h1>
<p class="note">Unchanged reference recordings vs neural output for the same
lines. In the top section both spectrograms share one time axis: same seconds-per-pixel,
speech onsets aligned for viewing, while actual onset/duration deltas remain visible.
Log-frequency axis (40 Hz&ndash;8 kHz), brightness = energy, red line = pitch (F0) contour,
+N&nbsp;s = time since speech onset. The PASS/5% badge is timing-only; raw low-band
energy deltas are listed separately for 40&ndash;150, 150&ndash;300, 300&ndash;500, and 500&ndash;1,000 Hz.
Click a spectrogram to seek; <button class="sync" style="padding:1px 8px">Sync play</button>
starts all displayed clips at their aligned positions. Regenerate with
<code>bash tools/render_voice_samples.sh</code>.</p>
<p class="legend"><b class="ref">Reference recording</b> &middot;
<b class="neural">Neural raw (VITS + RVC)</b> &middot;
<b class="aligned">Neural time-aligned analysis copy</b></p>

<h2>Same line, reference vs neural (aligned)</h2>
<div id="compare"></div>

<h2>Emotion presets (generated)</h2>
<div id="presets"></div>

<h2>Reference corpus (all clips)</h2>
<div id="corpus"></div>

<script>
"use strict";
// ------------------------------------------------------------------ WAV parse
function decodeWav(bytes) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (dv.byteLength < 12) return null;
  const tag = (o) => String.fromCharCode(dv.getUint8(o), dv.getUint8(o + 1),
      dv.getUint8(o + 2), dv.getUint8(o + 3));
  if (tag(0) !== "RIFF") return null;
  let off = 12, fmt = null, dataOff = null, dataLen = 0;
  while (off + 8 <= dv.byteLength) {
    const id = tag(off);
    const size = dv.getUint32(off + 4, true);
    if (id === "fmt ") fmt = off + 8;
    else if (id === "data") { dataOff = off + 8; dataLen = size; }
    off += 8 + size + (size & 1);
  }
  if (fmt === null || dataOff === null) return null;
  const format = dv.getUint16(fmt, true);
  const channels = Math.max(1, dv.getUint16(fmt + 2, true));
  const rate = dv.getUint32(fmt + 4, true);
  const bits = dv.getUint16(fmt + 14, true);
  let samples = null;
  if (format === 3 && bits === 32) {
    const n = Math.floor(dataLen / 4 / channels);
    samples = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      let v = 0;
      for (let c = 0; c < channels; c++) v += dv.getFloat32(dataOff + (i * channels + c) * 4, true);
      samples[i] = v / channels;
    }
  } else if (bits === 16) {
    const n = Math.floor(dataLen / 2 / channels);
    samples = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      let v = 0;
      for (let c = 0; c < channels; c++) v += dv.getInt16(dataOff + (i * channels + c) * 2, true);
      samples[i] = v / channels / 32768.0;
    }
  } else if (bits === 8) {
    const n = Math.floor(dataLen / channels);
    samples = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      let v = 0;
      for (let c = 0; c < channels; c++) v += dv.getUint8(dataOff + i * channels + c) - 128;
      samples[i] = v / channels / 128.0;
    }
  }
  if (!samples || samples.length === 0 || rate <= 0) return null;
  return { samples: samples, rate: rate };
}
// ------------------------------------------------------------------- FFT
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      let t = re[i]; re[i] = re[j]; re[j] = t;
      t = im[i]; im[i] = im[j]; im[j] = t;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    const wR = Math.cos(ang), wI = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cR = 1, cI = 0;
      for (let k = 0; k < len / 2; k++) {
        const uR = re[i + k], uI = im[i + k];
        const vR = re[i + k + len / 2] * cR - im[i + k + len / 2] * cI;
        const vI = re[i + k + len / 2] * cI + im[i + k + len / 2] * cR;
        re[i + k] = uR + vR; im[i + k] = uI + vI;
        re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI;
        const nR = cR * wR - cI * wI;
        cI = cR * wI + cI * wR;
        cR = nR;
      }
    }
  }
}
// -------------------------------------------------------------- spectrogram
const CMAP = [[0, 0, 5], [18, 0, 44], [50, 15, 80], [100, 50, 70],
              [160, 100, 40], [235, 190, 40], [255, 240, 150]];
function colorAt(t) {
  t = Math.max(0, Math.min(0.999, t)) * (CMAP.length - 1);
  const i = Math.floor(t), f = t - i;
  const a = CMAP[i], b = CMAP[Math.min(CMAP.length - 1, i + 1)];
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
}
function buildSpectrogram(clip) {
  const fftSize = 1024, hop = 256;
  const n = clip.samples.length;
  const nFrames = Math.max(1, Math.floor(Math.max(0, n - fftSize) / hop) + 1);
  const bins = fftSize / 2;
  const mags = new Float32Array(nFrames * bins);
  const win = new Float32Array(fftSize);
  for (let i = 0; i < fftSize; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (fftSize - 1));
  const re = new Float32Array(fftSize), im = new Float32Array(fftSize);
  const frameE = new Float32Array(nFrames);
  for (let f = 0; f < nFrames; f++) {
    const base = f * hop;
    let e = 0;
    for (let i = 0; i < fftSize; i++) {
      const v = clip.samples[base + i] || 0;
      re[i] = v * win[i]; im[i] = 0;
      if (i < hop) e += v * v;
    }
    frameE[f] = Math.sqrt(e / Math.max(1, Math.min(hop, n - base)));
    fft(re, im);
    for (let b = 0; b < bins; b++) mags[f * bins + b] = Math.hypot(re[b], im[b]);
  }
  // Speech onset/end from the frame-energy curve (used to align the
  // reference, raw neural, and comparison-copy views: same px/s, onsets lined
  // up, silence cropped only in the visual mapping).
  let maxE = 0;
  for (let f = 0; f < nFrames; f++) if (frameE[f] > maxE) maxE = frameE[f];
  let onsetF = 0, endF = nFrames - 1;
  if (maxE > 1e-6) {
    const thr = maxE * 0.10;
    for (let f = 0; f < nFrames; f++) if (frameE[f] > thr) { onsetF = f; break; }
    for (let f = nFrames - 1; f >= 0; f--) if (frameE[f] > thr) { endF = f; break; }
  }
  const onset = onsetF * hop / clip.rate;
  const end = Math.min(n / clip.rate, (endF * hop + hop) / clip.rate);
  // F0 contour via autocorrelation over 40 ms frames, 20 ms hop
  const afWin = Math.floor(clip.rate * 0.04), afHop = Math.floor(clip.rate * 0.02);
  const f0 = new Float32Array(nFrames), f0ok = new Uint8Array(nFrames);
  const analysisLength = Math.min(afWin, n);
  const minLag = Math.max(2, Math.floor(clip.rate / 400));
  const maxLag = Math.min(Math.floor(clip.rate / 55), analysisLength - 2);
  for (let f = 0; f < nFrames && maxLag > minLag; f++) {
    const c = f * hop + fftSize / 2;
    const s = Math.max(0, Math.min(Math.max(0, n - analysisLength),
        Math.floor(c - analysisLength / 2)));
    let energy = 0;
    for (let i = 0; i < analysisLength; i++) energy += clip.samples[s + i] * clip.samples[s + i];
    if (Math.sqrt(energy / analysisLength) < 0.012) continue;
    let best = 0, bestVal = 0;
    for (let lag = minLag; lag <= maxLag; lag++) {
      let sum = 0, a = 0, b2 = 0;
      for (let i = 0; i + lag < analysisLength; i++) {
        const x = clip.samples[s + i], y = clip.samples[s + i + lag];
        sum += x * y; a += x * x; b2 += y * y;
      }
      const v = sum / (Math.sqrt(a * b2) + 1e-9);
      if (v > bestVal) { bestVal = v; best = lag; }
    }
    if (bestVal > 0.55) { f0[f] = clip.rate / best; f0ok[f] = 1; }
  }
  return { mags: mags, nFrames: nFrames, bins: bins, hop: hop, f0: f0, f0ok: f0ok,
           onset: onset, end: end };
}
// ------------------------------------------------------------------ drawing
const SPEC_W = 720, SPEC_H = 170;
// map = { timeToX(t) -> px, xToTime(px) -> s }
function paintSpectrogram(canvas, clip, spec, map, frequencyRange) {
  canvas.width = SPEC_W; canvas.height = SPEC_H;
  const off = document.createElement("canvas");
  off.width = SPEC_W; off.height = SPEC_H;
  const octx = off.getContext("2d");
  const img = octx.createImageData(SPEC_W, SPEC_H);
  const { mags, nFrames, bins, f0, f0ok, onset, end } = spec;
  const nyq = clip.rate / 2;
  const fMin = frequencyRange[0];
  const fMax = Math.min(frequencyRange[1], nyq);
  const binHz = clip.rate / 1024;
  const logA = Math.log(fMax / fMin);
  const dBmin = -95, dBrange = 60;
  const duration = clip.samples.length / clip.rate;
  for (let px = 0; px < SPEC_W; px++) {
    const sec = map.xToTime(px);
    if (sec < onset || sec > duration) continue; // silence stays background
    const frame = sec * clip.rate / spec.hop;
    const f0i = Math.max(0, Math.min(nFrames - 1, Math.floor(frame)));
    const f1i = Math.min(nFrames - 1, f0i + 1);
    const fa = frame - f0i;
    for (let py = 0; py < SPEC_H; py++) {
      const freq = fMax * Math.exp(-logA * py / SPEC_H);
      const bin = Math.max(0, Math.min(bins - 1, freq / binHz));
      const b0 = Math.floor(bin), b1 = Math.min(bins - 1, b0 + 1), ba = bin - b0;
      const m0 = mags[f0i * bins + b0] * (1 - ba) + mags[f0i * bins + b1] * ba;
      const m1 = mags[f1i * bins + b0] * (1 - ba) + mags[f1i * bins + b1] * ba;
      const m = m0 * (1 - fa) + m1 * fa;
      const db = 20 * Math.log10(m + 1e-10);
      const t = Math.max(0, Math.min(1, (db - dBmin) / dBrange));
      const c = colorAt(t);
      const idx = (py * SPEC_W + px) * 4;
      img.data[idx] = c[0]; img.data[idx + 1] = c[1];
      img.data[idx + 2] = c[2]; img.data[idx + 3] = 255;
    }
  }
  octx.putImageData(img, 0, 0);
  // Frequency grid and labels. The low-band view gets the same readable Hz
  // labels as the full-band view instead of compressing voice body into a few
  // pixels at the bottom of the log axis.
  octx.strokeStyle = "rgba(255,255,255,0.20)";
  octx.fillStyle = "rgba(255,255,255,0.75)";
  octx.font = "10px monospace";
  const ticks = [40, 100, 200, 300, 500, 1000, 2000, 4000, 8000];
  for (const tick of ticks) {
    if (tick < fMin || tick > fMax) continue;
    const y = SPEC_H * Math.log(fMax / tick) / logA;
    octx.beginPath();
    octx.moveTo(0, y);
    octx.lineTo(SPEC_W, y);
    octx.stroke();
    octx.fillText(tick >= 1000 ? (tick / 1000) + "k" : tick + " Hz", 4,
        Math.max(10, y - 2));
  }
  // F0 contour (red)
  octx.strokeStyle = "#ff4444";
  octx.lineWidth = 1.5;
  octx.beginPath();
  let started = false;
  for (let f = 0; f < nFrames; f++) {
    if (!f0ok[f]) { started = false; continue; }
    const x = map.timeToX(f * spec.hop / clip.rate);
    if (x < 0 || x > SPEC_W) { started = false; continue; }
    const y = SPEC_H * Math.log(fMax / Math.max(fMin, f0[f])) / logA;
    if (!started) { octx.moveTo(x, y); started = true; } else octx.lineTo(x, y);
  }
  octx.stroke();
  // relative-seconds ruler from the speech onset
  octx.strokeStyle = "rgba(255,255,255,0.25)";
  octx.fillStyle = "rgba(255,255,255,0.75)";
  octx.font = "10px monospace";
  const pxPerSec = (map.timeToX(onset + 1) - map.timeToX(onset)) || 1;
  const step = pxPerSec * 0.5 > 44 ? 0.5 : 1.0;
  for (let t = 0; t <= end - onset + 1e-6; t += step) {
    const x = map.timeToX(onset + t);
    if (x < 0 || x > SPEC_W) continue;
    octx.beginPath();
    octx.moveTo(x, SPEC_H - 12);
    octx.lineTo(x, SPEC_H);
    octx.stroke();
    octx.fillText("+" + t.toFixed(1) + "s", x + 3, SPEC_H - 3);
  }
  canvas._base = off;
  canvas._map = map;
  drawOverlay(canvas, 0);
}
function drawOverlay(canvas, t) {
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  ctx.drawImage(canvas._base, 0, 0);
  const x = canvas._map.timeToX(t);
  if (x >= 0 && x <= W) {
    ctx.fillStyle = "rgba(0,230,118,0.95)";
    ctx.fillRect(x, 0, 1, H);
    ctx.fillStyle = "rgba(255,255,255,0.9)";
    ctx.font = "11px monospace";
    ctx.fillText(t.toFixed(1) + "s", Math.min(W - 52, x + 4), 14);
  }
}
// -------------------------------------------------------------------- mount
function b64ToBytes(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}
function wireAudio(canvas, audio, duration) {
  const overlay = () => drawOverlay(canvas, audio.currentTime);
  audio.addEventListener("timeupdate", overlay);
  audio.addEventListener("seeked", overlay);
  audio.addEventListener("play", () => requestAnimationFrame(function tick() {
    overlay();
    if (!audio.paused && !audio.ended) requestAnimationFrame(tick);
  }));
  canvas.addEventListener("click", (e) => {
    const r = canvas.getBoundingClientRect();
    const sec = canvas._map.xToTime((e.clientX - r.left) / r.width * canvas.width);
    audio.currentTime = Math.max(0, Math.min(Math.max(0, duration - 0.01), sec));
  });
}
// Standalone clip (presets / corpus): own time scale over the full duration.
function mountClip(container, kind, title, bytes) {
  const box = document.createElement("div");
  box.className = "cmp";
  const h = document.createElement("h4");
  h.className = kind;
  h.textContent = title;
  box.appendChild(h);
  const audio = document.createElement("audio");
  audio.controls = true;
  audio.preload = "none";
  const canvas = document.createElement("canvas");
  canvas.className = "spec";
  box.appendChild(audio);
  box.appendChild(canvas);
  container.appendChild(box);
  const clip = decodeWav(bytes);
  if (!clip) {
    canvas.width = SPEC_W; canvas.height = 150;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#222"; ctx.fillRect(0, 0, SPEC_W, 150);
    ctx.fillStyle = "#f66"; ctx.font = "13px monospace";
    ctx.fillText("cannot decode WAV", 12, 80);
    return;
  }
  const blob = new Blob([bytes.buffer.slice(bytes.byteOffset,
      bytes.byteOffset + bytes.byteLength)], { type: "audio/wav" });
  audio.src = URL.createObjectURL(blob);
  const spec = buildSpectrogram(clip);
  const duration = clip.samples.length / clip.rate;
  const pxPerSec = (SPEC_W - 16) / duration;
  const map = {
    timeToX: (t) => 8 + t * pxPerSec,
    xToTime: (px) => (px - 8) / pxPerSec,
  };
  paintSpectrogram(canvas, clip, spec, map, [40, 8000]);
  wireAudio(canvas, audio, duration);
}
// Aligned row inside a comparison card (shared px/s + onsets at the same x).
function buildAlignedRow(kind, title, bytes, align, frequencyRange, rangeLabel) {
  const row = document.createElement("div");
  row.className = "aligned-row";
  const h = document.createElement("h4");
  h.className = kind;
  h.textContent = title;
  row.appendChild(h);
  const body = document.createElement("div");
  body.className = "body";
  const range = document.createElement("div");
  range.className = "spec-label";
  range.textContent = rangeLabel;
  body.appendChild(range);
  const audio = document.createElement("audio");
  audio.controls = true;
  audio.preload = "none";
  const canvas = document.createElement("canvas");
  canvas.className = "spec";
  body.appendChild(audio);
  body.appendChild(canvas);
  row.appendChild(body);
  const clip = decodeWav(bytes);
  if (!clip) {
    canvas.width = SPEC_W; canvas.height = 150;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#222"; ctx.fillRect(0, 0, SPEC_W, 150);
    ctx.fillStyle = "#f66"; ctx.font = "13px monospace";
    ctx.fillText("cannot decode WAV", 12, 80);
    return { audio: audio, spec: null, row: row };
  }
  const blob = new Blob([bytes.buffer.slice(bytes.byteOffset,
      bytes.byteOffset + bytes.byteLength)], { type: "audio/wav" });
  audio.src = URL.createObjectURL(blob);
  const spec = buildSpectrogram(clip);
  const duration = clip.samples.length / clip.rate;
  const map = {
    timeToX: (t) => align.pad + (t - spec.onset) * align.pxPerSec,
    xToTime: (px) => spec.onset + (px - align.pad) / align.pxPerSec,
  };
  paintSpectrogram(canvas, clip, spec, map, frequencyRange);
  wireAudio(canvas, audio, duration);
  return { audio: audio, spec: spec, row: row };
}
// -------------------------------------------------------------------- build
const DATA = __DATA__;

function addCard(parentId, title, cmdText, contentEl, headExtra) {
  const card = document.createElement("div");
  card.className = "card";
  const head = document.createElement("div");
  head.className = "head";
  const h3 = document.createElement("h3");
  h3.textContent = title;
  head.appendChild(h3);
  if (headExtra) head.appendChild(headExtra);
  card.appendChild(head);
  if (cmdText) {
    const cmd = document.createElement("div");
    cmd.className = "cmd";
    cmd.textContent = cmdText;
    card.appendChild(cmd);
  }
  card.appendChild(contentEl);
  document.getElementById(parentId).appendChild(card);
}

const KINDS = [
  ["ref", "Reference recording"],
  ["neural", "Neural raw (VITS + RVC)"],
  ["aligned", "Neural aligned analysis copy"],
];

for (const item of DATA.compare) {
  const rowsEl = document.createElement("div");
  rowsEl.className = "rows";
  // Decode all clips first so the shared time scale can be computed.
  const decoded = [];
  for (const [kind, title] of KINDS) {
    if (item[kind] === undefined) continue;
    const bytes = b64ToBytes(item[kind]);
    const clip = decodeWav(bytes);
    decoded.push({ kind: kind, title: title, bytes: bytes,
                   spec: clip ? buildSpectrogram(clip) : null });
  }
  const PAD = 10;
  const maxSpeech = Math.max.apply(null,
      decoded.map(d => d.spec ? d.spec.end - d.spec.onset : 0).concat(0.5));
  const pxPerSec = (SPEC_W - 2 * PAD) / maxSpeech;
  const mounted = [];
  for (const d of decoded) {
    if (!d.spec) continue;
    const full = buildAlignedRow(d.kind, d.title, d.bytes,
        { pxPerSec: pxPerSec, pad: PAD }, [40, 8000], "Full band · 40–8,000 Hz");
    rowsEl.appendChild(full.row);
    mounted.push(full);
    const low = buildAlignedRow(d.kind, d.title + " · low band", d.bytes,
        { pxPerSec: pxPerSec, pad: PAD }, [40, 1000], "Low band expanded · 40–1,000 Hz");
    rowsEl.appendChild(low.row);
  }
  const timing = document.createElement("span");
  timing.className = "timing";
  const ref = decoded.find(d => d.kind === "ref");
  const neural = decoded.find(d => d.kind === "neural");
  const aligned = decoded.find(d => d.kind === "aligned");
    if (item.metrics) {
      const m = item.metrics;
      const lowBands = item.lowBands || [];
      const lowText = lowBands.map(b => b.range + " "
          + b.relativePercent.toFixed(0) + "%").join(" · ");
      timing.textContent = "timing: raw span Δ " + m.rawSpanDelta.toFixed(2)
          + "s · raw onset Δ " + m.rawOnsetDelta.toFixed(2)
          + "s · aligned span Δ " + m.alignedSpanDelta.toFixed(3)
          + "s · aligned error " + m.alignedPercent.toFixed(1) + "% ("
          + m.status + ") · raw low-band Δ " + lowText;
    }
  const btn = document.createElement("button");
  btn.className = "sync";
  btn.textContent = "Sync play";
  btn.addEventListener("click", () => {
    const playing = mounted.some(m => !m.audio.paused);
    if (playing) {
      mounted.forEach(m => m.audio.pause());
      btn.textContent = "Sync play";
    } else {
      mounted.forEach(m => { m.audio.currentTime = m.spec.onset; m.audio.play(); });
      btn.textContent = "Pause all";
    }
  });
  const extra = document.createElement("div");
  extra.appendChild(timing);
  extra.appendChild(btn);
  addCard("compare", item.label, "", rowsEl, extra);
}

for (const item of DATA.presets) {
  const grid = document.createElement("div");
  grid.className = "grid";
  mountClip(grid, "neural", "Generated", b64ToBytes(item.src));
  addCard("presets", item.label, item.cmd, grid);
}

for (const item of DATA.corpus) {
  const grid = document.createElement("div");
  grid.className = "grid";
  mountClip(grid, "ref", "Reference recording", b64ToBytes(item.src));
  addCard("corpus", item.label, "", grid);
}
</script>
</body>
</html>
"""

if __name__ == "__main__":
    sys.exit(main())
