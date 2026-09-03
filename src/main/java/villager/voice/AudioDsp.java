package villager.voice;

import java.util.ArrayList;
import java.util.List;

/** Small, readable DSP helpers used by the continuous TTS post-processing stage. */
public final class AudioDsp {
    private AudioDsp() {
    }

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
        float[] output = new float[outputLength];
        double scale = input.length == 1 ? 0.0 : (input.length - 1.0) / (outputLength - 1.0);
        for (int i = 0; i < outputLength; i++) {
            double position = i * scale;
            int left = (int) position;
            int right = Math.min(input.length - 1, left + 1);
            double amount = position - left;
            output[i] = (float) (input[left] * (1.0 - amount) + input[right] * amount);
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

    /**
     * Reduce synthesized speech noise without changing its channel layout.
     *
     * The low cut is deliberately gentle so the voice keeps its body. The high
     * cut is applied in three short passes because the RVC artifacts are
     * concentrated in the upper band; this smooths isolated clicks without
     * averaging across enough samples to flatten pitch movement.
     */
    public static void applySpeechDenoise(float[] samples, int sampleRate) {
        if (samples.length < 2 || sampleRate <= 0) {
            return;
        }
        // Remove sub-bass rumble below 80 Hz while retaining the low
        // fundamentals of the villager voice (roughly 100-300 Hz). One gentle
        // pass to avoid resonance buildup from multiple filtering stages.
        applyHighPass(samples, sampleRate, 80.0);
        // Stronger high-frequency cleanup, while keeping the important speech
        // consonant band usable. Three mild passes make the transition gradual
        // instead of producing a hard, muffled cutoff.
        applyLowPass(samples, sampleRate, 7600.0);
        applyLowPass(samples, sampleRate, 7600.0);
        applyLowPass(samples, sampleRate, 7600.0);
        // Notch out RVC synthesis artifacts clustered near 11.8 kHz. These
        // tonal spikes survive the low-pass because they sit close to the
        // Nyquist limit where a single-pole IIR rolls off slowly.
        applyNotchFilter(samples, sampleRate, 11850.0, 10.0);
        // Notch out potential midrange oscillation around 500 Hz. RVC
        // conversion can create narrow-band ringing in the midrange that
        // sounds like a wavering/robotic artifact; a narrow notch damps it.
        applyNotchFilter(samples, sampleRate, 500.0, 8.0);
        // Secondary notch at 800 Hz for upper-midrange artifacts.
        applyNotchFilter(samples, sampleRate, 800.0, 10.0);
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

    private static void applyLowPass(float[] samples, int sampleRate, double cutoffHz) {
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * cutoffHz / sampleRate);
        float previousOutput = samples[0];
        for (int i = 1; i < samples.length; i++) {
            previousOutput += (float) (alpha * (samples[i] - previousOutput));
            samples[i] = previousOutput;
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
        // Kill any narrowband midrange oscillation created by the tone-tilt
        // feedback loop interacting with the HPF and denoise stages.
        notchMidrange(samples, samples.length, 24000);
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

    /**
     * Second-order notch around 600 Hz (0.2 Q, ~6 dB) to zap residual ringing in
     * the vocal formant band. Gentle enough to keep brightness, strong enough to stop
     * the "robotic" midrange oscillation from the DSP feedback chain.
     */
    private static void notchMidrange(float[] samples, int length, int sampleRate) {
        if (length < 4 || sampleRate < 8000) return;
        double f0 = 600.0;
        double Q = 0.2;
        double r = Math.exp(-Math.PI * f0 / (Q * sampleRate));
        double cosw = Math.cos(2.0 * Math.PI * f0 / sampleRate);
        double a1 = -2.0 * r * cosw;
        double a2 = r * r;
        // peak gain G at f0 for ~6 dB notch depth with Q=0.2:
        double G = (1.0 - 2.0 * r * cosw + r * r) / (1.0 - 2.0 * r * cosw + r * r + 0.18);
        float x1 = samples[0], x2 = samples.length > 1 ? samples[1] : 0.0f;
        float y1 = 0.0f, y2 = 0.0f;
        for (int i = 0; i < length; i++) {
            float x0 = samples[i];
            float y0 = (float) (G * (x0 - x2) - a1 * y1 - a2 * y2);
            samples[i] = y0;
            x2 = x1; x1 = x0;
            y2 = y1; y1 = y0;
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
