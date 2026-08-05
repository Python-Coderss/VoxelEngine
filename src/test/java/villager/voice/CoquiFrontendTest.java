package villager.voice;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoquiFrontendTest {
    private static CoquiFrontend frontend() throws Exception {
        return new CoquiFrontend(
                Paths.get("models/java/coqui-vctk-vocab.json"),
                Paths.get("models/java/cmudict.dict"));
    }

    @Test
    public void loadsVocabAndDictionary() throws Exception {
        CoquiFrontend frontend = frontend();
        assertEquals(178, frontend.vocabSize());
        assertFalse(frontend.wordToIpa("hello").isEmpty());
    }

    /**
     * The CMU G2P produces the exact same token ids as the Python
     * tokenizer fixture for these lines (the Python fixture used eSpeak; CMU agrees on them),
     * which cross-validates the Java frontend against the reference.
     */
    @Test
    public void helloMatchesPythonGoldenTokens() throws Exception {
        assertArrayEquals(
                new int[]{178, 50, 178, 83, 178, 54, 178, 156, 178, 57, 178, 135, 178},
                frontend().textToIds("Hello"));
    }

    @Test
    public void noWithPunctuationMatchesGoldenTokens() throws Exception {
        assertArrayEquals(
                new int[]{178, 56, 178, 156, 178, 57, 178, 135, 178,
                        5, 178, 5, 178, 5, 178},
                frontend().textToIds("No!!!"));
    }

    @Test
    public void idsAreWithinModelVocab() throws Exception {
        int[] ids = frontend().textToIds("What are you doing, traveler?");
        assertTrue(ids.length > 10);
        for (int id : ids) {
            assertTrue("id out of range: " + id, id >= 0 && id < 179);
        }
        assertEquals(CoquiFrontend.BLANK_ID, ids[0]);
        assertEquals(CoquiFrontend.BLANK_ID, ids[ids.length - 1]);
    }

    @Test
    public void unknownWordsUseLetterFallback() throws Exception {
        assertFalse(frontend().wordToIpa("hrmm").isEmpty());
    }
}
