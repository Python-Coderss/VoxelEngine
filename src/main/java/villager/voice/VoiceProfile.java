package villager.voice;

/**
 * A character's voice identity: which local synthetic voice speaks the part
 * and how the line is delivered.
 *
 * <p>Cast from the two installed neural models. Villagers keep their recorded
 * add-on clips ({@link #VILLAGER}). Every other character speaks through
 * Kokoro-82M, so the cast is expressed as Kokoro style vectors; the Windows
 * SAPI candidates are the fallback when the model is missing and are named
 * after the closest installed voice.</p>
 *
 * <p><b>Minecraft: Story Mode cast.</b> The named characters of the story —
 * Jesse, Petra, Lukas, Axel, Olivia, Ivor, Soren, Gabriel, Magnus, Ellegaard,
 * Harper — are cast to trained Kokoro voices with matching register and
 * energy. Nothing is cloned: these are the model's own speakers, chosen for
 * timbre (Jesse reads bright and American, Soren deep and measured, Ivor
 * theatrical) rather than converted from any recording. Ancient builders cast
 * as members of the Order, so a build site is not one voice repeated
 * ({@link #forBuilder(int)}).</p>
 */
public enum VoiceProfile {

    /** Recorded villager clips only; never synthesized. */
    VILLAGER(new String[0], 0, 0, Speecher.VILLAGER, ""),

    /** The player: Male Jesse, bright American delivery for monologue. */
    PLAYER(new String[]{"Microsoft Zira", "Zira", "Microsoft Susan", "Susan",
            "Microsoft Zira Desktop", "Microsoft David", "David"},
            6, 6, Speecher.JESSE_MALE, "am_puck"),

    /** Male Jesse, the default player voice (monologue and replies). */
    JESSE_MALE(new String[]{"Microsoft David", "David", "Microsoft Mark", "Mark",
            "Microsoft David Desktop", "Microsoft Zira", "Zira"},
            6, 6, Speecher.JESSE_MALE, "am_puck"),

    /** Female Jesse, for players who pick the other Jesse. */
    JESSE_FEMALE(new String[]{"Microsoft Zira", "Zira", "Microsoft Susan", "Susan",
            "Microsoft Zira Desktop", "Microsoft David", "David",
            "Microsoft David Desktop", "Microsoft Hazel", "Hazel"},
            6, 6, Speecher.JESSE_FEMALE, "af_heart"),

    /** Petra: clipped, confident, quick to the point. */
    PETRA(new String[]{"Microsoft Zira", "Zira", "Microsoft Susan", "Susan",
            "Microsoft Zira Desktop", "Microsoft David", "David",
            "Microsoft David Desktop", "Microsoft Hazel", "Hazel"},
            8, 4, Speecher.PETRA, "af_nova"),

    /** Lukas: calm, careful, a little softer than the rest of the group. */
    LUKAS(new String[]{"Microsoft Mark", "Mark", "Microsoft David", "David",
            "Microsoft David Desktop", "Microsoft Hazel", "Hazel"},
            0, 2, Speecher.LUKAS, "am_michael"),

    /** Axel: loud, blunt, big chest. */
    AXEL(new String[]{"Microsoft David", "David", "Microsoft Mark", "Mark",
            "Microsoft David Desktop", "Microsoft Richard", "Richard"},
            -2, -1, Speecher.AXEL, "am_adam"),

    /** Olivia: bright, quick, technical. */
    OLIVIA(new String[]{"Microsoft Zira", "Zira", "Microsoft Susan", "Susan",
            "Microsoft Zira Desktop", "Microsoft David", "David",
            "Microsoft David Desktop", "Microsoft Hazel", "Hazel"},
            6, 3, Speecher.OLIVIA, "af_bella"),

    /** Ivor: theatrical, older, always mid-lecture. */
    IVOR(new String[]{"Microsoft George", "George", "Microsoft Mark", "Mark",
            "Microsoft David", "David", "Microsoft Hazel", "Hazel"},
            -4, -2, Speecher.IVOR, "bm_fable"),

    /** Soren: measured, deep, the Order's founder. */
    SOREN(new String[]{"Microsoft George", "George", "Microsoft David", "David",
            "Microsoft David Desktop", "Microsoft Hazel", "Hazel"},
            -8, -3, Speecher.SOREN, "bm_george"),

    /** Gabriel: heroic, resonant. */
    GABRIEL(new String[]{"Microsoft Richard", "Richard", "Microsoft David",
            "David", "Microsoft George", "George", "Microsoft Hazel", "Hazel"},
            -4, -2, Speecher.GABRIEL, "am_onyx"),

