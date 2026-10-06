package villager.voice;

/**
 * Immutable per-line playback controls for the recorded villager voice.
 *
 * <p>Only controls a recorded clip can actually honor are kept: speed, pitch,
 * volume, mood/sarcasm coloring, and the interrogative ending. Everything the
 * deleted synthesis stack needed (RVC retrieval weight, singing expression,
 * the model's register lift) is gone with it.</p>
 */
public final class SpeechOptions {
    // Tone is a delivery mood: -1 is serious, 0 is neutral, +1 is joking.
    public static final SpeechOptions DEFAULT = new SpeechOptions(
            1.0, 0.0, 1.0, 0.0, "happy", 0.0, false, true);

    private final double speed;
    private final double pitchSemitones;
    private final double volume;
    private final double tone;
    private final String emotion;
    private final double sarcasm;
    private final boolean question;
    private final boolean automaticQuestionDetection;

    public SpeechOptions() {
        this(1.0, 0.0, 1.0, 0.0, "happy", 0.0, false, true);
    }

    /** Backwards-compatible constructor for callers that only set speed/pitch. */
    public SpeechOptions(double speed, double pitchSemitones) {
        this(speed, pitchSemitones, 1.0, 0.0, "happy", 0.0, false, true);
    }

    /** Speed, pitch, volume and mood only. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone) {
        this(speed, pitchSemitones, volume, tone, "neutral", 0.0, false, true);
    }

    /** Complete profile with mood, sarcasm, and explicit question delivery. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, String emotion, double sarcasm,
                         boolean question) {
        this(speed, pitchSemitones, volume, tone, emotion, sarcasm, question, false);
    }

    private SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, String emotion, double sarcasm,
                         boolean question, boolean automaticQuestionDetection) {
        requireFinitePositive("speed", speed);
        requireFinite("pitchSemitones", pitchSemitones);
        requireRange("volume", volume, 0.0, 2.0);
        requireRange("tone", tone, -1.0, 1.0);
        requireRange("sarcasm", sarcasm, 0.0, 1.0);
        this.speed = speed;
        this.pitchSemitones = pitchSemitones;
        this.volume = volume;
        this.tone = tone;
        this.emotion = normalizeEmotion(emotion);
        this.sarcasm = sarcasm;
        this.question = question;
        this.automaticQuestionDetection = automaticQuestionDetection;
    }

    public double getSpeed() { return speed; }
    public double getPitchSemitones() { return pitchSemitones; }
    /** Linear output gain: 1 is unchanged, 0 is silent, 2 is +6 dB. */
    public double getVolume() { return volume; }
    /** Delivery mood: -1 serious, 0 neutral, +1 joking. */
    public double getTone() { return tone; }
    public String getEmotion() { return emotion; }
    /** Dry/sardonic delivery from 0 (sincere) to 1 (strong sarcasm). */
    public double getSarcasm() { return sarcasm; }
    /** Whether the line should use a rising interrogative ending. */
    public boolean isQuestion() { return question; }

    /** Whether punctuation may supply a question cue when the flag is omitted. */
    public boolean allowsAutomaticQuestionDetection() { return automaticQuestionDetection; }

    /** Return an equivalent profile with an explicit question flag. */
    public SpeechOptions withQuestion(boolean value) {
        return new SpeechOptions(speed, pitchSemitones, volume, tone,
                emotion, sarcasm, value, false);
    }

    /** Mark a metadata profile as allowing automatic question punctuation. */
    public SpeechOptions withAutomaticQuestionDetection() {
        return new SpeechOptions(speed, pitchSemitones, volume, tone,
                emotion, sarcasm, question, true);
    }

    /** Effective duration after emotion, mood, and sarcasm adjustments. */
    public double getEffectiveSpeed() {
        double multiplier = 1.0;
        if ("happy".equals(emotion)) multiplier = 1.08;
        if ("sad".equals(emotion)) multiplier = 0.86;
        if ("angry".equals(emotion)) multiplier = 1.12;
        if ("scared".equals(emotion)) multiplier = 1.16;
        // Joking delivery is lighter/faster; serious delivery is measured.
        multiplier *= 1.0 - tone * 0.08;
        multiplier *= 1.0 - sarcasm * 0.08;
        return speed * multiplier;
    }

    /** Effective spectral tilt derived from mood; tone itself is not EQ. */
    public double getEffectiveSpectralTilt() {
        double tilt = tone * 0.50;
        if ("happy".equals(emotion)) tilt += 0.12;
        if ("sad".equals(emotion)) tilt -= 0.16;
        if ("angry".equals(emotion)) tilt += 0.20;
        if ("scared".equals(emotion)) tilt += 0.10;
        tilt -= sarcasm * 0.12;
        return Math.max(-1.0, Math.min(1.0, tilt));
    }

    /** Backwards-compatible name now returns the effective mood value. */
    public double getEffectiveTone() {
        double mood = tone;
        if ("happy".equals(emotion)) mood += 0.10;
        if ("sad".equals(emotion)) mood -= 0.10;
        return Math.max(-1.0, Math.min(1.0, mood));
    }

    public double getEffectiveVolume() {
        double multiplier = 1.0;
        if ("happy".equals(emotion)) multiplier = 1.05;
        if ("sad".equals(emotion)) multiplier = 0.84;
        if ("angry".equals(emotion)) multiplier = 1.12;
        if ("scared".equals(emotion)) multiplier = 1.04;
        return Math.max(0.0, Math.min(2.0, volume * multiplier));
    }

    /** Stable text used as part of generated-audio cache keys. */
    public String cacheKey() {
        return String.format(java.util.Locale.ROOT,
                "v4;speed=%.6f;pitch=%.6f;volume=%.6f;tone=%.6f;emotion=%s;sarcasm=%.6f;question=%s",
                speed, pitchSemitones, volume, tone, emotion,
                sarcasm, question);
    }

    /** Conservative automatic punctuation rule used when metadata omits question. */
    public static boolean looksLikeQuestion(String text) {
        if (text == null) return false;
        String value = text.trim();
        while (value.endsWith("\"") || value.endsWith("'") || value.endsWith(")")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.endsWith("?");
    }

    private static String normalizeEmotion(String value) {
        String normalized = value == null ? "neutral" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) normalized = "neutral";
        if (!normalized.equals("neutral") && !normalized.equals("happy")
                && !normalized.equals("sad") && !normalized.equals("angry")
                && !normalized.equals("scared")) {
            throw new IllegalArgumentException(
                    "emotion must be neutral, happy, sad, angry, or scared");
        }
        return normalized;
    }

    private static void requireFinitePositive(String name, double value) {
        if (value <= 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }

    private static void requireFinite(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireRange(String name, double value, double minimum, double maximum) {
        if (Double.isNaN(value) || Double.isInfinite(value)
                || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
