package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The prosody builder is the part of local speech quality that can be tested
 * without spawning a voice process: escaping, pauses, emphasis and rate/pitch.
 */
public class SsmlProsodyTest {

    @Test
    public void wrapsTheLineInProsodyAndABreath() {
        String ssml = WindowsSapiTts.buildSsml("Hold the gate.", -12, -10);
        assertTrue(ssml.startsWith("<speak"));
        assertTrue(ssml.endsWith("</speak>"));
        assertTrue(ssml.contains("rate='-12%'"));
        assertTrue(ssml.contains("pitch='-10%'"));
        assertTrue("a lead-in beat keeps short lines from clipping",
                ssml.contains("<break time='120ms'/>Hold the gate."));
    }

    @Test
    public void escapesMarkupSoAuthoredTextCannotBreakTheDocument() {
        String ssml = WindowsSapiTts.buildSsml("Rock & roll <now> isn't \"safe\"", 0, 0);
        assertTrue(ssml.contains("Rock &amp; roll"));
        assertTrue(ssml.contains("&lt;now&gt;"));
        assertTrue(ssml.contains("isn&apos;t"));
        assertTrue(ssml.contains("&quot;safe&quot;"));
    }

    @Test
    public void pausesBetweenSentencesAndAfterCommas() {
        String ssml = WindowsSapiTts.buildSsml("First line. One, two.", 0, 0);
        assertTrue("a sentence end earns a longer beat",
                ssml.contains("First line.<break time=\"220ms\"/>"));
        assertTrue("a comma earns a shorter beat",
                ssml.contains("One,<break time=\"90ms\"/> two."));
    }

    @Test
    public void emphasizesShoutedWords() {
        String ssml = WindowsSapiTts.buildSsml("This is NEVER fine.", 0, 0);
        assertTrue(ssml.contains("<emphasis level=\"strong\">NEVER</emphasis>"));
    }

    @Test
    public void pausesAfterAVocativeWithoutDuplicatingTheCommaBreak() {
        String ssml = WindowsSapiTts.buildSsml("Jesse, follow the line.", 6, 6);
        assertTrue(ssml.contains("Jesse, <break time=\"160ms\"/>follow the line."));
        assertEquals("the comma must only pause once",
                1, count(ssml, "<break time=\"160ms\"/>"));
        assertTrue("the vocative comma is not treated as a list comma",
                !ssml.contains("Jesse,<break time=\"90ms\"/>"));
    }

    @Test
    public void blankTextStillProducesAValidDocument() {
        String ssml = WindowsSapiTts.buildSsml("   ", 0, 0);
        assertTrue(ssml.startsWith("<speak"));
        assertTrue(ssml.endsWith("</speak>"));
        assertTrue(ssml.contains("+0%"));
    }

    @Test
    public void signedPercentsUseTheSsmlConvention() {
        assertEquals("+6%", WindowsSapiTts.percent(6));
        assertEquals("-6%", WindowsSapiTts.percent(-6));
        assertEquals("+0%", WindowsSapiTts.percent(0));
    }

    private static int count(String value, String needle) {
        int seen = 0;
        int at = 0;
        while ((at = value.indexOf(needle, at)) >= 0) {
            seen++;
            at += needle.length();
        }
        return seen;
    }
}
