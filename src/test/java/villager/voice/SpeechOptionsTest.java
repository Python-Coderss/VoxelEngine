package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SpeechOptionsTest {
    @Test
    public void toneRepresentsMoodAndNewFieldsAreStable() {
        SpeechOptions joking = new SpeechOptions(1.0, 0.0, 1.0, 1.0,
                0.36, "happy", 0.0, 0.8, true);
        assertTrue(joking.getTone() > 0.0);
        assertTrue(joking.getSarcasm() > 0.0);
        assertTrue(joking.isQuestion());
        assertNotEquals(joking.cacheKey(), SpeechOptions.DEFAULT.cacheKey());
    }

    @Test
    public void punctuationQuestionDetectionHandlesQuotes() {
        assertTrue(SpeechOptions.looksLikeQuestion("Really?"));
        assertTrue(SpeechOptions.looksLikeQuestion("Really?\""));
        assertFalse(SpeechOptions.looksLikeQuestion("Really."));
        assertFalse(SpeechOptions.looksLikeQuestion(null));
    }

    @Test
    public void oldConstructorsDefaultNewControls() {
        SpeechOptions old = new SpeechOptions(1.0, 0.0, 1.0, 0.0, 0.36,
                "neutral", 0.0);
        assertTrue(old.getSarcasm() == 0.0);
        assertFalse(old.isQuestion());
    }
}
