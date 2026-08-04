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
        // fundamentals of the villager voice (roughly 100-300 Hz). Two passes
        // give -12 dB/octave roll-off instead of -6 dB/octave, aggressively
        // cutting the 20-80 Hz band.
        applyHighPass(samples, sampleRate, 80.0);
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
        int frameSize = Math.max(80, sampleRate / 100);
        int frameCount = (source.length + frameSize - 1) / frameSize;
        float[] energy = new float[frameCount];
        float maximum = 0.0f;
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * frameSize;
            int end = Math.min(source.length, start + frameSize);
            energy[frame] = rms(source, start, end);
            maximum = Math.max(maximum, energy[frame]);
        }
        if (maximum <= 1.0e-5f) {
            return;
        }

        float floor = maximum * 0.12f;
        for (int frame = 0; frame < frameCount; frame++) {
            double normalized = (energy[frame] - floor)
                    / Math.max(1.0e-6, maximum - floor);
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            energy[frame] = (float) (0.26 + 0.74 * normalized * normalized);
        }
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

    /** Apply a smooth first-order treble tilt without introducing sharp EQ edges. */
    public static void applyToneTilt(float[] samples, double tone) {
        if (tone == 0.0 || samples.length == 0) {
            return;
        }
        double amount = Math.max(-0.85, Math.min(0.85, tone)) * 0.18;
        float low = samples[0];
        for (int i = 0; i < samples.length; i++) {
            float current = samples[i];
            low += (current - low) * 0.035f;
            samples[i] = (float) (current + amount * (current - low));
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
