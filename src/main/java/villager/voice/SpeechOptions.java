package villager.voice;

/** Immutable per-line controls for the custom Java voice. */
public final class SpeechOptions {
    // Tone is now a delivery mood: -1 is serious, 0 is neutral, +1 is joking.
    // Keep the natural source mix conservative so RVC retains the villager body.
    public static final SpeechOptions DEFAULT = new SpeechOptions(
            1.0, 0.0, 1.0, 0.0, 0.60, "happy", 0.0, 0.0, false, true);

    private final double speed;
    private final double pitchSemitones;
    private final double volume;
    private final double tone;
    private final double naturalSourceMix;
    private final String emotion;
    private final double singing;
    private final double sarcasm;
    private final boolean question;
    private final boolean automaticQuestionDetection;

    public SpeechOptions() {
        this(1.0, 0.0, 1.0, 0.0, 0.60, "happy", 0.0, 0.0, false, true);
    }

    /** Backwards-compatible constructor for callers that only set speed/pitch. */
    public SpeechOptions(double speed, double pitchSemitones) {
        this(speed, pitchSemitones, 1.0, 0.0, 0.60, "happy", 0.0, 0.0, false, true);
    }

    /** Backwards-compatible constructor for the original editable profile. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix) {
        this(speed, pitchSemitones, volume, tone,
                Math.max(0.45, naturalSourceMix),
                "neutral", 0.0, 0.0, false, true);
    }

    /** Backwards-compatible complete profile before sarcasm/questions existed. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix, String emotion,
                         double singing) {
        this(speed, pitchSemitones, volume, tone, naturalSourceMix,
                emotion, singing, 0.0, false, true);
    }

    /** Complete profile with mood, sarcasm, and explicit question delivery. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix, String emotion,
                         double singing, double sarcasm, boolean question) {
        this(speed, pitchSemitones, volume, tone, naturalSourceMix, emotion,
                singing, sarcasm, question, false);
    }

    private SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix, String emotion,
                         double singing, double sarcasm, boolean question,
                         boolean automaticQuestionDetection) {
        requireFinitePositive("speed", speed);
        requireFinite("pitchSemitones", pitchSemitones);
        requireRange("volume", volume, 0.0, 2.0);
        requireRange("tone", tone, -1.0, 1.0);
        requireRange("naturalSourceMix", naturalSourceMix, 0.0, 0.6);
        requireRange("singing", singing, 0.0, 1.0);
        requireRange("sarcasm", sarcasm, 0.0, 1.0);
        this.speed = speed;
        this.pitchSemitones = pitchSemitones;
        this.volume = volume;
        this.tone = tone;
        this.naturalSourceMix = naturalSourceMix;
        this.emotion = normalizeEmotion(emotion);
        this.singing = singing;
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
    public double getNaturalSourceMix() { return naturalSourceMix; }
    public String getEmotion() { return emotion; }
    /** Singing expression from 0 (spoken) to 1 (strongly sung). */
    public double getSinging() { return singing; }
    /** Dry/sardonic delivery from 0 (sincere) to 1 (strong sarcasm). */
    public double getSarcasm() { return sarcasm; }
    /** Whether the line should use a rising interrogative ending. */
    public boolean isQuestion() { return question; }

    /** Whether punctuation may supply a question cue when the flag is omitted. */
    public boolean allowsAutomaticQuestionDetection() { return automaticQuestionDetection; }

    /** Return an equivalent profile with an explicit question flag. */
    public SpeechOptions withQuestion(boolean value) {
        return new SpeechOptions(speed, pitchSemitones, volume, tone, naturalSourceMix,
                emotion, singing, sarcasm, value, false);
    }

    /** Mark a metadata profile as allowing automatic question punctuation. */
    public SpeechOptions withAutomaticQuestionDetection() {
        return new SpeechOptions(speed, pitchSemitones, volume, tone, naturalSourceMix,
                emotion, singing, sarcasm, question, true);
    }

    /** Effective duration after emotion, mood, sarcasm, and singing adjustments. */
    public double getEffectiveSpeed() {
        double multiplier = 1.0;
        if ("happy".equals(emotion)) multiplier = 1.08;
        if ("sad".equals(emotion)) multiplier = 0.86;
        if ("angry".equals(emotion)) multiplier = 1.12;
        if ("scared".equals(emotion)) multiplier = 1.16;
        // Joking delivery is lighter/faster; serious delivery is measured.
        multiplier *= 1.0 - tone * 0.08;
        multiplier *= 1.0 - sarcasm * 0.08;
        if (singing > 0.0) multiplier *= 1.0 - singing * 0.12;
        return speed * multiplier;
    }

    /**
     * The villager timbre lives in Dan Lloyd's high register (TEAVSRP F0
     * median ~180 Hz per voice/corpus/analysis.txt), while neural bases speak
     * near 110 Hz. Without this lift the generator renders villager formants
     * over an octave-too-low excitation, which reads as hoarse and robotic.
     */
    public static final double VILLAGER_REGISTER_SEMITONES = 8.0;

    /** Effective RVC pitch offset after mood, emotion, sarcasm, and explicit pitch. */
    public double getEffectivePitchSemitones() {
        double offset = VILLAGER_REGISTER_SEMITONES + tone * 0.75;
        if ("happy".equals(emotion)) offset += 1.0;
        if ("sad".equals(emotion)) offset -= 1.5;
        if ("angry".equals(emotion)) offset += 0.8;
        if ("scared".equals(emotion)) offset += 2.0;
        // Sarcasm is deliberately a little deadpan and lowered.
        offset -= sarcasm * 0.8;
        return pitchSemitones + offset;
    }

    /** Effective spectral tilt derived from mood; tone itself is no longer EQ. */
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

    /** Singing and strong sarcasm avoid mixing a competing natural carrier. */
    public double getEffectiveNaturalSourceMix() {
        return singing > 0.0 ? 0.0 : naturalSourceMix * (1.0 - sarcasm * 0.35);
    }

    /** Stable text used as part of generated-audio cache keys. */
    public String cacheKey() {
        return String.format(java.util.Locale.ROOT,
                "v2;speed=%.6f;pitch=%.6f;volume=%.6f;tone=%.6f;natural=%.6f;emotion=%s;singing=%.6f;sarcasm=%.6f;question=%s",
                speed, pitchSemitones, volume, tone, naturalSourceMix, emotion,
                singing, sarcasm, question);
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
