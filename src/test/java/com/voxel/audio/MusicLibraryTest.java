package com.voxel.audio;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Headless coverage for the classified music library. */
public class MusicLibraryTest {

    @Test
    public void aetherFoldersAreHonoured() {
        assertEquals(MusicDirector.CONTEXT_BOSS,
                MusicLibrary.classify("aether", "boss/slider_fight.ogg", "slider_fight.ogg"));
        assertEquals(MusicDirector.CONTEXT_MENU,
                MusicLibrary.classify("aether", "menu.ogg", "menu.ogg"));
        assertEquals(MusicDirector.CONTEXT_AETHER,
                MusicLibrary.classify("aether", "aether3.ogg", "aether3.ogg"));
    }

    @Test
    public void mcsmMoodKeywordsClassify() {
        assertEquals(MusicDirector.CONTEXT_BOSS,
                MusicLibrary.classify("mcsm", "01/main/Wither A.mp3", "Wither A.mp3"));
        assertEquals(MusicDirector.CONTEXT_COMBAT,
                MusicLibrary.classify("mcsm", "02/main/IvorFight B.mp3", "IvorFight B.mp3"));
        assertEquals(MusicDirector.CONTEXT_CAVE,
                MusicLibrary.classify("mcsm", "04/main/Cave A (neutral).mp3", "Cave A (neutral).mp3"));
        assertEquals(MusicDirector.CONTEXT_NETHER,
                MusicLibrary.classify("mcsm", "03/main/Nether A.mp3", "Nether A.mp3"));
        assertEquals(MusicDirector.CONTEXT_VILLAGE,
                MusicLibrary.classify("mcsm", "02/main/BoomtownTheme A.mp3", "BoomtownTheme A.mp3"));
        assertEquals(MusicDirector.CONTEXT_TUTORIAL,
                MusicLibrary.classify("mcsm", "01/main/Montage A.mp3", "Montage A.mp3"));
        assertEquals(MusicDirector.CONTEXT_END,
                MusicLibrary.classify("mcsm", "05/main/Finale C.mp3", "Finale C.mp3"));
        assertEquals(MusicDirector.CONTEXT_STORY,
                MusicLibrary.classify("mcsm", "03/main/103Credits A.mp3", "103Credits A.mp3"));
        assertEquals(MusicDirector.CONTEXT_CALM,
                MusicLibrary.classify("mcsm", "03/main/Skyland A.mp3", "Skyland A.mp3"));
        assertEquals(MusicDirector.CONTEXT_DANGER,
                MusicLibrary.classify("mcsm", "01/main/ActionSus A.mp3", "ActionSus A.mp3"));
    }

    @Test
    public void emptyPoolsFallBackToTheNearestMood() {
        String[] danger = MusicLibrary.fallbackChain(MusicDirector.CONTEXT_DANGER);
        assertTrue("danger must borrow tension, not calm",
                java.util.Arrays.asList(danger).contains(MusicDirector.CONTEXT_CAVE));
        String[] sift = MusicLibrary.fallbackChain(MusicDirector.CONTEXT_SIFT);
        assertTrue(java.util.Arrays.asList(sift).contains(MusicDirector.CONTEXT_CAVE));
        String[] story = MusicLibrary.fallbackChain(MusicDirector.CONTEXT_STORY);
        assertEquals(MusicDirector.CONTEXT_STORY, story[0]);
    }

    @Test
    public void stingsAndTakesAreNotBackgroundMusic() {
        assertNull(MusicLibrary.classify("mcsm", "02/main/BoomtownSting A.mp3", "BoomtownSting A.mp3"));
        assertNull(MusicLibrary.classify("mcsm", "01/main/Wither riser A.mp3", "Wither riser A.mp3"));
        assertNull(MusicLibrary.classify("mcsm", "01/main/ocelotMobLayers00.mp3", "ocelotMobLayers00.mp3"));
    }

    @Test
    public void explicitMoodFoldersWin() {
        assertEquals(MusicDirector.CONTEXT_COMBAT,
                MusicLibrary.classify("fanmade", "combat/my_track.mp3", "my_track.mp3"));
        assertEquals(MusicDirector.CONTEXT_NIGHT,
                MusicLibrary.classify("minecraft", "night/calm_piano.mp3", "calm_piano.mp3"));
    }

    @Test
    public void scanSkipsUnusedFoldersShortFilesAndDuplicates() throws Exception {
        Path root = Files.createTempDirectory("voxel-music-library");
        Path main = Files.createDirectories(root.resolve("01/main"));
        Path unused = Files.createDirectories(root.resolve("01/unused"));
        createSized(main.resolve("Nether A.mp3"), 800_000);
        createSized(unused.resolve("Unused Nether.mp3"), 800_000);
        createSized(main.resolve("tiny sting.mp3"), 1000); // below the background threshold

        MusicLibrary lib = MusicLibrary.scan(root.toFile(), root.toFile());
        assertEquals("duplicate roots must not double-count", 1, lib.size());
        assertEquals(MusicDirector.CONTEXT_NETHER, lib.tracks().get(0).context);
        assertFalse(lib.isEmpty());
    }

    @Test
    public void bundledInstallPopulatesTheMainPools() {
        MusicLibrary lib = MusicLibrary.scan(
                new File("src/resources/music"),
                new File("src/main/resources/assets/aether/sounds/music"));
        if (lib.isEmpty()) return; // music is optional; never fail a headless CI without it
        assertTrue("combat pool", lib.count(MusicDirector.CONTEXT_COMBAT) > 0);
        assertTrue("boss pool", lib.count(MusicDirector.CONTEXT_BOSS) > 0);
        assertTrue("calm pool", lib.count(MusicDirector.CONTEXT_CALM) > 0);
        assertTrue("aether pool", lib.count(MusicDirector.CONTEXT_AETHER) > 0);
        assertTrue("tutorial pool", lib.count(MusicDirector.CONTEXT_TUTORIAL) > 0);
        // A pick always resolves, even for a pool nobody stocked.
        MusicLibrary.Track pick = lib.pick("does_not_exist", new Random(1), java.util.Collections.<File>emptyList());
        assertNotNull(pick);
    }

    private static void createSized(Path file, int bytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(bytes);
            raf.seek(0);
            raf.write(new byte[]{'I', 'D', '3'});
        }
    }
}
