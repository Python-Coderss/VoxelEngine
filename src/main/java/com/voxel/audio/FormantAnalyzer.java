package com.voxel.audio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * FormantAnalyzer — builds a "voice profile" from a vocal-dominant audio file.
 * <p>
 * Reads {@code inDir/full.mp3}, tracks F0 and formants (F1–F3) on harmonicity-gated
 * frames using LPC, aggregates the measurements into median + IQR, computes the
 * delta from a standard British-male template, and writes {@code dan_profile.json}
 * plus an {@code analysis_report.txt}.
 * <p>
 * Usage: com.voxel.audio.FormantAnalyzer [inDir] [outDir]
 */
public class FormantAnalyzer {

    static final int RATE = 16000;
    static final int LPC_ORDER = 14;
    static final int FFT_SIZE = 2048;
    static final int FRAME = RATE / 40;      // 25 ms
    static final int HOP = RATE / 100;       // 10 ms
    static final double F0_MIN = 70, F0_MAX = 250;

    // Standard British-male formant template (Hz): F1 F2 F3
    static final String[] VOWELS = {"i", "I", "E", "ae", "A", "Q", "c", "U", "u", "V", "3", "@"};
    static final double[][] TEMPLATE = {
            {270, 2290, 3010},   // i  heed
            {390, 1990, 2550},   // I  hid
            {530, 1840, 2480},   // E  head
            {660, 1720, 2410},   // ae had
            {730, 1090, 2440},   // A  hard
            {570, 840, 2410},    // Q  hod
            {570, 840, 2410},    // c  horde (~Q in BrE)
            {440, 1020, 2240},   // U  hood
            {300, 870, 2240},    // u  who'd
            {640, 1190, 2390},   // V  hud
            {490, 1350, 1690},   // 3  heard
            {500, 1500, 2500},   // @  schwa
    };

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0] : "tools/audio/in";
        String outDir = args.length > 1 ? args[1] : "tools/audio/out";
        File src = new File(inDir, "full.mp3");
        File outJson = new File(outDir, "dan_profile.json");
        File report = new File(outDir, "analysis_report.txt");
        outJson.getParentFile().mkdirs();

        StringBuilder rep = new StringBuilder();
        rep.append("=== Formant analysis ===\n");

        AudioData full = Mp3Decoder.decode(src);
        AudioData mono = new AudioData(full.toMono(), 1, full.sampleRate);
        if (mono.sampleRate != RATE) mono = AudioData.resample(mono, RATE);
        float[] x = mono.samples;
        rep.append("analyzed ").append(src.getName()).append(" @ ").append(RATE).append(" Hz, ")
                .append(fmt(x.length / (double) RATE)).append("s\n");

        // ── Frame loop ──
        double[] hamming = hamming(FRAME);
        int nFrames = (x.length - FRAME) / HOP;
        List<Double> f0s = new ArrayList<>();
        List<double[]> formantFrames = new ArrayList<>();
        double snsSum = 0;
        int voiced = 0;

        FFT lpcFft = new FFT(FFT_SIZE);
        double[] ltas = new double[FFT_SIZE / 2];

        for (int f = 0; f < nFrames; f++) {
            int base = f * HOP;
            float[] frame = new float[FRAME];
            for (int i = 0; i < FRAME; i++) frame[i] = x[base + i];
            double[] pw = pitchWindow(frame, hamming);
            double r0 = 0;
            for (int i = 0; i < FRAME; i++) r0 += pw[i] * pw[i];
            if (r0 < 1e-6) continue;
            double[] pitch = pitchAndSns(pw, r0);
            if (pitch[1] < 0.35) continue; // harmonicity gate: not voice-dominated
            voiced++;
            snsSum += pitch[1];
            f0s.add(pitch[0]);

            double[] formants = lpcFormants(frame, hamming, lpcFft);
            if (formants != null) {
                formantFrames.add(formants);
                // accumulate LTAS (float frame -> double buffer)
                double[] re = new double[FFT_SIZE];
                double[] im = new double[FFT_SIZE];
                for (int i = 0; i < FRAME; i++) re[i] = frame[i];
                lpcFft.transform(re, im, false);
                for (int k = 0; k < ltas.length; k++) {
                    double m = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                    ltas[k] += m;
                }
            }
        }
        if (f0s.isEmpty() || formantFrames.isEmpty()) {
            System.err.println("ERROR: no voiced frames found — is this audio voice-dominated?");
            System.exit(1);
        }

        // ── Aggregate F0 ──
        double[] f0arr = toArray(f0s);
        java.util.Arrays.sort(f0arr);
        double f0Med = median(f0arr);
        double f0Lo = percentile(f0arr, 5);
        double f0Hi = percentile(f0arr, 95);
        rep.append("voiced frames: ").append(voiced).append(" / ").append(nFrames).append("\n");
        rep.append("F0: median=").append(fmt(f0Med)).append(" Hz, 5-95% range [")
                .append(fmt(f0Lo)).append(", ").append(fmt(f0Hi)).append("]\n");

        // ── Aggregate formants (median + IQR) ──
        double[] f1 = new double[formantFrames.size()];
        double[] f2 = new double[formantFrames.size()];
        double[] f3 = new double[formantFrames.size()];
        for (int i = 0; i < formantFrames.size(); i++) {
            f1[i] = formantFrames.get(i)[0];
            f2[i] = formantFrames.get(i)[1];
            f3[i] = formantFrames.get(i)[2];
        }
        java.util.Arrays.sort(f1);
        java.util.Arrays.sort(f2);
        java.util.Arrays.sort(f3);
        double m1 = median(f1), m2 = median(f2), m3 = median(f3);
        rep.append("formants (median): F1=").append(fmt(m1)).append(" F2=").append(fmt(m2))
                .append(" F3=").append(fmt(m3)).append(" Hz\n");
        rep.append("formant spread (IQR): F1 [").append(fmt(percentile(f1, 25)))
                .append(", ").append(fmt(percentile(f1, 75))).append("], F2 [")
                .append(fmt(percentile(f2, 25))).append(", ").append(fmt(percentile(f2, 75))).append("]\n");

        // ── Delta from template ──
        double t1 = 0, t2 = 0, t3 = 0;
        for (double[] t : TEMPLATE) {
            t1 += t[0];
            t2 += t[1];
            t3 += t[2];
        }
        int n = TEMPLATE.length;
        t1 /= n;
        t2 /= n;
        t3 /= n;
        double d1 = m1 - t1, d2 = m2 - t2, d3 = m3 - t3;
        rep.append("delta vs template: dF1=").append(fmt(d1)).append(" dF2=").append(fmt(d2))
                .append(" dF3=").append(fmt(d3)).append("\n");

        // ── Spectral tilt from LTAS ──
        double lo = 0, hi = 0;
        for (int k = 0; k < ltas.length; k++) {
            double hz = k * (double) RATE / FFT_SIZE;
            if (hz >= 250 && hz <= 800) lo += ltas[k];
            else if (hz >= 2500 && hz <= 4000) hi += ltas[k];
        }
        double tilt = 20 * Math.log10((hi + 1e-12) / (lo + 1e-12));
        rep.append("spectral tilt (high band vs low band): ").append(fmt(tilt)).append(" dB\n");

        double breathiness = Math.max(0.02, Math.min(0.5, 1.0 - (snsSum / voiced)));
        rep.append("breathiness estimate: ").append(fmt(breathiness)).append("\n");

        // ── Build profile JSON ──
        JSONObject profile = new JSONObject();
        JSONObject f0 = new JSONObject();
        f0.put("mode", round1(f0Med));
        f0.put("min", round1(f0Lo));
        f0.put("max", round1(f0Hi));
        profile.put("f0", f0);
        profile.put("formantMedian", new JSONArray()
                .put(round1(m1)).put(round1(m2)).put(round1(m3)));
        profile.put("vowelDelta", new JSONArray().put(round1(d1)).put(round1(d2)).put(round1(d3)));
        profile.put("spectralTilt", round2(tilt));
        profile.put("breathiness", round2(breathiness));

        JSONObject vowels = new JSONObject();
        for (int i = 0; i < VOWELS.length; i++) {
            double f1v = Math.max(150, TEMPLATE[i][0] + d1);
            double f2v = Math.max(400, TEMPLATE[i][1] + d2);
            double f3v = Math.max(1200, TEMPLATE[i][2] + d3);
            vowels.put(VOWELS[i], new JSONArray().put(round1(f1v)).put(round1(f2v)).put(round1(f3v)));
        }
        profile.put("vowels", vowels);

        try (PrintWriter w = new PrintWriter(outJson)) {
            w.print(profile.toString(2));
        }
        rep.append("\nwrote ").append(outJson).append("\n");

        try (PrintWriter pw = new PrintWriter(report)) {
            pw.print(rep);
        }
        System.out.print(rep);
    }

    // ── Pitch ──

    static double[] pitchWindow(float[] frame, double[] hamming) {
        double mean = 0;
        for (float v : frame) mean += v;
        mean /= frame.length;
        double[] w = new double[frame.length];
        for (int i = 0; i < frame.length; i++) w[i] = (frame[i] - mean) * hamming[i];
        return w;
    }

    /** Returns {f0, normalized autocorrelation peak (SNS)}. */
    static double[] pitchAndSns(double[] w, double r0) {
        int minLag = RATE / (int) F0_MAX;
        int maxLag = Math.min(RATE / (int) F0_MIN, w.length - 1);
        double bestSns = 0;
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double num = 0;
            for (int i = 0; i < w.length - lag; i++) num += w[i] * w[i + lag];
            double den = 0;
            for (int i = 0; i < w.length - lag; i++) den += w[i] * w[i] + w[i + lag] * w[i + lag];
            if (den <= 1e-12) continue;
            double sns = 2 * num / den;
            if (sns > bestSns) {
                bestSns = sns;
                bestLag = lag;
            }
        }
        if (bestLag < 0) return new double[]{0, 0};
        // parabolic refinement
        double f0 = RATE / (double) bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double p0 = 2 * r0n(w, bestLag - 1);
            double p1 = 2 * r0n(w, bestLag);
            double p2 = 2 * r0n(w, bestLag + 1);
            double denom = p0 - 2 * p1 + p2;
            if (Math.abs(denom) > 1e-12) {
                double delta = 0.5 * (p0 - p2) / denom;
                f0 = RATE / (bestLag + delta);
            }
        }
        return new double[]{f0, bestSns};
    }

    static double r0n(double[] w, int lag) {
        double num = 0;
        for (int i = 0; i < w.length - lag; i++) num += w[i] * w[i + lag];
        return num;
    }

    // ── LPC formants ──

    static double[] lpcFormants(float[] frame, double[] hamming, FFT fft) {
        // DC-remove, window, pre-emphasize
        double mean = 0;
        for (float v : frame) mean += v;
        mean /= frame.length;
        double[] w = new double[FRAME];
        double prev = 0;
        for (int i = 0; i < FRAME; i++) {
            double v = (frame[i] - mean) * hamming[i];
            w[i] = v - 0.97 * prev;
            prev = v;
        }
        double[] a = levinsonDurbin(w, LPC_ORDER);
        if (a == null) return null;

        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        re[0] = 1.0;
        for (int i = 1; i <= LPC_ORDER && i < FFT_SIZE; i++) re[i] = a[i];
        fft.transform(re, im, false);
        double[] env = new double[FFT_SIZE / 2];
        for (int i = 0; i < env.length; i++) {
            double m = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            env[i] = 1.0 / (m + 1e-9);
        }
        // Argmax-in-band peak picking: for each formant band, find the frequency with the
        // strongest resonance. Strict local-maxima detection misses F1 when pre-emphasis
        // flattens the low-frequency envelope into a shoulder rather than a sharp peak.
        double f1 = peakInBand(env, 150, 900);
        double f2 = peakInBand(env, Math.max(700, f1 + 100), 2800);
        double f3 = peakInBand(env, Math.max(1600, f2 + 150), 4000);
        if (f1 <= 0) f1 = 500;
        if (f2 <= 0) f2 = 1500;
        if (f3 <= 0) f3 = Math.max(2500, f2 + 200); // keep ordering: F3 > F2
        return new double[]{f1, f2, f3};
    }

    /** Frequency of the strongest resonance within [loHz, hiHz]. Returns -1 if the band is empty. */
    static double peakInBand(double[] env, double loHz, double hiHz) {
        double bestHz = -1, bestVal = -1;
        for (int i = 1; i < env.length - 1; i++) {
            double hz = i * (double) RATE / FFT_SIZE;
            if (hz < loHz || hz > hiHz) continue;
            if (env[i] > bestVal) {
                bestVal = env[i];
                bestHz = hz;
            }
        }
        return bestHz;
    }

    static double[] levinsonDurbin(double[] x, int p) {
        int n = x.length;
        double[] r = new double[p + 1];
        for (int k = 0; k <= p; k++) {
            double s = 0;
            for (int i = 0; i < n - k; i++) s += x[i] * x[i + k];
            r[k] = s;
        }
        if (r[0] < 1e-12) return null;
        double[] a = new double[p + 1];
        double[] err = new double[p + 1];
        a[0] = 1.0;
        err[0] = r[0];
        for (int i = 1; i <= p; i++) {
            double kappa = r[i];
            for (int j = 1; j < i; j++) kappa -= a[j] * r[i - j];
            if (err[i - 1] < 1e-12) break;
            kappa /= err[i - 1];
            if (!Double.isFinite(kappa) || Math.abs(kappa) > 1) break;
            double[] an = new double[p + 1];
            an[0] = 1.0;
            for (int j = 1; j < i; j++) an[j] = a[j] - kappa * a[i - j];
            an[i] = kappa;
            a = an;
            err[i] = err[i - 1] * (1 - kappa * kappa);
        }
        return a;
    }

    // ── Helpers ──

    static double[] hamming(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
        return w;
    }

    static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    static double median(double[] sorted) {
        return percentile(sorted, 50);
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double idx = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted[lo];
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String fmt(double d) {
        return String.format("%.1f", d);
    }
}
