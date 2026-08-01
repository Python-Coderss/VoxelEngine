package com.voxel.audio;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * WavSubtract — extracts vocals from (full mix) − (instrumental) using
 * cross-correlation alignment, least-squares level match, and sample subtraction.
 * <p>
 * Usage: com.voxel.audio.WavSubtract [inDir] [outDir] [plain]
 * Reads {@code inDir/full.mp3} + {@code inDir/instrumental.mp3}, writes
 * {@code outDir/vocal.wav} (gain-matched) or {@code outDir/vocal2.wav} (plain,
 * no scaling) and a {@code subtract_report.txt} quality report.
 */
public class WavSubtract {

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0] : "tools/audio/in";
        String outDir = args.length > 1 ? args[1] : "tools/audio/out";
        boolean plain = args.length > 2 && args[2].equalsIgnoreCase("plain");
        File fullFile = new File(inDir, "full.mp3");
        File instFile = new File(inDir, "instrumental.mp3");
        File outWav = new File(outDir, plain ? "vocal2.wav" : "vocal.wav");
        File reportFile = new File(outDir, "subtract_report.txt");
        outWav.getParentFile().mkdirs();

        StringBuilder rep = new StringBuilder();
        rep.append("=== WavSubtract report ").append(plain ? "(PLAIN, no scaling)" : "(gain-matched)")
                .append(" ===\n");

        // ── Decode ──
        AudioData full = Mp3Decoder.decode(fullFile);
        AudioData inst = Mp3Decoder.decode(instFile);
        rep.append("full: rate=").append(full.sampleRate).append(" ch=").append(full.channels)
                .append(" dur=").append(fmt(full.durationSeconds())).append("s\n");
        rep.append("inst: rate=").append(inst.sampleRate).append(" ch=").append(inst.channels)
                .append(" dur=").append(fmt(inst.durationSeconds())).append("s\n");

        if (!full.isValid() || !inst.isValid()) {
            System.err.println("ERROR: decoded audio contains invalid samples");
            System.exit(1);
        }

        // Resample instrumental to full's rate if needed (alignment requires equal rates).
        if (inst.sampleRate != full.sampleRate) {
            rep.append("inst resampled ").append(inst.sampleRate).append(" -> ").append(full.sampleRate).append("\n");
            inst = AudioData.resample(inst, full.sampleRate);
        }

        // ── Align (on mono mid) ──
        float[] fullMono = full.toMono();
        float[] instMono = inst.toMono();

        int rate = full.sampleRate;
        // Coarse: envelope cross-correlation at 100ms frames.
        int frameLen = rate / 10;
        float[] fullEnv = envelope(fullMono, frameLen);
        float[] instEnv = envelope(instMono, frameLen);
        int coarseLagFrames = envelopeLag(fullEnv, instEnv);
        int coarseLag = coarseLagFrames * frameLen;
        rep.append("coarse envelope lag: ").append(coarseLagFrames).append(" frames = ")
                .append(fmt(coarseLag / (double) rate)).append("s\n");

        int ch = Math.min(full.channels, inst.channels);

        // Multi-candidate alignment. The envelope coarse lag can lock onto a tempo
        // harmonic (music at ~100 BPM has a 0.6s beat; the -1.2s hit is 2 beats).
        // Correlation is also inherently low when the vocal dominates the mix, so
        // we score each candidate by the residual AFTER subtraction (real cancellation)
        // rather than by correlation.
        int beat = rate * 6 / 10; // 0.6s tempo grid
        java.util.LinkedHashSet<Integer> cands = new java.util.LinkedHashSet<>();
        cands.add(0);
        cands.add(coarseLag);
        for (int k = -4; k <= 4; k++) cands.add(k * beat);
        int fineLag = coarseLag;
        double bestResid = Double.MAX_VALUE;
        double bestAlign = -1;
        rep.append("candidate search (fine lag, residual after subtraction):\n");
        for (int cand : cands) {
            int fine = fineAlign(fullMono, instMono, cand, rate);
            double resid = residualRms(full, inst, fine, ch, rate, plain);
            if (resid <= 0) continue;
            double corr = normCorr(fullMono, instMono, fine, rate);
            rep.append("  base ").append(fmt(cand / (double) rate)).append("s -> fine ")
                    .append(fmt(fine / (double) rate)).append("s: resid ")
                    .append(fmt(db(resid))).append(" dBFS, corr ").append(fmt(corr)).append("\n");
            if (resid < bestResid) {
                bestResid = resid;
                fineLag = fine;
                bestAlign = corr;
            }
        }
        rep.append("chosen fine lag: ").append(fineLag).append(" samples = ")
                .append(fmt(fineLag / (double) rate)).append("s (corr ")
                .append(fmt(bestAlign)).append(", resid ").append(fmt(db(bestResid))).append(" dBFS)\n");
        rep.append("note: correlation is naturally low when vocals dominate; residual is the real metric.\n");

        // ── Overlap region ──
        // inst[i] corresponds to full[i + fineLag]. Overlap in full-frame space:
        int fullFrames = full.frameCount();
        int instFrames = inst.frameCount();
        int overlapStart = Math.max(0, fineLag);
        int overlapEnd = Math.min(fullFrames, instFrames + fineLag);
        int overlap = overlapEnd - overlapStart;
        if (overlap < rate) {
            System.err.println("ERROR: no meaningful overlap after alignment (" + overlap + " frames)");
            System.exit(1);
        }
        rep.append("overlap: ").append(fmt(overlap / (double) rate)).append("s\n");

        // ── Level match (per channel). Plain mode forces gain = 1.0 (no scaling). ──
        double[] gain = new double[ch];
        if (plain) {
            Arrays.fill(gain, 1.0);
        } else {
            for (int c = 0; c < ch; c++) {
                double num = 0, den = 0;
                for (int t = overlapStart; t < overlapEnd; t++) {
                    double a = full.samples[t * full.channels + c];
                    double b = inst.samples[(t - fineLag) * inst.channels + c];
                    num += a * b;
                    den += b * b;
                }
                gain[c] = den > 1e-12 ? num / den : 1.0;
            }
        }
        rep.append("per-channel gain: ").append(Arrays.toString(gain)).append("\n");

        // ── Subtract ──
        float[] vocals = new float[overlap * ch];
        for (int t = 0; t < overlap; t++) {
            for (int c = 0; c < ch; c++) {
                double a = full.samples[(overlapStart + t) * full.channels + c];
                double b = inst.samples[(overlapStart + t - fineLag) * inst.channels + c];
                double v = a - gain[c] * b;
                vocals[t * ch + c] = (float) v;
            }
        }
        AudioData out = new AudioData(vocals, ch, rate);
        WavIO.write(outWav, out);
        rep.append("wrote ").append(outWav).append(" (").append(fmt(out.durationSeconds())).append("s, ch=")
                .append(ch).append(")\n");

        // ── Noise-floor quality gate ──
        // Find the quietest contiguous 10s window in the full track (potential blank filler).
        int blankLen = rate * 10;
        int blankStart = quietestWindowStart(fullMono, blankLen);
        double fullRms = rms(fullMono, overlapStart, overlapEnd);
        double vocalRms = rmsMono(vocals, ch, 0, overlap);
        double blankFullRms = rms(fullMono, blankStart, Math.min(fullMono.length, blankStart + blankLen));
        double blankInstRms = rms(instMono,
                Math.max(0, blankStart - fineLag),
                Math.min(instMono.length, blankStart + blankLen - fineLag));
        double blankVocalRms = rmsMono(vocals, ch,
                Math.max(0, blankStart - overlapStart),
                Math.min(overlap, blankStart + blankLen - overlapStart));
        double instRms = rms(instMono, Math.max(0, overlapStart - fineLag), Math.min(instMono.length, overlapEnd - fineLag));

        double fullDb = db(fullRms), vocalDb = db(vocalRms), blankFullDb = db(blankFullRms), blankVocalDb = db(blankVocalRms);
        rep.append("\n=== Quality gate ===\n");
        rep.append("full RMS:      ").append(fmt(fullDb)).append(" dBFS  (vocal region)\n");
        rep.append("inst RMS:      ").append(fmt(db(instRms))).append(" dBFS\n");
        rep.append("residual RMS:  ").append(fmt(vocalDb)).append(" dBFS\n");
        rep.append("  -> residual ").append(fmt(vocalDb - fullDb)).append(" dB below full (higher = cleaner)\n");
        rep.append("quietest 10s @ ").append(fmt(blankStart / (double) rate)).append("s:\n");
        rep.append("  full RMS:    ").append(fmt(blankFullDb)).append(" dBFS\n");
        rep.append("  inst RMS:    ").append(fmt(db(blankInstRms))).append(" dBFS\n");
        rep.append("  residual RMS:").append(fmt(blankVocalDb)).append(" dBFS\n");
        if (blankFullDb < -50) {
            rep.append("  -> true silent filler: cancellation ").append(fmt(blankVocalDb - blankFullDb))
                    .append(" dB in blank (should be very negative)\n");
            if (blankVocalDb - blankFullDb > -10) {
                rep.append("  WARNING: poor cancellation in a true silent region — alignment/level off.\n");
            } else {
                rep.append("  OK: clean cancellation in silent region.\n");
            }
        } else {
            rep.append("  (no true silent filler detected — blank gate is informational only)\n");
        }

        try (PrintWriter pw = new PrintWriter(reportFile)) {
            pw.print(rep);
        }
        System.out.print(rep);
    }

    // ── Alignment helpers ──

    /** Per-frame RMS envelope; frameLen in samples. */
    static float[] envelope(float[] mono, int frameLen) {
        int frames = mono.length / frameLen;
        float[] env = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            int base = f * frameLen;
            for (int i = 0; i < frameLen; i++) {
                float v = mono[base + i];
                sum += v * v;
            }
            env[f] = (float) Math.sqrt(sum / frameLen);
        }
        return env;
    }

    /** Cross-correlate envelopes; returns lag L where full[i] ≈ inst[i - L] (frames). */
    static int envelopeLag(float[] fullEnv, float[] instEnv) {
        int best = 0;
        double bestScore = -1;
        int maxLag = fullEnv.length; // search full range
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            int s = Math.max(0, lag);
            int e = Math.min(fullEnv.length, instEnv.length + lag);
            if (e - s < 50) continue;
            double num = 0, fa = 0, fb = 0;
            for (int i = s; i < e; i++) {
                double a = fullEnv[i];
                double b = instEnv[i - lag];
                num += a * b;
                fa += a * a;
                fb += b * b;
            }
            double score = num / Math.sqrt(fa * fb + 1e-12);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best;
    }

    /**
     * Residual RMS after gain-matched subtraction at a given lag — the real
     * cancellation score (lower = better aligned + matched).
     */
    static double residualRms(AudioData full, AudioData inst, int lag, int ch, int rate, boolean plain) {
        int fullFrames = full.frameCount();
        int instFrames = inst.frameCount();
        int start = Math.max(0, lag);
        int end = Math.min(fullFrames, instFrames + lag);
        if (end - start < 2 * rate) return 0;
        double[] gain = new double[ch];
        if (plain) {
            Arrays.fill(gain, 1.0);
        } else {
            for (int c = 0; c < ch; c++) {
                double num = 0, den = 0;
                for (int t = start; t < end; t++) {
                    double a = full.samples[t * full.channels + c];
                    double b = inst.samples[(t - lag) * inst.channels + c];
                    num += a * b;
                    den += b * b;
                }
                gain[c] = den > 1e-12 ? num / den : 1.0;
            }
        }
        // Score over a common interior window (4s margin) so each candidate's
        // different overlap edges don't bias the comparison — the gain is still
        // computed over the full overlap.
        int margin = 4 * rate; // 4s > max candidate shift (±2.65s)
        int sStart = start + margin;
        int sEnd = end - margin;
        if (sEnd - sStart < rate) return 0;
        double sum = 0;
        for (int t = sStart; t < sEnd; t++) {
            double v = 0;
            for (int c = 0; c < ch; c++) {
                double a = full.samples[t * full.channels + c];
                double b = inst.samples[(t - lag) * inst.channels + c];
                v += (a - gain[c] * b) / ch;
            }
            sum += v * v;
        }
        return Math.sqrt(sum / (sEnd - sStart));
    }

    /** Normalized cross-correlation of the loudest 1s window of inst against full at the given lag. */
    static double normCorr(float[] fullMono, float[] instMono, int lag, int rate) {
        int win = rate; // 1s window
        int instWinStart = loudestWindowStart(instMono, win, rate);
        int fullStart = lag + instWinStart;
        if (fullStart < 0 || fullStart + win > fullMono.length) return -1;
        double num = 0, fa = 0, fb = 0;
        for (int i = 0; i < win; i++) {
            double a = fullMono[fullStart + i];
            double b = instMono[instWinStart + i];
            num += a * b;
            fa += a * a;
            fb += b * b;
        }
        return num / Math.sqrt(fa * fb + 1e-12);
    }

    /**
     * Sample-level alignment around coarse lag ±250ms.
     * Slides the loudest 1s window of the instrumental against the full track.
     * Returns shift L where full[i] ≈ inst[i - L] (same space as the envelope lag).
     */
    static int fineAlign(float[] fullMono, float[] instMono, int coarseLag, int rate) {
        int win = rate; // 1s window
        int instWinStart = loudestWindowStart(instMono, win, rate);
        int searchHalf = rate / 4; // ±250ms
        // full[lag + i] ≈ inst[instWinStart + i]  ⇒  full[t] ≈ inst[t - (lag - instWinStart)]
        int center = coarseLag + instWinStart;
        int lo = Math.max(0, center - searchHalf);
        int hi = Math.min(fullMono.length - win, center + searchHalf);
        int best = center;
        double bestScore = -1;
        for (int lag = lo; lag <= hi; lag++) {
            double num = 0, fa = 0, fb = 0;
            for (int i = 0; i < win; i++) {
                double a = fullMono[lag + i];
                double b = instMono[instWinStart + i];
                num += a * b;
                fa += a * a;
                fb += b * b;
            }
            double score = num / Math.sqrt(fa * fb + 1e-12);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best - instWinStart;
    }

    /** Start of the loudest `win`-sample window in mono. */
    static int loudestWindowStart(float[] mono, int win, int rate) {
        double bestE = -1;
        int best = 0;
        int step = rate / 20;
        for (int s = 0; s + win <= mono.length; s += step) {
            double e = 0;
            for (int i = 0; i < win; i++) e += mono[s + i] * mono[s + i];
            if (e > bestE) {
                bestE = e;
                best = s;
            }
        }
        return best;
    }

    /** Start of the quietest `winLen`-sample window (the blank filler). */
    static int quietestWindowStart(float[] mono, int winLen) {
        if (mono.length <= winLen) return 0;
        double bestE = Double.MAX_VALUE;
        int best = 0;
        int step = winLen / 4; // coarse stride, then refine
        int coarse = 0;
        for (int s = 0; s + winLen <= mono.length; s += step) {
            double e = 0;
            for (int i = 0; i < winLen; i++) e += mono[s + i] * mono[s + i];
            if (e < bestE) {
                bestE = e;
                coarse = s;
            }
        }
        // refine around coarse by single samples using a sliding-window sum (O(n))
        int lo = Math.max(0, coarse - step);
        int hi = Math.min(mono.length - winLen, coarse + step);
        double e = 0;
        for (int i = 0; i < winLen; i++) e += mono[lo + i] * mono[lo + i];
        double best2 = e;
        best = lo;
        for (int s = lo + 1; s <= hi; s++) {
            e += mono[s + winLen - 1] * mono[s + winLen - 1] - mono[s - 1] * mono[s - 1];
            if (e < best2) {
                best2 = e;
                best = s;
            }
        }
        return best;
    }

    // ── Stats helpers ──

    static double rms(float[] mono, int start, int end) {
        if (end <= start) return 0;
        double sum = 0;
        for (int i = start; i < end; i++) sum += mono[i] * mono[i];
        return Math.sqrt(sum / (end - start));
    }

    static double rmsMono(float[] interleaved, int ch, int start, int end) {
        if (end <= start) return 0;
        double sum = 0;
        int count = 0;
        for (int t = start; t < end; t++) {
            float v = 0;
            for (int c = 0; c < ch; c++) v += interleaved[t * ch + c];
            v /= ch;
            sum += v * v;
            count++;
        }
        return Math.sqrt(sum / count);
    }

    static double db(double rms) {
        return 20 * Math.log10(rms + 1e-12);
    }

    static String fmt(double d) {
        return String.format("%.3f", d);
    }
}
