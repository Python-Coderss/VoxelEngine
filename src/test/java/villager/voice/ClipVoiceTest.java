package villager.voice;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClipVoiceTest {

    private static final String MANIFEST = "{"
            + "\"version\":1,"
            + "\"clips\":["
            + "{\"id\":\"teavrsp:hello\",\"kind\":\"teavrsp\",\"file\":\"Hello.wav\","
            + "\"text\":\"Hello\",\"duration\":0.5}"
            + "]}";

    /** A short synthetic sine clip standing in for the corpus wav. */
    private static WavAudio tone(int sampleRate, double seconds, double hz) {
        int count = (int) Math.round(sampleRate * seconds);
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / sampleRate));
        }
        return new WavAudio(sampleRate, samples);
    }

    private static ClipLibrary tempLibrary() throws Exception {
        Path dir = Files.createTempDirectory("clip-voice-test");
        tone(22050, 0.5, 220.0).write(dir.resolve("Hello.wav"));
        return new ClipLibrary(ClipIndex.parse(MANIFEST), dir, null);
    }

    @Test
    public void playsRecordedClipWithoutSynthesis() throws Exception {
        ClipVoice voice = new ClipVoice(tempLibrary(), new Random(5));
        WavAudio out = voice.render("Hello!", SpeechOptions.DEFAULT);
        assertNotNull(out);
        assertEquals(ClipVoice.DEFAULT_SAMPLE_RATE, out.sampleRate);
        assertTrue(out.samples.length > 0);
        voice.close();
    }

    @Test
    public void speedChangesDuration() throws Exception {
        ClipVoice voice = new ClipVoice(tempLibrary(), new Random(5));
        WavAudio normal = voice.render("Hello", new SpeechOptions(1.0, 0.0));
        WavAudio fast = voice.render("Hello", new SpeechOptions(2.0, 0.0));
        assertTrue("fast playback should be shorter ("
                        + normal.samples.length + " vs " + fast.samples.length + ")",
                fast.samples.length < normal.samples.length);
        voice.close();
    }

    @Test
    public void choosePicksTheRecordedTranscript() throws Exception {
        ClipVoice voice = new ClipVoice(tempLibrary(), new Random(5));
        ClipIndex.Clip clip = voice.choose("Hello");
        assertNotNull(clip);
        assertEquals("teavrsp:hello", clip.id);
        voice.close();
    }
}
