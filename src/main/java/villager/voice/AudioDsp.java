package villager.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, readable DSP helpers used to shape recorded villager clips: resample
 * for the playback rate/pitch shift, tilt the tone, and normalize the level.
 * No stage here invents signal — every helper only processes audio that was
 * decoded from a real recording.
 */
public final class AudioDsp {
    private AudioDsp() {
    }

    /**
     * Windowed-sinc resampler (Hann-windowed, 32-tap half-width).
     *
     * <p>Plain linear interpolation has no anti-aliasing attenuation when
     * down-sampling, so content above the destination Nyquist would fold back
     * into the audible band as hiss. This resampler low-passes at ~95% of the
     * destination Nyquist while resampling, so speed and pitch changes stay
     * clean.</p>
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
        // This helper maps input[0]..input[n-1] onto output[0]..output[m-1];
        // keep that phase convention so pitch and timing stay consistent.
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
        // only safety net this stage needs.
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] > 4.0f) samples[i] = 4.0f;
            else if (samples[i] < -4.0f) samples[i] = -4.0f;
        }
    }

    /**
     * Add a restrained interrogative emphasis to recorded clips: a smooth
     * terminal presence lift so playback still sounds questioning.
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
