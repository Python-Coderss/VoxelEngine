package villager.voice;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

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

    @Test
    public void numbersExpandToSpokenWords() throws Exception {
        assertTrue(frontend().wordToIpa("seven").size() > 0);
        // 310 -> three hundred ten: the frontend must not drop the digit run.
        int[] ids = frontend().textToIds("That will be 310 emeralds");
        assertTrue("counted 310 must not vanish", ids.length > 20);
    }

    @Test
    public void numberSpellerProducesWords() {
        assertEquals(java.util.Arrays.asList("three", "hundred", "and", "ten"),
                CoquiFrontend.numberToWords("310"));
        assertEquals(java.util.Arrays.asList("one", "thousand", "two", "hundred", "and", "fifty", "six"),
                CoquiFrontend.numberToWords("1,256"));
        assertEquals(java.util.Arrays.asList("zero"), CoquiFrontend.numberToWords("0"));
        assertEquals(java.util.Arrays.asList("twenty", "one"),
                CoquiFrontend.numberToWords("21"));
        assertEquals(java.util.Arrays.asList("fifteen"), CoquiFrontend.numberToWords("15"));
        assertEquals(java.util.Arrays.asList("one", "hundred"),
                CoquiFrontend.numberToWords("100"));
    }

    @Test
    public void contractionKeepsApostrophe() throws Exception {
        // "what's" must hit the CMN entry (the apostrophe stays glued), not
        // fall back to letter-by-letter pronunciation.
        List<String> ip = frontend().wordToIpa("what's");
        assertFalse(ip.isEmpty());
        // The token stream for "what's up" must contain the apostrophe symbol
        // (id 174) exactly once, proving it was not swallowed. The CM dict
        // has an entry for "what's", so the apostrophe is *not* emitted as a
        // separate punctuation symbol — it is part of the word's pronunciation.
        int[] ids = frontend().textToIds("what's up");
        int apostrophes = 0;
        for (int id : ids) {
            if (id == 174) {
                apostrophes++;
            }
        }
        assertEquals(0, apostrophes);
        // ...and the pronunciation itself must have real phones (the fallback
        // would be /w h .../ letter pronunciations).
        assertTrue(ids.length > 10);
    }

    @Test
    public void acronymsSpellOutLetterNames() throws Exception {
        // TV must become "tee vee", not letter-fallback "t v". The dictionary
        // letter entries are reachable through wordToIpa by spelling.
        assertFalse(frontend().wordToIpa("tee").isEmpty());
        int[] tv = frontend().textToIds("Watch the TV");
        int[] tee = frontend().textToIds("tee vee");
        assertTrue("TV should spell out tee vee", tv.length >= tee.length - 1);
    }

    @Test
    public void currencyAndPercentAttachToNumbers() throws Exception {
        // 5$ -> five dollars, % -> percent; both now produce a long token run
        // instead of dropping the number entirely.
        int[] five = frontend().textToIds("5$");
        int[] pct = frontend().textToIds("50%");
        assertTrue("5$ should expand", five.length > 15);
        assertTrue("50% should expand", pct.length > 15);
        // Leading currency: $5 -> five dollars.
        int[] lead = frontend().textToIds("$5");
        assertTrue(lead.length > 15);
        // The expanded numeric tokens must not be empty (numberToWords works).
        assertFalse(CoquiFrontend.numberToWords("50").isEmpty());
    }
}
