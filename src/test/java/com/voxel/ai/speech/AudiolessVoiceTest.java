package com.voxel.ai.speech;

import com.voxel.audio.LiveCaptions;
import com.voxel.audio.VillagerAudioManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import villager.voice.TextToSpeech;
import villager.voice.VoiceProfile;
import villager.voice.WavAudio;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The voice bridge must work when the machine has no audio device at all.
 *
 * <p>OpenAL is never initialized here: the manager is built with a temporary
 * cache and left closed for playback, exactly like a headless CI box or a
 * laptop with no output device. Lines must still be captioned and rendered to
 * the cache, and every playback entry point must be a safe no-op.</p>
 */
public class AudiolessVoiceTest {

    private static final class FakeTts implements TextToSpeech {
        final List<String> rendered = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public WavAudio render(String text, VoiceProfile profile) {
            rendered.add(profile.name() + ":" + text);
            return new WavAudio(16000, new float[800]);
        }
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private VillagerAudioManager audio;
    private FakeTts tts;

    @Before
    public void setUp() throws Exception {
        LiveCaptions.clear();
        CharacterVoice.resetForTest();
        tts = new FakeTts();
        CharacterVoice.setSynthesizerForTest(tts);
        System.setProperty("voxel.voice.cache",
                temp.newFolder("cache").getAbsolutePath());
        // No initialize(): there is no OpenAL device in this test.
        audio = new VillagerAudioManager(temp.newFolder("voice-cache").toPath(), null);
        CharacterVoice.bind(audio);
    }

    @After
    public void tearDown() {
        CharacterVoice.unbind();
        CharacterVoice.resetForTest();
        LiveCaptions.clear();
        System.clearProperty("voxel.voice.cache");
        if (audio != null) audio.close();
    }

    @Test
    public void withoutAnAudioDeviceLinesAreStillCaptionedAndRendered() {
        String line = "Hold the gate. The survey line ends here.";
        String spoken = CharacterVoice.speak(1, "Ancient Sentry", line,
                VoiceProfile.ANCIENT_BUILDER);
        assertEquals("the authored line is the caption", line, spoken);

        LiveCaptions.Snapshot snap = LiveCaptions.current();
        assertNotNull("the caption publishes without OpenAL", snap);
        assertEquals("Ancient Sentry", snap.speaker);
        LiveCaptions.tick(LiveCaptions.estimateDuration(line, 1.0) + 0.05f);
        assertEquals(line, LiveCaptions.current().revealedText);

        awaitRendered(1);
        assertEquals(VoiceProfile.ANCIENT_BUILDER + ":" + line, tts.rendered.get(0));
    }

    @Test
    public void queuingAudioWithoutADeviceIsANoOp() {
        // openALReady is false: this must drain neither queue nor device and
        // must never throw on the game thread.
        audio.playRendered(new WavAudio(16000, new float[800]), 1.0);
        audio.playRendered(null, 1.0);
        audio.playRendered(new WavAudio(16000, new float[0]), 1.0);
    }

    @Test
    public void announcedLinesReachTheHudWithoutADevice() {
        audio.announceLine("The lamps burn. The gates hold. Good.",
                "Ancient Curator", 2.0f);
        assertNotNull(LiveCaptions.current());
        assertEquals("Ancient Curator", LiveCaptions.current().speaker);
    }

    private void awaitRendered(int count) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (tts.rendered.size() < count && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals(count, tts.rendered.size());
        assertTrue(tts.rendered.size() > 0);
    }
}
