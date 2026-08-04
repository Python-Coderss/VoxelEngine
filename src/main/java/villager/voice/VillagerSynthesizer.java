package villager.voice;

import java.nio.file.Path;

/**
 * Dynamic custom villager voice for the game runtime.
 *
 * The default backend is the neural Piper/VITS plus RVC voice. The reference backend
 * uses transcript-named licensed clips for exact known lines and falls back to
 * formant synthesis for unseen text. The neural Piper/VITS plus RVC backend
 * remains available as an explicit mode, while formant is the deterministic fallback.
 */
public final class VillagerSynthesizer implements AutoCloseable {
    public static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final float PEAK_CEILING = 0.85f;
    private final VoiceMode mode;
    private final BaseTts baseTts;
    private final CustomRvcModel customVoice;
    private final FormantVillagerVoice formantVoice;
    private final ReferenceCorpusVoice referenceVoice;

    public VillagerSynthesizer() throws Exception {
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.FORMANT) {
            baseTts = null;
            customVoice = null;
            formantVoice = new FormantVillagerVoice();
            referenceVoice = null;
        } else if (mode == VoiceMode.REFERENCE) {
            baseTts = null;
            customVoice = null;
            formantVoice = null;
            referenceVoice = new ReferenceCorpusVoice();
        } else {
            ModelBundle bundle = ModelBundle.defaultBundle();
            baseTts = new BaseTts(bundle.directory());
            try {
                customVoice = new CustomRvcModel(
                        bundle.path("vec-768-layer-12.onnx"),
                        bundle.path("rvc-villager.onnx"));
            } catch (Exception e) {
                baseTts.close();
                throw e;
            }
            formantVoice = null;
            referenceVoice = null;
        }
    }

    public VillagerSynthesizer(Path modelDirectory) throws Exception {
        if (modelDirectory == null) {
            throw new IllegalArgumentException("modelDirectory must not be null");
        }
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.FORMANT) {
            baseTts = null;
            customVoice = null;
            formantVoice = new FormantVillagerVoice();
            referenceVoice = null;
        } else if (mode == VoiceMode.REFERENCE) {
            baseTts = null;
            customVoice = null;
            formantVoice = null;
            // ReferenceCorpusVoice uses voxel.voice.reference when supplied,
            // otherwise the project's voice/corpus corpus.
            referenceVoice = new ReferenceCorpusVoice();
        } else {
            ModelBundle bundle = ModelBundle.from(modelDirectory);
            baseTts = new BaseTts(bundle.directory());
            try {
                customVoice = new CustomRvcModel(
                        bundle.path("vec-768-layer-12.onnx"),
                        bundle.path("rvc-villager.onnx"));
            } catch (Exception e) {
                baseTts.close();
                throw e;
            }
            formantVoice = null;
            referenceVoice = null;
        }
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
        if (mode == VoiceMode.FORMANT) {
            return formantVoice.render(text, options);
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
        if (mode == VoiceMode.FORMANT) {
            formantVoice.close();
            return;
        }
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
