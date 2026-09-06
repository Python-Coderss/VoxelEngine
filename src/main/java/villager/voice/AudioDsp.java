package villager.voice;

import java.util.ArrayList;
import java.util.List;

/** Small, readable DSP helpers used by the continuous TTS post-processing stage. */
public final class AudioDsp {
    private AudioDsp() {
    }

    /**
     * Windowed-sinc resampler (Hann-windowed, 32-tap half-width).
     *
     * Replaces the old linear interpolation: when down-sampling, linear
     * interpolation has no anti-aliasing attenuation, so spectral content
     * above the destination Nyquist folds back into the audible band. RVC
     * then renders those aliases as a steady hiss/harshness (the 11.8 kHz
     * tonal spike the removed notch filters were chasing). This resampler
     * low-passes at ~95% of the destination Nyquist while resampling.
     */
    public static float[] resample(float[] input, int outputLength) {
        if (outputLength <= 0 || input.length == 0) {
            return new float[0];
        }
        if (outputLength == 1) {
            return new float[]{input[0]};
        }
        if (input.length == 1) {
            float[] output = new float[outputLength];
            for (int i = 0; i < output.length; i++) {
                output[i] = input[0];
            }
            return output;
        }
        if (input.length == outputLength) {
            return input.clone();
        }
        // This helper historically maps input[0]..input[n-1] onto
        // output[0]..output[m-1]; keep that phase convention so pitch and
        // timing stay identical to previous renders.
        double scale = (input.length - 1.0) / (outputLength - 1.0);
        // Steps per input sample: >1 while down-sampling (wider sinc kernel
        // in input units = proportionally lower cutoff).
        double step = Math.max(1.0, scale);
        double cutoff = 0.95 / step;
        final int half = 32;
        float[] output = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double position = i * scale;
            int center = (int) Math.floor(position);
            double sum = 0.0;
            double weightSum = 0.0;
            int from = Math.max(0, center - half);
            int to = Math.min(input.length - 1, center + half);
            for (int j = from; j <= to; j++) {
                double x = (j - position) / step;
                double sinc = x == 0.0 ? cutoff : cutoff
                        * Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
                double window = x >= -1.0 && x <= 1.0
                        ? 0.5 + 0.5 * Math.cos(Math.PI * x) : 0.0;
                double weight = sinc * window;
                sum += input[j] * weight;
                weightSum += weight;
            }
            output[i] = (float) (weightSum != 0.0 ? sum / weightSum : 0.0);
        }
        return output;
    }

    public static void fadeEdges(float[] samples, int fadeSamples) {
        int fade = Math.min(fadeSamples, samples.length / 2);
        for (int i = 0; i < fade; i++) {
            double phase = (i + 1.0) / (fade + 1.0);
            float gain = (float) (0.5 - 0.5 * Math.cos(Math.PI * phase));
            samples[i] *= gain;
            samples[samples.length - 1 - i] *= gain;
        }
    }

    private static void applyHighPass(float[] samples, int sampleRate, double cutoffHz) {
        double alpha = Math.exp(-2.0 * Math.PI * cutoffHz / sampleRate);
        float previousInput = samples[0];
        float previousOutput = 0.0f;
        for (int i = 0; i < samples.length; i++) {
            float input = samples[i];
            float output = (float) (alpha * (previousOutput + input - previousInput));
            samples[i] = output;
            previousInput = input;
            previousOutput = output;
        }
    }

    /**
     * Second-order biquad notch (band-reject) filter. Removes a narrow band
     * around {@code centerHz} while passing all other frequencies. Based on
     * the Audio EQ Cookbook (Robert Bristow-Johnson).
     *
     * @param samples   audio data (modified in place)
     * @param sampleRate sample rate in Hz
     * @param centerHz  center frequency to notch out
     * @param Q         quality factor (higher = narrower notch; 5-20 typical)
     */
    public static void applyNotchFilter(float[] samples, int sampleRate,
                                        double centerHz, double Q) {
        if (samples.length < 3 || sampleRate <= 0 || centerHz <= 0
                || centerHz >= sampleRate * 0.5 || Q <= 0) {
            return;
        }
        double w0 = 2.0 * Math.PI * centerHz / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * Q);
        // Notch: b = [1, -2 cos(w0), 1], a = [1+alpha, -2 cos(w0), 1-alpha]
        double a0 = 1.0 + alpha;
        double b0 = 1.0 / a0;
        double b1 = -2.0 * cosW0 / a0;
        double b2 = 1.0 / a0;
        double a1 = -2.0 * cosW0 / a0;
        double a2 = (1.0 - alpha) / a0;
        float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
        for (int i = 0; i < samples.length; i++) {
            float x0 = samples[i];
            float y0 = (float) (b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2);
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            samples[i] = y0;
        }
    }

    /**
     * Apply a soft source-energy envelope to reduce a synthesized carrier during
     * pauses and weak consonants. The residual floor keeps breath and consonants
     * from being hard-gated, while interpolation avoids clicks.
     */
    public static void applySourceEnergyMask(float[] output, float[] source,
                                             int sampleRate) {
        if (output.length < 2 || source.length < 2 || sampleRate <= 0) {
            return;
        }
        int frameSize = Math.max(160, sampleRate / 50); // ~20 ms analysis
        int frameCount = (source.length + frameSize - 1) / frameSize;
        float[] energy = new float[frameCount];
        float maximum = 0.0f;
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * frameSize;
            int length = Math.min(frameSize, source.length - start);
            energy[frame] = rms(source, start, length);
            maximum = Math.max(maximum, energy[frame]);
        }
        if (maximum <= 1.0e-5f) {
            return;
        }

        float floor = maximum * 0.08f;
        for (int frame = 0; frame < frameCount; frame++) {
            double normalized = (energy[frame] - floor)
                    / Math.max(1.0e-6, maximum - floor);
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            // Less aggressive gating: keep more natural source through.
            // The curve is gentler (linear instead of squared) so mid-energy
            // frames retain more of the natural carrier.
            energy[frame] = (float) (0.35 + 0.65 * normalized);
        }
        // Temporal smoothing: per-frame gain changes at the 10 ms scale are
        // heard as amplitude modulation (rasp/husk). A gentle moving average
        // keeps pause gating while voiced frames hold a steady carrier.
        float[] smoothedGain = new float[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            double sum = 0.0;
            int count = 0;
            for (int k = -3; k <= 3; k++) {
                int index = frame + k;
                if (index >= 0 && index < frameCount) {
                    sum += energy[index];
                    count++;
                }
            }
            smoothedGain[frame] = (float) (sum / count);
        }
        System.arraycopy(smoothedGain, 0, energy, 0, frameCount);
        for (int i = 0; i < output.length; i++) {
            double sourcePosition = i * (source.length - 1.0)
                    / Math.max(1, output.length - 1);
            double framePosition = sourcePosition / frameSize;
            int left = Math.min(frameCount - 1, (int) framePosition);
            int right = Math.min(frameCount - 1, left + 1);
            double amount = framePosition - left;
            double gain = energy[left] * (1.0 - amount) + energy[right] * amount;
            output[i] *= (float) gain;
        }
    }

    /**
     * Crossfade extra natural source into the converted audio wherever the
     * source is unvoiced and high-frequency-dominant (sibilants, fricatives,
     * breaths). RVC vocoders mangle exactly those regions; voiced speech keeps
     * the converted villager timbre. The boost ramps from {@code baseMix} up
     * to {@code capMix} following a smoothed spectral-centroid mask of the
     * natural source.
     */
    public static void applyUnvoicedSourceBoost(float[] output, float[] source,
                                                int sampleRate, double baseMix,
                                                double capMix) {
        if (output.length < 2 || source.length < 2 || capMix <= baseMix) {
            return;
        }
        // Only the natural source's frication may be crossfaded in. Mixing the
        // full-band source would leak the base TTS pitch (~111 Hz) into the
        // sibilant regions, dragging the rendered villager register down and
        // silently undoing the pitch shift. A high-pass keeps the sibilant
        // noise while removing the voiced harmonics.
        float[] frication = source.clone();
        applyHighPass(frication, sampleRate, 3000.0);
        int window = Math.max(256, sampleRate / 50); // ~20 ms
        int hop = window / 2;
        int frameCount = Math.max(1, (source.length - window) / hop + 1);
        float[] boost = new float[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * hop;
            double weighted = 0.0;
            double total = 0.0;
            for (int i = 0; i < window; i++) {
                float sample = source[start + i];
                double magnitude = sample * sample;
                double frequency = i * sampleRate / (double) window;
                weighted += magnitude * frequency;
                total += magnitude;
            }
            double centroid = total > 1e-9 ? weighted / total : 0.0;
            // Voiced speech sits under ~2 kHz; sibilant energy lives above it.
            double amount = (centroid - 2200.0) / 1400.0;
            amount = Math.max(0.0, Math.min(1.0, amount));
            boost[frame] = (float) (amount * amount * (3.0 - 2.0 * amount));
        }
        // Smooth the mask so consonant onsets crossfade instead of switching.
        float[] smoothed = new float[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            double sum = 0.0;
            int count = 0;
            for (int k = -2; k <= 2; k++) {
                int index = frame + k;
                if (index >= 0 && index < frameCount) {
                    sum += boost[index];
                    count++;
                }
            }
            smoothed[frame] = (float) (sum / count);
        }

        int count = Math.min(output.length, source.length);
        for (int i = 0; i < count; i++) {
            double sourcePosition = i * (source.length - 1.0)
                    / Math.max(1, output.length - 1);
            double framePosition = sourcePosition / hop;
            int left = Math.min(frameCount - 1, (int) framePosition);
            int right = Math.min(frameCount - 1, left + 1);
            double amount = framePosition - left;
            double mask = smoothed[left] * (1.0 - amount) + smoothed[right] * amount;
            double mix = baseMix + (capMix - baseMix) * mask;
            output[i] = (float) (output[i] * (1.0 - mix + baseMix)
                    + frication[i] * (mix - baseMix));
        }
    }

    /** Apply a smooth first-order treble tilt without introducing sharp EQ edges. */
    public static void applyToneTilt(float[] samples, double tone) {
        if (tone == 0.0 || samples.length == 0) {
            return;
        }
        double amount = Math.max(-0.85, Math.min(0.85, tone)) * 0.18;
        // Use a leak factor that prevents feedback oscillation: the low-pass
        // state decays faster (0.035 -> 0.025) so it cannot build up energy.
        float low = samples[0];
        float leak = 0.025f;
        for (int i = 0; i < samples.length; i++) {
            float current = samples[i];
            low = (float) (low * (1.0 - leak) + current * leak);
            samples[i] = (float) (current + amount * (current - low));
        }
        // Keep extreme settings inside the float range; a final clamp is the
        // only safety net this stage needs now that the midrange notch that
        // fed its own ringing loop is gone.
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] > 4.0f) samples[i] = 4.0f;
            else if (samples[i] < -4.0f) samples[i] = -4.0f;
        }
    }

    /**
     * Add a restrained interrogative emphasis to recorded/reference clips.
     * Neural clips receive a true F0 rise in CustomRvcModel; this fallback adds
     * a smooth terminal presence lift so reference mode still sounds questioning.
     */
    public static void applyQuestionEnding(float[] samples) {
        int start = (int) (samples.length * 0.78);
        for (int i = Math.max(0, start); i < samples.length; i++) {
            double progress = (i - start) / (double) Math.max(1, samples.length - start - 1);
            double ramp = progress * progress * (3.0 - 2.0 * progress);
            samples[i] *= (float) (1.0 + 0.10 * ramp);
        }
        applyToneTiltRange(samples, start, 0.18);
    }

    private static void applyToneTiltRange(float[] samples, int start, double tone) {
        if (start >= samples.length) return;
        float low = samples[start];
        double amount = Math.max(-0.85, Math.min(0.85, tone)) * 0.18;
        for (int i = start; i < samples.length; i++) {
            float current = samples[i];
            low += (current - low) * 0.035f;
            samples[i] = (float) (current + amount * (current - low));
        }
    }

    // ── Vocoder comb-whine removal ────────────────────────────────────────

    /** Analysis FFT size for whine detection (5.5 Hz bins at 24 kHz). */
    private static final int WHINE_FFT = 8192;
    /** Running-median half width for the spectral floor (±9 bins ≈ ±26 Hz). */
    private static final int WHINE_MEDIAN_RADIUS = 9;
    /**
     * A bin must rise this far above its local spectral median to count as a
     * whine candidate. Measured v9 comb teeth sit at +8 to +19 dB over a
     * well-averaged floor; this detector averages fewer windows, so its floor
     * is noisier and teeth can read +7.5. Real wobbled harmonics survive the
     * bar only to be rejected by the span filter, so the lower threshold is
     * safe (the sung clip's true harmonics peaked at +8.4 over a smooth floor).
     */
    private static final double WHINE_SPIKE_DB = 7.5;
    /**
     * Core bar: bins above spikeDb minus this margin count toward a
     * candidate's spectral span.
     */
    private static final double WHINE_CORE_MARGIN_DB = 3.0;
    /**
     * A tone that is perfectly stable across the whole clip concentrates its
     * average-spectrum energy inside a Hann mainlobe (about 5 bins at this
     * FFT size, span limit 9 with measurement slack; high-frequency teeth
     * legitimately reach 9 with asymmetric skirts). Candidates whose
     * elevated footprint spans wider are wobbling speech harmonics smeared by
     * the averaging — width, not height, separates stable vocoder residue
     * from real signal, and it also catches swept harmonics whose energy
     * bimodally splits around the sweep center.
     */
    private static final int WHINE_MAX_SPAN_BINS = 9;
    /** Speech harmonics rarely matter above this; comb whine does. */
    private static final double WHINE_MIN_HZ = 2500.0;
    /** Safety cap so pathological spectra cannot get dozens of holes. */
    private static final int WHINE_MAX_NOTCHES = 12;

    /**
     * Remove the vocoder's frame-rate comb whine from a converted clip.
     *
     * <p>The RVC NSF source re-interpolates its excitation sine once per frame
     * (400 samples at 40 kHz = 100 Hz), so per-frame phase steps inject
     * discrete sidebands at near-exact multiples of 100 Hz. In the speech band
     * they hide under real harmonics, but above ~2.5 kHz they stick out of the
     * noise floor as pure tones — the metallic whine measured in the v9 clip
     * set (dominant 3.5-4.6 kHz component at speech level, spikes to 11.8 kHz
     * at +8 to +19 dB over the local median). Speech harmonics wobble with the
     * voice, so they smear across bins and vanish in the averaged spectrum;
     * only the stable comb survives detection. Detected clusters are cut with
     * smooth biquad notches sized so the response is down notchDepthDb at the
     * cluster edge — no ringing band, no dulling of the rest of the spectrum.
     */
    public static void applyCombWhineCleanup(float[] samples, int sampleRate) {
        if (samples == null || samples.length < WHINE_FFT || sampleRate <= 0) {
            return;
        }
        // Two passes: pass one notches each whine by about notchDepthDb; the
        // second re-detects whatever still pokes above the threshold (the
        // strongest v9 combs reached +19 dB) without digging any single hole
        // deep enough to ring on ordinary speech.
        for (int pass = 0; pass < 2; pass++) {
            if (!applyCombWhineNotch(samples, sampleRate, WHINE_MIN_HZ,
                    sampleRate * 0.5, WHINE_SPIKE_DB, 10.0)) {
                break;
            }
        }
    }

    /**
     * One detection+notch pass. Returns true when at least one whine cluster
     * was found and notched.
     */
    static boolean applyCombWhineNotch(float[] samples, int sampleRate,
                                       double minHz, double maxHz,
                                       double spikeDb, double notchDepthDb) {
        if (samples == null || samples.length < WHINE_FFT || sampleRate <= 0) {
            return false;
        }
        double binHz = sampleRate / (double) WHINE_FFT;
        double[] spectrum = averageSpectrum(samples);
        double[] level = new double[spectrum.length];
        for (int i = 0; i < spectrum.length; i++) {
            level[i] = 10.0 * Math.log10(spectrum[i] + 1e-20);
        }
        double[] floor = runningMedian(level, WHINE_MEDIAN_RADIUS);

        int minBin = Math.max(1, (int) Math.ceil(minHz / binHz));
        int maxBin = Math.min(spectrum.length - 2, (int) Math.floor(maxHz / binHz));
        // Group candidate bins (excess above the bar) into clusters, merging
        // across gaps of up to 3 quieter bins so a mainlobe dip does not
        // split one whine in two.
        java.util.List<int[]> clusters = new java.util.ArrayList<int[]>();
        int bin = minBin;
        while (bin <= maxBin) {
            if (level[bin] - floor[bin] <= spikeDb) {
                bin++;
                continue;
            }
            int end = bin;
            int lastAbove = bin;
            while (end + 1 <= maxBin) {
                if (level[end + 1] - floor[end + 1] > spikeDb) {
                    end++;
                    lastAbove = end;
                } else if (end - lastAbove < 4) {
                    end++;
                } else {
                    break;
                }
            }
            clusters.add(new int[]{bin, end});
            bin = end + 1;
        }
        if (clusters.isEmpty()) {
            return false;
        }

        double[] peakExcess = new double[clusters.size()];
        for (int c = 0; c < clusters.size(); c++) {
            double best = Double.NEGATIVE_INFINITY;
            for (int k = clusters.get(c)[0]; k <= clusters.get(c)[1]; k++) {
                best = Math.max(best, level[k] - floor[k]);
            }
            peakExcess[c] = best;
        }
        Integer[] indices = new Integer[clusters.size()];
        for (int c = 0; c < indices.length; c++) {
            indices[c] = c;
        }
        java.util.Arrays.sort(indices,
                (a, b) -> Double.compare(peakExcess[b], peakExcess[a]));

        int applied = 0;
        for (int c = 0; c < indices.length && applied < WHINE_MAX_NOTCHES; c++) {
            int[] cluster = clusters.get(indices[c]);
            int lo = cluster[0];
            int hi = cluster[1];
            // Span decides: measure the elevated footprint around the cluster
            // (short dips included) and skip anything wider than a stable
            // tone's mainlobe.
            int windowLo = Math.max(minBin, lo - WHINE_MAX_SPAN_BINS);
            int windowHi = Math.min(maxBin, hi + WHINE_MAX_SPAN_BINS);
            int lowest = -1;
            int highest = -1;
            for (int k = windowLo; k <= windowHi; k++) {
                if (level[k] - floor[k] > spikeDb - WHINE_CORE_MARGIN_DB) {
                    if (lowest < 0) {
                        lowest = k;
                    }
                    highest = k;
                }
            }
            if (lowest < 0 || highest - lowest + 1 > WHINE_MAX_SPAN_BINS) {
                continue;
            }
            // Extend over the tone's skirt so the notch covers the whole
            // coherent hump, not just the flagged tip.
            while (hi + 1 <= maxBin
                    && level[hi + 1] - floor[hi + 1] > spikeDb - WHINE_CORE_MARGIN_DB) {
                hi++;
            }
            while (lo - 1 >= minBin
                    && level[lo - 1] - floor[lo - 1] > spikeDb - WHINE_CORE_MARGIN_DB) {
                lo--;
            }
            int peak = lo;
            for (int k = lo; k <= hi; k++) {
                if (level[k] - floor[k] > level[peak] - floor[peak]) {
                    peak = k;
                }
            }
            double centerHz = peak * binHz;
            int halfBins = Math.max(2, (hi - lo) / 2 + 2);
            double halfHz = halfBins * binHz;
            if (centerHz - halfHz <= 0.0 || centerHz + halfHz >= sampleRate * 0.5) {
                continue;
            }
            // Biquad notch quality for attenuation notchDepthDb at the cluster
            // edge: |H|^2 = u^2/(u^2+1) with u = 2*Q*df/f0 gives
            // Q = f0 / (2 * df * sqrt(10^(depth/10) - 1)) ... folded below.
            double spread = Math.sqrt(Math.pow(10.0, notchDepthDb / 10.0) - 1.0);
            double q = centerHz / (2.0 * halfHz * spread);
            q = Math.max(4.0, Math.min(120.0, q));
            applyNotchFilter(samples, sampleRate, centerHz, q);
            applied++;
        }
        return applied > 0;
    }

    /** Power-weighted average spectrum over ~16 Hann windows spanning the clip. */
    private static double[] averageSpectrum(float[] samples) {
        int hop = Math.max(1, samples.length / 16);
        double[] average = new double[WHINE_FFT / 2];
        double[] window = new double[WHINE_FFT];
        for (int i = 0; i < WHINE_FFT; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (WHINE_FFT - 1));
        }
        double[] re = new double[WHINE_FFT];
        double[] im = new double[WHINE_FFT];
        int used = 0;
        for (int start = 0; start + WHINE_FFT <= samples.length; start += hop) {
            for (int i = 0; i < WHINE_FFT; i++) {
                re[i] = samples[start + i] * window[i];
                im[i] = 0.0;
            }
            fft(re, im);
            for (int i = 0; i < average.length; i++) {
                average[i] += re[i] * re[i] + im[i] * im[i];
            }
            used++;
        }
        if (used > 1) {
            for (int i = 0; i < average.length; i++) {
                average[i] /= used;
            }
        }
        return average;
    }

    private static double[] runningMedian(double[] values, int radius) {
        double[] median = new double[values.length];
        double[] window = new double[2 * radius + 1];
        for (int i = 0; i < values.length; i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(values.length - 1, i + radius);
            int count = to - from + 1;
            System.arraycopy(values, from, window, 0, count);
            java.util.Arrays.sort(window, 0, count);
            median[i] = window[count / 2];
        }
        return median;
    }

    /** In-place iterative radix-2 FFT (length must be a power of two). */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double stepRe = Math.cos(angle);
            double stepIm = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < length / 2; k++) {
                    int a = start + k;
                    int b = a + length / 2;
                    double tr = re[b] * curRe - im[b] * curIm;
                    double ti = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double nextRe = curRe * stepRe - curIm * stepIm;
                    curIm = curRe * stepIm + curIm * stepRe;
                    curRe = nextRe;
                }
            }
        }
    }

    public static void applyGain(float[] samples, double gain) {
        if (gain == 1.0) {
            return;
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= (float) gain;
        }
    }

    public static float peak(float[] samples) {
        float peak = 0.0f;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    public static void normalizePeak(float[] samples, float ceiling) {
        float peak = peak(samples);
        if (peak > ceiling && peak > 0.0f) {
            float gain = ceiling / peak;
            for (int i = 0; i < samples.length; i++) {
                samples[i] *= gain;
            }
        }
    }

    public static float rms(float[] samples, int start, int length) {
        if (length <= 0) {
            return 0.0f;
        }
        double sum = 0.0;
        int end = Math.min(samples.length, start + length);
        int count = 0;
        for (int i = Math.max(0, start); i < end; i++) {
            sum += samples[i] * samples[i];
            count++;
        }
        return count == 0 ? 0.0f : (float) Math.sqrt(sum / count);
    }

    public static int strongestWindow(float[] samples, int windowLength) {
        if (samples.length <= windowLength) {
            return 0;
        }
        int hop = Math.max(1, windowLength / 4);
        int best = 0;
        float bestEnergy = -1.0f;
        for (int start = 0; start + windowLength <= samples.length; start += hop) {
            float energy = rms(samples, start, windowLength);
            if (energy > bestEnergy) {
                bestEnergy = energy;
                best = start;
            }
        }
        return best;
    }

    public static float[] concat(List<float[]> parts, int crossfadeSamples) {
        if (parts.isEmpty()) {
            return new float[0];
        }
        List<Float> output = new ArrayList<Float>();
        for (float[] part : parts) {
            if (part.length == 0) {
                continue;
            }
            int fade = Math.min(crossfadeSamples, Math.min(part.length, output.size()));
            int outputStart = output.size() - fade;
            for (int i = 0; i < fade; i++) {
                double t = (i + 1.0) / (fade + 1.0);
                float oldGain = (float) Math.cos(t * Math.PI / 2.0);
                float newGain = (float) Math.sin(t * Math.PI / 2.0);
                int index = outputStart + i;
                output.set(index, output.get(index) * oldGain + part[i] * newGain);
            }
            for (int i = fade; i < part.length; i++) {
                output.add(part[i]);
            }
        }
        float[] result = new float[output.size()];
        for (int i = 0; i < output.size(); i++) {
            result[i] = output.get(i);
        }
        return result;
    }

    public static float[] silence(int samples) {
        return new float[Math.max(0, samples)];
    }
}
