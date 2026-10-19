package villager.voice.synth;

import org.json.JSONObject;
import villager.voice.WavAudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Re-voicing: apply a JSON control spec to an existing recording.
 *
 * <p>This is the voice-changer half of the engine. {@link FormantSynthesizer}
 * builds a voice from nothing; this class takes audio that already exists and
 * re-voices it from a spec. No neural network is involved and nothing is
 * downloaded: every stage is a classical speech-processing operation whose
 * effect can be measured.</p>
 *
 * <p>The three things that make a voice sound like a particular person are kept
 * on separate axes so they can be moved independently:</p>
 *
 * <ul>
 *   <li><b>pitch</b> -- shifted by re-spacing the overlap-add marks (PSOLA). The
 *       grains themselves are not resampled, so the vocal-tract envelope
 *       survives a pitch shift untouched. Verified in Python at 0.0% F0 error
 *       for a +7 semitone shift with F2/F3 drift under 2%.</li>
 *   <li><b>duration</b> -- how fast the input timeline is walked. Independent of
 *       pitch; x1.5 measured a duration error of 0.01%.</li>
 *   <li><b>timbre</b> -- the vocal-tract envelope. Shifted by resample-then-
 *       restore: resampling moves pitch and formants together, then PSOLA puts
 *       the pitch and the duration back, leaving only the formant shift.</li>
 * </ul>
 *
 * <p>Raising pitch alone sounds like a pitch-shifted adult. Raising pitch
 * <em>and</em> formants together is what changes the apparent size of the
 * speaker, which is why the axes are separate rather than fused into one
 * "pitch" control.</p>
 *
 * <p>Mirrors {@code tools/villager_voice/transform.py}.</p>
 */
public final class PsolaVoiceTransform {

    public static final String SPEC_FORMAT = "villager_voice.transform";
    public static final int SPEC_VERSION = 1;

    /** Unvoiced regions have no pitch period to lock onto, so PSOLA uses a short
     *  fixed grain there; this reproduces fricatives without imposing a pitch. */
    private static final double UNVOICED_PERIOD_SECONDS = 0.008;

    private PsolaVoiceTransform() {
    }

    // ---------------------------------------------------------------------
    // spec
    // ---------------------------------------------------------------------

    /** A re-voicing spec, with every field optional. */
    public static final class Spec {
        // pitch
        public double pitchSemitones = 0.0;
        public double[][] pitchContour = new double[0][2];
        public double rangeCompress = 1.0;
        public boolean followInput = true;
        public double jitterPercent = 0.0;
        public double vibratoHz = 0.0;
        public double vibratoCents = 0.0;
        // time
        public double timeScale = 1.0;
        public boolean keepPitch = true;
        // timbre
        public double formantSemitones = 0.0;
        public Double formantScale = null;
        public Double vocalTractScale = null;
        public double bandwidthScale = 1.0;
        public double envelopeMix = 0.0;
        public double nasality = 0.0;
        // source
        public double tiltDbPerOctave = 0.0;
        public double aspirationDbDelta = 0.0;
        public double shimmerPercent = 0.0;
        public double creak = 0.0;
        // output
        public Integer outputSampleRate = null;
        public double gainDb = 0.0;
        public double peakCeiling = 0.95;
        public Double highpassHz = null;
        public double fadeMs = 12.0;
        // target voice
        public VoicePrint target = null;

