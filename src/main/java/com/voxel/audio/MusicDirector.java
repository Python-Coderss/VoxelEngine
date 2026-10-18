package com.voxel.audio;

/**
 * Picks which music pool should be playing from the live game state — the
 * "like Minecraft but responsive" layer.
 *
 * <p>The old director only knew calm/night/danger/combat. The game now has a
 * real library behind it (MCSM soundtrack, the Aether soundtrack, and any
 * Minecraft/fanmade tracks dropped into {@code src/main/resources/music}), so
 * the director also distinguishes the dimension, caves, villages, the tutorial
 * and boss fights. Priority is deliberate:</p>
 *
 * <pre>
 *   boss &gt; combat &gt; danger &gt; dimension mood &gt; cave &gt; night &gt; village/tutorial &gt; calm
 * </pre>
 *
 * <p>{@link #contextFor(int, int, boolean)} remains the legacy four-pool rule
 * and is kept as the documented, unit-tested core; {@link #contextForScene}
 * layers the world context on top.</p>
 */
public final class MusicDirector {

    public static final String CONTEXT_MENU = "menu";
    public static final String CONTEXT_CALM = "calm";
    public static final String CONTEXT_NIGHT = "night";
    public static final String CONTEXT_DANGER = "danger";
    public static final String CONTEXT_COMBAT = "combat";
    public static final String CONTEXT_BOSS = "boss";
    public static final String CONTEXT_CAVE = "cave";
    public static final String CONTEXT_NETHER = "nether";
    public static final String CONTEXT_END = "end";
    public static final String CONTEXT_AETHER = "aether";
    public static final String CONTEXT_SIFT = "sift";
    public static final String CONTEXT_TUTORIAL = "tutorial";
    public static final String CONTEXT_VILLAGE = "village";
    public static final String CONTEXT_STORY = "story";

    /** Every pool, in display order. */
    public static final String[] ALL_CONTEXTS = {
        CONTEXT_MENU, CONTEXT_CALM, CONTEXT_NIGHT, CONTEXT_DANGER, CONTEXT_COMBAT,
        CONTEXT_BOSS, CONTEXT_CAVE, CONTEXT_NETHER, CONTEXT_END, CONTEXT_AETHER,
        CONTEXT_SIFT, CONTEXT_TUTORIAL, CONTEXT_VILLAGE, CONTEXT_STORY
    };

    /** Hostile mobs within this many blocks (other than combat range) → danger. */
    public static final float DANGER_RANGE = 24.0f;
    /** Hostile mobs within this many blocks → combat. */
    public static final float COMBAT_RANGE = 10.0f;

    private MusicDirector() { }

    /**
     * The legacy four-pool rule.
     *
     * @param hostileNearby count of hostile mobs within {@link #DANGER_RANGE}
     * @param hostileClose  count of hostile mobs within {@link #COMBAT_RANGE}
     * @param isNight       true when the sun is below the horizon
     */
    public static String contextFor(int hostileNearby, int hostileClose, boolean isNight) {
        if (hostileClose > 0) return CONTEXT_COMBAT;
        if (hostileNearby > 0) return CONTEXT_DANGER;
        return isNight ? CONTEXT_NIGHT : CONTEXT_CALM;
    }

    /**
     * Full scene selection: the legacy hostile rule plus the place the player
     * is standing. Dimension moods only apply when nothing is hunting the
     * player, so the Nether can still interrupt its own ambience with combat
     * music when a blaze closes in.
     *
     * @param dimension    engine dimension name (overworld, nether, end, aether, sift, ...)
     * @param underground  true when the player is below the local surface
     * @param hostileNearby count within {@link #DANGER_RANGE}
     * @param hostileClose  count within {@link #COMBAT_RANGE}
     * @param isNight       sun below the horizon
     * @param bossFight     a boss is alive and engaged nearby
     * @param tutorialWorld the tutorial / showcase hub
     * @param nearVillage   a village is within earshot
     */
    public static String contextForScene(String dimension, boolean underground,
                                         int hostileNearby, int hostileClose, boolean isNight,
                                         boolean bossFight, boolean tutorialWorld,
                                         boolean nearVillage) {
        if (bossFight) return CONTEXT_BOSS;
        String hostile = contextFor(hostileNearby, hostileClose, isNight);
        if (CONTEXT_COMBAT.equals(hostile) || CONTEXT_DANGER.equals(hostile)) return hostile;

        String dim = dimension == null ? "" : dimension.toLowerCase(java.util.Locale.ROOT);
        switch (dim) {
            case "nether": return CONTEXT_NETHER;
            case "end": return CONTEXT_END;
            case "aether": return CONTEXT_AETHER;
            case "sift": return CONTEXT_SIFT;
            default: break;
        }
        if (underground) return CONTEXT_CAVE;
        if (isNight) return CONTEXT_NIGHT;
        if (tutorialWorld) return CONTEXT_TUTORIAL;
        if (nearVillage) return CONTEXT_VILLAGE;
        return CONTEXT_CALM;
    }

    /** Human-readable label for the HUD / status line. */
    public static String displayName(String context) {
        if (context == null || context.isEmpty()) return "Silence";
        switch (context) {
            case CONTEXT_MENU: return "Menu";
            case CONTEXT_CALM: return "Calm";
            case CONTEXT_NIGHT: return "Night";
            case CONTEXT_DANGER: return "Danger";
            case CONTEXT_COMBAT: return "Combat";
            case CONTEXT_BOSS: return "Boss";
            case CONTEXT_CAVE: return "Caves";
            case CONTEXT_NETHER: return "Nether";
            case CONTEXT_END: return "The End";
            case CONTEXT_AETHER: return "Aether";
            case CONTEXT_SIFT: return "The Sift";
            case CONTEXT_TUTORIAL: return "Tutorial";
            case CONTEXT_VILLAGE: return "Village";
            case CONTEXT_STORY: return "Story";
            default: return context;
        }
    }
}
