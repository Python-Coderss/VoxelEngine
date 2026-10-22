package com.voxel.audio;

/**
 * Words-in-the-air timing: where each word of a line actually lands in the
 * audio that is about to play.
 *
 * <p>Captions used to reveal on an estimate (or on the add-on's subtitle cues,
 * which are phrase-level and were scaled by speed alone), so the text ran ahead
 * of the voice whenever the renderer decoded late or pitched the clip. This
 * class reads the real PCM and finds the pauses: speech runs are detected from
 * frame energy, short gaps are bridged, and the words are distributed across
 * the voiced stretches by syllable weight. The result is a start time per word,
 * in seconds from the first sample, that follows the recording's own rhythm —
 * no transcript, no model, no network.</p>
 *
 * <p>Pure math over a mono sample array: no OpenAL, no files, so it is unit
 * tested against synthetic bursts with deliberate silence.</p>
 */
public final class CaptionTiming {

    /** Analysis hop. 20 ms is short enough for syllables and cheap enough per clip. */
    public static final float FRAME_SECONDS = 0.02f;

    /** Frames of sound kept after energy drops (consonant tails, breath). */
    private static final int HANGOVER_FRAMES = 5;

    /** Gaps shorter than this are part of the same phrase, not a pause. */
    private static final int BRIDGE_FRAMES = 8;

    /** Runs shorter than this are clicks, not speech. */
    private static final int MIN_RUN_FRAMES = 4;

    /** Absolute floor (-42 dBFS) so a silent recording yields no segments. */
    private static final float MIN_RMS = 0.008f;

    /** A run must also clear this fraction of the loudest frame. */
    private static final float PEAK_FRACTION = 0.09f;

    private CaptionTiming() {
    }

    /**
     * Start time of every word, in seconds from the start of the audio.
     *
     * @return one ascending start time per word, or null when the audio has no
     *         usable speech structure (caller falls back to its estimate)
     */
    public static float[] wordTimes(String text, float[] mono, int sampleRate) {
        String[] words = split(text);
        if (words.length == 0) return null;
        float[] segments = voicedSegments(mono, sampleRate);
        if (segments == null || segments.length < 2) return null;
        int[] weights = new int[words.length];
        for (int i = 0; i < words.length; i++) weights[i] = syllables(words[i]);
        return distribute(segments, weights);
    }

    /** Word count only (dynamic lines know their text before their audio). */
    public static float[] wordTimes(int wordCount, float[] mono, int sampleRate) {
        if (wordCount <= 0) return null;
        float[] segments = voicedSegments(mono, sampleRate);
        if (segments == null || segments.length < 2) return null;
        int[] weights = new int[wordCount];
        for (int i = 0; i < weights.length; i++) weights[i] = 1;
        return distribute(segments, weights);
    }

    /**
     * Voiced stretches as flat {@code [start, end, ...]} seconds, with short
     * gaps bridged and clicks removed.
     */
    public static float[] voicedSegments(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return null;
        int frame = Math.max(1, Math.round(sampleRate * FRAME_SECONDS));
        int frames = mono.length / frame;
        if (frames <= 0) return null;
        float[] rms = new float[frames];
        float peak = 0f;
        for (int f = 0; f < frames; f++) {
            double sum = 0.0;
            int base = f * frame;
            for (int i = 0; i < frame; i++) {
                float value = mono[base + i];
                sum += value * value;
            }
            rms[f] = (float) Math.sqrt(sum / frame);
            if (rms[f] > peak) peak = rms[f];
        }
        if (peak < MIN_RMS) return null;
        float threshold = Math.max(MIN_RMS, peak * PEAK_FRACTION);
        boolean[] voiced = new boolean[frames];
        for (int f = 0; f < frames; f++) {
            if (rms[f] >= threshold) {
                int from = Math.max(0, f - 1);
                int to = Math.min(frames - 1, f + HANGOVER_FRAMES);
                for (int v = from; v <= to; v++) voiced[v] = true;
            }
        }
        float[] runs = new float[frames * 2];
        int count = 0;
        int f = 0;
        while (f < frames) {
            if (!voiced[f]) {
                f++;
                continue;
            }
            int start = f;
            while (f < frames && voiced[f]) f++;
            if (f - start >= MIN_RUN_FRAMES) {
                runs[count++] = start * FRAME_SECONDS;
                runs[count++] = f * FRAME_SECONDS;
            }
        }
        if (count == 0) return null;
        float[] merged = new float[count];
        int mergedCount = 0;
        for (int i = 0; i < count; i += 2) {
            float start = runs[i];
            float end = runs[i + 1];
            if (mergedCount >= 2) {
                float previousEnd = merged[mergedCount - 1];
                if (start - previousEnd <= BRIDGE_FRAMES * FRAME_SECONDS) {
                    merged[mergedCount - 1] = end;
                    continue;
                }
            }
            merged[mergedCount++] = start;
            merged[mergedCount++] = end;
        }
        float[] trimmed = new float[mergedCount];
        System.arraycopy(merged, 0, trimmed, 0, mergedCount);
        return trimmed;
    }

    /** Spread the words over the voiced stretches by syllable weight. */
    private static float[] distribute(float[] segments, int[] weights) {
        float voicedSeconds = 0f;
        for (int i = 1; i < segments.length; i += 2) {
            voicedSeconds += Math.max(0f, segments[i] - segments[i - 1]);
        }
        if (voicedSeconds <= 0f) return null;
        float total = 0f;
        for (int weight : weights) total += Math.max(1, weight);
        float[] starts = new float[weights.length];
        float cumulative = 0f;
        int segment = 0;
        for (int w = 0; w < weights.length; w++) {
            float fraction = cumulative / total;
            float target = fraction * voicedSeconds;
            while (segment + 2 < segments.length) {
                float length = segments[segment + 1] - segments[segment];
                if (target <= length) break;
                target -= length;
                segment += 2;
            }
            if (segment + 1 >= segments.length) segment = segments.length - 2;
            float start = segments[segment] + Math.max(0f, target);
            float segmentEnd = segments[segment + 1];
            if (start > segmentEnd) start = segmentEnd;
            if (w > 0 && start < starts[w - 1]) start = starts[w - 1];
            starts[w] = start;
            cumulative += Math.max(1, weights[w]);
        }
        return starts;
    }

    private static String[] split(String text) {
        if (text == null || text.trim().isEmpty()) return new String[0];
        return text.trim().split("\\s+");
    }

    /** Syllable-ish weight from vowel groups, so longer words take longer. */
    static int syllables(String word) {
        if (word == null) return 1;
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        int groups = 0;
        boolean inVowel = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean vowel = c == 'a' || c == 'e' || c == 'i' || c == 'o'
                    || c == 'u' || c == 'y';
            if (vowel && !inVowel) groups++;
            inVowel = vowel;
        }
        if (groups == 0) groups = 1;
        if (lower.length() > 3 && lower.endsWith("e") && groups > 1) groups--;
        return groups;
    }
}