    /** Magnus: gruff, blunt, louder than he needs to be. */
    MAGNUS(new String[]{"Microsoft Richard", "Richard", "Microsoft David",
            "David", "Microsoft George", "George", "Microsoft Mark", "Mark"},
            -2, -3, Speecher.MAGNUS, "am_fenrir"),

    /** Ellegaard: forceful engineer, clipped and certain. */
    ELLEGAARD(new String[]{"Microsoft Susan", "Susan", "Microsoft Zira", "Zira",
            "Microsoft Hazel", "Hazel", "Microsoft Zira Desktop",
            "Microsoft David", "David", "Microsoft David Desktop"},
            4, 0, Speecher.ELLEGAARD, "af_jessica"),

    /** Harper: quiet, precise, technical. */
    HARPER(new String[]{"Microsoft Susan", "Susan", "Microsoft Hazel", "Hazel",
            "Microsoft Zira", "Zira", "Microsoft Zira Desktop",
            "Microsoft David", "David", "Microsoft David Desktop"},
            0, 1, Speecher.HARPER, "af_nicole"),

    /** Builders in general: measured, dry, scholarly (Soren's register). */
    ANCIENT_BUILDER(new String[]{"Microsoft Mark", "Mark",
            "Microsoft David", "David", "Microsoft David Desktop",
            "Microsoft Hazel", "Hazel"},
            -6, -6, Speecher.BUILDER, "bm_george"),

    /** Framing narration: slow, low, unhurried. */
    NARRATOR(new String[]{"Microsoft George", "George",
            "Microsoft David", "David", "Microsoft David Desktop",
            "Microsoft Hazel", "Hazel"},
            -12, -10, Speecher.NARRATOR, "bf_emma"),

    /** Villains: slower and pitched well below the builders. */
    BOSS(new String[]{"Microsoft Richard", "Richard",
            "Microsoft David", "David", "Microsoft David Desktop",
            "Microsoft Hazel", "Hazel"},
            -8, -20, Speecher.BOSS, "am_fenrir");

    /**
     * The Order of the Stone, in cast order. Ancient builders are individual
     * people, not a chorus: an entity id picks one of these voices so a whole
     * build site never speaks in a single register.
     */
    private static final VoiceProfile[] ORDER = {
            SOREN, IVOR, GABRIEL, MAGNUS, ELLEGAARD
    };

    /** Neutral voices for mobs with something to say. */
    private static final VoiceProfile[] MOB_CAST = {
            AXEL, MAGNUS, GABRIEL, IVOR
    };

    private final String[] voiceCandidates;
    private final int ratePercent;
    private final int pitchPercent;
    private final SpeechOptions options;
    private final String kokoroVoice;

    VoiceProfile(String[] voiceCandidates, int ratePercent, int pitchPercent,
                 SpeechOptions options, String kokoroVoice) {
        this.voiceCandidates = voiceCandidates;
        this.ratePercent = ratePercent;
        this.pitchPercent = pitchPercent;
        this.options = options;
        this.kokoroVoice = kokoroVoice;
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

    /**
     * Kokoro style vector for this role (empty for the recorded-clip
     * villager voice). {@code KokoroTts} falls back to a bundled voice when
     * the fetched model directory does not contain this name.
     */
    public String kokoroVoice() {
        return kokoroVoice;
    }

    /** Delivery profile (also drives the recorded-clip fallback). */
    public SpeechOptions options() {
        return options;
    }

    /** True when this role can be spoken by a local synthetic voice. */
    public boolean usesSynthesis() {
        return this != VILLAGER;
    }

    /** True for the MCSM story cast (everything except generic roles). */
    public boolean isStoryCharacter() {
        return this == JESSE_MALE || this == JESSE_FEMALE || this == PETRA
                || this == LUKAS || this == AXEL || this == OLIVIA || this == IVOR
                || this == SOREN || this == GABRIEL || this == MAGNUS
                || this == ELLEGAARD || this == HARPER;
    }

    /**
     * The voice for a cinematic speaker label. The scripts name the player
     * "You"/"Jesse" and the framing voice "Narrator"; named MCSM characters
     * resolve to their own profile; everyone else is a builder.
     */
    public static VoiceProfile forSpeaker(String speaker) {
        if (speaker == null) return NARRATOR;
        String name = speaker.trim();
        if (name.isEmpty()) return NARRATOR;
        VoiceProfile named = byCharacterName(name);
        if (named != null) return named;
        if (name.equalsIgnoreCase("Narrator")) return NARRATOR;
        if (name.equalsIgnoreCase("Villager")
                || name.toLowerCase(java.util.Locale.ROOT).contains("villager")) {
            return VILLAGER;
        }
        if (looksLikePlayer(name)) return playerVoice();
        return ANCIENT_BUILDER;
    }

    /** Exact MCSM character name (with a few aliases), or null. */
    public static VoiceProfile byCharacterName(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) return null;
        // Titles and epithets the scripts use around the names.
        if (key.contains("jesse")) {
            return key.contains("female") || key.contains("f.jesse")
                    ? JESSE_FEMALE : JESSE_MALE;
        }
        if (key.contains("petra")) return PETRA;
        if (key.contains("lukas")) return LUKAS;
        if (key.contains("axel")) return AXEL;
        if (key.contains("olivia")) return OLIVIA;
        if (key.contains("ivor")) return IVOR;
        if (key.contains("soren")) return SOREN;
        if (key.contains("gabriel")) return GABRIEL;
        if (key.contains("magnus")) return MAGNUS;
        if (key.contains("ellegaard")) return ELLEGAARD;
        if (key.contains("harper")) return HARPER;
        return null;
    }