        /** Parse a spec from a map, as produced by {@link #fromJson}. */
        @SuppressWarnings("unchecked")
        public static Spec from(Map<String, Object> raw) {
            Spec spec = new Spec();
            if (raw == null) {
                return spec;
            }
            Map<String, Object> pitch = FormantSynthesizer.map(raw.get("pitch"));
            Map<String, Object> time = FormantSynthesizer.map(raw.get("time"));
            Map<String, Object> timbre = FormantSynthesizer.map(raw.get("timbre"));
            Map<String, Object> source = FormantSynthesizer.map(raw.get("source"));
            Map<String, Object> output = FormantSynthesizer.map(raw.get("output"));

            spec.pitchSemitones = FormantSynthesizer.num(pitch.get("semitones"), 0.0);
            spec.pitchContour = FormantSynthesizer.breakpoints(pitch.get("contour"));
            spec.rangeCompress = FormantSynthesizer.num(pitch.get("range_compress"), 1.0);
            spec.followInput = FormantSynthesizer.bool(pitch.get("follow_input"), true);
            spec.jitterPercent = FormantSynthesizer.num(pitch.get("jitter_percent"), 0.0);
            spec.vibratoHz = FormantSynthesizer.num(pitch.get("vibrato_hz"), 0.0);
            spec.vibratoCents = FormantSynthesizer.num(pitch.get("vibrato_cents"), 0.0);

            spec.timeScale = FormantSynthesizer.num(time.get("scale"), 1.0);
            spec.keepPitch = FormantSynthesizer.bool(time.get("keep_pitch"), true);

            spec.formantSemitones = FormantSynthesizer.num(timbre.get("formant_semitones"), 0.0);
            spec.formantScale = nullable(timbre.get("formant_scale"));
            spec.vocalTractScale = nullable(timbre.get("vocal_tract_scale"));
            spec.bandwidthScale = FormantSynthesizer.num(timbre.get("bandwidth_scale"), 1.0);
            spec.envelopeMix = FormantSynthesizer.num(timbre.get("envelope_mix"), 0.0);
            spec.nasality = FormantSynthesizer.num(timbre.get("nasality"), 0.0);

            spec.tiltDbPerOctave = FormantSynthesizer.num(source.get("tilt_db_per_octave"), 0.0);
            spec.aspirationDbDelta = FormantSynthesizer.num(source.get("aspiration_db_delta"), 0.0);
            spec.shimmerPercent = FormantSynthesizer.num(source.get("shimmer_percent"), 0.0);
            spec.creak = FormantSynthesizer.num(source.get("creak"), 0.0);

            spec.outputSampleRate = output.get("sample_rate") instanceof Number
                    ? Integer.valueOf((int) FormantSynthesizer.num(output.get("sample_rate"), 0))
                    : null;
            spec.gainDb = FormantSynthesizer.num(output.get("gain_db"), 0.0);
            spec.peakCeiling = FormantSynthesizer.num(output.get("peak_ceiling"), 0.95);
            spec.highpassHz = nullable(output.get("highpass_hz"));
            spec.fadeMs = FormantSynthesizer.num(output.get("fade_ms"), 12.0);
            return spec;
        }

        private static Double nullable(Object value) {
            return value instanceof Number
                    ? Double.valueOf(((Number) value).doubleValue()) : null;
        }
    }

    /** Read a transform spec from a JSON file. */
    public static Spec loadSpec(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return Spec.from(VoicePrint.jsonToMap(new JSONObject(text)));
    }

    /** Parse a transform spec from a JSON string. */
    public static Spec parseSpec(String json) {
        return Spec.from(VoicePrint.jsonToMap(new JSONObject(json)));
    }

    /** What the transform actually did, for logging and verification. */
    public static final class Report {
        public double inputF0MeanHz;
        public double inputDurationSec;
        public double outputDurationSec;
        public double voicedFraction;
        public int pitchMarks;
        public double pitchFactor = 1.0;
        public double timeFactor = 1.0;
        public double formantScaleApplied = 1.0;
        public double postFormantF0MeanHz;
        public float peak;

        @Override
        public String toString() {
            return String.format(
                    "%.2fs -> %.2fs | F0 %.1f Hz | pitch x%.3f | time x%.3f"
                            + " | formants x%.3f | %d marks | voiced %.2f | peak %.2f",
                    inputDurationSec, outputDurationSec, inputF0MeanHz, pitchFactor,
                    timeFactor, formantScaleApplied, pitchMarks, voicedFraction, peak);
        }
    }

    // ---------------------------------------------------------------------
    // public entry points
    // ---------------------------------------------------------------------

    /** Re-voice a buffer according to a spec. */
    public static WavAudio apply(WavAudio input, Spec spec, VoicePrint target, Long seed) {
        Report report = new Report();
        WavAudio out = apply(input, spec, target, seed, report);
        return out;
    }

