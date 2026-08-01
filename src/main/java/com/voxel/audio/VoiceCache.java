package com.voxel.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Persistent cache for generated mono 16-bit PCM voice clips.
 *
 * Cache filenames are SHA-256 hashes of a format version and the exact dialogue
 * text. A version bump safely invalidates all old generated audio without having
 * to scan or migrate the cache directory.
 */
public final class VoiceCache {
    private static final String CACHE_VERSION = "voice-cache-v1";

    private final Path directory;

    public VoiceCache(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** Return a cached clip, or {@code null} when it has not been generated yet. */
    public AudioData load(String text) {
        Path file = cacheFile(text);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            AudioData audio = readWav(file.toFile());
            if (!audio.isValid() || audio.channels != 1 || audio.sampleRate <= 0
                    || audio.samples.length == 0) {
                throw new IOException("invalid cached audio format");
            }
            return audio;
        } catch (Exception error) {
            System.err.println("VoiceCache: ignoring invalid cache entry " + file + ": "
                    + error.getMessage());
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A later save can replace the bad entry.
            }
            return null;
        }
    }

    /** Write a clip atomically so an interrupted run cannot leave a partial WAV. */
    public void save(String text, AudioData audio) {
        if (audio == null || audio.channels != 1 || audio.samples.length == 0
                || !audio.isValid()) {
            return;
        }
        Path file = cacheFile(text);
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(directory);
            WavIO.write(temp.toFile(), audio);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("VoiceCache: could not save " + file + ": " + error.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    public Path directory() {
        return directory;
    }

    Path cacheFile(String text) {
        return directory.resolve(hash(text) + ".wav");
    }

    private static AudioData readWav(File file) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file)) {
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
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    int lo = bytes[i * 2] & 0xff;
                    int hi = bytes[i * 2 + 1];
                    short value = (short) ((hi << 8) | lo);
                    samples[i] = value / 32768.0f;
                }
                return new AudioData(samples, 1, Math.round(target.getSampleRate()));
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((CACHE_VERSION + "\n" + text)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
