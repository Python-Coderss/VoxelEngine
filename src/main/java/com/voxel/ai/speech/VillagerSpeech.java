package com.voxel.ai.speech;

import com.voxel.audio.VillagerAudioManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Static bridge letting AI brains trigger villager speech without owning the
 * audio stack. Bound once from Main after GameContext wiring; unbound = silent
 * no-op (safe in tests and headless runs). Rate-limits per villager id.
 */
public final class VillagerSpeech {

    private static final long MIN_INTERVAL_MILLIS = 4000L;

    private static volatile VillagerAudioManager audio;
    private static final Map<Integer, Long> lastSpoken = new HashMap<>();

    private VillagerSpeech() {
    }

    public static void bind(VillagerAudioManager manager) {
        audio = manager;
    }

    public static void unbind() {
        audio = null;
    }

    public static boolean isBound() {
        return audio != null;
    }

    /** @return true when the line was actually queued (rate-limit allows). */
    public static boolean say(int villagerId, String line) {
        if (audio == null || line == null || line.trim().isEmpty()) return false;
        long now = System.currentTimeMillis();
        Long last = lastSpoken.get(villagerId);
        if (last != null && now - last < MIN_INTERVAL_MILLIS) return false;
        lastSpoken.put(villagerId, now);
        audio.requestSpeech(line.trim());
        return true;
    }
}
