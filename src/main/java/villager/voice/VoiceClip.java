package villager.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * A generated mono PCM clip for game audio playback.
 *
 * The PCM buffer is signed 16-bit little-endian, exactly the format expected by
 * OpenAL's AL_FORMAT_MONO16. Each call to getPcm16() returns a new read-only
 * buffer positioned at zero.
 */
public final class VoiceClip {
    private final int sampleRate;
    private final float[] samples;

    VoiceClip(WavAudio audio) {
        this.sampleRate = audio.sampleRate;
        this.samples = audio.samples.clone();
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getSampleCount() {
        return samples.length;
    }

    public double getDurationSeconds() {
        return samples.length / (double) sampleRate;
    }

    /**
     * Return normalized samples in [-1, 1]. The returned array is a copy.
     */
    public float[] getSamples() {
        return samples.clone();
    }

    /**
     * Return signed 16-bit little-endian mono PCM for OpenAL/LWJGL.
     */
    public ByteBuffer getPcm16() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(samples.length * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : samples) {
            float clipped = Math.max(-1.0f, Math.min(1.0f, value));
            buffer.putShort((short) Math.round(clipped * 32767.0f));
        }
        buffer.flip();
        return buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Write the clip as a mono 16-bit PCM WAV. */
    public void writeWav(Path output) throws IOException {
        new WavAudio(sampleRate, samples.clone()).write(output);
    }
}
