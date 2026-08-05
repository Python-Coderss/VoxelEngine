package villager.voice;

import java.nio.file.Path;

/**
 * Dynamic custom villager voice for the game runtime.
 *
 * Dialogue is synthesized entirely in Java with the Coqui VCTK VITS base
 * ({@code coqui-vctk-vits.onnx}) and converted to Dan Lloyd's Element
 * Animation villager timbre by the RVC v2 model ({@code rvc-villager.onnx}).
 * The reference backend replays exact transcript-named corpus clips for
 * validation. No Python, eSpeak, subprocess, or network access at runtime.
 */
public final class VillagerSynthesizer implements AutoCloseable {
    public static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final float PEAK_CEILING = 0.85f;
    private final VoiceMode mode;
    private final NeuralBaseTts baseTts;
    private final CustomRvcModel customVoice;
    private final ReferenceCorpusVoice referenceVoice;

    public VillagerSynthesizer() throws Exception {
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.REFERENCE) {
            baseTts = null;
            customVoice = null;
            referenceVoice = new ReferenceCorpusVoice();
        } else {
            ModelBundle bundle = ModelBundle.defaultBundle();
            baseTts = coquiBase(bundle);
            customVoice = new CustomRvcModel(
                    bundle.path("vec-768-layer-12.onnx"),
                    bundle.path("rvc-villager.onnx"));
            referenceVoice = null;
        }
    }

    public VillagerSynthesizer(Path modelDirectory) throws Exception {
        if (modelDirectory == null) {
            throw new IllegalArgumentException("modelDirectory must not be null");
        }
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.REFERENCE) {
            baseTts = null;
            customVoice = null;
            referenceVoice = new ReferenceCorpusVoice();
        } else {
            ModelBundle bundle = ModelBundle.from(modelDirectory);
            baseTts = coquiBase(bundle);
            customVoice = new CustomRvcModel(
                    bundle.path("vec-768-layer-12.onnx"),
                    bundle.path("rvc-villager.onnx"));
            referenceVoice = null;
        }
    }

    /** The Coqui base is the only neural base; there is no Piper fallback. */
    private static NeuralBaseTts coquiBase(ModelBundle bundle) throws Exception {
        if (!bundle.hasCoqui()) {
            throw new IllegalStateException("Coqui should always be included");
        }
        return new CoquiVitsTts(bundle.directory());
    }

    public VoiceMode getMode() {
        return mode;
    }

    /** Backwards-compatible render using the default voice profile. */
    public synchronized WavAudio render(String text, double speed, double pitchSemitones)
            throws Exception {
        return render(text, new SpeechOptions(speed, pitchSemitones));
    }

    /** Render one line with the complete editable voice profile. */
    public synchronized WavAudio render(String text, SpeechOptions options)
            throws Exception {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (mode == VoiceMode.REFERENCE) {
            return referenceVoice.render(text, options);
        }

        WavAudio base = baseTts.synthesize(text, options.getEffectiveSpeed());
        WavAudio converted = customVoice.convert(base, options.getEffectivePitchSemitones(),
                options.getSinging(), options.getEmotion());
        // Keep a substantial amount of the natural VITS source under RVC. A
        // completely dry conversion is where the metallic/hacker quality is
        // most apparent; the default profile supplies 36% natural body.
        mixNaturalSource(converted, base, options.getEffectiveNaturalSourceMix());
        // Apply the source envelope after mixing so the natural VITS layer cannot
        // restore a continuously voiced carrier during pauses.
        WavAudio sourceAtOutputRate = base.resampled(converted.sampleRate);
        AudioDsp.applySourceEnergyMask(converted.samples, sourceAtOutputRate.samples,
                converted.sampleRate);
        // Clean RVC hiss/rumble after source mixing so both the converted and
        // natural layers receive the same gentle denoise and smoothing pass.
        AudioDsp.applySpeechDenoise(converted.samples, converted.sampleRate);
        AudioDsp.fadeEdges(converted.samples,
                Math.min(converted.sampleRate / 100, converted.samples.length / 5));
        AudioDsp.applyToneTilt(converted.samples, options.getEffectiveTone());
        AudioDsp.normalizePeak(converted.samples, PEAK_CEILING);
        AudioDsp.applyGain(converted.samples, options.getEffectiveVolume());
        AudioDsp.normalizePeak(converted.samples, 0.98f);
        return converted.resampled(DEFAULT_SAMPLE_RATE);
    }

    private static void mixNaturalSource(WavAudio converted, WavAudio base, double mix) {
        double naturalMix = Math.max(0.0, Math.min(0.5, mix));
        WavAudio natural = base.resampled(converted.sampleRate);
        int count = Math.min(converted.samples.length, natural.samples.length);
        for (int i = 0; i < count; i++) {
            converted.samples[i] = converted.samples[i] * (float) (1.0 - naturalMix)
                    + natural.samples[i] * (float) naturalMix;
        }
    }

    @Override
    public void close() throws Exception {
        if (mode == VoiceMode.REFERENCE) {
            referenceVoice.close();
            return;
        }
        try {
            customVoice.close();
        } finally {
            baseTts.close();
        }
    }
}
