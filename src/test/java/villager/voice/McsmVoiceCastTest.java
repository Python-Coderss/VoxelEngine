package villager.voice;

import com.voxel.cinematic.CinematicScript;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The MCSM cast must resolve by name, the player must be Male Jesse (the
 * bright American male style, not the old female read), and builders must not
 * all share one voice.
 */
public class McsmVoiceCastTest {

    private static final String[] NAMES = {
            "Jesse", "Female Jesse", "Petra", "Lukas", "Axel", "Olivia",
            "Ivor", "Soren", "Gabriel", "Magnus", "Ellegaard", "Harper"
    };

    @Test
    public void everyNamedCharacterHasItsOwnProfile() {
        for (String name : NAMES) {
            VoiceProfile profile = VoiceProfile.byCharacterName(name);
            assertNotNull(name + " must be cast", profile);
            assertTrue(name + " must be a story character", profile.isStoryCharacter());
            assertFalse(name + " has no Kokoro style",
                    profile.kokoroVoice().isEmpty());
        }
        assertEquals(VoiceProfile.JESSE_FEMALE,
                VoiceProfile.byCharacterName("Female Jesse"));
        assertEquals(VoiceProfile.JESSE_MALE, VoiceProfile.byCharacterName("jesse"));
    }

    @Test
    public void playerIsMaleJesseAndUsesAMaleStyle() {
        String previous = System.getProperty("voxel.voice.jesse");
        System.clearProperty("voxel.voice.jesse");
        try {
            assertEquals("the player defaults to Male Jesse",
                    VoiceProfile.PLAYER, VoiceProfile.forSpeaker("You"));
            assertEquals(VoiceProfile.PLAYER, VoiceProfile.playerVoice());
            assertEquals(VoiceProfile.PLAYER, VoiceProfile.forMonologue());
            assertTrue("Male Jesse must be a male style: "
                            + VoiceProfile.PLAYER.kokoroVoice(),
                    VoiceProfile.PLAYER.kokoroVoice().startsWith("am_")
                            || VoiceProfile.PLAYER.kokoroVoice().startsWith("bm_"));
            assertEquals("am_puck", VoiceProfile.PLAYER.kokoroVoice());

            System.setProperty("voxel.voice.jesse", "female");
            assertEquals(VoiceProfile.JESSE_FEMALE, VoiceProfile.playerVoice());
            assertEquals(VoiceProfile.JESSE_FEMALE, VoiceProfile.forMonologue());
        } finally {
            if (previous == null) {
                System.clearProperty("voxel.voice.jesse");
            } else {
                System.setProperty("voxel.voice.jesse", previous);
            }
        }
    }

    @Test
    public void ancientBuildersCastAsTheOrderNotOneVoice() {
        Set<VoiceProfile> voices = new HashSet<VoiceProfile>();
        for (int id = 0; id < 40; id++) {
            VoiceProfile builder = VoiceProfile.forBuilder(id);
            assertTrue("builder " + id + " must be a story voice",
                    builder.isStoryCharacter());
            voices.add(builder);
        }
        assertTrue("a build site must not be one voice: " + voices,
                voices.size() >= 5);
        // Deterministic: the same builder always sounds the same.
        assertEquals(VoiceProfile.forBuilder(11), VoiceProfile.forBuilder(11));
        assertNotEquals(VoiceProfile.forBuilder(11), VoiceProfile.forBuilder(12));
    }

    @Test
    public void cutsceneMonologueIsMarkedAndRoutedToJesse() {
        CinematicScript script = new CinematicScript();
        script.monologue(1.5f, "They built this to outlast them.");
        script.at(2.5f, "Soren", "And it did.");

        assertEquals(2, script.size());
        assertTrue("the inner line must be a monologue",
                CinematicScript.isMonologue(script.lines().get(0)));
        assertFalse("a spoken character line is not monologue",
                CinematicScript.isMonologue(script.lines().get(1)));
        assertEquals("Jesse", script.lines().get(0).speaker);
        assertEquals("Soren", script.lines().get(1).speaker);
    }

    @Test
    public void monologueStillSpeaksWithNoAudioDeviceOrModel() {
        // The real speech bridge, unbound: monologue must fall back to
        // captions with the authored text instead of going silent.
        com.voxel.ai.speech.CharacterVoice.resetForTest();
        String line = "The gate is older than the wall around it.";
        assertEquals(line,
                com.voxel.ai.speech.CharacterVoice.speakMonologue(line));
        // A blank thought never becomes a caption.
        assertEquals(null, com.voxel.ai.speech.CharacterVoice.speakMonologue("  "));
    }

    @Test
    public void mobsGetVoicesToo() {
        Set<VoiceProfile> voices = new HashSet<VoiceProfile>();
        for (int id = 0; id < 20; id++) {
            voices.add(VoiceProfile.forMob(id));
        }
        assertTrue("mobs should not share one voice", voices.size() >= 3);
    }
}