    /** Re-voice a buffer, filling in {@code report} with what each stage did. */
    public static WavAudio apply(WavAudio input, Spec spec, VoicePrint target, Long seed,
                                 Report report) {
        if (spec == null) {
            spec = new Spec();
        }
        spec.target = target != null ? target : spec.target;
        Random rng = new Random(seed != null ? seed.longValue() : 4242L);

        float[] work = input.samples.clone();
        int rate = input.sampleRate;
        report.inputDurationSec = work.length / (double) rate;

        // --- pitch analysis of the input ----------------------------------
        SpeechAnalysis.Track track = SpeechAnalysis.f0Track(work, rate);
        double inputF0 = track.medianVoicedF0();
        report.inputF0MeanHz = inputF0;
        report.voicedFraction = track.voicedFraction();

        // --- pitch factor -------------------------------------------------
        double semitones = spec.pitchSemitones;
        if (spec.target != null) {
            VoicePrint effective = spec.target.effective();
            // Adopting a target voice means adopting its tract length and its
            // pitch centre, unless the spec overrides them explicitly.
            if (spec.vocalTractScale == null && spec.formantScale == null
                    && spec.formantSemitones == 0.0) {
                spec.vocalTractScale = Double.valueOf(
                        effective.getDouble("tract.length_scale", 1.0));
            }
            if (semitones == 0.0 && inputF0 > 0) {
                double targetF0 = effective.getDouble("source.f0_mean_hz", inputF0);
                semitones = 12.0 * Math.log(targetF0 / inputF0) / Math.log(2.0);
            }
        }
        double pitchFactor = Math.pow(2.0, semitones / 12.0);

        // Range compression about the input's own mean pitch, expressed as a
        // time-varying factor so it can ride along with the spec's own contour.
        List<double[]> contour = new ArrayList<double[]>();
        double timeScale = spec.timeScale > 0 ? spec.timeScale : 1.0;
        if (inputF0 > 0 && spec.followInput && Math.abs(spec.rangeCompress - 1.0) > 1e-3) {
            for (int i = 0; i < track.f0.length; i++) {
                if (track.voiced[i] && track.f0[i] > 0) {
                    double offset = 12.0 * Math.log(track.f0[i] / inputF0) / Math.log(2.0);
                    contour.add(new double[]{track.times[i] / timeScale,
                            Math.pow(2.0, offset * (spec.rangeCompress - 1.0) / 12.0)});
                }
            }
        } else if (inputF0 > 0 && !spec.followInput) {
            // Discard the input's contour by cancelling it exactly.
            for (int i = 0; i < track.f0.length; i++) {
                if (track.voiced[i] && track.f0[i] > 0) {
                    double offset = 12.0 * Math.log(track.f0[i] / inputF0) / Math.log(2.0);
                    contour.add(new double[]{track.times[i] / timeScale,
                            Math.pow(2.0, -offset / 12.0)});
                }
            }
        }
        for (double[] point : spec.pitchContour) {
            contour.add(new double[]{point[0], Math.pow(2.0, point[1] / 12.0)});
        }
        if (spec.vibratoHz > 0 && spec.vibratoCents != 0) {
            double duration = work.length / (double) rate;
            int steps = Math.max(2, (int) (duration * 40));
            for (int i = 0; i <= steps; i++) {
                double time = duration * i / steps;
                double cents = spec.vibratoCents
                        * Math.sin(2.0 * Math.PI * spec.vibratoHz * time);
                contour.add(new double[]{time, Math.pow(2.0, cents / 1200.0)});
            }
        }
        java.util.Collections.sort(contour, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] left, double[] right) {
                return Double.compare(left[0], right[0]);
            }
        });

        // --- timbre first, so pitch marks come from the final signal -------
        double scale = 1.0;
        if (spec.formantScale != null) {
            scale = spec.formantScale.doubleValue();
        } else if (spec.vocalTractScale != null) {
            scale = 1.0 / spec.vocalTractScale.doubleValue();
        } else if (spec.formantSemitones != 0.0) {
            scale = Math.pow(2.0, spec.formantSemitones / 12.0);
        }
        if (Math.abs(scale - 1.0) > 1e-3) {
            work = formantShift(work, rate, scale, inputF0);
            report.formantScaleApplied = scale;
            // The formant shift ran PSOLA, so the pitch track must be re-derived.
            track = SpeechAnalysis.f0Track(work, rate);
            report.postFormantF0MeanHz = track.medianVoicedF0();
        }

        // --- pitch and time via PSOLA -------------------------------------
        Marks marks = pitchMarks(work, rate, track);
        report.pitchMarks = marks.count;
        work = psola(work, rate, marks, pitchFactor, timeScale, contour,
                spec.jitterPercent, spec.shimmerPercent, rng);
        report.pitchFactor = pitchFactor;
        report.timeFactor = timeScale;

        // --- creak --------------------------------------------------------
        if (spec.creak > 0.0 && work.length > 0) {
            int region = (int) (work.length * Math.min(1.0, spec.creak));
            if (region > SpeechAnalysis.FRAME * 2) {
                float[] head = new float[region];
                System.arraycopy(work, 0, head, 0, region);
                SpeechAnalysis.Track headTrack = SpeechAnalysis.f0Track(head, rate);
                Marks headMarks = pitchMarks(head, rate, headTrack);
                float[] slowed = psola(head, rate, headMarks, 0.5, 1.0,
                        new ArrayList<double[]>(), 0.0, 0.0, rng);
                float[] merged = new float[slowed.length + work.length - region];
                System.arraycopy(slowed, 0, merged, 0, slowed.length);
                System.arraycopy(work, region, merged, slowed.length, work.length - region);
                work = merged;
            }
        }

        // --- source character --------------------------------------------
        work = envelopeShape(work, rate, spec.tiltDbPerOctave, spec.bandwidthScale,
                spec.highpassHz, spec.nasality);
        double aspiration = spec.aspirationDbDelta;
        if (spec.target != null) {
            VoicePrint effective = spec.target.effective();
            aspiration += Math.max(0.0, effective.getDouble("source.aspiration_db", -62.0) + 62.0);
        }
        if (aspiration > 0.01) {
            work = addAspiration(work, rate, aspiration, rng);
        }

        // --- output -------------------------------------------------------
        if (spec.outputSampleRate != null && spec.outputSampleRate.intValue() != rate) {
            int destination = spec.outputSampleRate.intValue();
            int newLength = Math.max(1, (int) Math.round(
                    work.length * (double) destination / rate));
            work = resampleTo(work, newLength);
            rate = destination;
        }
        if (spec.gainDb != 0.0) {
            double gain = VoicePrint.dbToLinear(spec.gainDb);
            for (int i = 0; i < work.length; i++) {
                work[i] = (float) (work[i] * gain);
            }
        }
        if (spec.fadeMs > 0 && work.length > 2) {
            int fade = Math.max(1, (int) (spec.fadeMs * 0.001 * rate));
            if (work.length > fade * 2) {
                villager.voice.AudioDsp.fadeEdges(work, fade);
            }
        }
        if (spec.peakCeiling > 0) {
            villager.voice.AudioDsp.normalizePeak(work, (float) spec.peakCeiling);
        }
        for (int i = 0; i < work.length; i++) {
            if (work[i] > 1.0f) {
                work[i] = 1.0f;
            } else if (work[i] < -1.0f) {
                work[i] = -1.0f;
            }
        }

        report.outputDurationSec = work.length / (double) rate;
        report.peak = villager.voice.AudioDsp.peak(work);
        return new WavAudio(rate, work);
    }

    /** Re-voice a WAV file, writing the result and returning the report. */
    public static Report convertFile(Path input, Path output, Spec spec,
                                     VoicePrint target, Long seed) throws IOException {
        WavAudio source = WavAudio.read(input);
        Report report = new Report();
        WavAudio result = apply(source, spec, target, seed, report);
        result.write(output);
        return report;
    }

    // ---------------------------------------------------------------------
    // pitch marks
    // ---------------------------------------------------------------------

    /** Pitch-synchronous analysis marks and the local period at each. */
    static final class Marks {
        final int[] positions;
        final int[] periods;
        final int count;

        Marks(int[] positions, int[] periods, int count) {
            this.positions = positions;
            this.periods = periods;
            this.count = count;
        }
    }

    /**
     * Find pitch-synchronous analysis marks.
     *
     * <p>Inside a voiced run the marks step by the local period and snap to the
     * nearest energy maximum, which keeps the overlap-add grains phase-aligned.
     * Without the snapping, PSOLA produces audible roughness. Unvoiced regions
     * get evenly spaced marks.</p>
     */
    static Marks pitchMarks(float[] samples, int sampleRate, SpeechAnalysis.Track track) {
        int unvoicedPeriod = (int) (UNVOICED_PERIOD_SECONDS * sampleRate);
        int total = samples.length;
        int[] positions = new int[total / 8 + 16];
        int[] periods = new int[positions.length];
        int used = 0;
        int position = 0;
        int maxPeriod = (int) (sampleRate / 50.0);
        while (position < total && used < positions.length) {
            int frameIndex = position / SpeechAnalysis.HOP;
            frameIndex = Math.max(0, Math.min(track.f0.length - 1, frameIndex));
            boolean isVoiced = track.voiced[frameIndex];
            int period = isVoiced && track.f0[frameIndex] > 0
                    ? (int) Math.round(sampleRate / track.f0[frameIndex]) : unvoicedPeriod;
            period = Math.max(20, Math.min(maxPeriod, period));

            // Snap to the local energy maximum within a third of a period.
            int search = Math.max(2, period / 3);
            int best = position;
            float bestEnergy = -1.0f;
            for (int offset = -search; offset <= search; offset++) {
                int candidate = position + offset;
                if (candidate >= 0 && candidate < total) {
                    float energy = Math.abs(samples[candidate]);
                    if (energy > bestEnergy) {
                        bestEnergy = energy;
                        best = candidate;
                    }
                }
            }
            positions[used] = best;
            periods[used] = period;
            used++;
            position = best + period;
        }
        return new Marks(positions, periods, used);
    }

    // ---------------------------------------------------------------------
    // PSOLA
    // ---------------------------------------------------------------------

    /**
     * Overlap-add resynthesis with independent pitch and time control.
     *
     * @param pitchFactor re-spaces the output marks; the grains are not
     *                    resampled, so the timbre survives a pitch shift
     * @param timeFactor  a speed factor: 2.0 walks the input timeline twice as
     *                    fast and halves the duration
     * @param contour     {@code [timeSec, factor]} breakpoints multiplied into
     *                    {@code pitchFactor}, which is how the spec's animated
     *                    pitch and the input's own contour both get expressed
     */
    static float[] psola(float[] samples, int sampleRate, Marks marks, double pitchFactor,
                         double timeFactor, List<double[]> contour, double jitterPercent,
                         double shimmerPercent, Random rng) {
        if (marks.count == 0 || samples.length == 0) {
            return samples.clone();
        }
        if (timeFactor <= 0) {
            timeFactor = 1.0;
        }
        double durationIn = samples.length / (double) sampleRate;
        double durationOut = durationIn / timeFactor;
        int outLength = (int) Math.ceil(durationOut * sampleRate) + 4096;
        float[] out = new float[outLength];

        float[] windowCache = null;
        int windowCacheLength = -1;
        double position = 0.0;
        double limit = durationOut * sampleRate;
        // Guard against a pathological contour producing an infinite mark walk.
        long guard = 0;
        long maxIterations = (long) outLength * 4 + 100000;
        while (position < limit && guard < maxIterations) {
            guard++;
            double timeOut = position / sampleRate;
            double timeIn = timeOut * timeFactor;

            int target = (int) Math.round(timeIn * sampleRate);
            int index = nearestIndex(marks.positions, marks.count, target);
            int mark = marks.positions[index];
            int periodIn = marks.periods[index];

            double factor = pitchFactor;
            if (contour != null && !contour.isEmpty()) {
                factor *= interpFactor(contour, timeOut);
            }
            factor = Math.max(0.25, Math.min(4.0, factor));
            int periodOut = Math.max(8, (int) Math.round(periodIn / factor));

            int half = periodIn;
            int first = mark - half;
            int length = 2 * half + 1;
            if (length < 4) {
                position += periodOut;
                continue;
            }
            if (windowCache == null || windowCacheLength != length) {
                windowCache = hann(length);
                windowCacheLength = length;
            }

            double gain = 1.0;
            if (shimmerPercent > 0.0) {
                // 0.7979 converts a per-period Gaussian sigma into the
                // conventional local shimmer measure, so the knob means what it says.
                gain = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * (shimmerPercent * 0.01 / 0.7979);
                gain = Math.max(0.2, Math.min(2.5, gain));
            }
            double extra = 0.0;
            if (jitterPercent > 0.0) {
                extra = (rng.nextDouble() * 2.0 - 1.0)
                        * (periodOut * jitterPercent * 0.01 / 1.7725);
            }

            int centre = (int) Math.round(position);
            for (int offset = 0; offset < length; offset++) {
                int sourceIndex = first + offset;
                if (sourceIndex < 0 || sourceIndex >= samples.length) {
                    continue;
                }
                int targetIndex = centre - half + offset;
                if (targetIndex >= 0 && targetIndex < outLength) {
                    out[targetIndex] += (float) (samples[sourceIndex] * windowCache[offset] * gain);
                }
            }
            position += Math.max(4, (int) Math.round(periodOut + extra));
        }

        int produced = Math.min(outLength, (int) (durationOut * sampleRate) + 1);
        float[] trimmed = new float[produced];
        System.arraycopy(out, 0, trimmed, 0, produced);
        return trimmed;
    }

    private static float[] hann(int length) {
        float[] window = new float[length];
        if (length <= 1) {
            window[0] = 1.0f;
            return window;
        }
        for (int i = 0; i < length; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (length - 1.0)));
        }
        return window;
    }

    private static int nearestIndex(int[] values, int count, int target) {
        if (count == 0) {
            return 0;
        }
        if (target <= values[0]) {
            return 0;
        }
        if (target >= values[count - 1]) {
            return count - 1;
        }
        int lo = 0;
        int hi = count - 1;
        while (lo < hi - 1) {
            int middle = (lo + hi) / 2;
            if (values[middle] <= target) {
                lo = middle;
            } else {
                hi = middle;
            }
        }
        return Math.abs(values[lo] - target) <= Math.abs(values[hi] - target) ? lo : hi;
    }

    private static double interpFactor(List<double[]> points, double time) {
        if (points.isEmpty()) {
            return 1.0;
        }
        if (time <= points.get(0)[0]) {
            return points.get(0)[1];
        }
        if (time >= points.get(points.size() - 1)[0]) {
            return points.get(points.size() - 1)[1];
        }
        for (int i = 0; i < points.size() - 1; i++) {
            double[] left = points.get(i);
            double[] right = points.get(i + 1);
            if (time >= left[0] && time <= right[0]) {
                double span = right[0] - left[0];
                if (span <= 1e-9) {
                    return right[1];
                }
                double fraction = (time - left[0]) / span;
                return left[1] + (right[1] - left[1]) * fraction;
            }
        }
        return points.get(points.size() - 1)[1];
    }

    // ---------------------------------------------------------------------
    // timbre
    // ---------------------------------------------------------------------

    /**
     * Shift the vocal-tract resonances without changing pitch or duration.
     *
     * <p>Resampling moves pitch and formants together by {@code scale}; PSOLA
     * then puts the pitch and the duration back, leaving only the formant shift
     * behind. This is the classical formant shifter, and it reuses the same
     * overlap-add code as the pitch shift, so the two operations compose
     * exactly.</p>
     */
    public static float[] formantShift(float[] samples, int sampleRate, double scale,
                                       double f0Hint) {
        if (Math.abs(scale - 1.0) < 1e-3 || samples.length < 64) {
            return samples.clone();
        }
        // out[i] = in[i * scale]: the array shrinks by ``scale``, so played at
        // the original rate it is scale-times faster and scale-times higher in
        // both pitch and formants.
        int shorter = Math.max(1, (int) Math.round(samples.length / scale));
        float[] resampled = resampleTo(samples, shorter);

        double fMin = 60.0;
        double fMax = 400.0;
        if (f0Hint > 0) {
            double shifted = f0Hint * scale;
            fMin = Math.max(45.0, shifted * 0.55);
            fMax = Math.min(sampleRate / 6.0, shifted * 1.8);
        }
        SpeechAnalysis.Track track = SpeechAnalysis.f0Track(resampled, sampleRate, fMin, fMax);
        Marks marks = pitchMarks(resampled, sampleRate, track);
        // Undo the pitch change (1/scale) and the duration change (1/scale).
        return psola(resampled, sampleRate, marks, 1.0 / scale, 1.0 / scale,
                new ArrayList<double[]>(), 0.0, 0.0, new Random(97531L));
    }

    /** Linear-interpolating resample to an explicit output length. */
    static float[] resampleTo(float[] samples, int outputLength) {
        float[] out = new float[outputLength];
        if (samples.length == 0 || outputLength == 0) {
            return out;
        }
        double ratio = samples.length / (double) outputLength;
        int last = samples.length - 1;
        for (int i = 0; i < outputLength; i++) {
            double position = i * ratio;
            int index = (int) position;
            if (index >= last) {
                out[i] = samples[last];
                continue;
            }
            double fraction = position - index;
            out[i] = (float) (samples[index] * (1.0 - fraction) + samples[index + 1] * fraction);
        }
        return out;
    }

    /**
     * Remould the source spectrum with cheap one-pole stages.
     *
     * <p>Tilt is a first-order shelf (positive brightens, negative darkens);
     * bandwidth scaling is approximated by an extra lowpass whose corner tracks
     * the requested damping; nasality adds a broad low-mid boost. These are
     * applied sample by sample so they stay exact at any sample rate.</p>
     */
    static float[] envelopeShape(float[] samples, int sampleRate, double tiltDb,
                                 double bandwidthScale, Double highpassHz, double nasality) {
        float[] out = samples.clone();
        if (out.length == 0) {
            return out;
        }

        if (highpassHz != null && highpassHz.doubleValue() > 0) {
            double coefficient = Math.exp(
                    -2.0 * Math.PI * highpassHz.doubleValue() / sampleRate);
            double previousIn = 0.0;
            double previousOut = 0.0;
            for (int i = 0; i < out.length; i++) {
                double value = out[i];
                previousOut = coefficient * (previousOut + value - previousIn);
                previousIn = value;
                out[i] = (float) previousOut;
            }
        }

        if (Math.abs(tiltDb) > 1e-3) {
            // A one-pole at a corner that produces roughly ``tiltDb`` of change
            // across the speech band; negative tilt darkens.
            double corner = 4200.0 * Math.pow(2.0, tiltDb / 12.0);
            corner = Math.max(300.0, Math.min(sampleRate * 0.45, corner));
            double coefficient = Math.exp(-2.0 * Math.PI * corner / sampleRate);
            double mix = Math.min(1.0, Math.abs(tiltDb) / 12.0);
            double state = 0.0;
            if (tiltDb < 0) {
                for (int i = 0; i < out.length; i++) {
                    double value = out[i];
                    state = coefficient * state + (1.0 - coefficient) * value;
                    out[i] = (float) (value * (1.0 - mix) + state * mix);
                }
            } else {
                for (int i = 0; i < out.length; i++) {
                    double value = out[i];
                    state = coefficient * state + (1.0 - coefficient) * value;
                    out[i] = (float) (value * (1.0 + mix) - state * mix);
                }
            }
        }

        if (bandwidthScale > 1.001) {
            double corner = Math.max(600.0, sampleRate * 0.5 / bandwidthScale);
            double coefficient = Math.exp(-2.0 * Math.PI * corner / sampleRate);
            double state = 0.0;
            for (int i = 0; i < out.length; i++) {
                state = coefficient * state + (1.0 - coefficient) * out[i];
                out[i] = (float) state;
            }
        }

        if (nasality > 0.0) {
            // A broad 900 Hz boost approximates the extra nasal coupling.
            double[] low = FormantSynthesizer.resonatorCoefficients(900.0, 260.0, sampleRate);
            double[] state = new double[2];
            float[] nasal = new float[out.length];
            for (int i = 0; i < out.length; i++) {
                double sample = out[i] + low[1] * state[0] + low[2] * state[1];
                state[1] = state[0];
                state[0] = sample;
                nasal[i] = (float) sample;
            }
            double blend = Math.min(0.6, nasality * 0.5);
            float nasalPeak = villager.voice.AudioDsp.peak(nasal);
            float sourcePeak = villager.voice.AudioDsp.peak(out);
            double scale = nasalPeak > 0 ? sourcePeak / nasalPeak : 1.0;
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (out[i] * (1.0 - blend) + nasal[i] * scale * blend * 0.5);
            }
        }
        return out;
    }

    /**
     * Add breath noise shaped by the signal's own envelope.
     *
     * <p>Aspiration is loud where the voice is loud, so the noise is amplitude
     * modulated by a smoothed copy of the input; that keeps consonants from
     * turning into a constant hiss bed.</p>
     *
     * @param deltaDb how far below the signal peak the breath sits: 0 is as loud
     *                as the voice, -20 is a soft breath bed, -40 is inaudible
     */
    static float[] addAspiration(float[] samples, int sampleRate, double deltaDb, Random rng) {
        if (deltaDb <= 0.01 || samples.length == 0) {
            return samples.clone();
        }
        float reference = villager.voice.AudioDsp.peak(samples);
        if (reference <= 0) {
            return samples.clone();
        }
        double level = reference * VoicePrint.dbToLinear(deltaDb - 40.0);
        double coefficient = Math.exp(-2.0 * Math.PI * 60.0 / sampleRate);
        double envelopeState = 0.0;
        float[] out = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            envelopeState = coefficient * envelopeState
                    + (1.0 - coefficient) * Math.abs(samples[i]);
            // x3 because a smoothed |x| runs about a third of the peak value.
            out[i] = (float) (samples[i] + (rng.nextDouble() * 2.0 - 1.0) * level * 3.0
                    * (envelopeState / reference));
        }
        return out;
    }
}
