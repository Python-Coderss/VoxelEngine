package villager.voice;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end Kokoro render with no OpenAL device and no game bound: text goes
 * in, a 24 kHz WAV comes out. This is the audioless verification the sound
 * stack needs — a missing sound card must never disable speech, only playback.
 */
public class KokoroTtsHeadlessTest {

    @Test
    public void rendersAudibleSpeechWithoutAnAudioDevice() throws Exception {
        Path dir = KokoroTts.defaultDirectory();
        assumeTrue("run tools/fetch_kokoro.py to install the model",
                Files.isRegularFile(dir.resolve("kokoro-v1.0.onnx"))
                        && Files.isRegularFile(dir.resolve("gb_gold.json")));

        KokoroTts tts = new KokoroTts(dir);
        try {
            assertTrue("the bundle must load with the downloaded dictionaries",
                    tts.isAvailable());
            assertEquals("bm_george", tts.styleName(VoiceProfile.ANCIENT_BUILDER));

            WavAudio audio = tts.render("Hold the gate, builder.", VoiceProfile.ANCIENT_BUILDER);
            assertEquals(KokoroTts.OUTPUT_RATE, audio.sampleRate);
            assertTrue("a short line should still be over a second: "
                            + audio.samples.length,
                    audio.samples.length > KokoroTts.OUTPUT_RATE);

            double energy = 0.0;
            float peak = 0.0f;
            for (float sample : audio.samples) {
                assertFalse("the model produced a NaN sample", Float.isNaN(sample));
                energy += sample * (double) sample;
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue("rendered audio is silent", energy > 1.0);
            assertTrue("peak sample should be normalized", peak > 0.2f);

            Path wav = Files.createTempFile("kokoro-headless", ".wav");
            // Java Sound keeps a Windows file handle after writing; let the
            // JVM remove the temp file at exit instead of deleting it here.
            wav.toFile().deleteOnExit();
            audio.write(wav);
            assertTrue("WAV must be written to disk", Files.size(wav) > 2000);
            assertEquals("16-bit mono PCM plus a 44-byte header",
                    44L + audio.samples.length * 2L, Files.size(wav));
        } finally {
            tts.close();
        }
    }

    @Test
    public void aMissingBundleReportsUnavailableInsteadOfThrowing() {
        KokoroTts missing = new KokoroTts(
                java.nio.file.Paths.get("target", "no-such-kokoro-bundle"));
        assertFalse(missing.isAvailable());
        missing.close();
    }
}
