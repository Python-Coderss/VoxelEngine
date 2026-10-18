package villager.voice;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Smoke test for the local voice. It runs only on a machine that actually has
 * one installed, so the suite still passes on Linux/macOS or a stripped-down
 * Windows image; the interesting contract (fallbacks, captions, caching) is
 * covered by {@code CharacterVoiceTest} without any audio device.
 */
public class WindowsSapiTtsTest {

    @Test
    public void localVoiceRendersAudibleSpeech() throws Exception {
        WindowsSapiTts tts = new WindowsSapiTts(Paths.get("target", "tts-test"));
        assumeTrue("requires an installed Windows speech voice", tts.isAvailable());

        WavAudio audio = tts.render("Hold the gate. The survey line ends here.",
                VoiceProfile.ANCIENT_BUILDER);
        assertTrue("a real sample rate", audio.sampleRate >= 8000);
        assertTrue("at least half a second of audio",
                audio.samples.length > audio.sampleRate / 2);

        double energy = 0.0;
        for (int i = 0; i < audio.samples.length; i++) {
            energy += audio.samples[i] * audio.samples[i];
        }
        assertTrue("the render is not silence", energy > 0.0001);
    }

    @Test
    public void localSpeechCanBeTurnedOff() {
        String previous = System.getProperty(WindowsSapiTts.ENABLED_PROPERTY);
        System.setProperty(WindowsSapiTts.ENABLED_PROPERTY, "off");
        try {
            WindowsSapiTts tts = new WindowsSapiTts(Paths.get("target", "tts-off"));
            assertFalse("an opt-out must never probe or spawn a process", tts.isAvailable());
        } finally {
            if (previous == null) {
                System.clearProperty(WindowsSapiTts.ENABLED_PROPERTY);
            } else {
                System.setProperty(WindowsSapiTts.ENABLED_PROPERTY, previous);
            }
        }
    }
}
