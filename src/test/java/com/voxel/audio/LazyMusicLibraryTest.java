package com.voxel.audio;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the deferred-scan contract of {@link LazyMusicLibrary}: every accessor
 * must be safe before, during and after the background scan, and the catalogue
 * must be identical to an eager {@link MusicLibrary#scan} of the same roots.
 */
public class LazyMusicLibraryTest {

    /** A small pool of real playable-looking files (over the sting threshold). */
    private static File makeLibraryRoot(File dir, String[] names) throws Exception {
        List<File> roots = new ArrayList<>();
        File calm = new File(dir, "calm");
        assertTrue(calm.mkdirs() || calm.isDirectory());
        for (String n : names) {
            File f = new File(calm, n);
            // > MIN_TRACK_BYTES so classify() keeps it, and a real MP3 header
            // would only matter for playback, not for scanning.
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(new byte[MusicLibrary.MIN_TRACK_BYTES > Integer.MAX_VALUE
                        ? 1024 : (int) MusicLibrary.MIN_TRACK_BYTES + 1]);
            }
            roots.add(f);
        }
        return dir;
    }

    @Test
    public void accessorsAreSafeBeforeTheScanLands() throws Exception {
        File dir = Files.createTempDirectory("lazy-pre").toFile();
        try {
            makeLibraryRoot(dir, new String[]{"one.mp3", "two.mp3"});
            LazyMusicLibrary lib = new LazyMusicLibrary(Collections.singletonList(dir));

            // Not started yet: must behave like an empty library, never throw.
            assertTrue(lib.isEmpty());
            assertEquals(0, lib.size());
            assertEquals(0, lib.count(MusicDirector.CONTEXT_CALM));
            assertNotNull(lib.tracks());
            assertTrue(lib.tracks().isEmpty());
            assertNotNull(lib.populatedContexts());
            assertTrue(lib.populatedContexts().isEmpty());
            assertNull(lib.pick(MusicDirector.CONTEXT_CALM, new Random(1), null));
            assertTrue(!lib.isReady());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void scanPopulatesTheSameCatalogueAsAnEagerScan() throws Exception {
        File dir = Files.createTempDirectory("lazy-eq").toFile();
        try {
            makeLibraryRoot(dir, new String[]{"one.mp3", "two.mp3", "three.mp3"});

            MusicLibrary eager = MusicLibrary.scan(Collections.singletonList(dir));

            LazyMusicLibrary lazy = new LazyMusicLibrary(Collections.singletonList(dir));
            lazy.start();
            MusicLibrary resolved = lazy.await();

            assertTrue(lazy.isReady());
            assertEquals(eager.size(), lazy.size());
            assertEquals(eager.size(), resolved.size());
            assertEquals(eager.count(MusicDirector.CONTEXT_CALM), lazy.count(MusicDirector.CONTEXT_CALM));

            // Same tracks, same pools.
            List<String> eagerFiles = names(eager);
            List<String> lazyFiles = names(lazy);
            Collections.sort(eagerFiles);
            Collections.sort(lazyFiles);
            assertEquals(eagerFiles, lazyFiles);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void pickWorksOnceReady() throws Exception {
        File dir = Files.createTempDirectory("lazy-pick").toFile();
        try {
            makeLibraryRoot(dir, new String[]{"one.mp3", "two.mp3"});
            LazyMusicLibrary lib = new LazyMusicLibrary(Collections.singletonList(dir));
            lib.start();
            lib.await();

            MusicLibrary.Track t = lib.pick(MusicDirector.CONTEXT_CALM, new Random(7), null);
            assertNotNull("a scanned library must yield a pickable track", t);
            assertNotNull(t.file);
            assertTrue(t.file.isFile());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void missingRootsYieldAnEmptyLibraryWithoutThrowing() throws Exception {
        LazyMusicLibrary lib = new LazyMusicLibrary(
                Collections.singletonList(new File("no-such-music-root-here")));
        lib.start();
        MusicLibrary resolved = lib.await();
        assertNotNull(resolved);
        assertTrue(lib.isEmpty());
        assertEquals(0, lib.size());
        assertNull(lib.pick(MusicDirector.CONTEXT_CALM, new Random(1), null));
    }

    private static List<String> names(MusicLibrary lib) {
        List<String> out = new ArrayList<>();
        for (MusicLibrary.Track t : lib.tracks()) {
            out.add(t.file.getName());
        }
        return out;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}