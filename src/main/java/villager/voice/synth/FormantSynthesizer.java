package villager.voice.synth;

import villager.voice.AudioDsp;
import villager.voice.WavAudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Source-filter formant synthesiser: the villager voice, recreated.
 *
 * <p>Classic speech synthesis, no models and no network:</p>
 *
 * <ol>
 *   <li><b>Source.</b> A Rosenberg (or LF-style, pulse, buzz) glottal flow
 *       waveform whose period is driven by the F0 contour and perturbed by
 *       jitter, shimmer, creak, subharmonic mix and vibrato, plus aspiration
 *       noise for breathiness.</li>
 *   <li><b>Tract.</b> A five-resonator cascade (Klatt digital resonators) driven
 *       to per-phone formant targets and smoothed by the coarticulation setting,
 *       so vowels lean into each other the way real speech does. A parallel
 *       branch adds frication and stop bursts; a nasal pole/zero pair handles
 *       nasality.</li>
 *   <li><b>Output.</b> Lip radiation (+6 dB/oct), spectral tilt, high-frequency
 *       loss, band limiting, optional room, gain, two-sided peak normalisation
 *       and fades.</li>
 * </ol>
 *
 * <p>Everything is read from the voiceprint and the score, so every stage is
 * controllable from JSON, and a render is deterministic for a given
 * {@code random_seed}. Mirrors {@code tools/villager_voice/synth.py}; the Python
 * implementation is the verified reference (its round trip recovers F0 to 1.0%
 * and F2/F3 to 2.6%/0.6%). Random streams differ between the two languages, so
 * a given seed produces statistically identical but not bit-identical audio.</p>
 */
public final class FormantSynthesizer {

    /** Control-rate block: parameter changes happen once per block, ~1.3 ms at 24 kHz. */
    public static final int BLOCK = 32;

    /** Tract shape used where no phone covers a block (pauses, lead-in, tail). */
    public static final double[] NEUTRAL = {500.0, 1500.0, 2500.0, 3400.0, 4300.0};
    public static final double[] NEUTRAL_BW = {70.0, 100.0, 160.0, 210.0, 270.0};

    /**
     * Jitter and shimmer are specified as the conventional <em>local</em>
     * measures, mean|T<sub>i</sub> - T<sub>i-1</sub>| / mean(T). A per-period
     * Gaussian of standard deviation sigma produces a local measure of
     * sigma * sqrt(2) * sqrt(2/pi) = 1.128 sigma, so the perturbation is scaled
     * by 1/1.128 to make the knob mean what it says.
     */
    private static final double PERTURBATION_TO_LOCAL =
            1.0 / (Math.sqrt(2.0) * Math.sqrt(2.0 / Math.PI));

    private FormantSynthesizer() {
    }

    // ---------------------------------------------------------------------
    // control tracks
    // ---------------------------------------------------------------------

    /** Per-block control arrays covering one rendered utterance. */
    static final class Tracks {
        final int blocks;
        int totalSamples;
        final double[] amp;
        final double[] noiseCenter;
        final double[] noiseQ;
        // Reassigned in place by the coarticulation smoother, so not final.
        double[] f0;
        double[] voice;
        double[] noise;
        double[] nasal;
        final double[][] formants;
        final double[][] bandwidths;
        /** Blocks where the score's breathiness contour overrides the voiceprint. */
        final Map<Integer, Double> breathOverride = new LinkedHashMap<Integer, Double>();

        Tracks(int blocks) {
            this.blocks = blocks;
            this.f0 = new double[blocks];
            this.amp = new double[blocks];
            this.voice = new double[blocks];
            this.noise = new double[blocks];
            this.noiseCenter = new double[blocks];
            this.noiseQ = new double[blocks];
            this.nasal = new double[blocks];
            this.formants = new double[blocks][];
            this.bandwidths = new double[blocks][];
            // Blocks no phone covers must hold a *neutral* tract shape. Zeroed
            // formants clamp to 20 Hz resonators with Q near 400, which ring for
            // hundreds of milliseconds and dump low-frequency garbage into the
            // next phone's attack.
            for (int i = 0; i < blocks; i++) {
                this.noiseCenter[i] = 2500.0;
                this.noiseQ[i] = 1.0;
                this.formants[i] = NEUTRAL.clone();
                this.bandwidths[i] = NEUTRAL_BW.clone();
            }
        }
    }

    // ---------------------------------------------------------------------
    // public entry points
    // ---------------------------------------------------------------------

