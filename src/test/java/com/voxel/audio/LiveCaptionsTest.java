package com.voxel.audio;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveCaptionsTest {

    @Before
    public void clearCaptions() {
        LiveCaptions.clear();
    }

    @Test
    public void revealsWordsProgressively() {
        LiveCaptions.show("Farmer", "I am building a very fine wall", 4f);
        LiveCaptions.tick(2f); // half of the reveal over 7 words -> 4 shown
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull(snap);
        assertEquals("I am building a", snap.revealedText);
        assertTrue(snap.progress > 0.4f && snap.progress < 0.6f);
    }

    @Test
    public void holdsFullTextThenExpires() {
        LiveCaptions.show("Farmer", "Hello there", 1f);
        LiveCaptions.tick(1.1f);
        assertEquals("Hello there", LiveCaptions.current().revealedText);
        // reveal (1s) + hold (~1.5s) + fade (0.5s) -> gone
        LiveCaptions.tick(4f);
        LiveCaptions.tick(0.1f);
        assertNull(LiveCaptions.current());
    }

    @Test
    public void sameSpeakerReplacesImmediately() {
        LiveCaptions.show("Farmer", "First line", 3f);
        LiveCaptions.tick(0.5f);
        LiveCaptions.show("Farmer", "Second line", 3f);
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull(snap);
        assertTrue("newest line from the same speaker wins",
                snap.revealedText.startsWith("Second"));
    }

    @Test
    public void differentSpeakerQueuesBehind() {
        LiveCaptions.show("Farmer", "First line", 1f);
        LiveCaptions.show("Nitwit", "Second line", 1f);
        LiveCaptions.tick(4f);
        LiveCaptions.tick(0.1f);
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull(snap);
        assertEquals("Nitwit", snap.speaker);
        assertTrue(snap.revealedText.startsWith("Second"));
    }

    @Test
    public void emptyLinesAreIgnored() {
        LiveCaptions.show("Farmer", "   ", 2f);
        assertNull(LiveCaptions.current());
    }

    @Test
    public void durationEstimateScalesWithWordsAndSpeed() {
        float shortLine = LiveCaptions.estimateDuration("Hello", 1.0);
        float longLine = LiveCaptions.estimateDuration(
                "this is a much longer line with many words in it", 1.0);
        assertTrue(longLine > shortLine);
        float fast = LiveCaptions.estimateDuration("this is a longer line entirely", 2.0);
        float slow = LiveCaptions.estimateDuration("this is a longer line entirely", 1.0);
        assertTrue(fast < slow);
        assertTrue(LiveCaptions.estimateDuration("x", 1.0) >= 1.0f);
        assertTrue(LiveCaptions.estimateDuration(shortLine + "", 1.0) >= 1.0f);
    }

    @Test
    public void audioCaptionsWaitForPlaybackToStart() {
        LiveCaptions.showForAudio("Farmer", "Hello there friend", 2f);
        // Request time is not speech time: the clip may still be decoding.
        LiveCaptions.tick(1.2f);
        assertEquals("a caption must not run ahead of its audio", "",
                LiveCaptions.current().revealedText);

        LiveCaptions.beginAudio(2f, new float[]{0f, 0.5f, 1.1f});
        LiveCaptions.tick(0.1f);
        assertEquals("Hello", LiveCaptions.current().revealedText);
        LiveCaptions.tick(0.5f);
        assertEquals("Hello there", LiveCaptions.current().revealedText);
        LiveCaptions.tick(0.6f);
        assertEquals("Hello there friend", LiveCaptions.current().revealedText);
    }

    @Test
    public void measuredWordTimesFollowThePauses() {
        LiveCaptions.showForAudio("Farmer", "we built this wall", 3f);
        // First two words in the first burst, the rest after the pause.
        LiveCaptions.beginAudio(3f, new float[]{0.40f, 0.72f, 1.52f, 1.86f});
        LiveCaptions.tick(0.45f);
        assertEquals("we", LiveCaptions.current().revealedText);
        LiveCaptions.tick(0.55f);
        assertEquals("the pause holds the rest back", "we built",
                LiveCaptions.current().revealedText);
        LiveCaptions.tick(0.9f);
        assertEquals("we built this wall", LiveCaptions.current().revealedText);
    }

    @Test
    public void aCaptionWithNoAudioStillReveals() {
        LiveCaptions.showForAudio("Farmer", "No device on this machine", 1f);
        LiveCaptions.tick(3f);
        assertNotNull("the watchdog must not leave the caption stuck",
                LiveCaptions.current());
        assertTrue(LiveCaptions.current().revealedText.length() > 0);
    }

    @Test
    public void clearDropsEverything() {
        LiveCaptions.show("Farmer", "First line", 2f);
        LiveCaptions.show("Nitwit", "Second line", 2f);
        LiveCaptions.clear();
        assertNull(LiveCaptions.current());
    }
}
