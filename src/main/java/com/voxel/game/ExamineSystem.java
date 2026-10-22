package com.voxel.game;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Look-at lore: what Jesse thinks when the player examines the world.
 *
 * <p>Every entry is a hand-written piece of lore plus a first-person
 * monologue line, spoken in Male Jesse's voice ({@code CharacterVoice
 * .speakMonologue}). Entries are matched by block-name keywords, so the whole
 * world is examinable without listing thousands of blocks: a copper ore, a
 * copper block and a copper door all land on the metalworking entry. Notable
 * blocks (the command block, the rift frame, the Villager News studio) have
 * their own entry and win over the category.</p>
 *
 * <p>Pure data + lookup, no rendering and no game state, so it is unit
 * tested: every entry needs a title, body and monologue, and unknown blocks
 * must still get a real entry rather than an empty inspection.</p>
 */
public final class ExamineSystem {

    /** One examinable subject. */
    public static final class Lore {
        public final String key;
        public final String title;
        public final String body;
        public final String monologue;

        Lore(String key, String title, String body, String monologue) {
            this.key = key;
            this.title = title;
            this.body = body;
            this.monologue = monologue;
        }

        @Override
        public String toString() {
            return title + ": " + body;
        }
    }

    /** Named entries: the story's own objects, checked before the categories. */
    private static final Map<String, Lore> NAMED = new LinkedHashMap<String, Lore>();
    /** Category entries in match order (first keyword hit wins). */
    private static final Map<String, Lore> CATEGORIES = new LinkedHashMap<String, Lore>();
    private static final Map<String, String[]> KEYWORDS = new LinkedHashMap<String, String[]>();

    static {
        named("command_block", "The Command Block",
                "A cube of black iron and red glass. The ancient builders did not "
                        + "carve commands into stone — they wrote them into the world itself.",
                "Someone typed a universe into this box. I should not touch it.");
        named("rift_frame", "Rift Frame",
                "Soul-slate pillars around a dead aperture. The frame remembers the "
                        + "shape of a door even when there is no door in it.",
                "It is a doorway with the door deleted. That is somehow worse.");
        named("sift_portal", "Sift Rift",
                "A tear into the Sift, stitched open by a melody. The air on the far "
                        + "side moves like it is being sieved.",
                "The Sift. Everything that falls through the world ends up there.");
        named("villager_tv", "Villager News Desk",
                "A studio built into a village wall. The camera is always on; the "
                        + "broadcast never really stops.",
                "They report on us. I have never once seen them blink.");
        named("enchanting_table", "Enchanting Table",
                "Obsidian and diamond over an open book. The glyphs rearrange when "
                        + "you are not reading them directly.",
                "The book is writing itself. I am choosing not to think about that.");
        named("anvil", "Anvil",
                "Builder steel, worn smooth in the middle. Every dent is somebody's "
                        + "impatience.",
                "Someone made a thousand things on this and never once cursed. Unlikely.");
        named("beacon", "Beacon",
                "A pyramid of mineral blocks under a single bright core. The beam is "
                        + "not light — it is permission.",
                "That beam is visible from the far ridge. So is everything we build.");
        named("end_portal", "End Portal",
                "A ring of end stone and green fire. The frame does not reflect the "
                        + "room you are standing in.",
                "The End. Nobody builds out there. They only arrive.");
        named("nether_portal", "Nether Portal",
                "Obsidian lit with violet fire. The heat comes through before the "
                        + "light does.",
                "The Nether does not want visitors. It tolerates them.");
        named("door", "Door",
                "A working door: hinge, latch, two halves. The Order's oldest habit is "
                        + "closing things behind them.",
                "Doors are a promise that you are coming back.");
        named("bed", "Bed",
                "Wool over planks, set against a wall. Even builders needed somewhere "
                        + "to put the day down.",
                "Set the spawn. Sleep. Wake up somewhere that is not here.");

        // Deliberately no bare "redstone": a repeater is a mechanism, not a
        // seam, and "redstone_ore" already matches "ore".
        category("ore", new String[]{"ore", "diamond_block", "emerald_block",
                "gold_block", "iron_block", "copper_block", "coal_block",
                "lapis_block", "quartz_block"},
                "Ore",
                "Raw mineral in stone, still carrying the shape of the seam it came "
                        + "from. The builders sorted by colour, then by ring.",
                "Everything under here was somebody's project once.");
        category("metal", new String[]{"metal", "steel", "brass", "ingot", "block_of"},
                "Worked Metal",
                "Smelted, poured, hammered. Nothing here was found in this shape.",
                "Metal remembers the hammer. That is the whole trick.");
        // Ruin is checked before Stonework so "mossy_stonebrick" reads as a
        // ruin rather than as fresh masonry.
        category("ruin", new String[]{"ancient", "ruin", "cracked", "mossy", "chiseled",
                "stronghold", "fortress", "temple", "monument"},
                "Ruin",
                "Builder work with the edges gone. The plan is still legible if you "
                        + "stand back far enough.",
                "They built for centuries and still ran out of time.");
        category("stone", new String[]{"stone", "brick", "cobble", "slate", "marble",
                "concrete", "tile", "purpur", "obsidian", "basalt", "deepslate",
                "andesite", "granite", "diorite", "sandstone", "gravel"},
                "Stonework",
                "Cut stone, laid in courses. The joint is the signature: the builders "
                        + "never trusted mortar alone.",
                "Somebody set this by hand. Respect the joint.");
        category("wood", new String[]{"wood", "log", "plank", "door", "fence", "gate",
                "ladder", "chest", "barrel", "bookshelf", "crafting"},
                "Timber",
                "Planed timber, still faintly resinous. Oak for structure, spruce for "
                        + "cold, dark oak for things that should not be found.",
                "Grain runs one way. Whoever built this knew that.");
        category("plant", new String[]{"leaves", "sapling", "flower", "grass", "wheat",
                "crop", "vine", "lily", "cactus", "bamboo", "mushroom", "pumpkin",
                "melon", "hay", "moss"},
                "Growing Things",
                "Something that decided to live here after the builders left. The "
                        + "village farms are the only order left above ground.",
                "It grew back. That is the closest thing to good news out here.");
        category("soil", new String[]{"dirt", "sand", "clay", "farmland", "snow",
                "ice", "podzol", "mycelium", "soul_soil"},
                "Ground",
                "Loose ground, trodden into a path. Paths are the oldest record of "
                        + "where people actually went.",
                "Follow the trodden line. It knows where the village is.");
        category("water", new String[]{"water", "lava", "fluid", "kelp", "sea"},
                "Fluid",
                "Moving liquid in a carved channel. The irrigation here is older than "
                        + "the houses it feeds.",
                "Lava is just water that kept its anger.");
        category("light", new String[]{"torch", "lantern", "lamp", "glowstone",
                "light", "candle", "campfire", "shroomlight", "sea_lantern"},
                "Light",
                "A working light: someone expected to come back after dark. Lantern "
                        + "lines are the builders' handwriting.",
                "Every lantern is an argument against the night.");
        category("machine", new String[]{"piston", "repeater", "comparator", "observer",
                "hopper", "dispenser", "dropper", "rail", "minecart", "redstone",
                "lever", "button", "pressure", "machine", "gear", "shaft", "belt"},
                "Mechanism",
                "A mechanism with a job. Redstone is not magic — it is a signal that "
                        + "refuses to be forgotten.",
                "It is still powered. Something still thinks this should be running.");
        category("storage", new String[]{"chest", "barrel", "shulker", "vault",
                "furnace", "smoker", "blast", "cauldron", "brewing"},
                "Storage",
                "A container that outlived its owner. Every chest in a ruin is a "
                        + "question about who emptied it.",
                "Someone packed this and then did not come back.");
        category("glass", new String[]{"glass", "pane", "window", "ice"},
                "Glass",
                "Poured glass, set in a frame. The builders wanted to see out without "
                        + "letting the weather in.",
                "Clear glass is optimistic. Someone expected a view.");
        category("cloth", new String[]{"wool", "carpet", "banner", "cloth", "bed"},
                "Cloth",
                "Dyed wool, hung or laid flat. Colour is how a village says whose side "
                        + "of the valley it is on.",
                "A banner is a flag you can carry. Or bury someone under.");
    }

