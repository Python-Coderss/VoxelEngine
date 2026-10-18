package villager.voice;

/**
 * A character's voice identity: which local synthetic voice speaks the part
 * and how the line is delivered.
 *
 * <p>Villagers keep their recorded add-on clips ({@link #VILLAGER}); the
 * characters whose dialogue was previously caption-only — ancient builders,
 * the player, the narrator and bosses — speak through a local TTS voice with a
 * distinct register per role. The bundled {@link #options()} profile is also
 * used when a machine has no local voice, because the recorded-clip fallback
 * shapes clips with the same speed/pitch/mood controls.</p>
 */
public enum VoiceProfile {

    /** Recorded villager clips only; never synthesized. */
    VILLAGER(new String[0], 0, 0, Speecher.VILLAGER),

    /** Builders: measured, dry, scholarly. */
    ANCIENT_BUILDER(new String[]{"Microsoft Hazel Desktop", "Microsoft Hazel", "Hazel",
            "Microsoft David Desktop", "Microsoft David", "David"},
            -6, -6, Speecher.BUILDER),

    /** The player character, answering in cutscenes. */
    PLAYER(new String[]{"Microsoft Zira Desktop", "Microsoft Zira", "Zira",
            "Microsoft David Desktop", "David"},
            6, 6, Speecher.PLAYER),

    /** Framing narration: slow, low, unhurried. */
    NARRATOR(new String[]{"Microsoft David Desktop", "Microsoft David", "David",
            "Microsoft Hazel Desktop", "Hazel"},
            -12, -10, Speecher.NARRATOR),

    /** Villains: slower and pitched well below the builders. */
    BOSS(new String[]{"Microsoft David Desktop", "Microsoft David", "David",
            "Microsoft Hazel Desktop", "Hazel"},
            -8, -20, Speecher.BOSS);

    private final String[] voiceCandidates;
    private final int ratePercent;
    private final int pitchPercent;
    private final SpeechOptions options;

    VoiceProfile(String[] voiceCandidates, int ratePercent, int pitchPercent,
                 SpeechOptions options) {
        this.voiceCandidates = voiceCandidates;
        this.ratePercent = ratePercent;
        this.pitchPercent = pitchPercent;
        this.options = options;
    }

    /** Installed-voice names to try, best first. */
    public String[] voiceCandidates() {
        return voiceCandidates.clone();
    }

    /** Speaking rate relative to the voice's natural pace, in percent. */
    public int ratePercent() {
        return ratePercent;
    }

    /** Pitch shift relative to the voice's natural register, in percent. */
    public int pitchPercent() {
        return pitchPercent;
    }

    /** Delivery profile (also drives the recorded-clip fallback). */
    public SpeechOptions options() {
        return options;
    }

    /** True when this role can be spoken by a local synthetic voice. */
    public boolean usesSynthesis() {
        return this != VILLAGER;
    }

    /**
     * The profile for a cinematic speaker label. The scripts name the player
     * "You" and the framing voice "Narrator"; everyone else is a character in
     * the world, so they get the builder/villain register rather than the
     * villager clip voice.
     */
    public static VoiceProfile forSpeaker(String speaker) {
        if (speaker == null) return NARRATOR;
        String name = speaker.trim();
        if (name.isEmpty()) return NARRATOR;
        if (name.equalsIgnoreCase("Narrator")) return NARRATOR;
        if (name.equalsIgnoreCase("You") || name.equalsIgnoreCase("Player")) return PLAYER;
        if (name.equalsIgnoreCase("Villager") || name.toLowerCase(java.util.Locale.ROOT)
                .contains("villager")) {
            return VILLAGER;
        }
        return ANCIENT_BUILDER;
    }

    /** Named profiles for readability at call sites. */
    private static final class Speecher {
        static final SpeechOptions VILLAGER = new SpeechOptions(
                1.0, 0.0, 1.0, 0.0, "happy", 0.0, false);
        static final SpeechOptions BUILDER = new SpeechOptions(
                0.95, -1.0, 1.0, -0.6, "neutral", 0.0, false);
        static final SpeechOptions PLAYER = new SpeechOptions(
                1.02, 1.0, 1.0, 0.15, "happy", 0.0, false);
        static final SpeechOptions NARRATOR = new SpeechOptions(
                0.92, -1.5, 1.0, -0.7, "neutral", 0.0, false);
        static final SpeechOptions BOSS = new SpeechOptions(
                0.94, -2.5, 1.1, -0.9, "angry", 0.0, false);

        private Speecher() {
        }
    }
}
