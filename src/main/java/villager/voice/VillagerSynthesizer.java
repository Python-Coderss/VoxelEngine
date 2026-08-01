package villager.voice;

import java.nio.file.Path;

/**
 * Dynamic custom villager voice for the game runtime.
 *
 * The runtime never uses FreeTTS, Kevin, Python, or a subprocess. It generates
 * base speech with the bundled Piper/VITS ONNX model and converts that audio
 * through the exported villager RVC model using Java ONNX Runtime.
 */
public final class VillagerSynthesizer implements AutoCloseable {
    public static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final float PEAK_CEILING = 0.85f;

    private final BaseTts baseTts;
    private final CustomRvcModel customVoice;

    public VillagerSynthesizer() throws Exception {
        this(ModelBundle.defaultBundle());
    }

    public VillagerSynthesizer(Path modelDirectory) throws Exception {
        this(ModelBundle.from(modelDirectory));
    }

    private VillagerSynthesizer(ModelBundle bundle) throws Exception {
        baseTts = new BaseTts(bundle.directory());
        try {
            customVoice = new CustomRvcModel(
                    bundle.path("vec-768-layer-12.onnx"),
                    bundle.path("rvc-villager.onnx"));
        } catch (Exception e) {
            baseTts.close();
            throw e;
        }
    }

    public synchronized WavAudio render(String text, double speed, double pitchSemitones)
            throws Exception {
        WavAudio base = baseTts.synthesize(text, speed);
        WavAudio converted = customVoice.convert(base, pitchSemitones);
        AudioDsp.fadeEdges(converted.samples,
                Math.min(converted.sampleRate / 100, converted.samples.length / 5));
        AudioDsp.normalizePeak(converted.samples, PEAK_CEILING);
        return converted.resampled(DEFAULT_SAMPLE_RATE);
    }

    @Override
    public void close() throws Exception {
        try {
            customVoice.close();
        } finally {
            baseTts.close();
        }
    }
}
