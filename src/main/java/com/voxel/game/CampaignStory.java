package com.voxel.game;

import org.lwjgl.glfw.GLFW;

import villager.voice.SpeechOptions;

/**
 * Story beats for the wider campaign map: MCSM Episodes 2-8, the Minecraft
 * Legends campaign, and both Dungeons journeys (§7). The same shape as
 * {@link McsmStory} — walking into a site plays its beat, a named character
 * speaks through the villager voice pipeline, and a numbered choice takes a
 * spoken reply. State is per session; every beat fires once.
 *
 * <p>Kept separate from {@link McsmStory} so the episode-1 path stays small and
 * so these later beats can be tested without the original one-shot state.</p>
 */
public final class CampaignStory {

    /** A dialogue choice: the option label and the character's spoken reply. */
    private static final class Choice {
        final String label;
        final String reply;

        Choice(String label, String reply) {
            this.label = label;
            this.reply = reply;
        }
    }

    /** A story beat anchored to a campaign site. */
    private static final class Beat {
        final String site, chapter, character, line;
        final float radius;
        final SpeechOptions voice;
        final Choice[] choices;
        boolean shown;
        /** Mob to summon when the beat fires (a name the spawn switch knows). */
        String mob;

        Beat(String site, String chapter, String character, String line,
             float radius, SpeechOptions voice, Choice... choices) {
            this.site = site;
            this.chapter = chapter;
            this.character = character;
            this.line = line;
            this.radius = radius;
            this.voice = voice;
            this.choices = choices;
        }

        Beat withMob(String mobName) {
            this.mob = mobName;
            return this;
        }
    }

    private static final SpeechOptions NARRATOR =
            new SpeechOptions(0.98, 0.0, 1.0, 0.1, "neutral", 0.0, false);
    private static final SpeechOptions HERO =
            new SpeechOptions(0.96, -1.5, 1.0, 0.2, "happy", 0.0, false);
    private static final SpeechOptions VILLAIN =
            new SpeechOptions(1.06, 2.5, 1.0, -0.2, "angry", 0.6, false);

