package villager.voice;

import java.util.List;
import java.util.Locale;

/**
 * Per-word emotion state snapshot for {@link KokoroVoice}.
 *
 * <p>Each snapshot carries an emotion plus speed, pitch, volume, and tone for
 * one word of a line. When rendering, words are grouped into segments at
 * emotion-change boundaries; each segment is synthesized as one Kokoro call
 * (never mid-word, which is what caused voice cracks in the per-syllable
 * renderer), run through the full RVC pipeline, and crossfade-blended with its
 * neighbors. A line with a single uniform state is one Kokoro call —
 * bit-identical to the original kokoro samples.
 *
 * <h3>JSON preset format</h3>
 * <pre>{@code
 * {
 *   "line": "I am haggling you.",
 *   "snapshots": [
 *     {"text": "I",        "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.0},
 *     {"text": "am",       "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.1},
 *     {"text": "haggling", "emotion": "happy",   "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.3},
 *     {"text": "you",      "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.0}
 *   ]
 * }
 * }</pre>
 *
 * <p>The {@code text} field matches a word of the line (case-insensitive,
 * trailing punctuation ignored). Unmatched words keep the previous snapshot's
 * state; the first word defaults to "neutral".
 */
public final class SyllableSnapshot {
    /** Word text to match, case-insensitive (trailing punctuation ignored). */
    public final String text;
    /** One of neutral, happy, sad, angry, scared. */
    public final String emotion;
    /** Duration multiplier: 1.0 is normal, higher is slower. */
    public final double speed;
    /** Extra RVC pitch offset in semitones. */
    public final double pitch;
    /** Linear output gain: 1.0 is unchanged. */
    public final double volume;
    /** Delivery mood: -1 serious to +1 joking. */
    public final double tone;

    public SyllableSnapshot(String text, String emotion, double speed, double pitch,
                            double volume, double tone) {
        this.text = text;
        this.emotion = emotion == null ? "neutral" : emotion;
        this.speed = speed;
        this.pitch = pitch;
        this.volume = volume;
        this.tone = tone;
    }

    /**
     * Resolve the effective state for one word. The best-matching snapshot
     * wins (exact match &gt; prefix/suffix &gt; containment); no match keeps
     * the previous state so unspecified words ride along with their context.
     */
    public static SnapshotState resolveForWord(List<SyllableSnapshot> snapshots,
                                               String word, SnapshotState previous) {
        if (snapshots == null || snapshots.isEmpty()) {
            return previous != null ? previous : defaultState();
        }
        String w = word.toLowerCase(Locale.ROOT)
                .replaceAll("[.,!?\\u2026:;\\\"\\u201d']+$", "");
        SyllableSnapshot best = null;
        int bestScore = 0;
        for (SyllableSnapshot snap : snapshots) {
            String s = snap.text.toLowerCase(Locale.ROOT);
            int score = matchScore(s, w);
            if (score > bestScore) {
                bestScore = score;
                best = snap;
            }
        }
        if (best == null) {
            return previous != null ? previous : defaultState();
        }
        return new SnapshotState(best.emotion, best.speed, best.pitch,
                best.volume, best.tone);
    }

    /** Match quality: exact = 100, prefix/suffix scaled by length, containment halved. */
    private static int matchScore(String snapText, String wordText) {
        if (snapText.equals(wordText)) return 100;
        if (wordText.startsWith(snapText)) return snapText.length() * 10;
        if (wordText.endsWith(snapText)) return snapText.length() * 10;
        if (wordText.contains(snapText)) return snapText.length() * 5;
        return 0;
    }

    public static SnapshotState defaultState() {
        return new SnapshotState("neutral", 1.0, 0.0, 1.0, 0.0);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "Snapshot{%s e=%s s=%.2f p=%.1f v=%.2f t=%.2f}",
                text, emotion, speed, pitch, volume, tone);
    }

    /** Resolved state for one word (or a run of words) after matching. */
    public static final class SnapshotState {
        public final String emotion;
        public final double speed;
        public final double pitch;
        public final double volume;
        public final double tone;

        SnapshotState(String emotion, double speed, double pitch,
                      double volume, double tone) {
            this.emotion = emotion;
            this.speed = speed;
            this.pitch = pitch;
            this.volume = volume;
            this.tone = tone;
        }

        /** True when two words can share one Kokoro segment (no splice). */
        public boolean sameAs(SnapshotState other) {
            return other != null
                    && emotion.equals(other.emotion)
                    && Math.abs(speed - other.speed) < 1e-9
                    && Math.abs(pitch - other.pitch) < 1e-9
                    && Math.abs(volume - other.volume) < 1e-9
                    && Math.abs(tone - other.tone) < 1e-9;
        }

        /** SpeechOptions for this state through the standard pipeline. */
        public SpeechOptions toSpeechOptions() {
            return new SpeechOptions(speed, pitch, volume, tone,
                    0.36, emotion, 0.0, 0.0, false);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "State{e=%s s=%.2f p=%.1f v=%.2f t=%.2f}",
                    emotion, speed, pitch, volume, tone);
        }
    }
}
