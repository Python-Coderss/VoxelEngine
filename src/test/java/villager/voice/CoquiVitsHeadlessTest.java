package villager.voice;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end Coqui VITS render with no OpenAL device and no game bound: text
 * goes in, a 22050 Hz WAV comes out of the fixed-voice inventory, and the
 * spectral artifact audit signs it off. This is the audioless verification
 * the dynamic villager voice needs — a missing sound card must never disable
 * speech, and vocoder artifacts must fail the test, not the player's ears.
 */
public class CoquiVitsHeadlessTest {

    private static boolean bundlePresent() {
        Path dir = CoquiVitsTts.defaultDirectory();
        return Files.isRegularFile(dir.resolve("coqui-vctk-vits.onnx"))
                && Files.isRegularFile(dir.resolve("coqui-vctk-vocab.json"))
                && Files.isRegularFile(dir.resolve("coqui-vctk-speaker_ids.json"))
                && Files.isRegularFile(
                        KokoroTts.defaultDirectory().resolve("gb_gold.json"));
    }

    @Test
    public void rendersAudibleSpeechWithoutAnAudioDevice() throws Exception {
        assumeTrue("install the Coqui bundle beside dev/voice-models",
                bundlePresent());
        CoquiVitsTts tts = new CoquiVitsTts(CoquiVitsTts.defaultDirectory());
        try {
            assertTrue("the bundle must load", tts.isAvailable());
            List<String> inventory = tts.speakerInventory();
            assertTrue("the VCTK inventory must hold the fixed voices",
                    inventory.size() >= 100);
            assertTrue(inventory.contains("p225"));

            // Two villagers must keep two different fixed voices.
            String first = tts.speakerForSeed(7);
            String second = tts.speakerForSeed(8);
            assertFalse("adjacent villagers must not share a voice",
                    first.equals(second));
            assertEquals("voice assignment must be deterministic",
                    first, tts.speakerForSeed(7));

            WavAudio audio = tts.renderLine(
                    "Hello there, traveler. Fine iron today.", SpeechOptions.DEFAULT, 7);
            assertEquals(CoquiVitsTts.OUTPUT_RATE, audio.sampleRate);
            assertTrue("a short line should still be over a second: "
                            + audio.samples.length,
                    audio.samples.length > CoquiVitsTts.OUTPUT_RATE);

            double energy = 0.0;
            float peak = 0.0f;
            for (float sample : audio.samples) {
                assertFalse("the model produced a NaN sample", Float.isNaN(sample));
                energy += sample * (double) sample;
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue("rendered audio is silent", energy > 1.0);
            assertTrue("peak sample should be normalized", peak > 0.2f);

            SpectralAudit.Report report =
                    SpectralAudit.audit(audio.samples, audio.sampleRate);
            System.out.println("CoquiVitsHeadlessTest: " + report);
            assertTrue("spectral audit failed: " + report.failures,
                    report.passes());

            Path wav = Files.createTempFile("coqui-headless", ".wav");
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
    public void villagerLinesAuditClean() throws Exception {
        assumeTrue("install the Coqui bundle beside dev/voice-models",
                bundlePresent());
        CoquiVitsTts tts = new CoquiVitsTts(CoquiVitsTts.defaultDirectory());
        try {
            assertTrue(tts.isAvailable());
            String[] lines = {
                    "The gate is closing. What do we do?",
                    "My cabbages are ruined, I tell you. Ruined!",
                    "Have you seen my pickaxe anywhere?",
            };
            for (int i = 0; i < lines.length; i++) {
                WavAudio audio = tts.renderLine(
                        lines[i], SpeechOptions.DEFAULT, 100 + i);
                assertTrue("line " + i + " is silent", audio.samples.length > 22050);
                SpectralAudit.Report report =
                        SpectralAudit.audit(audio.samples, audio.sampleRate);
                System.out.println("CoquiVitsHeadlessTest[" + i + "]: " + report);
                assertTrue("line " + i + " failed audit: " + report.failures,
                        report.passes());
            }
        } finally {
            tts.close();
        }
    }

    @Test
    public void aMissingBundleReportsUnavailableInsteadOfThrowing() {
        CoquiVitsTts missing = new CoquiVitsTts(
                java.nio.file.Paths.get("target", "no-such-coqui-bundle"));
        assertFalse(missing.isAvailable());
        missing.close();
    }
}
