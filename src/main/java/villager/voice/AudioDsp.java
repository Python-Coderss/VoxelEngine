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
