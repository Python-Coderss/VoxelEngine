package com.voxel.ai.speech;

import com.voxel.audio.LiveCaptions;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ancient builders are voiceless in the world but must still appear in the
 * live captions. These tests cover that contract: a line reaches
 * {@link LiveCaptions} with no audio stack involved, empty lines are ignored,
 * and the per-speaker rate limit holds a crowd back.
 */
public class CaptionSpeechTest {

    @Before
    public void reset() {
        LiveCaptions.clear();
        CaptionSpeech.resetForTest();
    }

    @Test
    public void lineAppearsInLiveCaptions() {
        String line = "You stand on stone we raised before your grandfathers were born.";
        assertTrue(CaptionSpeech.say(7, "Architect of the Grand Archive", line));
        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull("the caption must be visible", snap);
        assertEquals("Architect of the Grand Archive", snap.speaker);
        // Captions reveal word by word; after the reveal window the whole line shows.
        assertTrue(snap.revealedText.startsWith("You"));
        LiveCaptions.tick(LiveCaptions.estimateDuration(line, 1.0) + 0.05f);
        assertEquals(line, LiveCaptions.current().revealedText);
    }

    @Test
    public void emptyLinesAreIgnoredAndDoNotConsumeTheRateLimit() {
        assertFalse(CaptionSpeech.say(9, "Scholar", "   "));
        assertNull(LiveCaptions.current());
        // The rejected empty line must not have started the cooldown.
        assertFalse(CaptionSpeech.isRateLimited(9));
        assertTrue(CaptionSpeech.say(9, "Scholar", "The charts agree with the survey."));
    }

    @Test
    public void sameSpeakerIsRateLimited() {
        assertTrue(CaptionSpeech.say(3, "Engineer", "Bring the crane around."));
        assertFalse("a second line immediately after is dropped",
                CaptionSpeech.say(3, "Engineer", "Measure twice."));
        assertTrue(CaptionSpeech.isRateLimited(3));
    }

    @Test
    public void differentSpeakersAreIndependent() {
        assertTrue(CaptionSpeech.say(11, "Sentry", "All quiet along the outer wall."));
        assertTrue(CaptionSpeech.say(12, "Curator", "I record it for the archive."));
    }

    @Test
    public void resetForTestClearsTheCooldown() {
        CaptionSpeech.say(5, "Sentry", "Hold the line!");
        assertTrue(CaptionSpeech.isRateLimited(5));
        CaptionSpeech.resetForTest();
        assertFalse(CaptionSpeech.isRateLimited(5));
        assertTrue(CaptionSpeech.say(5, "Sentry", "Fall back to the hall."));
    }
}
