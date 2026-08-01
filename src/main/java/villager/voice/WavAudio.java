package villager.voice;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small mono floating-point audio container backed by Java Sound. */
public final class WavAudio {
    public final int sampleRate;
    public final float[] samples;

    public WavAudio(int sampleRate, float[] samples) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
        this.samples = samples == null ? new float[0] : samples;
    }

    public static WavAudio read(Path path) throws IOException {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat source = input.getFormat();
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(),
                    16,
                    1,
                    2,
                    source.getSampleRate(),
                    false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, input)) {
                byte[] bytes = readAll(pcm);
                int count = bytes.length / 2;
                float[] data = new float[count];
                for (int i = 0; i < count; i++) {
                    int lo = bytes[i * 2] & 0xff;
                    int hi = bytes[i * 2 + 1];
                    short value = (short) ((hi << 8) | lo);
                    data[i] = value / 32768.0f;
                }
                return new WavAudio(Math.round(target.getSampleRate()), data);
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Cannot read WAV file: " + path, e);
        }
    }

    public void write(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float value = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            int sample = Math.round(value * 32767.0f);
            bytes[i * 2] = (byte) (sample & 0xff);
            bytes[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                1,
                2,
                sampleRate,
                false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(bytes), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    public WavAudio resampled(int destinationRate) {
        if (destinationRate == sampleRate) {
            return this;
        }
        // Multiply in double precision: long clips can overflow int before
        // the float division, collapsing the destination length to one sample.
        int length = Math.max(1, (int) Math.round(
                samples.length * (double) destinationRate / sampleRate));
        return new WavAudio(destinationRate, AudioDsp.resample(samples, length));
    }

    private static byte[] readAll(AudioInputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        byte[] result = new byte[0];
        int used = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (used + read > result.length) {
                int next = Math.max(used + read, Math.max(8192, result.length * 2));
                byte[] expanded = new byte[next];
                System.arraycopy(result, 0, expanded, 0, used);
                result = expanded;
            }
            System.arraycopy(buffer, 0, result, used, read);
            used += read;
        }
        byte[] exact = new byte[used];
        System.arraycopy(result, 0, exact, 0, used);
        return exact;
    }
}
