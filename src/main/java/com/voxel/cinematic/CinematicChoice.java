package com.voxel.cinematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One MCSM-style decision: a spoken prompt, up to four options, a countdown and
 * a default option for players who say nothing.
 *
 * <p>Built with the small fluent API so scenes read like a script:</p>
 * <pre>
 * new CinematicChoice("gate_fork", "Ancient Builder",
 *         "The gate is closing. What do you do?", 7f)
 *     .option("Hold it open", "Then hold it. I will remember that.")
 *     .option("Run for the tunnel")
 *     .defaultOption(0);
 * </pre>
 *
 * <p>The pick is recorded in {@link com.voxel.game.StoryState} as
 * {@code choice.<id> = <label>} plus a recap line, so later scenes and villager
 * dialogue can react to it.</p>
 */
public final class CinematicChoice {

    /** HUD rows and the 1-4 key bindings. */
    public static final int MAX_OPTIONS = 4;
    /** Never give the player less time than a comfortable read. */
    public static final float MIN_SECONDS = 3f;
    public static final float MAX_SECONDS = 30f;

    /** One selectable line. The optional response is spoken right after picking. */
    public static final class Option {
        public final String label;
        public final String response;

        public Option(String label, String response) {
            this.label = label == null || label.trim().isEmpty() ? "…" : label.trim();
            this.response = response == null ? null : response.trim();
        }
    }

    private final String id;
    private final String speaker;
    private final String prompt;
    private final float seconds;
    private final List<Option> options = new ArrayList<Option>();
    private int defaultIndex = 0;

    public CinematicChoice(String id, String speaker, String prompt, float seconds) {
        this.id = id == null || id.trim().isEmpty() ? "choice" : id.trim();
        this.speaker = speaker == null || speaker.trim().isEmpty() ? "Narrator" : speaker.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        this.seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    /** Add an option, ignoring anything past {@link #MAX_OPTIONS}. */
    public CinematicChoice option(String label) {
        return option(label, null);
    }

    /** Add an option with a spoken follow-up line. */
    public CinematicChoice option(String label, String response) {
        if (options.size() >= MAX_OPTIONS) return this;
        options.add(new Option(label, response));
        return this;
    }

    /** Which option is taken when the timer runs out (default 0). */
    public CinematicChoice defaultOption(int index) {
        this.defaultIndex = Math.max(0, Math.min(MAX_OPTIONS - 1, index));
        return this;
    }

    public String id() { return id; }
    public String speaker() { return speaker; }
    public String prompt() { return prompt; }
    public float seconds() { return seconds; }
    public int defaultIndex() { return defaultIndex; }
    public int optionCount() { return options.size(); }

    public Option optionAt(int index) {
        if (options.isEmpty()) return new Option("Continue", null);
        return options.get(Math.max(0, Math.min(options.size() - 1, index)));
    }

    public List<Option> options() {
        return Collections.unmodifiableList(options);
    }
}
