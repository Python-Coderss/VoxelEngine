package villager.voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Recorded-clip "speech": there is no synthesis anymore. A line is played as
 * the recorded clip whose transcript best matches its words (exact hit when
 * one exists), post-processed for speed, pitch, mood, and level. Unmatched
 * lines fall back to a vocalization, which is exactly the kind of confident
 * nonsense these villagers produce anyway.
 */
public final class ClipVoice implements AutoCloseable {

    public static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final float PEAK_CEILING = 0.85f;
    private static final int RECENT_MEMORY = 6;

    private final ClipLibrary library;
    private final Random rng;
    private final List<String> recent = new ArrayList<>();

    public ClipVoice(ClipLibrary library) {
        this(library, new Random(20261004L));
    }

    public ClipVoice(ClipLibrary library, Random rng) {
        if (library == null) {
            throw new IllegalArgumentException("library must not be null");
        }
        this.library = library;
        this.rng = rng == null ? new Random() : rng;
    }

    public ClipLibrary library() {
        return library;
    }

    /** Which clip would play for this line (selection only, no decoding). */
    public ClipIndex.Clip choose(String text) {
        ClipIndex.Clip clip = library.index().bestMatch(text, rng, recent);
        if (clip != null) {
            remember(clip.id);
        }
        return clip;
    }

    /**
     * "Render" one line: pick the best recorded clip and shape it to the
     * requested delivery. Never synthesizes anything.
     */
    public WavAudio render(String text, SpeechOptions options) throws IOException {
        ClipIndex.Clip clip = choose(text);
        if (clip == null) {
            throw new IOException("no voice clips available");
        }
        return render(clip, options);
    }

    /**
     * Play one specific clip (the one whose transcript the captions show) so
     * audio and captions can never drift apart.
     */
    public WavAudio render(ClipIndex.Clip clip, SpeechOptions options) throws IOException {
        if (clip == null) {
            throw new IllegalArgumentException("clip must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        WavAudio source = library.load(clip).resampled(DEFAULT_SAMPLE_RATE);
        float[] samples = source.samples.clone();

        // Playback shaping: effective speed plus a clamped pitch tilt. The
        // recorded voice is already the villager, so the old +8-semitone
        // register lift does not apply — pitch just flavors the delivery.
        double pitch = clamp(options.getPitchSemitones() + emotionPitch(options), -6.0, 6.0);
        double factor = options.getEffectiveSpeed() * Math.pow(2.0, -pitch / 12.0);
        if (Math.abs(factor - 1.0) > 1e-3) {
            int length = Math.max(1, (int) Math.round(samples.length / factor));
            samples = AudioDsp.resample(samples, length);
        }
        AudioDsp.applyToneTilt(samples, options.getEffectiveSpectralTilt());
        if (options.getSarcasm() > 0.0) {
            AudioDsp.applyGain(samples, 1.0 - options.getSarcasm() * 0.08);
        }
        if (options.isQuestion()) {
            AudioDsp.applyQuestionEnding(samples);
        }
        AudioDsp.applyGain(samples, options.getEffectiveVolume());
        AudioDsp.normalizePeak(samples, PEAK_CEILING);
        AudioDsp.fadeEdges(samples, Math.min(DEFAULT_SAMPLE_RATE / 80, samples.length / 5));
        return new WavAudio(DEFAULT_SAMPLE_RATE, samples);
    }

    private static double emotionPitch(SpeechOptions options) {
        String emotion = options.getEmotion();
        if ("happy".equals(emotion)) return 1.0;
        if ("sad".equals(emotion)) return -1.5;
        if ("angry".equals(emotion)) return 0.8;
        if ("scared".equals(emotion)) return 2.0;
        return 0.0;
    }

    private void remember(String id) {
        recent.add(id);
        while (recent.size() > RECENT_MEMORY) {
            recent.remove(0);
        }
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }

    @Override
    public void close() {
        library.close();
    }
}
