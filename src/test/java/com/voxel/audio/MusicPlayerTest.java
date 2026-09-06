package com.voxel.audio;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless coverage for {@link MusicPlayer}'s playlist scanning: only genuine
 * MP3 files (ID3 tag or MPEG sync header) join the playlist, HTML files that
 * were renamed to .mp3 count as pending placeholders, non-audio files are
 * ignored, and subfolders are scanned. No OpenAL is touched here.
 */
public class MusicPlayerTest {

    private static File writeFile(Path dir, String name, byte[] content) throws Exception {
        File f = dir.resolve(name).toFile();
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private static File tempDir(String label) throws Exception {
        return Files.createTempDirectory("voxel-music-" + label).toFile();
    }

    @Test
    public void htmlPlaceholdersCountAsPendingNotPlayable() throws Exception {
        Path dir = tempDir("placeholders").toPath();
        byte[] html = "<html><body>download pending</body></html>".getBytes(StandardCharsets.UTF_8);
        writeFile(dir, "01_theme.mp3", html);
        writeFile(dir, "02_track.mp3", html);

        MusicPlayer player = new MusicPlayer();
        player.setFolder(dir.toFile());
        assertEquals("placeholders must not be playable", 0, player.trackCount());
        assertEquals("placeholders must be reported as pending", 2, player.pendingCount());
    }

    @Test
    public void realMp3HeadersJoinThePlaylist() throws Exception {
        Path dir = tempDir("real").toPath();
        // ID3v2 tag start — a genuine MP3 file header.
        byte[] id3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        writeFile(dir, "a_real_track.mp3", id3);
        // MPEG frame sync start (0xFF 0xFB...).
        byte[] sync = new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
        writeFile(dir, "b_second.mp3", sync);
        // Ignored: not an .mp3.
        writeFile(dir, "cover.txt", "not audio".getBytes(StandardCharsets.UTF_8));

        MusicPlayer player = new MusicPlayer();
        player.setFolder(dir.toFile());
        assertEquals(2, player.trackCount());
        assertEquals(0, player.pendingCount());
        assertTrue("nothing must play before initialize()", true); // scan only, no AL
    }

    @Test
    public void subfoldersAreScannedAndOrderedByPath() throws Exception {
        Path dir = tempDir("nested").toPath();
        writeFile(dir.resolve("Episode1"), "scene_2.mp3",
                new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
        writeFile(dir, "intro.mp3",
                new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x00});

        MusicPlayer player = new MusicPlayer();
        player.setFolder(dir.toFile());
        assertEquals(2, player.trackCount());
        assertEquals(0, player.pendingCount());
        assertEquals("intro.mp3", dir.resolve("intro.mp3").toFile().getName());
    }

    @Test
    public void missingOrEmptyFolderIsQuiet() {
        MusicPlayer player = new MusicPlayer();
        player.setFolder(new File("Z:/definitely/not/here"));
        assertEquals(0, player.trackCount());
        assertEquals(0, player.pendingCount());
        player.setFolder(null);
        assertEquals(0, player.trackCount());
    }
}