    /**
     * Render a performance score to audio.
     *
     * @param score     the score map, as produced by {@link ProsodyScore} or read
     *                  from a hand-written score JSON
     * @param print     the voiceprint; when null, one embedded in the score is
     *                  used, and failing that the defaults
     * @param seed      random seed override, or null to use the voiceprint's
     */
    @SuppressWarnings("unchecked")
    public static WavAudio render(Map<String, Object> score, VoicePrint print, Integer seed) {
        if (print == null) {
            Object embedded = score.get("voiceprint");
            print = embedded instanceof Map
                    ? new VoicePrint((Map<String, Object>) embedded) : VoicePrint.defaults();
        }
        VoicePrint effective = print.effective();
        Map<String, Object> source = effective.section("source");
        Map<String, Object> tract = effective.section("tract");
        Map<String, Object> output = effective.section("output");

        int sampleRate = (int) num(output.get("sample_rate"), 24000);
        long randomSeed = seed != null ? seed.longValue()
                : (long) num(effective.get("random_seed"), 20261004);
        Random rng = new Random(randomSeed);

        Tracks track = buildTracks(score, effective, sampleRate);
        int blocks = track.blocks;
        int totalSamples = track.totalSamples;

        String modelName = str(source.get("model"), "rosenberg");
        boolean whisperOnly = "whisper".equals(modelName);
        double openQuotient = num(source.get("open_quotient"), 0.62);
        double skew = num(source.get("speed_ratio"), 0.42);
        double jitter = num(source.get("jitter_percent"), 0.85) / 100.0 * PERTURBATION_TO_LOCAL;
        double shimmer = num(source.get("shimmer_percent"), 3.5) / 100.0 * PERTURBATION_TO_LOCAL;
        double aspirationDb = num(source.get("aspiration_db"), -62.0);
        double breathiness = num(source.get("breathiness"), 0.22);
        double creak = num(source.get("creak_percent"), 0.0);
        double subharmonic = num(source.get("subharmonic_mix"), 0.0);
        double vibratoRate = num(source.get("vibrato_rate_hz"), 5.1);
        double vibratoDepth = num(source.get("vibrato_depth_cents"), 24.0) / 1200.0;
        double vibratoOnset = num(source.get("vibrato_onset_ms"), 220.0) / 1000.0;
        double drift = num(source.get("pitch_drift_cents_per_sec"), -3.0) / 100.0;
        double sourceTilt = num(source.get("source_tilt_db_per_oct"), -9.0);

        double nasalFormant = num(tract.get("nasal_formant_hz"), 285.0);
        double nasalBandwidth = num(tract.get("nasal_bandwidth_hz"), 95.0);
        double nasalZero = num(tract.get("nasal_zero_hz"), 950.0);
        double nasalZeroBw = num(tract.get("nasal_zero_bandwidth_hz"), 130.0);
        double radiation = num(tract.get("radiation_db_per_oct"), 6.0) / 6.0;
        double tilt = num(tract.get("spectral_tilt_db_per_oct"), -3.0);
        double hfLoss = num(tract.get("hf_loss_hz"), 6800.0);
        int order = (int) num(tract.get("cascade_order"), 5);
        order = Math.max(1, Math.min(5, order));

        // --- per-block filter coefficients --------------------------------
        double[][][] coefficients = new double[blocks][order][3];
        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < order; i++) {
                coefficients[b][i] = resonatorCoefficients(
                        track.formants[b][i], track.bandwidths[b][i], sampleRate);
            }
        }
        double[] nasalPole = resonatorCoefficients(nasalFormant, nasalBandwidth, sampleRate);
        double[] nasalZeroCoefficients =
                antiresonatorCoefficients(nasalZero, nasalZeroBw, sampleRate);
        double hfCoefficient = 1.0 - Math.exp(-2.0 * Math.PI * hfLoss / sampleRate);

        // --- sample loop ---------------------------------------------------
        double[] samples = new double[totalSamples];
        double phase = 0.0;
        double subPhase = 0.0;
        double periodGain = 1.0;
        // A pitch period (~207 samples at 116 Hz) spans about six control blocks
        // of 32 samples, so the per-cycle perturbation must live *outside* the
        // block loop. Re-deriving it from the contour each block overwrote the
        // jitter before it could affect most of the cycle, which made the knob
        // measure as a no-op.
        double periodMultiplier = 1.0;
        double voicedTime = 0.0;

        double[][] state = new double[5][2];
        double[] nasalState = new double[2];
        double[] zeroState = new double[2];
        double[] noiseState = new double[2];
        double tiltLow = 0.0;
        double sourceLow = 0.0;
        double hfLow = 0.0;
        double previousOutput = 0.0;
        double noiseA = 1.0;
        double noiseB1 = 0.0;
        double noiseB2 = 0.0;

        for (int b = 0; b < blocks; b++) {
            int blockStart = b * BLOCK;
            if (blockStart >= totalSamples) {
                break;
            }
            int blockEnd = Math.min(totalSamples, blockStart + BLOCK);

            double baseF0 = track.f0[b];
            double voiceGain = track.voice[b];
            double noiseGain = track.noise[b];
            double amp = track.amp[b];
            double nasalMix = track.nasal[b];
            double center = track.noiseCenter[b];
            double q = Math.max(0.25, track.noiseQ[b]);

            double[] noiseCoefficients =
                    resonatorCoefficients(center, Math.max(80.0, center / q), sampleRate);
            noiseA = noiseCoefficients[0];
            noiseB1 = noiseCoefficients[1];
            noiseB2 = noiseCoefficients[2];

            if (voiceGain > 0.0 && !whisperOnly) {
                voicedTime += (blockEnd - blockStart) / (double) sampleRate;
            } else {
                voicedTime = 0.0;
            }

            double vibrato = 0.0;
            if (vibratoDepth > 0.0 && vibratoRate > 0.0 && voicedTime > vibratoOnset) {
                double ramp = Math.min(1.0, (voicedTime - vibratoOnset) / 0.35);
                vibrato = vibratoDepth * ramp;
            }

            double timeSeconds = blockStart / (double) sampleRate;
            double driftFactor = 1.0 + drift * timeSeconds * 0.01;

            // A score's breathiness contour overrides the voiceprint value for
            // this block. effective() gives 30 dB of aspiration travel per unit
            // of breathiness, so the override converts back with the same rule.
            Double override = track.breathOverride.get(b);
            double blockBreathiness = breathiness;
            double blockAspirationDb = aspirationDb;
            if (override != null) {
                blockBreathiness = override.doubleValue();
                blockAspirationDb = aspirationDb + 30.0 * (blockBreathiness - breathiness);
            }
            double aspirationLevel = VoicePrint.dbToLinear(blockAspirationDb);

            for (int n = blockStart; n < blockEnd; n++) {
                // --- source ------------------------------------------------
                double sourceValue;
                if (whisperOnly) {
                    sourceValue = 0.0;
                } else if (voiceGain > 1e-5 && baseF0 > 1.0) {
                    double instant = baseF0 * periodMultiplier * driftFactor;
                    if (vibrato != 0.0) {
                        instant *= 1.0 + vibrato
                                * Math.sin(2.0 * Math.PI * vibratoRate * (n / (double) sampleRate));
                    }
                    if (instant < 1.0) {
                        instant = 1.0;
                    }
                    phase += instant / sampleRate;
                    if (phase >= 1.0) {
                        phase -= 1.0;
                        // New glottal period: apply jitter, shimmer and creak.
                        periodMultiplier = 1.0;
                        if (jitter > 0.0) {
                            periodMultiplier += jitter * rng.nextGaussian();
                            periodMultiplier = Math.max(0.5, Math.min(2.0, periodMultiplier));
                        }
                        periodGain = 1.0 + (shimmer > 0.0 ? shimmer * rng.nextGaussian() : 0.0);
                        if (creak > 0.0 && rng.nextDouble() < creak * 0.25) {
                            periodGain *= 0.55;
                            periodMultiplier *= 0.5;
                        }
                    }
                    sourceValue = glottal(modelName, phase, openQuotient, skew) * periodGain;
                    if (subharmonic > 0.0) {
                        subPhase += (baseF0 * periodMultiplier * 0.5) / sampleRate;
                        if (subPhase >= 1.0) {
                            subPhase -= 1.0;
                        }
                        sourceValue = sourceValue * (1.0 - subharmonic)
                                + subharmonic * glottalBuzz(subPhase, openQuotient, skew);
                    }
                    sourceValue *= voiceGain;
                } else {
                    sourceValue = 0.0;
                }

                double white = rng.nextDouble() * 2.0 - 1.0;

                // Source spectral tilt: a leaky integrator darkens the excitation.
                sourceLow += (sourceValue - sourceLow) * 0.08;
                if (sourceTilt < 0.0) {
                    double amount = Math.max(-0.9, sourceTilt / 24.0);
                    sourceValue = sourceValue + amount * (sourceValue - sourceLow);
                }

                double excitation = sourceValue + white * aspirationLevel
                        * Math.max(voiceGain, 0.25 * blockBreathiness);

                // --- frication / burst noise (parallel branch) -------------
                double noiseOut = 0.0;
                if (noiseGain > 1e-5) {
                    double y1 = noiseState[0];
                    double y2 = noiseState[1];
                    double value = noiseA * white + noiseB1 * y1 + noiseB2 * y2;
                    noiseState[0] = value;
                    noiseState[1] = y1;
                    noiseOut = value * noiseGain * 2.2;
                }

                // --- cascade tract -----------------------------------------
                double value = excitation;
                for (int i = 0; i < order; i++) {
                    double a = coefficients[b][i][0];
                    double b1 = coefficients[b][i][1];
                    double b2 = coefficients[b][i][2];
                    double y1 = state[i][0];
                    double y2 = state[i][1];
                    value = a * value + b1 * y1 + b2 * y2;
                    state[i][0] = value;
                    state[i][1] = y1;
                }

                // --- nasal coupling ----------------------------------------
                if (nasalMix > 0.001) {
                    double y1 = nasalState[0];
                    double y2 = nasalState[1];
                    double pole = nasalPole[0] * value + nasalPole[1] * y1 + nasalPole[2] * y2;
                    nasalState[0] = pole;
                    nasalState[1] = y1;
                    double z1 = zeroState[0];
                    double z2 = zeroState[1];
                    double zeroed = nasalZeroCoefficients[0] * pole
                            + nasalZeroCoefficients[1] * z1 + nasalZeroCoefficients[2] * z2;
                    zeroState[0] = pole;
                    zeroState[1] = z1;
                    value = value * (1.0 - nasalMix) + zeroed * nasalMix;
                }

                value += noiseOut;

                // --- radiation, tilt, HF loss ------------------------------
                // Lip radiation is a first difference (+6 dB/oct). It turns the
                // glottal *flow* into the radiated pressure wave, and is the only
                // thing offsetting the -12 dB/oct source rolloff plus the
                // cascade's unity DC gain.
                if (radiation > 0.0) {
                    double difference = value - previousOutput;
                    previousOutput = value;
                    value = value * (1.0 - radiation) + difference * radiation;
                }
                tiltLow += (value - tiltLow) * 0.03;
                if (tilt != 0.0) {
                    double amount = Math.max(-0.9, Math.min(0.9, tilt * 0.14));
                    value = value + amount * (value - tiltLow);
                }
                hfLow += (value - hfLow) * hfCoefficient;
                value = hfLow;

                samples[n] = value * amp;
            }
        }

        float[] out = new float[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            out[i] = (float) samples[i];
        }
        out = overlayEvents(out, score, effective, sampleRate, rng);
        outputStage(out, effective, sampleRate);
        return new WavAudio(sampleRate, out);
    }

    /** Render a score with the voiceprint's own seed. */
    public static WavAudio render(Map<String, Object> score, VoicePrint print) {
        return render(score, print, null);
    }

    /**
     * Speak a line of text with a voiceprint.
     *
     * @param delivery optional per-line overrides (emotion, rate, pitch, ...),
     *                 mirroring the existing {@code SpeechOptions} fields
     */
    public static WavAudio renderText(String text, VoicePrint print,
                                      Map<String, Object> delivery) {
        return render(ProsodyScore.build(text, print, delivery, null), print, null);
    }

    /** Speak a line of text with no delivery overrides. */
    public static WavAudio renderText(String text, VoicePrint print) {
        return renderText(text, print, null);
    }

    // ---------------------------------------------------------------------
    // non-speech vocalisations
    // ---------------------------------------------------------------------

    /**
     * Render a standalone vocalisation: {@code laugh}, {@code hmm}, {@code sigh}
     * or {@code gasp}. Returns an empty array for an unknown kind.
     */
    static float[] renderEvent(String kind, VoicePrint effective, int sampleRate,
                               Double duration, Random rng) {
        double f0Mean = effective.getDouble("source.f0_mean_hz", 116.0);
        if ("laugh".equals(kind)) {
            // A laugh is a series of short stressed schwa pulses with a falling
            // pitch staircase, so it is expressed as a score and rendered by the
            // same path as speech rather than by a special case.
            double total = duration != null ? duration.doubleValue() : 0.6;
            int pulses = Math.max(2, (int) (total / 0.16));
            List<Object> slots = new ArrayList<Object>();
            List<Object> f0Points = new ArrayList<Object>();
            List<Object> ampPoints = new ArrayList<Object>();
            double t = 0.0;
            double[] targets = VillagerPhones.formantTargets("AX", 0);
            double[] bandwidths = VillagerPhones.bandwidthTargets("AX", 0);
            for (int i = 0; i < pulses; i++) {
                Map<String, Object> slot = new LinkedHashMap<String, Object>();
                slot.put("sym", "AX");
                slot.put("start", round4(t));
                slot.put("dur", 0.10);
                slot.put("stress", 0);
                slot.put("amp", i % 2 == 0 ? 0.9 : 0.6);
                slot.put("voiced", Boolean.TRUE);
                slot.put("f", toList(targets));
                slot.put("bw", toList(bandwidths));
                slot.put("manner", VillagerPhones.VOWEL);
                slot.put("word", 0);
                slot.put("syll", i);
                slots.add(slot);
                f0Points.add(pair(round4(t), 3.0 - i * 0.6));
                ampPoints.add(pair(round4(t), 2.0));
                f0Points.add(pair(round4(t + 0.1), -1.0));
                ampPoints.add(pair(round4(t + 0.1), -14.0));
                t += 0.16;
            }
            Map<String, Object> score = new LinkedHashMap<String, Object>();
            score.put("phones", slots);
            score.put("pauses", new ArrayList<Object>());
            Map<String, Object> scoreContours = new LinkedHashMap<String, Object>();
            scoreContours.put("f0", f0Points);
            scoreContours.put("amp", ampPoints);
            score.put("contours", scoreContours);
            score.put("duration", round4(t));
            WavAudio audio = render(score, effective, 0);
            return audio.samples;
        }
        if ("hmm".equals(kind) || "murmur".equals(kind)) {
            int length = (int) ((duration != null ? duration.doubleValue() : 0.5) * sampleRate);
            return toneBurst(length, sampleRate, f0Mean, effective, rng, "hmm", 0.0, false);
        }
        if ("sigh".equals(kind)) {
            int length = (int) ((duration != null ? duration.doubleValue() : 0.8) * sampleRate);
            return toneBurst(length, sampleRate, f0Mean * 0.9, effective, rng,
                    "falling", 1.0, false);
        }
        if ("gasp".equals(kind)) {
            int length = (int) ((duration != null ? duration.doubleValue() : 0.3) * sampleRate);
            return toneBurst(length, sampleRate, f0Mean, effective, rng,
                    "rising", 1.4, true);
        }
        return new float[0];
    }

    /** A single sustained vocalisation used by {@link #renderEvent}. */
    private static float[] toneBurst(int length, int sampleRate, double f0,
                                     VoicePrint effective, Random rng, String contour,
                                     double breathy, boolean unvoiced) {
        if (length <= 0) {
            return new float[0];
        }
        double openQuotient = effective.getDouble("source.open_quotient", 0.62);
        double skew = effective.getDouble("source.speed_ratio", 0.42);
        String symbol = "hmm".equals(contour) ? "HMM" : "AX";
        double[] targets = VillagerPhones.formantTargets(symbol, 0);
        double[] bandwidths = VillagerPhones.bandwidthTargets(symbol, 0);
        double lengthScale = effective.getDouble("tract.length_scale", 1.0);
        double[][] coefficients = new double[5][];
        for (int i = 0; i < 5; i++) {
            coefficients[i] = resonatorCoefficients(targets[i] * lengthScale,
                    bandwidths[i], sampleRate);
        }
        double[] out = new double[length];
        double[][] state = new double[5][2];
        double phase = 0.0;
        double aspiration = VoicePrint.dbToLinear(
                effective.getDouble("source.aspiration_db", -62.0)
                        + 26.0 * Math.max(breathy,
                                effective.getDouble("source.breathiness", 0.2)));
        for (int n = 0; n < length; n++) {
            double progress = n / (double) Math.max(1, length - 1);
            double instant;
            if ("falling".equals(contour)) {
                instant = f0 * (1.0 - 0.28 * progress);
            } else if ("rising".equals(contour)) {
                instant = f0 * (1.0 + 0.22 * progress);
            } else if ("hmm".equals(contour)) {
                instant = f0 * (1.0 + 0.03 * Math.sin(2.0 * Math.PI * 3.1 * progress));
            } else {
                instant = f0;
            }
            double envelope = Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, progress)));
            double value;
            if (unvoiced) {
                value = (rng.nextDouble() * 2.0 - 1.0) * envelope;
            } else {
                phase += instant / sampleRate;
                if (phase >= 1.0) {
                    phase -= 1.0;
                }
                value = glottalRosenberg(phase, openQuotient, skew) * envelope;
                value += (rng.nextDouble() * 2.0 - 1.0) * aspiration * envelope;
            }
            for (int i = 0; i < 5; i++) {
                double a = coefficients[i][0];
                double b1 = coefficients[i][1];
                double b2 = coefficients[i][2];
                double y1 = state[i][0];
                double y2 = state[i][1];
                value = a * value + b1 * y1 + b2 * y2;
                state[i][0] = value;
                state[i][1] = y1;
            }
            out[n] = value * 0.5;
        }
        double peakValue = 0.0;
        for (double value : out) {
            peakValue = Math.max(peakValue, Math.abs(value));
        }
        float[] result = new float[length];
        double factor = peakValue > 0.0 ? 0.85 / peakValue : 1.0;
        for (int i = 0; i < length; i++) {
            result[i] = (float) (out[i] * factor);
        }
        return result;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static List<Object> pair(double time, double value) {
        List<Object> out = new ArrayList<Object>(2);
        out.add(Double.valueOf(time));
        out.add(Double.valueOf(value));
        return out;
    }

    private static List<Object> toList(double[] values) {
        List<Object> out = new ArrayList<Object>(values.length);
        for (double value : values) {
            out.add(Double.valueOf(value));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // track construction
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Tracks buildTracks(Map<String, Object> score, VoicePrint effective, int sampleRate) {
        Map<String, Object> source = effective.section("source");
        Map<String, Object> tract = effective.section("tract");
        Map<String, Object> articulation = effective.section("articulation");

        double duration = Math.max(0.05, num(score.get("duration"), 0.0));
        for (Object item : list(score.get("phones"))) {
            Map<String, Object> slot = map(item);
            duration = Math.max(duration, num(slot.get("start"), 0.0) + num(slot.get("dur"), 0.0));
        }
        for (Object item : list(score.get("pauses"))) {
            Map<String, Object> pause = map(item);
            duration = Math.max(duration,
                    num(pause.get("at"), 0.0) + num(pause.get("dur"), 0.0));
        }
        int totalSamples = (int) Math.ceil(duration * sampleRate) + BLOCK * 4;
        int blocks = Math.max(2, (totalSamples + BLOCK - 1) / BLOCK);

        Tracks track = new Tracks(blocks);
        double[] blockTimes = new double[blocks];
        for (int i = 0; i < blocks; i++) {
            blockTimes[i] = (i + 0.5) * BLOCK / (double) sampleRate;
        }

        Map<String, Object> contours = map(score.get("contours"));
        double[] f0Contour = interpPoints(breakpoints(contours.get("f0")), blockTimes, 0.0);
        double[] ampContour = interpPoints(breakpoints(contours.get("amp")), blockTimes, 0.0);
        double[] scaleContour = interpPoints(breakpoints(contours.get("formant_scale")),
                blockTimes, 1.0);
        double[] breathContour = interpPoints(breakpoints(contours.get("breathiness")),
                blockTimes, -1.0);

        double f0Mean = num(source.get("f0_mean_hz"), 116.0);
        double f0Floor = num(source.get("f0_floor_hz"), 68.0);
        double f0Ceiling = num(source.get("f0_ceiling_hz"), 330.0);
        double length = num(tract.get("length_scale"), 1.0);
        Map<String, Object> formantScale = map(tract.get("formant_scale"));
        double bandwidthScale = num(tract.get("bandwidth_scale"), 1.0);
        double extraBandwidth = num(tract.get("extra_bandwidth_hz"), 25.0);
        double coarticulation = num(articulation.get("coarticulation"), 0.55);
        double transitionMs = num(articulation.get("transition_ms"), 40.0);
        double undershoot = num(articulation.get("undershoot"), 0.18);
        double burstDb = num(articulation.get("stop_burst_db"), -13.0);
        double aspirationMs = num(articulation.get("stop_aspiration_ms"), 42.0);
        double releaseDb = num(articulation.get("release_noise_db"), -22.0);
        double nasalityBase = num(tract.get("nasality"), 0.0);
        double fricationGain = VoicePrint.dbToLinear(num(tract.get("frication_gain_db"), 0.0));
        double sibilanceBoost = VoicePrint.dbToLinear(num(tract.get("sibilance_boost_db"), 0.0));

        for (Object item : list(score.get("phones"))) {
            Map<String, Object> slot = map(item);
            String symbol = str(slot.get("sym"), "IX");
            VillagerPhones.Phone spec = VillagerPhones.get(symbol);
            String manner = spec.manner;

            int startBlock = (int) Math.floor(num(slot.get("start"), 0.0) * sampleRate / BLOCK);
            int endBlock = (int) Math.ceil(
                    (num(slot.get("start"), 0.0) + num(slot.get("dur"), 0.0)) * sampleRate / BLOCK);
            startBlock = Math.max(0, Math.min(startBlock, blocks - 1));
            endBlock = Math.max(0, Math.min(endBlock, blocks));
            if (endBlock <= startBlock) {
                endBlock = Math.min(blocks, startBlock + 1);
            }
            int count = Math.max(1, endBlock - startBlock);

            // Formant trajectory: diphthongs glide between two targets.
            double[] startTargets = new double[5];
            double[] endTargets = new double[5];
            if (VillagerPhones.DIPHTHONG.equals(manner) && spec.targets > 1) {
                System.arraycopy(spec.f, 0, startTargets, 0, 5);
                System.arraycopy(spec.f, 5, endTargets, 0, 5);
            } else {
                Object slotF = slot.get("f");
                double[] fromSlot = doubles(slotF, 5);
                if (fromSlot != null) {
                    System.arraycopy(fromSlot, 0, startTargets, 0, 5);
                } else {
                    System.arraycopy(VillagerPhones.formantTargets(symbol, 0), 0, startTargets, 0, 5);
                }
                System.arraycopy(startTargets, 0, endTargets, 0, 5);
            }

            // Undershoot pulls short phones toward the neutral tract shape.
            double phoneDuration = Math.max(0.005, num(slot.get("dur"), 0.08));
            double shortness = Math.max(0.0, Math.min(1.0, (0.12 - phoneDuration) / 0.12));
            double pull = undershoot * shortness;
            for (int i = 0; i < 5; i++) {
                startTargets[i] = NEUTRAL[i] + (startTargets[i] - NEUTRAL[i]) * (1.0 - pull);
                endTargets[i] = NEUTRAL[i] + (endTargets[i] - NEUTRAL[i]) * (1.0 - pull);
            }

            double amplitude = slot.containsKey("amp")
                    ? num(slot.get("amp"), spec.amp) : spec.amp;
            boolean voicedPhone = slot.containsKey("voiced")
                    ? bool(slot.get("voiced"), spec.voiced) : spec.voiced;
            double phoneF0Offset = num(slot.get("f0_semitones"), 0.0);

            double closure = spec.closure;
            double aspiration = spec.aspiration;
            double voicingInClosure = spec.voicingDuringClosure;

            for (int offset = 0; offset < count; offset++) {
                int index = startBlock + offset;
                if (index >= blocks) {
                    break;
                }
                double progress = (offset + 0.5) / count;
                double timeInSlot = progress * phoneDuration;

                for (int i = 0; i < 5; i++) {
                    double value = startTargets[i] + (endTargets[i] - startTargets[i]) * progress;
                    double scaled = value * length
                            * num(formantScale.get("f" + (i + 1)), 1.0) * scaleContour[index];
                    track.formants[index][i] = scaled;
                }
                double[] phoneBw = doubles(slot.get("bw"), 5);
                if (phoneBw == null) {
                    phoneBw = spec.bandwidths(0);
                }
                for (int i = 0; i < 5; i++) {
                    track.bandwidths[index][i] =
                            phoneBw[i] * bandwidthScale + extraBandwidth;
                }

                // --- excitation for this block -----------------------------
                double voiceGain = 0.0;
                double noiseGain = 0.0;
                double center = 2500.0;
                double q = 1.0;

                if (VillagerPhones.SILENCE.equals(manner)) {
                    voiceGain = 0.0;
                } else if (VillagerPhones.STOP.equals(manner)
                        || VillagerPhones.AFFRICATE.equals(manner)) {
                    double closureEnd = closure > 0
                            ? Math.min(phoneDuration * 0.62, closure) : phoneDuration * 0.45;
                    double burstWindow = 0.014;
                    double aspirationWindow = Math.max(0.012,
                            aspiration > 0 ? aspiration : aspirationMs / 1000.0);
                    if (timeInSlot < closureEnd) {
                        // Closure: silence, or a weak buzz for voiced stops.
                        voiceGain = amplitude * (voicedPhone ? voicingInClosure : 0.0);
                        noiseGain = 0.0;
                        center = 700.0;
                        q = 1.0;
                    } else if (timeInSlot < closureEnd + burstWindow) {
                        // Burst: a short broadband pop at the place of articulation.
                        double decay = 1.0 - (timeInSlot - closureEnd) / burstWindow;
                        noiseGain = VoicePrint.dbToLinear(burstDb) * Math.max(0.0, decay);
                        if (spec.burst != null) {
                            center = spec.burst[1];
                            q = spec.burst[2];
                            noiseGain *= spec.burst[0] / 0.25;
                        }
                        voiceGain = amplitude * (voicedPhone ? 0.15 : 0.0);
                    } else {
                        // Aspiration into the release: decaying breath.
                        double elapsed = timeInSlot - closureEnd - burstWindow;
                        double decay = Math.max(0.0, 1.0 - elapsed / aspirationWindow);
                        noiseGain = VoicePrint.dbToLinear(releaseDb) * decay
                                + VoicePrint.dbToLinear(burstDb) * 0.25 * decay;
                        if (spec.burst != null) {
                            center = spec.burst[1] * 1.25;
                            q = spec.burst[2];
                        }
                        voiceGain = amplitude * decay * (voicedPhone ? 0.55 : 0.0);
                    }
                } else if (VillagerPhones.FRICATIVE.equals(manner)) {
                    if (spec.noise != null) {
                        center = spec.noise[1];
                        q = spec.noise[2];
                        noiseGain = spec.noise[0] * fricationGain;
                        if (center > 2400.0) {
                            noiseGain *= sibilanceBoost;
                        }
                    } else {
                        center = 4000.0;
                        noiseGain = 0.2 * fricationGain;
                    }
                    // Frication ramps in and out so it does not click.
                    double edge = Math.min(1.0, Math.min(progress / 0.18,
                            (1.0 - progress) / 0.18));
                    noiseGain *= Math.max(0.0, edge);
                    voiceGain = voicedPhone ? amplitude : 0.0;
                    if (voicedPhone) {
                        noiseGain *= 0.75;
                    }
                } else if (VillagerPhones.NASAL.equals(manner)
                        || VillagerPhones.VOCALISATION.equals(manner)) {
                    voiceGain = amplitude;
                    noiseGain = 0.0;
                } else {
                    voiceGain = amplitude;
                    if ("HH".equals(symbol)) {
                        noiseGain = 0.30 * fricationGain;
                        center = spec.noise != null ? spec.noise[1] : 1500.0;
                        q = 0.7;
                        voiceGain *= 0.35;
                    }
                }

                double semitones = f0Contour[index] + phoneF0Offset;
                double f0 = f0Mean * Math.pow(2.0, semitones / 12.0);
                track.f0[index] = Math.max(f0Floor, Math.min(f0Ceiling, f0));
                double loudness = VoicePrint.dbToLinear(ampContour[index]);
                track.amp[index] = loudness;
                track.voice[index] = voiceGain * loudness;
                track.noise[index] = noiseGain * loudness;
                track.noiseCenter[index] = center;
                track.noiseQ[index] = q;
                track.nasal[index] = Math.max(nasalityBase, Math.min(1.0, spec.nasal));
            }

            // The breathiness contour overrides the voiceprint per block.
            for (int index = startBlock; index < Math.min(endBlock, blocks); index++) {
                if (breathContour[index] >= 0.0) {
                    track.breathOverride.put(index, breathContour[index]);
                }
            }
        }

        // --- coarticulation smoothing --------------------------------------
        // The smoother's follow factor comes from the transition time: a longer
        // transition, or heavier coarticulation, means the tract lags more.
        double blockSeconds = BLOCK / (double) sampleRate;
        double transition = Math.max(0.004,
                (transitionMs / 1000.0) * (0.45 + 1.1 * coarticulation));
        double alpha = Math.min(1.0, blockSeconds / transition);
        for (int i = 0; i < 5; i++) {
            double[] column = new double[blocks];
            for (int b = 0; b < blocks; b++) {
                column[b] = track.formants[b][i];
            }
            double[] smoothed = smoothOnePole(column, alpha);
            for (int b = 0; b < blocks; b++) {
                track.formants[b][i] = smoothed[b];
            }
            for (int b = 0; b < blocks; b++) {
                column[b] = track.bandwidths[b][i];
            }
            smoothed = smoothOnePole(column, Math.min(1.0, alpha * 1.6));
            for (int b = 0; b < blocks; b++) {
                track.bandwidths[b][i] = smoothed[b];
            }
        }
        track.f0 = smoothOnePole(track.f0, Math.min(1.0, alpha * 2.2));
        track.voice = smoothOnePole(track.voice, Math.min(1.0, alpha * 2.2));
        track.noise = smoothOnePole(track.noise, Math.min(1.0, alpha * 2.2));
        track.nasal = smoothOnePole(track.nasal, Math.min(1.0, alpha * 2.2));

        track.totalSamples = totalSamples;
        return track;
    }

    // ---------------------------------------------------------------------
    // output stage
    // ---------------------------------------------------------------------

    /** Band limiting, room, gain, two-sided peak normalisation, clip and fades. */
    static void outputStage(float[] samples, VoicePrint effective, int sampleRate) {
        Map<String, Object> output = effective.section("output");
        double gain = VoicePrint.dbToLinear(num(output.get("gain_db"), 0.0));
        double ceiling = num(output.get("peak_ceiling"), 0.95);
        double highpass = num(output.get("highpass_hz"), 70.0);
        double lowpass = num(output.get("lowpass_hz"), 9500.0);
        double roomMix = num(output.get("room_mix"), 0.0);
        double roomDecay = num(output.get("room_decay_ms"), 0.0);
        double fadeMs = num(output.get("fade_ms"), 12.0);

        if (highpass > 0.0) {
            double coefficient = Math.exp(-2.0 * Math.PI * highpass / sampleRate);
            double previousIn = 0.0;
            double previousOut = 0.0;
            for (int i = 0; i < samples.length; i++) {
                double value = samples[i];
                previousOut = coefficient * (previousOut + value - previousIn);
                previousIn = value;
                samples[i] = (float) previousOut;
            }
        }
        if (lowpass < sampleRate * 0.45) {
            double coefficient = 1.0 - Math.exp(-2.0 * Math.PI * lowpass / sampleRate);
            double state = 0.0;
            for (int i = 0; i < samples.length; i++) {
                state += coefficient * (samples[i] - state);
                samples[i] = (float) state;
            }
        }
        if (roomMix > 0.0 && roomDecay > 0.0) {
            simpleRoom(samples, sampleRate, roomMix, roomDecay);
        }
        if (gain != 1.0) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (float) (samples[i] * gain);
            }
        }

        float peakValue = AudioDsp.peak(samples);
        // Two-sided normalisation when enabled: a render whose loudest moment is
        // a vowel rather than a stop burst would otherwise stay ~22 dB below the
        // ceiling. The 1e-4 floor stops genuine silence being amplified into
        // noise; with the knob off this stays a limiter.
        boolean normalise = bool(output.get("normalize_peak"), true);
        if (peakValue > 0.0
                && ((normalise && peakValue > 1e-4) || peakValue > ceiling)) {
            AudioDsp.normalizePeak(samples, (float) ceiling);
        }
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] > 1.0f) {
                samples[i] = 1.0f;
            } else if (samples[i] < -1.0f) {
                samples[i] = -1.0f;
            }
        }
        int fade = (int) (fadeMs / 1000.0 * sampleRate);
        if (fade > 0 && samples.length > fade * 2) {
            AudioDsp.fadeEdges(samples, fade);
        }
    }

    /** Two comb filters and one allpass: enough for a small interior. */
    private static void simpleRoom(float[] samples, int sampleRate, double mix, double decayMs) {
        int length = samples.length;
        if (length == 0) {
            return;
        }
        double feedback = Math.max(0.0, Math.min(0.92, 1.0 - 3.0 / Math.max(1.0, decayMs / 10.0)));
        double[] wet = new double[length];
        double[] delays = {29.0, 37.0};
        for (double delayMs : delays) {
            int delay = Math.max(1, (int) (delayMs / 1000.0 * sampleRate));
            double[] buffer = new double[delay];
            int index = 0;
            for (int i = 0; i < length; i++) {
                double delayed = buffer[index];
                buffer[index] = samples[i] + delayed * feedback;
                index = (index + 1) % delay;
                wet[i] += delayed * 0.5;
            }
        }
        // An allpass smears the comb colouration.
        int delay = Math.max(1, (int) (5.0 / 1000.0 * sampleRate));
        double[] buffer = new double[delay];
        int index = 0;
        double allpassGain = 0.5;
        for (int i = 0; i < length; i++) {
            double delayed = buffer[index];
            double value = wet[i] + allpassGain * delayed;
            buffer[index] = value - allpassGain * wet[i];
            index = (index + 1) % delay;
            wet[i] = delayed + allpassGain * value;
        }
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (samples[i] * (1.0 - mix) + wet[i] * mix);
        }
    }

    // ---------------------------------------------------------------------
    // events
    // ---------------------------------------------------------------------

    /** Mix the score's non-speech vocalisations in at their timestamps. */
    @SuppressWarnings("unchecked")
    static float[] overlayEvents(float[] samples, Map<String, Object> score,
                                 VoicePrint effective, int sampleRate, Random rng) {
        List<Object> events = list(score.get("events"));
        if (events.isEmpty()) {
            return samples;
        }
        List<float[]> parts = new ArrayList<float[]>();
        List<Integer> offsets = new ArrayList<Integer>();
        int required = samples.length;
        for (Object item : events) {
            Map<String, Object> event = map(item);
            String kind = str(event.get("kind"), "").toLowerCase();
            double at = num(event.get("at"), 0.0);
            double duration = num(event.get("dur"), 0.0);
            double gain = num(event.get("gain"), 1.0);
            float[] clip = renderEvent(kind, effective, sampleRate,
                    duration > 0 ? Double.valueOf(duration) : null, rng);
            if (clip == null || clip.length == 0) {
                continue;
            }
            int offset = (int) Math.round(at * sampleRate);
            parts.add(clip);
            offsets.add(offset);
            required = Math.max(required, offset + clip.length);
            if (gain != 1.0) {
                AudioDsp.applyGain(clip, gain);
            }
        }
        if (parts.isEmpty()) {
            return samples;
        }
        float[] out = new float[required];
        System.arraycopy(samples, 0, out, 0, samples.length);
        for (int i = 0; i < parts.size(); i++) {
            float[] clip = parts.get(i);
            int offset = offsets.get(i);
            for (int j = 0; j < clip.length; j++) {
                int target = offset + j;
                if (target >= 0 && target < out.length) {
                    out[target] += clip[j];
                }
            }
        }
        float peak = AudioDsp.peak(out);
        if (peak > 1.0f) {
            AudioDsp.applyGain(out, 1.0 / peak);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // glottal source models
    // ---------------------------------------------------------------------

    /**
     * Evaluate a glottal flow model.
     *
     * @param u     phase in [0,1)
     * @param open  open-phase fraction of the period
     * @param skew  rise fraction of the open phase
     */
    public static double glottal(String model, double u, double open, double skew) {
        if ("lf_style".equals(model)) {
            return glottalLfStyle(u, open, skew);
        }
        if ("pulse".equals(model)) {
            return glottalPulse(u, open, skew);
        }
        if ("buzz".equals(model)) {
            return glottalBuzz(u, open, skew);
        }
        return glottalRosenberg(u, open, skew);
    }

    /**
     * Rosenberg rise/fall: a raised-cosine opening followed by a cosine return.
     * The workhorse model, and the closest honest stand-in for a measured
     * glottal flow without implementing full Liljencrants-Fant.
     */
    public static double glottalRosenberg(double u, double openQuotient, double skew) {
        double to = Math.max(0.05, openQuotient);
        double tr = Math.max(0.02, skew) * to;
        double tf = Math.max(0.02, to - tr);
        if (u >= to) {
            return 0.0;
        }
        if (u < tr) {
            return 1.0 - Math.cos(Math.PI * u / (2.0 * tr));
        }
        return Math.cos(Math.PI * (u - tr) / (2.0 * tf));
    }

    /** Fast rise with an exponential return phase, for LF-like brightness. */
    public static double glottalLfStyle(double u, double openQuotient, double skew) {
        double to = Math.max(0.05, openQuotient);
        double tr = Math.max(0.02, skew) * to * 0.75;
        double tf = Math.max(0.02, to - tr);
        if (u >= to) {
            return 0.0;
        }
        if (u < tr) {
            return Math.sin(Math.PI * u / (2.0 * tr));
        }
        double tail = Math.exp(-5.5 * (u - tr) / tf);
        double end = Math.exp(-5.5);
        return Math.max(0.0, (tail - end) / (1.0 - end));
    }

    /** Bright impulse-like source: sharp attack, fast decay. */
    public static double glottalPulse(double u, double openQuotient, double skew) {
        double to = Math.max(0.05, openQuotient) * 0.6;
        if (u >= to) {
            return 0.0;
        }
        double x = u / to;
        return x * Math.exp(1.0 - x) * Math.exp(-3.0 * x);
    }

    /** Band-limited sawtooth (-6 dB/oct harmonics): a thin, reedy voice. */
    public static double glottalBuzz(double u, double openQuotient, double skew) {
        return 1.0 - 2.0 * u;
    }

    // ---------------------------------------------------------------------
    // filter helpers
    // ---------------------------------------------------------------------

    /** Klatt digital resonator coefficients {@code (a, b1, b2)}. */
    static double[] resonatorCoefficients(double frequency, double bandwidth, int sampleRate) {
        double f = Math.max(20.0, Math.min(frequency, sampleRate * 0.45));
        double bw = Math.max(20.0, Math.min(bandwidth, sampleRate * 0.3));
        double radius = Math.exp(-Math.PI * bw / sampleRate);
        double theta = 2.0 * Math.PI * f / sampleRate;
        double b1 = 2.0 * radius * Math.cos(theta);
        double b2 = -(radius * radius);
        return new double[]{1.0 - b1 - b2, b1, b2};
    }

    /** Klatt anti-resonator (a spectral zero), from the same pole placement. */
    static double[] antiresonatorCoefficients(double frequency, double bandwidth, int sampleRate) {
        double[] pole = resonatorCoefficients(frequency, bandwidth, sampleRate);
        if (Math.abs(pole[0]) < 1e-9) {
            return new double[]{1.0, 0.0, 0.0};
        }
        return new double[]{1.0 / pole[0], -pole[1] / pole[0], -pole[2] / pole[0]};
    }

    /** Causal one-pole smoother; {@code alpha} is the per-block follow factor. */
    static double[] smoothOnePole(double[] values, double alpha) {
        if (alpha >= 1.0 || values.length == 0) {
            return values.clone();
        }
        double[] out = new double[values.length];
        out[0] = values[0];
        double previous = values[0];
        for (int i = 1; i < values.length; i++) {
            previous = previous + alpha * (values[i] - previous);
            out[i] = previous;
        }
        return out;
    }

    /** Piecewise-linear sample of {@code [[t, value], ...]} at each time. */
    static double[] interpPoints(double[][] points, double[] times, double fallback) {
        double[] out = new double[times.length];
        if (points == null || points.length == 0) {
            java.util.Arrays.fill(out, fallback);
            return out;
        }
        double[][] sorted = points.clone();
        java.util.Arrays.sort(sorted, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] left, double[] right) {
                return Double.compare(left[0], right[0]);
            }
        });
        int index = 0;
        int last = sorted.length - 1;
        for (int i = 0; i < times.length; i++) {
            double t = times[i];
            while (index < last && sorted[index + 1][0] < t) {
                index++;
            }
            double t0 = sorted[index][0];
            double v0 = sorted[index][1];
            double t1 = sorted[Math.min(index + 1, last)][0];
            double v1 = sorted[Math.min(index + 1, last)][1];
            if (t <= t0) {
                out[i] = v0;
            } else if (t >= t1 || t1 == t0) {
                out[i] = v1;
            } else {
                out[i] = v0 + (v1 - v0) * (t - t0) / (t1 - t0);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // score access helpers
    // ---------------------------------------------------------------------

    /** Convert a contour value (a JSON list of {@code [t, value]} pairs). */
    static double[][] breakpoints(Object contour) {
        List<Object> raw = list(contour);
        if (raw.isEmpty()) {
            return new double[0][2];
        }
        double[][] out = new double[raw.size()][2];
        int used = 0;
        for (Object item : raw) {
            if (item instanceof List) {
                List<Object> pair = (List<Object>) item;
                if (pair.size() >= 2) {
                    out[used][0] = num(pair.get(0), 0.0);
                    out[used][1] = num(pair.get(1), 0.0);
                    used++;
                }
            }
        }
        if (used == out.length) {
            return out;
        }
        double[][] trimmed = new double[used][2];
        System.arraycopy(out, 0, trimmed, 0, used);
        return trimmed;
    }

    static double num(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    static String str(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.<Object>emptyList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value
                : Collections.<String, Object>emptyMap();
    }

    /** Read a JSON list of numbers into a fixed-length array, or null. */
    static double[] doubles(Object value, int length) {
        if (!(value instanceof List)) {
            return null;
        }
        List<Object> raw = (List<Object>) value;
        if (raw.size() < length) {
            return null;
        }
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            out[i] = num(raw.get(i), 0.0);
        }
        return out;
    }
}
