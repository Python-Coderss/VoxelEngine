package com.voxel.game;

import com.voxel.entity.Entity;
import com.voxel.entity.VillagerEntity;
import villager.voice.SpeechOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Trigger → reaction engine for villager voice lines, modeled on the
 * "Villager News" Bedrock addon design: villagers notice player actions and
 * world events near them and answer with a voiced reaction.
 *
 * Design notes mirrored from the addon's Triggers &amp; Reactions guide:
 * <ul>
 *   <li>Most reactions require a villager to be nearby or able to see the
 *       event (distance + rough line-of-sight check).</li>
 *   <li>Reactions use per-(villager, trigger) cooldowns so repeating the same
 *       action doesn't spam dialogue.</li>
 *   <li>Not every trigger fires every time — a chance roll and the global
 *       {@link #setChattiness(int) chattiness} setting gate reactions.</li>
 *   <li>Triggers have multiple possible reactions so villagers don't always
 *       respond the same way; the last line is never repeated back to back.</li>
 *   <li>Some reactions are longer, more unusual "rare lines" with their own
 *       frequency setting ({@link #setRareLineChance(float)}).</li>
 *   <li>Reactions play with expressive talk animation + lip sync on the
 *       speaking villager.</li>
 * </ul>
 */
public final class VillagerReactions {

    /** Chattiness 0 = silent, 1 = shy, 2 = normal, 3 = super chatty. */
    private static int chattiness = 2;
    /** Probability [0..1] that a rare (long, over-the-top) line is used. */
    private static float rareLineChance = 0.12f;

    private static final Random RNG = new Random();

    /** Per-(villager id, trigger) cooldown clock in seconds. */
    private static final Map<String, Float> COOLDOWNS = new HashMap<String, Float>();
    /** Last chosen line index per (villager id, trigger) to avoid repeats. */
    private static final Map<String, Integer> LAST_LINE = new HashMap<String, Integer>();

    private static float globalCooldown = 0f;
    /** "Break several blocks in quick succession" streak tracking. */
    private static float breakStreakTimer = 0f;
    private static int breakStreak = 0;

    private VillagerReactions() {
    }

    /** A world event villagers can notice, with its detection + dialogue pool. */
    public enum Trigger {
        SEES_PLAYER(10f, 0.35f, 24f, "happy", 0.8f,
                "Oh! Hello there.",
                "Hm? A visitor?",
                "Good day to you!",
                "Ah, the player returns."),
        STAND_STILL(8f, 0.25f, 10f, "neutral", 0.6f,
                "Are you... a statue now?",
                "Hello? You have gone very still.",
                "Did you fall asleep standing up?"),
        NUDGE(6f, 0.6f, 5f, "angry", -0.2f,
                "Hey! Personal space!",
                "Hrrm, do you mind?",
                "Watch where you are walking!"),
        EAT(8f, 0.45f, 10f, "neutral", 0.8f,
                "Ooh, what are you eating?",
                "That smells good. Hmm.",
                "Eating again? In this economy?",
                "Save some for the rest of us!"),
        HARVEST(10f, 0.5f, 10f, "angry", -0.1f,
                "Those crops were not yours, hmm.",
                "Hey! I was growing those!",
                "Thief! ...Well, enjoy them, I suppose."),
        HARVEST_NEAR_FARMER(12f, 0.8f, 12f, "angry", -0.6f,
                "Those are MY crops! I grew them with these hands!",
                "HRRM! You take my crops and leave nothing?",
                "The soil remembers, and so do I!"),
        OPEN_CHEST(10f, 0.4f, 10f, "neutral", 0.7f,
                "Looking for something?",
                "That chest is not a marketplace, hmm.",
                "Find anything good in there?"),
        OPEN_OWN_CHEST(10f, 0.8f, 10f, "angry", -0.6f,
                "That is MY chest! Put that back!",
                "Hrrrm! Thieves in my own house!",
                "I knew I should have built a lock!"),
        CRAFT(10f, 0.35f, 12f, "happy", 0.8f,
                "Ooh, making something?",
                "A fine craft! Hmm, not bad.",
                "My grandfather built those, you know."),
        MINE_ORE(10f, 0.4f, 12f, "happy", 0.9f,
                "Shiny! You struck something shiny!",
                "Ooh, is that ore? How exciting!",
                "Digging for treasure, are we?"),
        FURNACE(10f, 0.3f, 12f, "happy", 0.7f,
                "The furnace warms the whole house, hmm.",
                "Smells like progress!",
                "Cooking up something nice?"),
        BREAK_BLOCK(8f, 0.35f, 10f, "neutral", 0.3f,
                "Hey! I was using that!",
                "Hm?! Why did you break that?",
                "Destruction! Always destruction with you people!"),
        BREAK_MANY(12f, 0.6f, 14f, "scared", -0.1f,
                "One block was an accident. Five is a lifestyle!",
                "Slow down! You are reshaping the neighborhood!",
                "Hrrm, should I be worried?"),
        BREAK_WOOD(10f, 0.5f, 10f, "angry", -0.3f,
                "That was perfectly good wood!",
                "Trees take YEARS to grow, hmm!",
                "My fence! Well, it was going to fall over anyway."),
        BREAK_STONE(10f, 0.4f, 10f, "neutral", 0.4f,
                "Breaking stone again?",
                "All that dust... my sinuses, hmm.",
                "Rocks today, rubble tomorrow."),
        BREAK_DOOR(10f, 0.8f, 10f, "angry", -0.7f,
                "MY DOOR! That was a perfectly good door!",
                "HRRRM! Who breaks a door?!",
                "The door was open! THE DOOR WAS OPEN!"),
        DEATH_WITNESSED(14f, 0.9f, 16f, "scared", -0.4f,
                "Oh my! Did you see that? They just... dropped!",
                "Rest in pieces, friend. Hmm.",
                "Should we... should we say something?",
                "I saw nothing. I saw EVERYTHING."),
        WAKE_UP(12f, 0.4f, 12f, "happy", 0.9f,
                "Good morning! Sleep well?",
                "Rise and shine, hmm!",
                "You were out cold!"),
        PLAYER_LOW_HEALTH(12f, 0.5f, 12f, "sad", -0.5f,
                "You do not look so good, friend.",
                "Careful! You are leaking!",
                "Perhaps sit down for a moment, hmm?"),
        PLAYER_DAMAGED(10f, 0.35f, 10f, "sad", -0.3f,
                "Ouch! That looked painful!",
                "Watch yourself!",
                "Hrrm, that is going to leave a mark."),
        VILLAGER_CHAT(20f, 0.8f, 14f, "happy", 0.9f,
                "Hrrm hrrm. Hrrm!",
                "Hmm? Hmm hmm.",
                "Hrrm. Hrrm hrrm hrrm!",
                "Hmm! ...Hmm.");

        public final float range;
        public final float chance;
        public final float cooldown;
        /** Delivery mood; one of neutral, happy, sad, angry, scared. */
        public final String emotion;
        /** Delivery tone: -1 serious .. +1 joking. */
        public final double tone;
        final String[] lines;

        Trigger(float range, float chance, float cooldown, String emotion,
                double tone, String... lines) {
            this.range = range;
            this.chance = chance;
            this.cooldown = cooldown;
            this.emotion = emotion;
            this.tone = tone;
            this.lines = lines;
        }
    }

    public static void setChattiness(int level) {
        chattiness = Math.max(0, Math.min(3, level));
    }

    public static int getChattiness() {
        return chattiness;
    }

    public static void setRareLineChance(float chance) {
        rareLineChance = Math.max(0f, Math.min(1f, chance));
    }

    /**
     * Fire a trigger at a world position. Nearby villagers that can see the
     * spot may respond, subject to cooldowns, chance and chattiness.
     *
     * @return the text of the reaction if one played, or {@code null}
     */
    public static String fire(GameContext ctx, Trigger trigger, float x, float y, float z) {
        if (ctx == null || ctx.entityManager == null || ctx.villagerAudioManager == null) {
            return null;
        }
        if (chattiness == 0) {
            return null;
        }
        // Chattiness scales both the chance roll and the global pacing.
        float chanceScale = chattiness == 1 ? 0.45f : chattiness == 3 ? 1.6f : 1f;
        if (RNG.nextFloat() > trigger.chance * chanceScale) {
            return null;
        }
        if (globalCooldown > 0f) {
            return null;
        }

        VillagerEntity speaker = null;
        float best = trigger.range * trigger.range;
        for (int i = 0; i < ctx.entityManager.getEntityCount(); i++) {
            Entity e = ctx.entityManager.getEntity(i);
            if (!(e instanceof VillagerEntity)) {
                continue;
            }
            VillagerEntity v = (VillagerEntity) e;
            float dx = (float) v.getPosX() - x;
            float dy = (float) (v.getPosY() + 1.0) - (y + 1.0f);
            float dz = (float) v.getPosZ() - z;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > best) {
                continue;
            }
            if (!canSee(ctx, v, x, y, z)) {
                continue;
            }
            String key = v.id + ":" + trigger.name();
            Float until = COOLDOWNS.get(key);
            if (until != null && until > ctx.worldTime) {
                continue;
            }
            speaker = v;
            best = d2;
        }
        if (speaker == null) {
            return null;
        }
        // A farmer present for a crop harvest complains much harder (the
        // addon's "Harvest Crops Near a Farmer" trigger).
        if (trigger == Trigger.HARVEST
                && speaker.getProfession() == VillagerEntity.Profession.FARMER) {
            trigger = Trigger.HARVEST_NEAR_FARMER;
        }

        String key = speaker.id + ":" + trigger.name();
        COOLDOWNS.put(key, ctx.worldTime + trigger.cooldown);
        globalCooldown = 2.5f + (chattiness == 3 ? 1f : 4f) * RNG.nextFloat();

        int index;
        do {
            index = RNG.nextInt(trigger.lines.length);
        } while (trigger.lines.length > 1 && index == lastIndex(key));
        LAST_LINE.put(key, index);

        String text = trigger.lines[index];
        // Rare lines: longer, more unusual reactions (rarely chosen, and only
        // on chatty settings, matching the addon's Rare Villager Lines knob).
        if (RNG.nextFloat() < rareLineChance && chattiness >= 2) {
            text = rareVariant(trigger, text);
        }
        SpeechOptions options = new SpeechOptions(1.0, 0.0, 1.0, trigger.tone, 0.60,
                trigger.emotion, 0.0, 0.0, text.indexOf('?') >= 0);
        speaker.startTalking(estimateDuration(text));
        ctx.villagerAudioManager.requestSpeech(text, options);
        return text;
    }

    /** Tick down the pacing cooldowns; call once per frame. */
    public static void tick(float dt) {
        if (globalCooldown > 0f) {
            globalCooldown = Math.max(0f, globalCooldown - dt);
        }
        if (breakStreakTimer > 0f) {
            breakStreakTimer = Math.max(0f, breakStreakTimer - dt);
        }
    }

    /**
     * A block was broken at (x, y, z). Picks the matching break/harvest
     * trigger from the block name and lets nearby villagers react.
     */
    public static String onBlockBroken(GameContext ctx, int x, int y, int z,
                                       String blockName) {
        breakStreak = breakStreakTimer > 0f ? breakStreak + 1 : 1;
        breakStreakTimer = 3.0f;
        String n = blockName == null ? "" : blockName.toLowerCase(java.util.Locale.ROOT);
        Trigger trigger;
        if (n.contains("wheat") || n.contains("carrot") || n.contains("potato")
                || n.contains("beet") || n.contains("melon") || n.contains("pumpkin")
                || n.contains("crop") || n.contains("cocoa") || n.contains("berry")
                || n.contains("wart")) {
            trigger = Trigger.HARVEST;
        } else if (n.contains("door")) {
            trigger = Trigger.BREAK_DOOR;
        } else if (n.contains("log") || n.contains("plank") || n.contains("wood")) {
            trigger = Trigger.BREAK_WOOD;
        } else if (n.contains("ore")) {
            trigger = Trigger.MINE_ORE;
        } else if (n.contains("stone") || n.contains("cobble") || n.contains("brick")
                || n.contains("obsidian") || n.contains("granite") || n.contains("diorite")
                || n.contains("andesite") || n.contains("basalt") || n.contains("deepslate")
                || n.contains("sandstone") || n.contains("netherrack")) {
            trigger = Trigger.BREAK_STONE;
        } else {
            trigger = Trigger.BREAK_BLOCK;
        }
        // Breaking several blocks in quick succession is its own reaction.
        if (breakStreak >= 4) {
            String many = fire(ctx, Trigger.BREAK_MANY, x, y, z);
            if (many != null) {
                return many;
            }
        }
        return fire(ctx, trigger, x, y, z);
    }

    /** Rough visibility check: clear line of sight through the voxel grid. */
    private static boolean canSee(GameContext ctx, VillagerEntity v,
                                  float x, float y, float z) {
        if (ctx.world == null) {
            return true;
        }
        float sx = (float) v.getPosX();
        float sy = (float) (v.getPosY() + 1.6);
        float sz = (float) v.getPosZ();
        float dx = x - sx;
        float dy = (y + 0.5f) - sy;
        float dz = z - sz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.5f) {
            return true;
        }
        int steps = Math.max(2, (int) (dist * 1.5f));
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            int bx = (int) Math.floor(sx + dx * t);
            int by = (int) Math.floor(sy + dy * t);
            int bz = (int) Math.floor(sz + dz * t);
            int block = ctx.world.getVoxel(bx, by, bz);
            if (block != 0 && block != 15) { // solid and not water blocks sight
                return false;
            }
        }
        return true;
    }

    private static int lastIndex(String key) {
        Integer last = LAST_LINE.get(key);
        return last == null ? -1 : last;
    }

    private static String rareVariant(Trigger trigger, String fallback) {
        switch (trigger) {
            case BREAK_BLOCK:
                return "In my day we BUILT things! Now everyone just breaks things! The world was finished, you know!";
            case EAT:
                return "I once knew a man who ate nothing but bread for forty years. Forty years! He was very thin. And very bread.";
            case MINE_ORE:
                return "My uncle dug for ore once. Found a cave. Found a skeleton. Found religion, he says!";
            case DEATH_WITNESSED:
                return "I have seen things you would not believe. Attack ships on fire off the shoulder of a mountain. And also that. That was also something.";
            case OPEN_OWN_CHEST:
                return "That chest has been in my family for GENERATIONS! It contains nothing of value, but that is not the POINT!";
            case NUDGE:
                return "Do that again and I shall write a VERY strongly worded sign!";
            default:
                return fallback + " ...Hmm. I have said too much.";
        }
    }

    /** Rough spoken duration in seconds for lip sync / gesture timing. */
    private static float estimateDuration(String text) {
        return Math.max(1.2f, text.length() * 0.052f + 0.4f);
    }
}