    private ExamineSystem() {
    }

    private static void named(String key, String title, String body, String monologue) {
        NAMED.put(key, new Lore(key, title, body, monologue));
    }

    private static void category(String key, String[] keywords, String title,
                                 String body, String monologue) {
        CATEGORIES.put(key, new Lore(key, title, body, monologue));
        KEYWORDS.put(key, keywords);
    }

    /**
     * Lore for a block name ({@code "stonebrick"}, {@code "oak_door_lower"}, or
     * a display name). Never null: an unknown block still gets the fallback
     * entry, so every look-at produces a monologue.
     */
    public static Lore describe(String blockName) {
        String key = normalize(blockName);
        if (key.isEmpty()) return unknown();
        Lore exact = NAMED.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, Lore> entry : NAMED.entrySet()) {
            if (key.contains(entry.getKey())) return entry.getValue();
        }
        for (Map.Entry<String, String[]> entry : KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (matches(key, keyword)) return CATEGORIES.get(entry.getKey());
            }
        }
        return unknown();
    }

    /**
     * Token-aware keyword match. Plain substring matching made "redstone" hit
     * the stone category and "redstone_repeater" read as masonry, so a keyword
     * must be a whole token or the start of a compound token ("stonebrick").
     * Direction still matters: "redstone" never matches "stone".
     */
    static boolean matches(String key, String keyword) {
        if (key == null || keyword == null || keyword.isEmpty()) return false;
        if (key.equals(keyword)) return true;
        int from = 0;
        while (true) {
            int at = key.indexOf(keyword, from);
            if (at < 0) return false;
            boolean startsToken = at == 0 || key.charAt(at - 1) == '_';
            int end = at + keyword.length();
            if (startsToken && (end == key.length() || key.charAt(end) == '_')) {
                return true;
            }
            if (startsToken && keyword.length() >= 4) {
                // Compound token such as "stonebrick" or "cobblestone".
                return true;
            }
            from = at + 1;
        }
    }

    /** The fallback entry, shared by unknown and empty names. */
    public static Lore unknown() {
        return new Lore("unknown", "Unfamiliar Work",
                "This is not builder work. Or it is builder work so old that "
                        + "even the joints have forgotten the method.",
                "New stone. Or old enough to be new again. Either way, I am looking at it.");
    }

    /** Number of hand-written named entries (tests). */
    public static int namedCount() {
        return NAMED.size();
    }

    /** Number of category entries (tests). */
    public static int categoryCount() {
        return CATEGORIES.size();
    }

    /** Normalize a block id/name into a keyword-matching key. */
    static String normalize(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT).trim();
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) out.append('_');
                out.append(c);
                space = false;
            } else {
                space = true;
            }
        }
        return out.toString();
    }
}