    private static final Beat[] BEATS = {
            // ── MCSM Episodes 2-8 ──
            new Beat("redstonia", "Redstonia", "Ellegaard",
                    "Redstonia runs on redstone and stubbornness. Pick a lever, hero.",
                    40f, HERO,
                    new Choice("Ask about the Command Block.",
                            "Every circuit here answers to it. Do not touch the wrong one."),
                    new Choice("Compliment the machines.",
                            "Of course they are magnificent. I engineered them.")),
            new Beat("boom_town", "Boom Town", "Magnus",
                    "Welcome to Boom Town! Mind the TNT. Actually — do mind it.",
                    40f, VILLAIN,
                    new Choice("Light the fuse anyway.",
                            "Now THAT is the spirit of Boom Town!"),
                    new Choice("Keep your hands to yourself.",
                            "Boring. Safe, but boring.")),
            new Beat("sky_city", "Sky City", "Aiden",
                    "You climbed all the way up here? The view is worth it, I suppose.",
                    40f, NARRATOR),
            new Beat("crown_mesa", "Crown Mesa", "Ivor",
                    "The mesa hides the old dig sites. Everything valuable is buried.",
                    40f, VILLAIN),
            new Beat("wither_storm_arena", "The Wither Storm", "Gabriel",
                    "Something is wrong — the sky is going dark over the arena!",
                    44f, HERO).withMob("wither_storm"),
            new Beat("orders_temple", "The Order's Temple", "Soren",
                    "The Order stood here, once. Remember what we were meant to build.",
                    40f, NARRATOR),
            new Beat("beacontown", "Beacontown", "Petra",
                    "New Beacontown, rebuilt stone by stone. We do not quit easily.",
                    40f, HERO),
            new Beat("freds_keep", "Fred's Keep", "Narrator",
                    "Fred's keep still stands, a monument to a builder who never gave up.",
                    40f, NARRATOR),
            new Beat("portal_network", "The Portal Network", "Ivor",
                    "Mind where you step. These portals do not all lead where they claim.",
                    40f, VILLAIN),
            // ── Minecraft Legends ──
            new Beat("well_of_fate", "The Well of Fate", "The Host",
                    "The horde is coming, and the Well of Fate calls for a hero.",
                    42f, NARRATOR),
            new Beat("great_hog_camp", "The Great Hog's Camp", "The Great Hog",
                    "You bring a handful of heroes against MY horde? Amusing.",
                    44f, VILLAIN).withMob("great_hog"),
            new Beat("hero_outpost", "The Hero Outpost", "The Host",
                    "Rally your allies here. An outpost held is a kingdom defended.",
                    40f, HERO),
            new Beat("defended_village", "The Defended Village", "Villager",
                    "Hmm! Hmm hmm hmm — the village stands thanks to you.",
                    40f, HERO),
            // ── Minecraft Dungeons 1 ──
            new Beat("creeper_woods", "Creeper Woods", "Narrator",
                    "The woods rustle. Every shadow could be about to explode.",
                    40f, NARRATOR),
            new Beat("pumpkin_pastures", "Pumpkin Pastures", "Narrator",
                    "A field of pumpkins, and something carved a grin in every one.",
                    40f, NARRATOR),
            new Beat("soggy_swamp", "Soggy Swamp", "Narrator",
                    "The water is foul and the fog is worse. Keep moving.",
                    40f, NARRATOR),
            new Beat("cacti_canyon", "Cacti Canyon", "Narrator",
                    "Watch the cacti on the rim — and the drop over the bridge.",
                    40f, NARRATOR),
            new Beat("desert_temple", "Desert Temple", "Narrator",
                    "The temple has waited a thousand years for a visitor this rude.",
                    40f, NARRATOR),
            new Beat("highblock_halls", "Highblock Halls", "Narrator",
                    "The castle is taken. Its halls belong to the illagers now.",
                    42f, VILLAIN).withMob("vindicator"),
            new Beat("fiery_forge", "Fiery Forge", "Narrator",
                    "Heat rolls off the forge. Something is being armed down here.",
                    40f, NARRATOR),
            new Beat("obsidian_pinnacle", "Obsidian Pinnacle", "The Arch-Illager",
                    "You climb to me at last. The Heart of Ender will finish this.",
                    46f, VILLAIN).withMob("arch_illager"),
            new Beat("island_realms", "Island Realms", "The Heart of Ender",
                    "A floating outpost in the clouds, guarding a path to the Heart.",
                    42f, VILLAIN).withMob("heart_of_ender"),
            // ── Minecraft Dungeons 2 (Sift-linked) ──
            new Beat("rift_stop", "The Rift Stop", "Narrator",
                    "A rift hums open. Beyond it lies the Sift, and the souls within.",
                    42f, NARRATOR),
            new Beat("illager_keep", "The Illager Keep", "Narrator",
                    "The illagers learned to follow the rifts. That is how this began.",
                    42f, VILLAIN).withMob("evoker"),
    };

    private static Beat awaiting = null;
    private static boolean key1Down = false, key2Down = false;
    private static String spawnRequest = null;

    private CampaignStory() {
    }

    /** Resets all beat state (tests / new worlds). */
    public static void resetForTest() {
        for (Beat b : BEATS) b.shown = false;
        awaiting = null;
        spawnRequest = null;
        key1Down = false;
        key2Down = false;
    }

    /**
     * One-shot mob summon requested by the last fired beat (a name the spawn
     * switch understands), or null. Main spawns it and clears the request.
     */
    public static String consumeSpawnRequest() {
        String r = spawnRequest;
        spawnRequest = null;
        return r;
    }

    /** The mob the beat at index i summons, or null (tests / tools). */
    public static String mobAt(int i) {
        return BEATS[i].mob;
    }

    /** Number of campaign beats (tests / tools). */
    public static int beatCount() {
        return BEATS.length;
    }

    /** The chapter title of the beat at index i (tests / tools). */
    public static String chapterAt(int i) {
        return BEATS[i].chapter;
    }

    /** True when a beat is waiting for the player to press 1 or 2. */
    public static boolean isAwaitingChoice() {
        return awaiting != null;
    }

