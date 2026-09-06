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
                    bundle.path("rvc-villager.onnx"),
                    bundle.directory().resolve("rmvpe.onnx"),
                    bundle.directory().resolve("rvc-villager-index.bin"));
            referenceVoice = null;
        }
    }

    /** Full pipeline with an explicit base backend (e.g. the Kokoro eval path). */
    VillagerSynthesizer(NeuralBaseTts injectedBase, Path modelDirectory) throws Exception {
        if (injectedBase == null || modelDirectory == null) {
            throw new IllegalArgumentException("base and model directory are required");
        }
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.REFERENCE) {
            throw new IllegalStateException(
                    "voxel.voice.mode=reference cannot use an injected neural base");
        }
        ModelBundle bundle = ModelBundle.from(modelDirectory);
        baseTts = injectedBase;
        customVoice = new CustomRvcModel(
                bundle.path("vec-768-layer-12.onnx"),
                bundle.path("rvc-villager.onnx"),
                bundle.directory().resolve("rmvpe.onnx"),
                bundle.directory().resolve("rvc-villager-index.bin"));
        referenceVoice = null;
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
        // Legacy/direct callers do not carry catalog metadata, so punctuation
        // supplies the natural question cue without changing ordinary lines.
        SpeechOptions effectiveOptions = options.isQuestion()
                || !options.allowsAutomaticQuestionDetection()
                || !SpeechOptions.looksLikeQuestion(text)
                ? options : options.withQuestion(true);
        if (mode == VoiceMode.REFERENCE) {
            return referenceVoice.render(text, effectiveOptions);
        }

        WavAudio base = baseTts.synthesize(text, effectiveOptions.getEffectiveSpeed(),
                effectiveOptions.getEmotion());
        WavAudio converted = customVoice.convert(base, effectiveOptions.getEffectivePitchSemitones(),
                effectiveOptions.getSinging(), effectiveOptions.getEmotion(),
                effectiveOptions.getSarcasm(), effectiveOptions.isQuestion(),
                effectiveOptions.getEffectiveIndexRate());
        // Artifact control now lives inside the RVC stage itself: the sinc
        // resampler kills aliasing hiss at the source, top-8 retrieval removes
        // the metallic per-frame timbre quantization, and unvoiced protect
        // keeps consonants carried by the original encoder features.
        //
        // The previous downstream band-aids are deliberately gone: the 40%
        // natural-carrier mix dulled the timbre back toward the base TTS, and
        // the denoise/notch chain (including notches at 500/800 Hz, right in
        // the villager formant band) flattened the voice it was trying to
        // clean. Adding DSP after a clean conversion can only make it worse.
        AudioDsp.fadeEdges(converted.samples,
                Math.min(converted.sampleRate / 100, converted.samples.length / 5));
        // Cut the vocoder's frame-rate comb whine before tone shaping so the
        // tilt does not push the whine further above the noise floor.
        AudioDsp.applyCombWhineCleanup(converted.samples, converted.sampleRate);
        AudioDsp.applyToneTilt(converted.samples, effectiveOptions.getEffectiveSpectralTilt());
        AudioDsp.normalizePeak(converted.samples, PEAK_CEILING);
        AudioDsp.applyGain(converted.samples, effectiveOptions.getEffectiveVolume());
        AudioDsp.normalizePeak(converted.samples, 0.98f);
        return converted.resampled(DEFAULT_SAMPLE_RATE);
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
