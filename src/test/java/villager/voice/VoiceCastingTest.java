package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Casting is pure data: each role lists the voices to try, best first. The
 * WinRT OneCore voices come first because they are the modern, less robotic
 * set; the legacy SAPI names stay as fallbacks so older machines still speak.
 */
public class VoiceCastingTest {

    @Test
    public void rolesPreferTheModernOneCoreVoices() {
        assertTrue("the narrator is the documentary voice",
                first(VoiceProfile.NARRATOR).contains("George"));
        assertTrue("builders get the warm male voice",
                first(VoiceProfile.ANCIENT_BUILDER).contains("Mark"));
        assertTrue("the player keeps the bright female voice",
                first(VoiceProfile.PLAYER).contains("Zira"));
        assertTrue("villains get the deepest male voice",
                first(VoiceProfile.BOSS).contains("Richard"));
    }

    @Test
    public void everySyntheticRoleKeepsADavidFallback() {
        for (VoiceProfile profile : VoiceProfile.values()) {
            if (!profile.usesSynthesis()) continue;
            assertTrue(profile.name() + " needs a voice that exists on every Windows",
                    anyCandidateContains(profile, "David"));
        }
    }

    @Test
    public void legacySapiDesktopNamesRemainAsLastResort() {
        assertTrue(anyCandidateContains(VoiceProfile.ANCIENT_BUILDER, "Desktop"));
        assertTrue(anyCandidateContains(VoiceProfile.PLAYER, "Desktop"));
        assertTrue(anyCandidateContains(VoiceProfile.NARRATOR, "Desktop"));
        assertTrue(anyCandidateContains(VoiceProfile.BOSS, "Desktop"));
    }

    @Test
    public void shortNamesArePresentSoSubstringMatchingWorksOnBothEngines() {
        // WinRT exposes "Microsoft Mark" while SAPI exposes "Microsoft Mark
        // Desktop"; a bare "Mark" / "David" candidate matches either.
        assertTrue(anyCandidateContains(VoiceProfile.ANCIENT_BUILDER, "Mark"));
        assertTrue(anyCandidateContains(VoiceProfile.NARRATOR, "George"));
        assertTrue(anyCandidateContains(VoiceProfile.PLAYER, "Zira"));
        assertTrue(anyCandidateContains(VoiceProfile.BOSS, "Richard"));
    }

    @Test
    public void villagerStillUsesRecordedClipsOnly() {
        assertTrue(VoiceProfile.VILLAGER.voiceCandidates().length == 0);
        assertTrue(!VoiceProfile.VILLAGER.usesSynthesis());
    }

    private static String first(VoiceProfile profile) {
        String[] candidates = profile.voiceCandidates();
        assertTrue(profile + " has candidates", candidates.length > 0);
        return candidates[0];
    }

    private static boolean anyCandidateContains(VoiceProfile profile, String needle) {
        for (String candidate : profile.voiceCandidates()) {
            if (candidate.contains(needle)) return true;
        }
        return false;
    }

    @Test
    public void signedPercentCodesRemainStableForTheSapiHelper() {
        assertEquals("n6", WindowsSapiTts.code(-6));
        assertEquals("p6", WindowsSapiTts.code(6));
        assertEquals("0", WindowsSapiTts.code(0));
    }
}
