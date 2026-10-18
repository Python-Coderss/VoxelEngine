package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Character profiles must stay valid and distinct. */
public class VoiceProfileTest {

    @Test
    public void everyProfileHasAValidDelivery() {
        for (VoiceProfile profile : VoiceProfile.values()) {
            assertNotNull(profile.name() + " has options", profile.options());
            assertTrue(profile.name() + " speaks at a sensible rate",
                    profile.options().getEffectiveSpeed() > 0.5);
            assertTrue(profile.name() + " stays inside the pitch range",
                    Math.abs(profile.options().getPitchSemitones()) <= 4.0);
        }
    }

    @Test
    public void onlyTheVillagerUsesRecordedClips() {
        assertFalse(VoiceProfile.VILLAGER.usesSynthesis());
        for (VoiceProfile profile : VoiceProfile.values()) {
            if (profile == VoiceProfile.VILLAGER) continue;
            assertTrue(profile.name() + " has a local voice to try",
                    profile.voiceCandidates().length > 0);
            assertTrue(profile.name() + " uses synthesis", profile.usesSynthesis());
        }
    }

    @Test
    public void rolesHaveDistinctRegisters() {
        assertTrue("bosses sit below builders",
                VoiceProfile.BOSS.pitchPercent() < VoiceProfile.ANCIENT_BUILDER.pitchPercent());
        assertTrue("narrators speak slower than builders",
                VoiceProfile.NARRATOR.ratePercent() < VoiceProfile.ANCIENT_BUILDER.ratePercent());
        assertTrue("builders are more measured than the player",
                VoiceProfile.ANCIENT_BUILDER.options().getEffectiveSpeed()
                        < VoiceProfile.PLAYER.options().getEffectiveSpeed());
    }

    @Test
    public void cinematicLabelsMapToTheRightCharacter() {
        assertEquals(VoiceProfile.NARRATOR, VoiceProfile.forSpeaker("Narrator"));
        assertEquals(VoiceProfile.PLAYER, VoiceProfile.forSpeaker("You"));
        assertEquals(VoiceProfile.PLAYER, VoiceProfile.forSpeaker("player"));
        assertEquals(VoiceProfile.VILLAGER, VoiceProfile.forSpeaker("Villager"));
        assertEquals(VoiceProfile.ANCIENT_BUILDER,
                VoiceProfile.forSpeaker("Ancient Engineer"));
    }

    @Test
    public void signedPercentsAreEncodedSoPowerShellCannotReadThemAsFlags() {
        assertEquals("n6", WindowsSapiTts.code(-6));
        assertEquals("p6", WindowsSapiTts.code(6));
        assertEquals("0", WindowsSapiTts.code(0));
    }
}
