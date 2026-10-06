package com.voxel.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Live speech captions: what a villager (or mob) says appears on screen as it
 * "speaks", revealed word by word and timed to the line. Speech is now played
 * from recorded clips — and often silently while the voice stack is warming
 * up — so the caption is the authoritative display of the spoken line.
 *
 * <p>Thread-safe: {@link #show} may be called from the logic thread, the
 * render thread, or the voice worker; {@link #tick} runs on the render thread.</p>
 */
public final class LiveCaptions {

    /** Immutable view of the currently displayed caption. */
    public static final class Snapshot {
        public final String speaker;
        public final String revealedText;
        public final float alpha;
        public final float progress;

        Snapshot(String speaker, String revealedText, float alpha, float progress) {
            this.speaker = speaker;
            this.revealedText = revealedText;
            this.alpha = alpha;
            this.progress = progress;
        }
    }

    private static final class Caption {
        final String speaker;
        final String text;
        final String[] words;
        /** Addon subtitle cue times (seconds, ascending); null = word reveal. */
        final float[] cueTimes;
        final String[] cueTexts;
        float revealSeconds;
        float elapsed;

        Caption(String speaker, String text, float revealSeconds,
                float[] cueTimes, String[] cueTexts) {
            this.speaker = speaker == null || speaker.isEmpty() ? "Villager" : speaker;
            this.text = text == null ? "" : text;
            String trimmed = this.text.trim();
            this.words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
            this.cueTimes = cueTimes;
            this.cueTexts = cueTexts;
            this.revealSeconds = Math.max(0.6f, revealSeconds);
        }

        float holdSeconds() {
            return 1.4f + words.length * 0.04f;
        }

        float fadeSeconds() {
            return 0.5f;
        }

        float totalSeconds() {
            return revealSeconds + holdSeconds() + fadeSeconds();
        }

        String revealed(float progress) {
            // Cue-timed captions: fragments appear exactly when the addon
            // subtitle cues say they do.
            if (cueTimes != null && cueTexts != null && cueTimes.length > 0) {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < cueTimes.length && i < cueTexts.length; i++) {
                    if (cueTimes[i] > elapsed) break;
                    if (out.length() > 0) out.append(' ');
                    out.append(cueTexts[i]);
                }
                return out.toString();
            }
            if (words.length == 0) return text;
            int shown = Math.max(1, (int) Math.ceil(progress * words.length));
            if (shown >= words.length) return text;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0) out.append(' ');
                out.append(words[i]);
            }
            return out.toString();
        }
    }

    private static final int MAX_QUEUED = 3;
    private static final List<Caption> QUEUE = new ArrayList<>();
    private static Caption current;
    private static float fadeAlpha;

    private LiveCaptions() {
    }

    /**
     * Show one spoken line. Duration is how long the reveal should take; use
     * {@link #estimateDuration} when only the text is known. A new line from
     * the same speaker replaces the visible one (rapid chatter).
     */
    public static synchronized void show(String speaker, String text, float durationSeconds) {
        show(speaker, text, durationSeconds, null, null);
    }

    /**
     * Show one clip transcript with the recording's own subtitle cues: each
     * fragment appears at its cue time. Cue times must already be scaled to
     * caption speed by the caller.
     */
    public static synchronized void show(String speaker, String text,
                                         float durationSeconds,
                                         float[] cueTimes, String[] cueTexts) {
        if (text == null || text.trim().isEmpty()) return;
        Caption caption = new Caption(speaker, text, durationSeconds, cueTimes, cueTexts);
        fadeAlpha = 1f;
        if (current != null && current.speaker.equals(caption.speaker)) {
            current = caption;
            return;
        }
        if (current != null) {
            QUEUE.add(caption);
            while (QUEUE.size() > MAX_QUEUED) {
                QUEUE.remove(0);
            }
        } else {
            current = caption;
        }
    }

    /** Voice playback started: sync the reveal to the clip's real duration. */
    public static synchronized void retimeCurrent(float durationSeconds) {
        if (current != null && durationSeconds > 0f
                && (current.cueTimes == null || current.cueTimes.length == 0)) {
            current.revealSeconds = Math.max(0.6f,
                    Math.min(durationSeconds, current.revealSeconds * 1.6f + 0.4f));
        }
    }

    /** Advance caption timers; call once per frame with the frame delta. */
    public static synchronized void tick(float dt) {
        if (current == null) {
            fadeAlpha = 0f;
            return;
        }
        current.elapsed += Math.max(0f, dt);
        float total = current.totalSeconds();
        if (current.elapsed >= total) {
            current = QUEUE.isEmpty() ? null : QUEUE.remove(0);
            return;
        }
        float remaining = total - current.elapsed;
        float fade = current.fadeSeconds();
        fadeAlpha = remaining < fade ? Math.max(0f, remaining / fade) : 1f;
    }

    /** Current caption state for the HUD, or null when nothing is speaking. */
    public static synchronized Snapshot current() {
        if (current == null) {
            return null;
        }
        float progress = Math.min(1f, current.elapsed / current.revealSeconds);
        return new Snapshot(current.speaker, current.revealed(progress), fadeAlpha, progress);
    }

    /** Estimate a natural reveal time for a line at the given speed. */
    public static float estimateDuration(String text, double speed) {
        int words = text == null || text.trim().isEmpty()
                ? 1 : text.trim().split("\\s+").length;
        double perSecond = 2.6 * Math.max(0.25, speed);
        float seconds = (float) (0.35 + words / perSecond);
        return Math.max(1.0f, Math.min(8.0f, seconds));
    }

    /** Drop everything immediately (scene cuts, death, menus). */
    public static synchronized void clear() {
        current = null;
        QUEUE.clear();
        fadeAlpha = 0f;
    }
}
