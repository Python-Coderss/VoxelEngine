package com.voxel.game;

import org.lwjgl.glfw.GLFW;

import villager.voice.SpeechOptions;

/**
 * The Minecraft: Story Mode story beats: three episodes anchored to the
 * {@link com.voxel.world.McsmStructures} sites. Coming near a site plays its
 * beat — the named character speaks (through the villager voice pipeline) and
 * the player picks a numbered dialogue choice with the 1/2 keys, which gets a
 * spoken reply. The pumpkin hideout beat summons the White Pumpkin boss.
 *
 * <p>State is per session; every beat fires once. {@link #tick} is called from
 * the logic thread each frame and only does cheap distance/key checks.</p>
 */
public final class McsmStory {

    /** Story host: the game surfaces the story needs (popup, status, voice). */
    public interface Host {
        void showPopup(String title, String subtitle);
        void setStatus(String message);
        void speak(String text, SpeechOptions options);
    }

    /** A dialogue choice: the option label and the character's spoken reply. */
    private static final class Choice {
        final String label;
        final String reply;
        Choice(String label, String reply) {
            this.label = label;
            this.reply = reply;
        }
    }

    /** A story beat tied to a location. */
    private static final class Beat {
        final String site, episode, character, line;
        final float radius;
        final SpeechOptions voice;
        final Choice[] choices;
        boolean shown;

        Beat(String site, String episode, String character, String line,
             float radius, SpeechOptions voice, Choice... choices) {
            this.site = site;
            this.episode = episode;
            this.character = character;
            this.line = line;
            this.radius = radius;
            this.voice = voice;
            this.choices = choices;
        }
    }

    // Character voices (speed, pitch, volume, tone, emotion, sarcasm, question).
    private static final SpeechOptions GABRIEL =
            new SpeechOptions(0.95, -2.5, 1.0, 0.25, "happy", 0.0, false);
    private static final SpeechOptions IVOR =
            new SpeechOptions(1.08, 1.5, 1.0, -0.15, "angry", 0.7, false);
    private static final SpeechOptions PETRA =
            new SpeechOptions(1.0, 0.5, 1.0, 0.1, "scared", 0.0, false);

    private static final Beat[] BEATS = {
        new Beat("endercon", "Episode 1 · The Order of the Stone", "Gabriel",
                "Welcome to EnderCon, hero! Today we fight, we build, we celebrate.",
                42f, GABRIEL,
                new Choice("Challenge the champion.",
                        "Bold words! The arena awaits — make me proud."),
                new Choice("Ask about the Order of the Stone.",
                        "We built this world with our own hands. Never forget that.")),
        new Beat("soren", "Episode 2 · The Builder's Secret", "Ivor",
                "Careful where you step! That tower is very much unfinished.",
                42f, IVOR,
                new Choice("Take the Formidi-Bomb plans.",
                        "Ha! You will want clay, gunpowder and TNT. Handle with care!"),
                new Choice("Leave them be.",
                        "Wise. Explosives and wisdom rarely travel together.")),
        new Beat("pumpkin", "Episode 3 · The White Pumpkin", "Petra",
                "The White Pumpkin is real, and it is down that shaft. Get ready!",
                38f, PETRA),
    };

    private static Beat awaiting = null;   // beat waiting for a 1/2 choice
    private static boolean bossRequested = false;

    private McsmStory() { }

    /** Resets all story state (tests / new worlds). */
    public static void resetForTest() {
        for (Beat b : BEATS) b.shown = false;
        awaiting = null;
        bossRequested = false;
    }

    /** True when a beat is waiting for the player to press 1 or 2. */
    public static boolean isAwaitingChoice() {
        return awaiting != null;
    }

    /**
     * One-shot boss summon request for the pumpkin beat (Main spawns the
     * entity and clears the flag via this method).
     */
    public static boolean consumeBossSpawnRequest() {
        boolean r = bossRequested;
        bossRequested = false;
        return r;
    }

    /**
     * Advances the story: fires any beat whose site the player has entered and
     * polls the 1/2 keys while a choice is pending. Called every logic tick.
     */
    public static void tick(Host host, long window, float playerX, float playerZ, float dt) {
        if (awaiting == null) {
            for (Beat b : BEATS) {
                if (b.shown) continue;
                int[] site = siteOf(b.site);
                float dx = playerX - site[0], dz = playerZ - site[1];
                if (dx * dx + dz * dz > b.radius * b.radius) continue;
                b.shown = true;
                host.showPopup(b.episode, b.character + ": " + b.line);
                host.speak(b.line, b.voice);
                if (b.choices.length == 0) {
                    if ("pumpkin".equals(b.site)) {
                        bossRequested = true;
                        host.setStatus("The White Pumpkin approaches...");
                    }
                } else {
                    awaiting = b;
                    StringBuilder sb = new StringBuilder("Choose: ");
                    for (int i = 0; i < b.choices.length; i++) {
                        sb.append('[').append(i + 1).append("] ").append(b.choices[i].label).append("  ");
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

    private static int[] siteOf(String site) {
        if ("endercon".equals(site)) return com.voxel.world.McsmStructures.ENDERCON;
        if ("soren".equals(site)) return com.voxel.world.McsmStructures.SORENS_SITE;
        return com.voxel.world.McsmStructures.PUMPKIN_HIDEOUT;
    }

    // Edge-triggered key state (1/2 across all beats).
    private static boolean key1Down = false, key2Down = false;

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
