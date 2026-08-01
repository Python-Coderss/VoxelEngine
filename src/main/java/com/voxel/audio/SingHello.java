package com.voxel.audio;

import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * SingHello — sings "hello world" through the Klatt formant synthesizer using
 * the voice profile produced by {@link FormantAnalyzer}.
 * <p>
 * Usage: com.voxel.audio.SingHello [outDir]
 * Reads {@code outDir/dan_profile.json} (falls back to defaults), writes
 * {@code outDir/hello_world.wav} and a {@code sing_report.txt}.
 */
public class SingHello {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "tools/audio/out";
        File profileFile = new File(outDir, "dan_profile.json");
        File outWav = new File(outDir, "hello_world.wav");
        File reportFile = new File(outDir, "sing_report.txt");
        outWav.getParentFile().mkdirs();

        StringBuilder rep = new StringBuilder();
        rep.append("=== SingHello ===\n");

        // ── Load profile (or defaults) ──
        double f0Mode = 120, breathiness = 0.10, tilt = -5.0;
        double[] delta = {0, 0, 0};
        JSONObject vowels = new JSONObject();
        if (profileFile.exists()) {
            String txt = new String(Files.readAllBytes(profileFile.toPath()), StandardCharsets.UTF_8);
            JSONObject p = new JSONObject(txt);
            JSONObject f0 = p.optJSONObject("f0");
            if (f0 != null) f0Mode = f0.optDouble("mode", 120);
            breathiness = p.optDouble("breathiness", 0.10);
            tilt = p.optDouble("spectralTilt", -5.0);
            if (p.has("vowelDelta")) {
                org.json.JSONArray d = p.getJSONArray("vowelDelta");
                for (int i = 0; i < 3 && i < d.length(); i++) delta[i] = d.getDouble(i);
            }
            vowels = p.optJSONObject("vowels");
            rep.append("profile loaded: ").append(profileFile).append("\n");
            rep.append("  f0 mode=").append(fmt(f0Mode)).append(" breathiness=").append(fmt(breathiness))
                    .append(" tilt=").append(fmt(tilt)).append("\n");
        } else {
            rep.append("profile not found — using default template\n");
        }

        // ── Vowel lookup with delta applied ──
        double[] v = vowel(vowels, "@", delta); // schwa
        rep.append("schwa from profile: F1=").append(fmt(v[0])).append(" F2=").append(fmt(v[1]))
                .append(" F3=").append(fmt(v[2])).append("\n");

        // ── Build "hello world" phoneme sequence (BrE-ish) ──
        // Melody anchored near Dan's modal pitch (profile F0 mode ≈ 170 Hz).
        // The earlier "too high" came from the melody CLIMBING to e4 (287 Hz) —
        // a straining top for any male singer — not from the tonic itself.
        // Keep the tonic at ~0.95x his modal pitch and cap the phrase at d4
        // (~243 Hz), a comfortable male range, so it keeps Dan's pitch identity
        // without reaching for the top. (0.78x = 133 Hz sounded like a different,
        // deeper person — generic.)
        double tonic = Math.min(175, Math.max(145, f0Mode * 0.95));
        double g3 = tonic;
        double a3 = tonic * Math.pow(2, 2.0 / 12);   // +2 st
        double c4 = tonic * Math.pow(2, 5.0 / 12);   // +5 st
        double d4 = tonic * Math.pow(2, 7.0 / 12);   // +7 st

        List<KlattSynth.Seg> segs = new ArrayList<>();

