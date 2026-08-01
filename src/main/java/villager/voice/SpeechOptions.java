package villager.voice;

/** Immutable per-line controls for the custom Java voice. */
public final class SpeechOptions {
    public static final SpeechOptions DEFAULT = new SpeechOptions(1.0, 0.0);

    private final double speed;
    private final double pitchSemitones;

    public SpeechOptions() {
        this(1.0, 0.0);
    }

    public SpeechOptions(double speed, double pitchSemitones) {
        if (speed <= 0.0 || Double.isNaN(speed) || Double.isInfinite(speed)) {
            throw new IllegalArgumentException("speed must be finite and greater than zero");
        }
        if (Double.isNaN(pitchSemitones) || Double.isInfinite(pitchSemitones)) {
            throw new IllegalArgumentException("pitchSemitones must be finite");
        }
        this.speed = speed;
        this.pitchSemitones = pitchSemitones;
    }

    public double getSpeed() {
        return speed;
    }

    public double getPitchSemitones() {
        return pitchSemitones;
    }
}
