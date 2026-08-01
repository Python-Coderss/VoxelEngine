package com.voxel.audio;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoiceCacheTest {
    @Test
    public void savesAndLoadsMonoPcmAcrossCacheInstances() throws Exception {
        Path directory = Files.createTempDirectory("voxel-voice-cache");
        try {
            VoiceCache first = new VoiceCache(directory);
            AudioData original = new AudioData(
                    new float[]{-0.75f, -0.25f, 0.0f, 0.25f, 0.75f}, 1, 24000);

            assertNull(first.load("A cached greeting"));
            first.save("A cached greeting", original);
            assertTrue(Files.exists(first.cacheFile("A cached greeting")));

            VoiceCache second = new VoiceCache(directory);
            AudioData loaded = second.load("A cached greeting");
            assertNotNull(loaded);
            assertEquals(1, loaded.channels);
            assertEquals(24000, loaded.sampleRate);
            assertEquals(original.samples.length, loaded.samples.length);
            for (int i = 0; i < original.samples.length; i++) {
                assertEquals(original.samples[i], loaded.samples[i], 1.0f / 32767.0f);
            }
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    public void removesCorruptEntryAndFallsBackToMiss() throws Exception {
        Path directory = Files.createTempDirectory("voxel-voice-cache-corrupt");
        try {
            VoiceCache cache = new VoiceCache(directory);
            cache.save("Broken greeting", new AudioData(new float[]{0.1f, 0.2f}, 1, 24000));
            Files.write(cache.cacheFile("Broken greeting"), new byte[]{0, 1, 2, 3});

            assertNull(cache.load("Broken greeting"));
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.forEach(files::add);
        }
        Collections.sort(files, (left, right) -> right.getNameCount() - left.getNameCount());
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (java.io.IOException error) {
                // Windows audio providers can release a handle slightly after
                // AudioSystem closes it; do not turn cleanup into a test failure.
                file.toFile().deleteOnExit();
            }
        }
    }
}
