package com.voxel.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent memory for MCSM-style decisions: named flags plus a short,
 * player-readable recap of the choices they made.
 *
 * <p>The cinematic choice overlay writes here when an option is picked
 * ({@code choice.<id> = <label>}); gameplay code reads flags back with
 * {@link #is(String, String)} so a later scene, villager line or reward can
 * react to what the player said earlier. The state is saved in {@code level.dat}
 * (flags + recap) and restored by {@code WorldSaveManager}.</p>
 *
 * <p>Thread-safe: choices resolve on the logic thread but the recap is read by
 * the HUD.</p>
 */
public final class StoryState {

    /** Keep the recap short — it is a memory aid, not a transcript. */
    private static final int MAX_CHOICES = 12;

    private final Map<String, String> flags = new LinkedHashMap<String, String>();
    private final List<String> choices = new ArrayList<String>();

    /** Record a flag value (replacing any previous value for the key). */
    public synchronized void set(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        flags.put(key, value == null ? "" : value);
    }

    /** Flag value, or the supplied fallback when unset. */
    public synchronized String get(String key, String fallback) {
        String value = flags.get(key);
        return value == null ? fallback : value;
    }

    /** True when the flag exists (any value). */
    public synchronized boolean has(String key) {
        return flags.containsKey(key);
    }

    /** True when the flag is set to {@code value} (case-insensitive). */
    public synchronized boolean is(String key, String value) {
        String stored = flags.get(key);
        return stored != null && value != null && stored.equalsIgnoreCase(value);
    }

    /**
     * Append a decision to the recap. Newest last; the oldest entries fall off
     * once {@link #MAX_CHOICES} is reached.
     */
    public synchronized void recordChoice(String prompt, String option) {
        String promptText = prompt == null ? "" : prompt.trim();
        String optionText = option == null ? "" : option.trim();
        if (optionText.isEmpty()) return;
        StringBuilder line = new StringBuilder();
        if (!promptText.isEmpty()) line.append(promptText).append(" — ");
        line.append(optionText);
        choices.add(line.toString());
        while (choices.size() > MAX_CHOICES) choices.remove(0);
    }

    /** Immutable snapshot of the flags (insertion ordered). */
    public synchronized Map<String, String> flags() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(flags));
    }

    /** Immutable snapshot of the choice recap, oldest first. */
    public synchronized List<String> choices() {
        return Collections.unmodifiableList(new ArrayList<String>(choices));
    }

    /** Replace the whole state (save load). Null entries are ignored. */
    public synchronized void restore(Map<String, String> savedFlags, List<String> savedChoices) {
        flags.clear();
        choices.clear();
        if (savedFlags != null) {
            for (Map.Entry<String, String> entry : savedFlags.entrySet()) {
                if (entry.getKey() != null) flags.put(entry.getKey(), entry.getValue());
            }
        }
        if (savedChoices != null) {
            for (String line : savedChoices) {
                if (line != null && !line.trim().isEmpty()) choices.add(line);
            }
            while (choices.size() > MAX_CHOICES) choices.remove(0);
        }
    }

    /** Forget everything (new world). */
    public synchronized void clear() {
        flags.clear();
        choices.clear();
    }
}
