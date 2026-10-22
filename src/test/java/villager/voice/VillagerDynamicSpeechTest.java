package villager.voice;

import com.voxel.audio.VillagerAudioManager;

import org.junit.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Villagers keep their recordings whenever a clip is on topic, and speak
 * unmatched lines with the dynamic fixed-voice TTS instead of a random
 * vocalization. The injected fake voice makes the routing deterministic; the
 * real model is covered by {@link CoquiVitsHeadlessTest}.
 */
public class VillagerDynamicSpeechTest {

    private static final String INDEX = "{\"clips\":["
            + "{\"id\":\"greet\",\"kind\":\"addon\",\"file\":\"greet.ogg\","
            + "\"text\":\"Hello friend\",\"duration\":1.0},"
            + "{\"id\":\"hmm\",\"kind\":\"addon\",\"file\":\"hmm.ogg\","
            + "\"text\":\"\",\"duration\":0.5}]}";

    private static final class FakeVoice implements TextToSpeech {
        final AtomicInteger renders = new AtomicInteger();
        volatile String lastText;
        volatile boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public WavAudio render(String text, VoiceProfile profile) {
            renders.incrementAndGet();
            lastText = text;
            float[] samples = new float[2205];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = 0.3f * (float) Math.sin(2.0 * Math.PI * 220.0 * i / 22050);
            }
            return new WavAudio(22050, samples);
        }
    }

    private static VillagerAudioManager manager(Path cache) throws Exception {
        ClipLibrary library = new ClipLibrary(
                ClipIndex.parse(INDEX), cache.resolve("corpus"), null);
        return new VillagerAudioManager(cache, library);
    }

    private static void waitForCacheWav(Path cache) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(cache, "*.wav")) {
                for (Path ignored : stream) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("dynamic render never reached the cache");
    }

    @Test
    public void clipsWinWhenOnTopicAndDynamicSpeaksTheRest() throws Exception {
        Path cache = Files.createTempDirectory("villager-dynamic");
        VillagerAudioManager audio = manager(cache);
        FakeVoice fake = new FakeVoice();
        audio.setDynamicVoiceForTest(fake);
        try {
            // Exact transcript: the recording speaks, no synthesis.
            assertEquals("Hello friend", audio.requestSpeech(
                    "Hello friend", SpeechOptions.DEFAULT, "Villager", 7));
            assertEquals(0, fake.renders.get());

            // Shared words: the related recording speaks, no synthesis.
            assertEquals("Hello friend", audio.requestSpeech(
                    "Hello there, friend!", SpeechOptions.DEFAULT, "Villager", 7));
            assertEquals(0, fake.renders.get());

            // Nothing shares a word: the authored line is spoken dynamically.
            String line = "Quantum flux capacitors hum at midnight";
            assertEquals(line, audio.requestSpeech(
                    line, SpeechOptions.DEFAULT, "Villager", 7));
            waitForCacheWav(cache);
            assertEquals(line, fake.lastText);

            // A repeated line is a cache hit: captioned again, never re-rendered.
            fake.renders.set(0);
            assertEquals(line, audio.requestSpeech(
                    line, SpeechOptions.DEFAULT, "Villager", 7));
            Thread.sleep(2500);
            assertEquals("the cache must serve repeats without rendering",
                    0, fake.renders.get());
        } finally {
            audio.close();
        }
    }

    @Test
    public void unavailableDynamicVoiceKeepsTheLegacyVocalization() throws Exception {
        Path cache = Files.createTempDirectory("villager-dynamic-legacy");
        VillagerAudioManager audio = manager(cache);
        FakeVoice fake = new FakeVoice();
        fake.available = false;
        audio.setDynamicVoiceForTest(fake);
        try {
            // Clips still speak when they are on topic.
            assertEquals("Hello friend", audio.requestSpeech(
                    "Hello friend", SpeechOptions.DEFAULT, "Villager", 7));
            // Unrelated lines fall back to the legacy vocalization path: the
            // vocalization clip carries no transcript, so nothing is captioned.
            assertNull(audio.requestSpeech("Quantum flux capacitors hum at midnight",
                    SpeechOptions.DEFAULT, "Villager", 7));
            assertEquals(0, fake.renders.get());
        } finally {
            audio.close();
        }
    }

    @Test
    public void dynamicVoiceCanBeDisabled() throws Exception {
        String previous = System.getProperty(
                VillagerAudioManager.DYNAMIC_PROPERTY);
        System.setProperty(VillagerAudioManager.DYNAMIC_PROPERTY, "false");
        try {
            Path cache = Files.createTempDirectory("villager-dynamic-off");
            VillagerAudioManager audio = manager(cache);
            FakeVoice fake = new FakeVoice();
            audio.setDynamicVoiceForTest(fake);
            try {
                assertNull(audio.requestSpeech(
                        "Quantum flux capacitors hum at midnight",
                        SpeechOptions.DEFAULT, "Villager", 7));
                // Give the worker a chance to prove it renders nothing.
                Thread.sleep(1500);
                assertEquals(0, fake.renders.get());
            } finally {
                audio.close();
            }
        } finally {
            if (previous == null) {
                System.clearProperty(VillagerAudioManager.DYNAMIC_PROPERTY);
            } else {
                System.setProperty(VillagerAudioManager.DYNAMIC_PROPERTY, previous);
            }
        }
    }
}