        // h  — aspiration into schwa
        segs.add(new KlattSynth.Seg(70, v[0], v[1], v[2], 0, 0, 0.15, 0.55).withNoiseFreq(1500));
        // ə  — schwa, "hell-"
        double[] o = vowel(vowels, "Q", delta);   // o from template ("Q" hod)
        double[] u = vowel(vowels, "u", delta);   // u for the oʊ offglide
        double[] er = vowel(vowels, "3", delta);  // ɜː for "world"
        // Sung contour: HOLD the vowels steady, glide only on the consonants.
        // Constant note-to-note sliding on every syllable sounds like wailing /
        // straining — the "choking" complaint.
        segs.add(new KlattSynth.Seg(110, v[0], v[1], v[2], g3, g3, 1.0, 0.01));
        // l  — lateral, transition into the o
        segs.add(new KlattSynth.Seg(80, 360 + delta[0], 1300 + delta[1], 2500 + delta[2],
                g3, c4, 1.0, 0.01));
        // oʊ — diphthong, "-lo" (held, no straining climb)
        segs.add(new KlattSynth.Seg(170, o[0], o[1], o[2], c4, c4, 1.0, 0.01)
                .withTransition(u[0], u[1], u[2]));
        segs.add(new KlattSynth.Seg(110, u[0], u[1], u[2], c4, d4, 1.0, 0.01));
        // breath before "world"
        segs.add(new KlattSynth.Seg(70, u[0], u[1], u[2], 0, 0, 0, 0));
        // w  — glide into ɜː, "world", descending phrase
        segs.add(new KlattSynth.Seg(80, u[0], u[1], u[2], d4, d4, 0.9, 0.02)
                .withTransition(er[0], er[1], er[2]));
        segs.add(new KlattSynth.Seg(200, er[0], er[1], er[2], d4, c4, 1.0, 0.01));
        // l  — final lateral
        segs.add(new KlattSynth.Seg(90, 360 + delta[0], 1300 + delta[1], 2500 + delta[2],
                c4, a3, 1.0, 0.01));
        // d  — stop: closure then release burst
        segs.add(new KlattSynth.Seg(70, 200 + delta[0], 1700, 2600, a3, a3, 0.35, 0.0));
        segs.add(new KlattSynth.Seg(45, 200 + delta[0], 1700, 2600, a3, g3, 0.1, 0.8)
                .withNoiseFreq(2500));
        // tail
        segs.add(new KlattSynth.Seg(150, 500, 1500, 2500, 0, 0, 0, 0));

        // ── Render ──
        KlattSynth synth = new KlattSynth(breathiness, tilt);
        float[] samples = synth.render(segs, 5.5, 0.02);
        AudioData out = new AudioData(samples, 1, KlattSynth.FS);
        WavIO.write(outWav, out);

        double dur = out.durationSeconds();
        rep.append("rendered ").append(segs.size()).append(" segments -> ")
                .append(fmt(dur)).append("s\n");
        rep.append("wrote ").append(outWav).append(" (").append(fmt(samples.length / 1024.0 / 1024.0))
                .append(" MB, ").append(KlattSynth.FS).append(" Hz mono)\n");
        rep.append("melody tonic: ").append(fmt(tonic)).append(" Hz (f0 mode ").append(fmt(f0Mode))
                .append(" Hz); contour g3-c4-d4 / d4-c4-a3-g3, vowels held\n");
        rep.append("spectral shaping: low-shelf +")
                .append(fmt(KlattSynth.bassBoostDb(tilt))).append(" dB @300 Hz, formant taper 1.0/0.95/0.8/0.6/0.45, F0 jitter ±1%\n");

        try (PrintWriter pw = new PrintWriter(reportFile)) {
            pw.print(rep);
        }
        System.out.print(rep);
    }

    /** Look up a vowel formant triple, add the delta if profile has it, else template. */
    static double[] vowel(JSONObject vowels, String key, double[] delta) {
        if (vowels != null && vowels.has(key)) {
            org.json.JSONArray a = vowels.getJSONArray(key);
            return new double[]{a.getDouble(0), a.getDouble(1), a.getDouble(2)};
        }
        double[][] template = {
                {270, 2290, 3010}, {390, 1990, 2550}, {530, 1840, 2480}, {660, 1720, 2410},
                {730, 1090, 2440}, {570, 840, 2410}, {570, 840, 2410}, {440, 1020, 2240},
                {300, 870, 2240}, {640, 1190, 2390}, {490, 1350, 1690}, {500, 1500, 2500},
        };
        String[] keys = {"i", "I", "E", "ae", "A", "Q", "c", "U", "u", "V", "3", "@"};
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                double[] t = template[i];
                return new double[]{
                        Math.max(150, t[0] + delta[0]),
                        Math.max(400, t[1] + delta[1]),
                        Math.max(1200, t[2] + delta[2]),
                };
            }
        }
        return new double[]{500 + delta[0], 1500 + delta[1], 2500 + delta[2]};
    }

    static String fmt(double d) {
        return String.format("%.1f", d);
    }
}