    /**
     * Advances the campaign story: fires the first unshown beat whose site the
     * player has entered, and polls 1/2 while a choice is pending.
     */
    public static void tick(McsmStory.Host host, long window,
                            float playerX, float playerZ, float dt) {
        if (host == null) return;
        if (awaiting == null) {
            for (Beat b : BEATS) {
                if (b.shown) continue;
                int[] site = coordsOf(b.site);
                if (site == null) continue;
                float dx = playerX - site[0], dz = playerZ - site[1];
                if (dx * dx + dz * dz > b.radius * b.radius) continue;
                b.shown = true;
                host.showPopup(b.chapter, b.character + ": " + b.line);
                host.speak(b.line, b.voice);
                if (b.mob != null) {
                    spawnRequest = b.mob;
                    host.setStatus("Something answers the call...");
                }
                if (b.choices.length > 0) {
                    awaiting = b;
                    StringBuilder sb = new StringBuilder("Choose: ");
                    for (int i = 0; i < b.choices.length; i++) {
                        sb.append('[').append(i + 1).append("] ")
                          .append(b.choices[i].label).append("  ");
                    }
                    host.setStatus(sb.toString().trim());
                }
                break;
            }
        } else {
            int picked = -1;
            if (pressed(window, GLFW.GLFW_KEY_1)) picked = 0;
            else if (pressed(window, GLFW.GLFW_KEY_2)) picked = 1;
            if (picked >= 0 && picked < awaiting.choices.length) {
                Choice c = awaiting.choices[picked];
                host.setStatus(awaiting.character + ": " + c.label);
                host.speak(c.reply, awaiting.voice);
                awaiting = null;
            }
        }
    }

    /** Absolute map column of a campaign site, or null if unknown. */
    static int[] coordsOf(String site) {
        switch (site) {
            case "redstonia": return com.voxel.world.McsmStructures.REDSTONIA;
            case "boom_town": return com.voxel.world.McsmStructures.BOOM_TOWN;
            case "sky_city": return com.voxel.world.McsmStructures.SKY_CITY;
            case "crown_mesa": return com.voxel.world.McsmStructures.CROWN_MESA;
            case "wither_storm_arena": return com.voxel.world.McsmStructures.WITHER_STORM_ARENA;
            case "orders_temple": return com.voxel.world.McsmStructures.ORDERS_TEMPLE;
            case "beacontown": return com.voxel.world.McsmStructures.BEACONTOWN;
            case "freds_keep": return com.voxel.world.McsmStructures.FREDS_KEEP;
            case "portal_network": return com.voxel.world.McsmStructures.PORTAL_NETWORK;
            case "well_of_fate": return com.voxel.world.LegendsStructures.WELL_OF_FATE;
            case "great_hog_camp": return com.voxel.world.LegendsStructures.GREAT_HOG_CAMP;
            case "hero_outpost": return com.voxel.world.LegendsStructures.HERO_OUTPOST;
            case "defended_village": return com.voxel.world.LegendsStructures.DEFENDED_VILLAGE;
            case "creeper_woods": return com.voxel.world.DungeonsStructures.CREEPER_WOODS;
            case "pumpkin_pastures": return com.voxel.world.DungeonsStructures.PUMPKIN_PASTURES;
            case "soggy_swamp": return com.voxel.world.DungeonsStructures.SOGGY_SWAMP;
            case "cacti_canyon": return com.voxel.world.DungeonsStructures.CACTI_CANYON;
            case "desert_temple": return com.voxel.world.DungeonsStructures.DESERT_TEMPLE;
            case "highblock_halls": return com.voxel.world.DungeonsStructures.HIGHBLOCK_HALLS;
            case "fiery_forge": return com.voxel.world.DungeonsStructures.FIERY_FORGE;
            case "obsidian_pinnacle": return com.voxel.world.DungeonsStructures.OBSIDIAN_PINNACLE;
            case "island_realms": return com.voxel.world.DungeonsStructures.ISLAND_REALMS;
            case "rift_stop": return com.voxel.world.DungeonsStructures.RIFT_STOP;
            case "illager_keep": return com.voxel.world.DungeonsStructures.ILLAGER_KEEP;
            default: return null;
        }
    }

    private static boolean pressed(long window, int key) {
        boolean down = window != 0 && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        boolean edge;
        if (key == GLFW.GLFW_KEY_1) {
            edge = down && !key1Down;
            key1Down = down;
        } else {
            edge = down && !key2Down;
            key2Down = down;
        }
        return edge;
    }
}
