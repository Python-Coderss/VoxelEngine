package com.voxel.ai.speech;

import com.voxel.audio.LiveCaptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Caption-only speech for entities that have no voice.
 *
 * <p>The ancient builders are the civilization that raised the world before the
 * extinction. They are deliberately <b>not voiced</b>: their lines never touch
 * {@link com.voxel.audio.VillagerAudioManager}, recorded clips, or OpenAL. What
 * they "say" is still shown to the player, word by word, through the shared
 * {@link LiveCaptions} overlay — the same one villagers use.</p>
 *
 * <p>Lines are rate-limited per speaker id so a crowd of builders cannot
 * monopolize the caption queue (which caps at three entries).</p>
 */
public final class CaptionSpeech {

    /** Minimum time between two lines from the same speaker. */
    private static final long MIN_INTERVAL_MILLIS = 7000L;

    private static final Map<Integer, Long> lastSpoken = new HashMap<>();

    private CaptionSpeech() {
    }

    /** Show a line with a reveal time estimated from its length. */
    public static boolean say(int speakerId, String speaker, String line) {
        if (line == null) return false;
        return say(speakerId, speaker, line, LiveCaptions.estimateDuration(line, 1.0));
    }

    /**
     * Show a line for a fixed reveal time.
     *
     * @return true when the line was published (the rate limit allowed it)
     */
    public static boolean say(int speakerId, String speaker, String line,
                              float durationSeconds) {
        if (line == null || line.trim().isEmpty()) return false;
        if (!allow(speakerId)) return false;
        LiveCaptions.show(speaker == null || speaker.isEmpty() ? "Builder" : speaker,
                line.trim(), durationSeconds);
        return true;
    }

    /** True while a new line from this speaker would be rate-limited. */
    public static boolean isRateLimited(int speakerId) {
        Long last = lastSpoken.get(speakerId);
        return last != null && System.currentTimeMillis() - last < MIN_INTERVAL_MILLIS;
    }

    private static boolean allow(int speakerId) {
        long now = System.currentTimeMillis();
        Long last = lastSpoken.get(speakerId);
        if (last != null && now - last < MIN_INTERVAL_MILLIS) return false;
        lastSpoken.put(speakerId, now);
        return true;
    }

    /** Clears the per-speaker rate limits (tests / fresh scenes). */
    public static void resetForTest() {
        lastSpoken.clear();
    }
}
