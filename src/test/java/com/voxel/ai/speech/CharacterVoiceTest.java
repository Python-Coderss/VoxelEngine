package com.voxel.ai.speech;

import com.voxel.audio.LiveCaptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import villager.voice.TextToSpeech;
import villager.voice.VoiceProfile;
import villager.voice.WavAudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every character that speaks authored text must be audible and captioned.
 *
 * <p>These tests pin the contract of {@link CharacterVoice}: with a local voice
 * the authored line is what is spoken and what is shown; without one the line
 * still reaches the captions; repeated lines are not doubled; and cutscene
 * beats are never dropped by the conversational rate limit. A fake synthesizer
 * stands in for the operating-system voice so the tests are deterministic and
 * never spawn a process.</p>
 */
public class CharacterVoiceTest {

    private static final class FakeTts implements TextToSpeech {
        final List<String> rendered = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public WavAudio render(String text, VoiceProfile profile) {
            rendered.add(profile.name() + ":" + text);
            return new WavAudio(16000, new float[1600]);
        }
    }

    private FakeTts tts;
    private String previousCacheProperty;

    @Rule
    public TemporaryFolder cacheRoot = new TemporaryFolder();

    @Before
    public void reset() throws Exception {
        LiveCaptions.clear();
        CharacterVoice.resetForTest();
        // Each test gets its own cache: a cached WAV from an earlier run must
        // never hide whether this run synthesized the line.
        previousCacheProperty = System.getProperty("voxel.voice.cache");
        System.setProperty("voxel.voice.cache", cacheRoot.newFolder("cache").getAbsolutePath());
        tts = new FakeTts();
        CharacterVoice.setSynthesizerForTest(tts);
    }

    @After
    public void tearDown() {
        CharacterVoice.resetForTest();
        LiveCaptions.clear();
        if (previousCacheProperty == null) {
            System.clearProperty("voxel.voice.cache");
        } else {
            System.setProperty("voxel.voice.cache", previousCacheProperty);
        }
    }

    @Test
    public void authoredLineIsSpokenAndCaptionedVerbatim() {
        String line = "You stand on stone we raised before your grandfathers were born.";
        String spoken = CharacterVoice.speak(7, "Architect of the Grand Archive", line,
                VoiceProfile.ANCIENT_BUILDER);
        assertEquals("the authored line is what the voice says", line, spoken);

        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull("the line is captioned", snap);
        assertEquals("Architect of the Grand Archive", snap.speaker);
        LiveCaptions.tick(LiveCaptions.estimateDuration(line, 1.0) + 0.05f);
        assertEquals("the caption is the spoken text", line,
                LiveCaptions.current().revealedText);

        awaitRendered(1);
        assertEquals(VoiceProfile.ANCIENT_BUILDER + ":" + line, tts.rendered.get(0));
    }

    @Test
    public void emptyLinesAreIgnoredAndDoNotConsumeTheRateLimit() {
        assertNull(CharacterVoice.speak(9, "Scholar", "   ",
                VoiceProfile.ANCIENT_BUILDER));
        assertNotNull("the next real line still speaks",
                CharacterVoice.speak(9, "Scholar", "The charts agree with the survey.",
                        VoiceProfile.ANCIENT_BUILDER));
    }

    @Test
    public void sameSpeakerIsRateLimited() {
        assertNotNull(CharacterVoice.speak(3, "Engineer", "Bring the crane around.",
                VoiceProfile.ANCIENT_BUILDER));
        assertNull("a second line immediately after is dropped",
                CharacterVoice.speak(3, "Engineer", "Measure twice.",
                        VoiceProfile.ANCIENT_BUILDER));
    }

    @Test
    public void aCutsceneBeatIsNeverRateLimited() {
        assertNotNull(CharacterVoice.speakScripted("Ancient Sentry", "Hold the line!"));
        assertNotNull("scripted timing already decided when this line happens",
                CharacterVoice.speakScripted("Ancient Sentry", "Fall back to the hall."));
        awaitRendered(2);
    }

    @Test
    public void theSameLineFromTheSameSpeakerIsNotDoubled() {
        assertNotNull(CharacterVoice.speakScripted("Architect", "The keystone goes in last."));
        assertEquals("a direct interaction and its cutscene share one line",
                "The keystone goes in last.",
                CharacterVoice.speakScripted("Architect", "The keystone goes in last."));
        awaitRendered(1);
        sleep(150);
        assertEquals("only one take is rendered", 1, tts.rendered.size());
    }

    @Test
    public void speakersGetDistinctProfiles() {
        CharacterVoice.speakScripted("Narrator", "The Overworld is a little safer tonight.");
        CharacterVoice.speakScripted("You", "Not today.");
        CharacterVoice.speakScripted("Ancient Sentry", "All quiet along the outer wall.");
        awaitRendered(3);
        assertTrue(tts.rendered.contains(VoiceProfile.NARRATOR + ":"
                + "The Overworld is a little safer tonight."));
        assertTrue(tts.rendered.contains(VoiceProfile.PLAYER + ":Not today."));
        assertTrue(tts.rendered.contains(VoiceProfile.ANCIENT_BUILDER + ":"
                + "All quiet along the outer wall."));
    }

    @Test
    public void withoutALocalVoiceTheLineStillAppearsInCaptions() {
        tts.available = false;
        CharacterVoice.resetForTest();
        CharacterVoice.setSynthesizerForTest(tts);

        String line = "The lamps burn. The gates hold. Good.";
        String spoken = CharacterVoice.speak(21, "Ancient Curator", line,
                VoiceProfile.ANCIENT_BUILDER);
        assertEquals("the authored line is captioned", line, spoken);
        assertNotNull(LiveCaptions.current());
        assertEquals("Ancient Curator", LiveCaptions.current().speaker);
        assertTrue("nothing is rendered without a voice", tts.rendered.isEmpty());
    }

    @Test
    public void unboundVoiceIsSilentButCaptioned() {
        CharacterVoice.resetForTest();
        assertFalse("no audio stack is bound", CharacterVoice.isBound());
        String line = "Measure twice.";
        assertNotNull(CharacterVoice.speak(33, "Ancient Architect", line,
                VoiceProfile.ANCIENT_BUILDER));
        assertNotNull(LiveCaptions.current());
    }

    @Test
    public void villagersStayOnTheirRecordedVoice() {
        assertNotNull(CharacterVoice.speakScripted("Villager", "Hmm..."));
        sleep(150);
        assertTrue("a villager label never spends a synthetic take",
                tts.rendered.isEmpty());
    }

    @Test
    public void cinematicSpeakerLabelsMapToCharacters() {
        assertEquals(VoiceProfile.NARRATOR, VoiceProfile.forSpeaker("Narrator"));
        assertEquals(VoiceProfile.NARRATOR, VoiceProfile.forSpeaker(null));
        assertEquals(VoiceProfile.PLAYER, VoiceProfile.forSpeaker("You"));
        assertEquals(VoiceProfile.VILLAGER, VoiceProfile.forSpeaker("Villager"));
        assertEquals(VoiceProfile.ANCIENT_BUILDER, VoiceProfile.forSpeaker("Architect of the Grand Archive"));
    }

    private void awaitRendered(int count) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (tts.rendered.size() < count && System.currentTimeMillis() < deadline) {
            sleep(20);
        }
        assertEquals("all requested lines were rendered", count, tts.rendered.size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