    /**
     * The player's own voice: Male Jesse by default, female with
     * {@code -Dvoxel.voice.jesse=female}. {@link #PLAYER} is the role alias
     * for Male Jesse, so existing callers and scripts keep resolving to the
     * player without special cases.
     */
    public static VoiceProfile playerVoice() {
        String choice = System.getProperty("voxel.voice.jesse", "male");
        return choice != null && choice.trim().equalsIgnoreCase("female")
                ? JESSE_FEMALE : PLAYER;
    }

    /**
     * Jesse speaking to themselves: the player's monologue voice. Male Jesse
     * is the default, so a monologue is the same voice the player answers in.
     */
    public static VoiceProfile forMonologue() {
        return playerVoice();
    }

    /** An Order member for an ancient builder, stable per entity id. */
    public static VoiceProfile forBuilder(int seed) {
        return ORDER[Math.floorMod(seed, ORDER.length)];
    }

    /** A voice for a mob that talks, stable per entity id. */
    public static VoiceProfile forMob(int seed) {
        return MOB_CAST[Math.floorMod(seed, MOB_CAST.length)];
    }

    private static boolean looksLikePlayer(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("you") || lower.equals("player") || lower.equals("me")
                || lower.equals("jesse's voice");
    }

    /**
     * Named profiles for readability at call sites. Pitch stays inside ±4
     * semitones so no delivery is a caricature.
     */
    private static final class Speecher {
        static final SpeechOptions VILLAGER = new SpeechOptions(
                1.0, 0.0, 1.0, 0.0, "happy", 0.0, false);
        static final SpeechOptions BUILDER = new SpeechOptions(
                0.95, -1.0, 1.0, -0.6, "neutral", 0.0, false);
        static final SpeechOptions JESSE_MALE = new SpeechOptions(
                1.03, 1.0, 1.05, 0.2, "happy", 0.0, false);
        static final SpeechOptions JESSE_FEMALE = new SpeechOptions(
                1.02, 1.0, 1.0, 0.15, "happy", 0.0, false);
        static final SpeechOptions PETRA = new SpeechOptions(
                1.08, 0.5, 1.05, 0.0, "neutral", 0.0, false);
        static final SpeechOptions LUKAS = new SpeechOptions(
                0.98, 0.0, 0.95, -0.3, "neutral", 0.0, false);
        static final SpeechOptions AXEL = new SpeechOptions(
                0.96, -0.5, 1.15, 0.5, "happy", 0.0, false);
        static final SpeechOptions OLIVIA = new SpeechOptions(
                1.05, 0.5, 1.0, 0.2, "happy", 0.0, false);
        static final SpeechOptions IVOR = new SpeechOptions(
                0.92, -0.5, 1.05, -0.2, "neutral", 0.0, false);
        static final SpeechOptions SOREN = new SpeechOptions(
                0.9, -1.5, 1.0, -0.7, "neutral", 0.0, false);
        static final SpeechOptions GABRIEL = new SpeechOptions(
                0.94, -1.0, 1.1, 0.2, "neutral", 0.0, false);
        static final SpeechOptions MAGNUS = new SpeechOptions(
                0.97, -1.0, 1.2, 0.4, "angry", 0.0, false);
        static final SpeechOptions ELLEGAARD = new SpeechOptions(
                1.0, 0.0, 1.05, 0.3, "neutral", 0.0, false);
        static final SpeechOptions HARPER = new SpeechOptions(
                0.96, 0.5, 0.95, -0.1, "neutral", 0.0, false);
        static final SpeechOptions NARRATOR = new SpeechOptions(
                0.92, -1.5, 1.0, -0.7, "neutral", 0.0, false);
        static final SpeechOptions BOSS = new SpeechOptions(
                0.94, -2.5, 1.1, -0.9, "angry", 0.0, false);

        private Speecher() {
        }
    }
}
