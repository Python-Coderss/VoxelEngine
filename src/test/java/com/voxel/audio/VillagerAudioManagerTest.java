package com.voxel.audio;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import villager.voice.ClipIndex;
import villager.voice.ClipLibrary;
import villager.voice.ClipScript;
import villager.voice.SpeechOptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The speech contract: a line only ever plays and captions as the exact
 * transcript of a real recorded clip — audio and captions cannot drift apart.
 */
public class VillagerAudioManagerTest {

    private static final String MANIFEST = "{"
            + "\"version\":1,"
            + "\"clips\":["
            + "{\"id\":\"addon:a\",\"kind\":\"addon\",\"file\":\"a\","
            + "\"text\":\"Ahh! Help! Run away!\",\"duration\":1.5,"
            + "\"cues\":[[0,\"Ahh! Help!\"],[0.9,\"Run away!\"]]},"
            + "{\"id\":\"addon:b\",\"kind\":\"addon\",\"file\":\"b\","
            + "\"text\":\"Look at that beautiful wall!\",\"duration\":2.0},"
            + "{\"id\":\"teavrsp:hello\",\"kind\":\"teavrsp\",\"file\":\"Hello.wav\","
            + "\"text\":\"Hello\",\"duration\":0.6}"
            + "]}";

    private VillagerAudioManager manager;

    @Before
    public void setUp() throws Exception {
        LiveCaptions.clear();
        Path emptyDir = Files.createTempDirectory("audio-manager-test");
        ClipLibrary library = new ClipLibrary(ClipIndex.parse(MANIFEST), emptyDir, null);
        manager = new VillagerAudioManager(
                Files.createTempDirectory("audio-manager-cache"), library);
    }

    @After
    public void tearDown() {
        if (manager != null) manager.close();
        LiveCaptions.clear();
    }

    @Test
    public void spokenLineIsAlwaysAClipTranscript() {
        String spoken = manager.requestSpeech("something about running away",
                SpeechOptions.DEFAULT, "Farmer");
        assertNotNull("resolves to a clip", spoken);
        assertTrue("must be one of the clip transcripts",
                spoken.equals("Ahh! Help! Run away!")
                        || spoken.equals("Look at that beautiful wall!")
                        || spoken.equals("Hello"));
    }

    @Test
    public void captionMatchesTheSpokenClipExactly() {
        String spoken = manager.requestSpeech("beautiful wall", SpeechOptions.DEFAULT,
                "Builder");
        assertEquals("Look at that beautiful wall!", spoken);
        LiveCaptions.tick(0.01f);
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull(snap);
        assertEquals("Builder", snap.speaker);
        assertEquals("caption is the clip transcript", spoken, snap.revealedText);
    }

    @Test
    public void captionsRevealOnTheClipsOwnCueTiming() {
        String spoken = manager.requestSpeech("help run away", SpeechOptions.DEFAULT,
                "Farmer");
        assertEquals("Ahh! Help! Run away!", spoken);
        LiveCaptions.tick(0.2f); // first cue at t=0
        assertEquals("Ahh! Help!", LiveCaptions.current().revealedText);
        LiveCaptions.tick(1.2f); // second cue at t=0.9 (speed 1.0 * DEFAULT 1.08)
        String now = LiveCaptions.current().revealedText;
        assertTrue("second fragment appears on cue (" + now + ")",
                now.contains("Run away!"));
    }

    @Test
    public void topicRequestsSpeakOnlyClipLines() {
        String spoken = manager.requestTopic(ClipScript.Topic.PANIC,
                SpeechOptions.DEFAULT, "Nitwit");
        assertNotNull(spoken);
        assertTrue("panic topic returns a panic clip transcript",
                spoken.equals("Ahh! Help! Run away!"));
    }

    @Test
    public void nothingIsSaidWithoutClips() throws Exception {
        VillagerAudioManager silent = new VillagerAudioManager(
                Files.createTempDirectory("audio-manager-cache"),
                new ClipLibrary(ClipIndex.parse("{\"version\":1,\"clips\":[]}"),
                        Files.createTempDirectory("empty-corpus"), null));
        try {
            LiveCaptions.clear();
            assertNull("no clips means no line and no caption",
                    silent.requestSpeech("hello?", SpeechOptions.DEFAULT, "Farmer"));
            assertNull(LiveCaptions.current());
        } finally {
            silent.close();
        }
    }

    @Test
    public void exactTranscriptHintsWin() {
        String spoken = manager.requestSpeech("Hello", SpeechOptions.DEFAULT, "Farmer");
        assertEquals("Hello", spoken);
    }
}
