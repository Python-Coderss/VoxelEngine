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
        float[] cueTimes;
        final String[] cueTexts;
        /** Per-word start times measured from the audio that is playing. */
        float[] wordTimes;
        /** True until the audio actually starts (decode/queue delay). */
        boolean waitForAudio;
        float waited;
        float revealSeconds;
        float elapsed;

        Caption(String speaker, String text, float revealSeconds,
                float[] cueTimes, String[] cueTexts, boolean waitForAudio) {
            this.speaker = speaker == null || speaker.isEmpty() ? "Villager" : speaker;
            this.text = text == null ? "" : text;
            String trimmed = this.text.trim();
            this.words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
            this.cueTimes = cueTimes;
            this.cueTexts = cueTexts;
            this.waitForAudio = waitForAudio;
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
            if (waitForAudio) return "";
            // Measured word timing: the word appears exactly when the voice
            // reaches it, including across the recording's pauses.
            if (wordTimes != null && wordTimes.length > 0 && words.length > 0) {
                int shown = 0;
                for (int i = 0; i < wordTimes.length && i < words.length; i++) {
                    if (wordTimes[i] > elapsed) break;
                    shown++;
                }
                if (shown <= 0) return "";
                if (shown >= words.length) return text;
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < shown; i++) {
                    if (i > 0) out.append(' ');
                    out.append(words[i]);
                }
                return out.toString();
            }
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
    /** How long a caption waits for audio before revealing on its estimate. */
    private static final float AUDIO_WAIT_TIMEOUT = 2.5f;
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
        show(speaker, text, durationSeconds, null, null, false, null);
    }

    /**
     * Show one clip transcript with the recording's own subtitle cues: each
     * fragment appears at its cue time. Cue times must already be scaled to
     * caption speed by the caller.
     */
    public static synchronized void show(String speaker, String text,
                                         float durationSeconds,
                                         float[] cueTimes, String[] cueTexts) {
        show(speaker, text, durationSeconds, cueTimes, cueTexts, false, null);
    }

    /**
     * Show a line whose audio is about to play. The reveal holds at the first
     * word until {@link #beginAudio} reports the real start and timings, so a
     * slow decode can never leave the caption running ahead of the voice.
     */
    public static synchronized void showForAudio(String speaker, String text,
                                                 float durationSeconds) {
        show(speaker, text, durationSeconds, null, null, true, null);
    }

    /** Show a clip line that waits for its recording (cue-timed). */
    public static synchronized void showForAudio(String speaker, String text,
                                                 float durationSeconds,
                                                 float[] cueTimes, String[] cueTexts) {
        show(speaker, text, durationSeconds, cueTimes, cueTexts, true, null);
    }

    /**
     * Show a line whose per-word timings were measured offline from the same
     * recording (see {@link ClipWordTiming}); the reveal still waits for
     * playback to actually start.
     */
    public static synchronized void showForAudio(String speaker, String text,
                                                 float durationSeconds,
                                                 float[] wordTimes) {
        show(speaker, text, durationSeconds, null, null, true, wordTimes);
    }

    private static synchronized void show(String speaker, String text,
                                          float durationSeconds,
                                          float[] cueTimes, String[] cueTexts,
                                          boolean waitForAudio, float[] wordTimes) {
        if (text == null || text.trim().isEmpty()) return;
        Caption caption = new Caption(speaker, text, durationSeconds, cueTimes,
                cueTexts, waitForAudio);
        if (wordTimes != null && wordTimes.length == caption.words.length) {
            caption.wordTimes = wordTimes;
        }
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

    /** Voice playback started: sync the reveal to the real duration. */
    public static synchronized void retimeCurrent(float durationSeconds) {
        beginAudio(durationSeconds, null);
    }

    /**
     * The audio is starting now. Restarts the reveal clock from zero and, when
     * {@code wordTimes} is known, reveals each word at its measured moment in
     * the recording. Ignored when the visible caption is not waiting for audio
     * (caption-only lines, or a caption that has already started).
     */
    public static synchronized void beginAudio(float durationSeconds,
                                               float[] wordTimes) {
        if (current == null || !current.waitForAudio) return;
        current.waitForAudio = false;
        current.elapsed = 0f;
        current.waited = 0f;
        if (durationSeconds > 0f) {
            current.revealSeconds = Math.max(0.6f, durationSeconds);
        }
        if (wordTimes != null && wordTimes.length > 0) {
            current.wordTimes = wordTimes;
        }
    }

    /** Advance caption timers; call once per frame with the frame delta. */
    public static synchronized void tick(float dt) {
        if (current == null) {
            fadeAlpha = 0f;
            return;
        }
        if (current.waitForAudio) {
            // No audio arrived (no device, failed decode, or a caption that is
            // only meant to be read): reveal on the estimate instead of
            // leaving the line stuck on its first word.
            current.waited += Math.max(0f, dt);
            if (current.waited < AUDIO_WAIT_TIMEOUT) {
                fadeAlpha = 1f;
                return;
            }
            current.waitForAudio = false;
            current.elapsed = 0f;
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

    /**
     * Word count of the caption that is waiting for its audio, or 0. The audio
     * queue uses it to time words inside a PCM buffer it did not author (a
     * local TTS render) without re-reading the text.
     */
    public static synchronized int pendingWordCount() {
        if (current == null) return 0;
        return current.words.length;
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
