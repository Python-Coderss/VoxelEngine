package villager.voice;

/** Immutable per-line controls for the custom Java voice. */
public final class SpeechOptions {
    // Neural default tuned toward the supplied TEAVSRP corpus: retain some of
    // the natural base waveform under RVC instead of producing a dry vocoder.
    public static final SpeechOptions DEFAULT = new SpeechOptions(
            1.0, 0.0, 1.0, -0.10, 0.36, "happy", 0.0);

    private final double speed;
    private final double pitchSemitones;
    private final double volume;
    private final double tone;
    private final double naturalSourceMix;
    private final String emotion;
    private final double singing;

    public SpeechOptions() {
        this(1.0, 0.0, 1.0, -0.10, 0.36, "happy", 0.0);
    }

    /** Backwards-compatible constructor for callers that only set speed/pitch. */
    public SpeechOptions(double speed, double pitchSemitones) {
        this(speed, pitchSemitones, 1.0, -0.10, 0.36, "happy", 0.0);
    }

    /** Backwards-compatible constructor for the original editable profile. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix) {
        this(speed, pitchSemitones, volume, tone, naturalSourceMix, "neutral", 0.0);
    }

    /** Complete voice profile, including emotional delivery and singing amount. */
    public SpeechOptions(double speed, double pitchSemitones, double volume,
                         double tone, double naturalSourceMix, String emotion,
                         double singing) {
        requireFinitePositive("speed", speed);
        requireFinite("pitchSemitones", pitchSemitones);
        requireRange("volume", volume, 0.0, 2.0);
        requireRange("tone", tone, -1.0, 1.0);
        requireRange("naturalSourceMix", naturalSourceMix, 0.0, 0.5);
        requireRange("singing", singing, 0.0, 1.0);
        this.speed = speed;
        this.pitchSemitones = pitchSemitones;
        this.volume = volume;
        this.tone = tone;
        this.naturalSourceMix = naturalSourceMix;
        this.emotion = normalizeEmotion(emotion);
        this.singing = singing;
    }

    public double getSpeed() {
        return speed;
    }

    public double getPitchSemitones() {
        return pitchSemitones;
    }

    /** Linear output gain: 1 is unchanged, 0 is silent, 2 is +6 dB. */
    public double getVolume() {
        return volume;
    }

    /** Tonal tilt: -1 is warmer/darker, 0 is neutral, +1 is brighter. */
    public double getTone() {
        return tone;
    }

    /** Fraction of the natural VITS waveform mixed under the RVC result. */
    public double getNaturalSourceMix() {
        return naturalSourceMix;
    }

    /** Delivery style applied on top of the explicit numeric controls. */
    public String getEmotion() {
        return emotion;
    }

    /** Singing expression from 0 (spoken) to 1 (strongly sung). */
    public double getSinging() {
        return singing;
    }

    /** Effective duration multiplier after applying the selected emotion. */
    public double getEffectiveSpeed() {
        double multiplier = 1.0;
        if ("happy".equals(emotion)) multiplier = 1.08;
        if ("sad".equals(emotion)) multiplier = 0.86;
        if ("angry".equals(emotion)) multiplier = 1.12;
        if ("scared".equals(emotion)) multiplier = 1.16;
        if (singing > 0.0) multiplier *= 1.0 - singing * 0.12;
        return speed * multiplier;
    }

    /** Effective RVC pitch offset after applying the selected emotion. */
    public double getEffectivePitchSemitones() {
        double offset = 0.0;
        if ("happy".equals(emotion)) offset = 1.0;
        if ("sad".equals(emotion)) offset = -1.5;
        if ("angry".equals(emotion)) offset = 0.8;
        if ("scared".equals(emotion)) offset = 2.0;
        return pitchSemitones + offset;
    }

    /** Effective spectral tilt after applying the selected emotion. */
    public double getEffectiveTone() {
        double offset = 0.0;
        if ("happy".equals(emotion)) offset = 0.12;
        if ("sad".equals(emotion)) offset = -0.16;
        if ("angry".equals(emotion)) offset = 0.20;
        if ("scared".equals(emotion)) offset = 0.10;
        return Math.max(-1.0, Math.min(1.0, tone + offset));
    }

    /** Effective loudness after applying the selected emotional intensity. */
    public double getEffectiveVolume() {
        double multiplier = 1.0;
        if ("happy".equals(emotion)) multiplier = 1.05;
        if ("sad".equals(emotion)) multiplier = 0.84;
        if ("angry".equals(emotion)) multiplier = 1.12;
        if ("scared".equals(emotion)) multiplier = 1.04;
        return Math.max(0.0, Math.min(2.0, volume * multiplier));
    }

    /** Singing deliberately avoids mixing the spoken VITS waveform underneath. */
    public double getEffectiveNaturalSourceMix() {
        return singing > 0.0 ? 0.0 : naturalSourceMix;
    }

    /** Stable text used as part of generated-audio cache keys. */
    public String cacheKey() {
        return String.format(java.util.Locale.ROOT,
                "speed=%.6f;pitch=%.6f;volume=%.6f;tone=%.6f;natural=%.6f;emotion=%s;singing=%.6f",
                speed, pitchSemitones, volume, tone, naturalSourceMix, emotion, singing);
    }

    private static String normalizeEmotion(String value) {
        String normalized = value == null ? "neutral" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = "neutral";
        }
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
