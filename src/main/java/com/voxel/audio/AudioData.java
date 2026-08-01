package com.voxel.audio;

/**
 * In-memory PCM audio container: interleaved float samples in [-1, 1].
 * channel c of frame f lives at samples[f * channels + c].
 */
public class AudioData {
    public final float[] samples;
    public final int channels;
    public final int sampleRate;

    public AudioData(float[] samples, int channels, int sampleRate) {
        this.samples = samples;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    public int frameCount() {
        return samples.length / channels;
    }

    public double durationSeconds() {
        return frameCount() / (double) sampleRate;
    }

    /** Downmix to mono (mid = (L+R)/2 when stereo). */
    public float[] toMono() {
        if (channels == 1) return samples;
        int n = frameCount();
        float[] m = new float[n];
        for (int i = 0; i < n; i++) {
            m[i] = (samples[2 * i] + samples[2 * i + 1]) * 0.5f;
        }
        return m;
    }

    /** Linear-interpolation resample to a new sample rate. */
    public static AudioData resample(AudioData a, int newRate) {
        if (a.sampleRate == newRate) return a;
        double ratio = newRate / (double) a.sampleRate;
        int newFrames = (int) Math.round(a.frameCount() * ratio);
        float[] out = new float[newFrames * a.channels];
        for (int c = 0; c < a.channels; c++) {
            for (int i = 0; i < newFrames; i++) {
                double pos = i / ratio;
                int i0 = (int) pos;
                int i1 = Math.min(i0 + 1, a.frameCount() - 1);
                double frac = pos - i0;
                out[i * a.channels + c] = (float) (a.samples[i0 * a.channels + c] * (1 - frac)
                        + a.samples[i1 * a.channels + c] * frac);
            }
        }
        return new AudioData(out, a.channels, newRate);
    }

    /** True if every sample is finite and within [-2, 2] (sanity check). */
    public boolean isValid() {
        for (float s : samples) {
            if (!Float.isFinite(s) || Math.abs(s) > 2.0f) return false;
        }
        return true;
    }
}
