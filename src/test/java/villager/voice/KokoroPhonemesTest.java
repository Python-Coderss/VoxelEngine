package villager.voice;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The Kokoro frontend is testable without ONNX and without an audio device:
 * the phoneme stream is the part that decides whether the model says the
 * authored line or something else.
 *
 * <p>The tests skip when the downloaded bundle is absent (a fresh clone has no
 * model); {@code tools/fetch_kokoro.py} installs it.</p>
 */
public class KokoroPhonemesTest {

    private static Path modelDir;
    private static MisakiEnG2P g2p;

    @BeforeClass
    public static void load() throws Exception {
        modelDir = KokoroTts.defaultDirectory();
        assumeTrue("Kokoro bundle not downloaded; run tools/fetch_kokoro.py",
                Files.isRegularFile(modelDir.resolve("gb_gold.json"))
                        && Files.isRegularFile(modelDir.resolve("gb_silver.json")));
        g2p = new MisakiEnG2P(modelDir);
    }

    @Test
    public void readsAuthoredEnglishWithStress() {
        String ipa = g2p.phonemize("The gate remembers what the builders buried.");
        assertEquals("ðə ɡˈAt ɹɪmˈɛmbəz wˌɒt ðə bˈɪldəz bˈɛɹɪd.", ipa.trim());
        assertTrue("stress marks drive Kokoro's intonation", ipa.contains("ˈ"));
    }

    @Test
    public void speaksNumbersAndContractions() {
        String ipa = g2p.phonemize("The builders arrived in 2012 and didn't stop.");
        assertTrue("a year is spoken as words", ipa.contains("twˈɛnti twˈɛlv"));
        assertTrue("the n't contraction is one word", ipa.contains("dˈɪdᵊnt"));
    }

    @Test
    public void everyPhonemeIsAKeywordInTheModelVocabulary() throws Exception {
        Path config = modelDir.resolve("kokoro-config.json");
        assumeTrue("model config missing", Files.isRegularFile(config));
        JSONObject vocab = new JSONObject(new String(
                Files.readAllBytes(config), StandardCharsets.UTF_8)).getJSONObject("vocab");
        Set<String> tokens = new HashSet<String>();
        for (String key : vocab.keySet()) tokens.add(key);

        String ipa = g2p.phonemize(
                "Jesse, follow the line. The quarry does not grade on effort. Never.");
        assertFalse(ipa.trim().isEmpty());
        for (int i = 0; i < ipa.length(); i++) {
            char symbol = ipa.charAt(i);
            if (symbol == ' ') continue;
            assertTrue("unmapped phoneme '" + symbol + "' in: " + ipa,
                    tokens.contains(String.valueOf(symbol)));
        }
    }

    @Test
    public void expandsCurrencyAndPercentBeforeSpeaking() {
        // The G2P drops '$'; the renderer must turn it into words first.
        assertEquals("We have 5 dollars and 3 percent.", KokoroTts.normalize("We have $5 and 3%."));
        assertEquals("Rock and roll", KokoroTts.normalize("Rock & roll"));
    }
}
