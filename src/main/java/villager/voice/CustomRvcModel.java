package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Java-only inference wrapper for the exported RVC v2 villager model. */
public final class CustomRvcModel implements AutoCloseable {
    private static final int SAMPLE_RATE = 40000;
    private static final int CONTENT_RATE = 16000;
    private static final int CONTENT_DIMENSION = 768;
    private static final int NOISE_CHANNELS = 192;
    // A 38.4 ms full analysis window retains syllable-level pitch movement.
    // The old 1024-sample radius covered 51.2 ms and smeared rises/falls into
    // a flatter contour before RVC saw them.
    private static final int PITCH_RADIUS = 768;

    // Only treat a change close to a true octave as a tracking error. Constraining
    // every jump to roughly a fifth made natural speech inflections monotone.
    private static final float OCTAVE_ERROR_HIGH = 1.75f;
    private static final float OCTAVE_ERROR_LOW = 0.57f;
    private static final double[] PITCH_WINDOW = createHannWindow(PITCH_RADIUS * 2);

    private static double[] createHannWindow(int length) {
        double[] window = new double[length];
        for (int i = 0; i < length; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i
                    / Math.max(1, length - 1));
        }
        return window;
    }

    private final OrtEnvironment environment;
    private final java.nio.file.Path contentModelPath;
    private final OrtSession rvcSession;
    /** Neural F0 tracker; null when rmvpe.onnx is absent from the bundle. */
    private final RmvpePitch rmvpe;
    /** Training-embedding retrieval; null when the flattened index is absent. */
    private final FeatureIndex featureIndex;
    private static final double INDEX_RATE = 0.55;
    /** Seeded per clip via {@link #noiseSeed} before each conversion. */
    private final Random random = new Random(0x56494C4C41474552L);

    public CustomRvcModel(java.nio.file.Path contentModel, java.nio.file.Path rvcModel)
            throws Exception {
        this(contentModel, rvcModel, null, null);
    }

    public CustomRvcModel(java.nio.file.Path contentModel, java.nio.file.Path rvcModel,
                          java.nio.file.Path rmvpeModel, java.nio.file.Path featureIndexPath)
            throws Exception {
        environment = OrtEnvironment.getEnvironment();
        contentModelPath = contentModel.toAbsolutePath().normalize();
        OrtSession rvc = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // Both graphs receive variable-length clips. ORT's memory-pattern
            // planner is optimized for fixed shapes and can reuse the first
            // execution plan on the next request, collapsing a Conv input to
            // {1}. Disable it for these dynamic audio sessions.
            options.setMemoryPatternOptimization(false);
            rvc = environment.createSession(rvcModel.toString(), options);
        } catch (Exception e) {
            if (rvc != null) {
                rvc.close();
            }
            throw e;
        }
        rvcSession = rvc;
        if (rmvpeModel != null && java.nio.file.Files.isRegularFile(rmvpeModel)) {
            java.nio.file.Path bank =
                    rmvpeModel.toAbsolutePath().normalize().resolveSibling("rmvpe-mel.f32");
            rmvpe = java.nio.file.Files.isRegularFile(bank)
                    ? new RmvpePitch(rmvpeModel, bank) : null;
        } else {
            rmvpe = null;
        }
        featureIndex = featureIndexPath != null
                && java.nio.file.Files.isRegularFile(featureIndexPath)
                ? FeatureIndex.load(featureIndexPath) : null;
    }

    public WavAudio convert(WavAudio source, double pitchSemitones) throws Exception {
        return convert(source, pitchSemitones, 0.0);
    }

    /** Convert with an optional singing vibrato amount from 0 to 1. */
    public WavAudio convert(WavAudio source, double pitchSemitones, double singing) throws Exception {
        return convert(source, pitchSemitones, singing, "neutral");
    }

    /** Convert with singing vibrato and an emotional phrase contour. */
    public WavAudio convert(WavAudio source, double pitchSemitones, double singing,
                            String emotion) throws Exception {
        return convert(source, pitchSemitones, singing, emotion, 0.0, false);
    }

    /** Convert with singing, emotion, sarcasm, and an optional question rise. */
    public WavAudio convert(WavAudio source, double pitchSemitones, double singing,
                            String emotion, double sarcasm, boolean question) throws Exception {
        WavAudio at40k = source.resampled(SAMPLE_RATE);
        float[] samples = at40k.samples;
        float[] at16k = resample(samples, SAMPLE_RATE, CONTENT_RATE);

        float[][] content = content(at16k);
        if (content.length == 0 || content[0].length != CONTENT_DIMENSION) {
            throw new IllegalStateException("ContentVec returned an unexpected shape");
        }
        int contentFrames = content.length;
        if (featureIndex != null) {
            featureIndex.apply(content, INDEX_RATE);
        }
        int modelFrames = contentFrames * 2;

        float[] pitchf = estimatePitch(samples, at16k, modelFrames, pitchSemitones,
                singing, emotion, sarcasm, question);
        long[] quantizedPitch = new long[modelFrames];
        for (int i = 0; i < modelFrames; i++) {
            quantizedPitch[i] = quantizePitch(pitchf[i]);
        }
        // The static 1024-frame graph renders every padded frame as voiced
        // audio, so the padding must carry a plausible voiced pitch. Padding
        // with the unvoiced code (pitch=1, pitchf=0) collapses the entire
        // window to the model's default ~100 Hz register - even the real
        // voiced region. Pad with the clip's median voiced pitch instead;
        // the padded region's audio is trimmed away by fitOutput afterwards.
        float pitchfPad = medianVoiced(pitchf);
        long pitchPad = quantizePitch(pitchfPad);
        // One seed per clip: every line gets its own RVC posterior noise (so
        // artifacts do not repeat identically on every line) while a re-render
        // of the same line stays byte-identical for the voice cache.
        random.setSeed(noiseSeed(samples));
        float[] noiseGains = noiseGains(samples, SAMPLE_RATE, modelFrames);

        // The exported graph is a fixed-frame-budget graph (see
        // dev/rvc-export/export_villager_onnx.py): dynamic-shape exports bake
        // trace-time sizes into attention reshapes and break on other lengths,
        // so conversion runs in padded windows instead. phone_lengths keeps
        // the real frame count and masks attention, but the pitch-embedding
        // path still processes padded frames, so pitch/pitchf padding carries
        // the clip's median voiced pitch (see pitchfPad above) - unvoiced
        // padding collapses the whole window to ~100 Hz. The extra output
        // beyond the real frame count is trimmed by fitOutput afterwards.
        java.util.List<float[]> chunks = new java.util.ArrayList<float[]>();
        int frameStart = 0;
        while (frameStart < modelFrames) {
            int window = Math.min(RVC_FRAME_BUDGET, modelFrames - frameStart);
            chunks.add(runWindow(content, contentFrames, quantizedPitch, pitchf,
                    noiseGains, frameStart, window, samples.length,
                    pitchfPad, pitchPad));
            frameStart += window;
        }
        float[] output = concatChunks(chunks);
        // The ONNX graph returns normalized float samples. WavAudio already
        // converts [-1, 1] floats to 16-bit PCM, so do not divide by 32767.
        output = fitOutput(output, samples.length);
        return new WavAudio(SAMPLE_RATE, output)
                .resampled(VillagerSynthesizer.DEFAULT_SAMPLE_RATE);
    }

    private static final int RVC_FRAME_BUDGET = 1024;
    /** Wide enough to hide NSF phase resets at window joins inaudibly. */
    private static final int RVC_WINDOW_FADE = 256;

    /** Run one fixed-size RVC window starting at a global frame index. */
    private float[] runWindow(float[][] content, int contentFrames,
                              long[] quantizedPitch, float[] pitchf, float[] noiseGains,
                              int frameStart, int window, int expectedSamples,
                              float pitchfPad, long pitchPad)
            throws Exception {
        FloatBuffer phoneBuffer = directFloatBuffer(RVC_FRAME_BUDGET * CONTENT_DIMENSION);
        for (int f = 0; f < window; f++) {
            int global = frameStart + f;
            float[] original = content[Math.min(contentFrames - 1, global / 2)];
            phoneBuffer.put(original);
        }
        while (phoneBuffer.hasRemaining()) {
            phoneBuffer.put(0.0f);
        }
        phoneBuffer.rewind();

        LongBuffer pitchBuffer = directLongBuffer(RVC_FRAME_BUDGET);
        for (int f = 0; f < window; f++) {
            pitchBuffer.put(quantizedPitch[frameStart + f]);
        }
        while (pitchBuffer.hasRemaining()) {
            pitchBuffer.put(pitchPad);
        }
        pitchBuffer.rewind();

        FloatBuffer noiseBuffer = directFloatBuffer(NOISE_CHANNELS * RVC_FRAME_BUDGET);
        for (int channel = 0; channel < NOISE_CHANNELS; channel++) {
            for (int f = 0; f < window; f++) {
                // Scale the excitation by local energy: pauses and unvoiced
                // consonants carry less random noise, which removes the steady
                // hiss RVC otherwise synthesizes into the gaps between words.
                noiseBuffer.put((float) (random.nextGaussian()
                        * noiseGains[frameStart + f]));
            }
        }
        while (noiseBuffer.hasRemaining()) {
            noiseBuffer.put(0.0f);
        }
        noiseBuffer.rewind();

        LongBuffer lengthBuffer = directLongBuffer(1);
        lengthBuffer.put(window).flip();
        FloatBuffer pitchfBuffer = directFloatBuffer(RVC_FRAME_BUDGET);
        for (int f = 0; f < window; f++) {
            pitchfBuffer.put(pitchf[frameStart + f]);
        }
        while (pitchfBuffer.hasRemaining()) {
            pitchfBuffer.put(pitchfPad);
        }
        pitchfBuffer.rewind();
        LongBuffer speakerBuffer = directLongBuffer(1);
        speakerBuffer.put(0L).flip();

        boolean dumpThis = System.getProperty("rvc.dump") != null && frameStart == 0;
        if (dumpThis) {
            try {
                java.nio.file.Path dumpDir =
                        java.nio.file.Paths.get(System.getProperty("rvc.dump"));
                java.nio.file.Files.createDirectories(dumpDir);
                dumpTensor(dumpDir.resolve("phone.bin"), phoneBuffer);
                dumpTensor(dumpDir.resolve("pitch.bin"), pitchBuffer);
                dumpTensor(dumpDir.resolve("pitchf.bin"), pitchfBuffer);
                dumpTensor(dumpDir.resolve("rnd.bin"), noiseBuffer);
            } catch (Exception dumpFailure) {
                System.err.println("DBG dump failed: " + dumpFailure);
            }
        }

        try (OnnxTensor phoneTensor = OnnxTensor.createTensor(environment, phoneBuffer,
                     new long[]{1, RVC_FRAME_BUDGET, CONTENT_DIMENSION});
             OnnxTensor lengthTensor = OnnxTensor.createTensor(environment, lengthBuffer,
                     new long[]{1});
             OnnxTensor pitchTensor = OnnxTensor.createTensor(environment, pitchBuffer,
                     new long[]{1, RVC_FRAME_BUDGET});
             OnnxTensor pitchfTensor = OnnxTensor.createTensor(environment, pitchfBuffer,
                     new long[]{1, RVC_FRAME_BUDGET});
             OnnxTensor speakerTensor = OnnxTensor.createTensor(environment, speakerBuffer,
                     new long[]{1});
             OnnxTensor noiseTensor = OnnxTensor.createTensor(environment, noiseBuffer,
                     new long[]{1, NOISE_CHANNELS, RVC_FRAME_BUDGET})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put("phone", phoneTensor);
            inputs.put("phone_lengths", lengthTensor);
            inputs.put("pitch", pitchTensor);
            inputs.put("pitchf", pitchfTensor);
            inputs.put("ds", speakerTensor);
            inputs.put("rnd", noiseTensor);
            try (OrtSession.Result result = rvcSession.run(inputs)) {
                OnnxTensor outputTensor = (OnnxTensor) result.get(0);
                long[] outputShape = outputTensor.getInfo().getShape();
                if (outputShape.length != 3 || outputShape[0] != 1 || outputShape[1] != 1) {
                    throw new IllegalStateException("RVC output must be [batch, 1, samples]: "
                            + java.util.Arrays.toString(outputShape));
                }
                FloatBuffer outputBuffer = outputTensor.getFloatBuffer().duplicate();
                outputBuffer.rewind();
                float[] audio = new float[outputBuffer.remaining()];
                outputBuffer.get(audio);
                if (dumpThis) {
                    try {
                        WavAudio rawWindow = new WavAudio(SAMPLE_RATE,
                                audio.clone());
                        AudioDsp.normalizePeak(rawWindow.samples, 0.9f);
                        rawWindow.write(java.nio.file.Paths.get(
                                System.getProperty("rvc.dump"), "window_raw.wav"));
                        StringBuilder pf = new StringBuilder();
                        int shown = 0;
                        for (int f2 = 0; f2 < window && shown < 30; f2++) {
                            if (pitchf[frameStart + f2] > 0) {
                                pf.append(String.format("%.0f,",
                                        pitchf[frameStart + f2]));
                                shown++;
                            }
                        }
                        System.err.println("DBG window pitchf[:30]: " + pf);
                    } catch (Exception dumpFailure) {
                        System.err.println("DBG wav dump failed: " + dumpFailure);
                    }
                }
                return fitOutput(audio, Math.min(expectedSamples - frameStart * 400,
                        window * 400));
            }
        }
    }

    /** Join windows with micro-fades so NSF phase resets cannot click. */

    private static float[] concatChunks(java.util.List<float[]> chunks) {
        int total = 0;
        for (float[] chunk : chunks) {
            total += chunk.length;
        }
        float[] joined = new float[total];
        int offset = 0;
        for (int c = 0; c < chunks.size(); c++) {
            float[] chunk = chunks.get(c);
            if (c > 0) {
                for (int i = 0; i < RVC_WINDOW_FADE && i < chunk.length; i++) {
                    chunk[i] *= i / (float) RVC_WINDOW_FADE;
                }
            }
            if (c < chunks.size() - 1) {
                for (int i = 0; i < RVC_WINDOW_FADE && i < chunk.length; i++) {
                    chunk[chunk.length - 1 - i] *= i / (float) RVC_WINDOW_FADE;
                }
            }
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        return joined;
    }

    private float[][] content(float[] audio16k) throws Exception {
        // This ContentVec export is variable-length and fails on the second
        // run when the same OrtSession is reused, even with memory-pattern
        // optimization disabled. Isolate each request in a fresh session so
        // ORT cannot retain an execution plan from the previous clip.
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setMemoryPatternOptimization(false);
            try (OrtSession contentSession = environment.createSession(contentModelPath.toString(), options)) {
                FloatBuffer audioBuffer = directFloatBuffer(audio16k.length);
                audioBuffer.put(audio16k).flip();
                try (OnnxTensor input = OnnxTensor.createTensor(environment,
                        audioBuffer, new long[]{1, 1, audio16k.length});
                     OrtSession.Result result = contentSession.run(Collections.singletonMap(
                             contentSession.getInputNames().iterator().next(), input))) {
                    OnnxTensor output = (OnnxTensor) result.get(0);
                    long[] shape = output.getInfo().getShape();
                    if (shape.length != 3 || shape[0] != 1) {
                        throw new IllegalStateException("ContentVec output must be rank 3, got "
                                + java.util.Arrays.toString(shape));
                    }

                    // This exported ContentVec model has the fixed layout
                    // [1, frames, 768]. Do not infer orientation from dimensions:
                    // [1, 768, 768] is valid and dimensionally ambiguous.
                    if (shape[2] != CONTENT_DIMENSION) {
                        throw new IllegalStateException("ContentVec output must be [1, frames, 768]: "
                                + java.util.Arrays.toString(shape));
                    }
                    int frames = (int) shape[1];
                    if (frames <= 0) {
                        throw new IllegalStateException("ContentVec returned no frames");
                    }

                    FloatBuffer values = output.getFloatBuffer().duplicate();
                    values.rewind();
                    float[][] normalized = new float[frames][CONTENT_DIMENSION];
                    for (int frame = 0; frame < frames; frame++) {
                        values.get(normalized[frame]);
                    }
                    return normalized;
                }
            }
        }
    }

    private float[] estimatePitch(float[] audio, float[] audio16k, int frames,
                                  double semitones, double singing, String emotion,
                                  double sarcasm, boolean question) {
        float[] raw = new float[frames];
        double shift = Math.pow(2.0, semitones / 12.0);

        if (rmvpe != null && audio16k != null && audio16k.length > 0) {
            // Neural F0 tracking. The villager model was trained on RMVPE
            // contours; matching the extractor at inference time is the single
            // biggest factor for natural (non-buzzy) pitch in the output.
            try {
                raw = resampleFrames(rmvpe.pitch(audio16k), frames);
            } catch (java.lang.Exception failure) {
                throw new IllegalStateException("RMVPE pitch tracking failed", failure);
            }
        } else {
            float[] energies = new float[frames];
            double maximumEnergy = 0.0;
            for (int frame = 0; frame < frames; frame++) {
                int center = (int) ((frame + 0.5) * audio.length / (double) frames);
                int start = Math.max(0, center - PITCH_RADIUS);
                int end = Math.min(audio.length, center + PITCH_RADIUS);
                energies[frame] = rms(audio, start, end);
                maximumEnergy = Math.max(maximumEnergy, energies[frame]);
            }

            // Do not let autocorrelation invent a voiced F0 for weak consonants,
            // breath, or pauses. Carrying pitch through those regions was the main
            // difference from the reference: generated voicing was about 87% versus
            // about 42% in the matching TEAVSRP clip.
            float voicingFloor = (float) maximumEnergy * 0.14f;
            for (int frame = 0; frame < frames; frame++) {
                int center = (int) ((frame + 0.5) * audio.length / (double) frames);
                int start = Math.max(0, center - PITCH_RADIUS);
                int end = Math.min(audio.length, center + PITCH_RADIUS);
                if (energies[frame] >= voicingFloor) {
                    raw[frame] = autocorrelationPitch(audio, start, end);
                }
            }
        }

        // Absolute register lift. The fixed +8 semitone offset maps a ~111 Hz
        // base onto the villager register, but overshoots when the base already
        // runs high (bright female deliveries read ~188 Hz and would land at
        // ~298 Hz). Instead map the base contour's median onto the target
        // register and let the remaining semitones (user + emotion offsets)
        // shift relative to that fixed target.
        double shiftEff = registerShift(raw, shift, semitones);
        for (int frame = 0; frame < frames; frame++) {
            if (raw[frame] > 0.0f) {
                float value = raw[frame] * (float) shiftEff;
                // Register guard: the villager timbre lives near 180 Hz
                // (TEAVSRP analysis). RMVPE on synthetic bases flips clusters
                // across octaves; folding into the character's plausible band
                // keeps the generator on-register even when a flip slips
                // through the repairs. The low side doubles (fixes RMVPE
                // octave-down reading), the high side clamps instead of
                // halving: halving frames above 300 Hz splits one contour
                // into two clusters and makes the pitch knob non-monotonic.
                while (value > 0.0f && value < 120.0f) {
                    value *= 2.0f;
                }
                if (value > 300.0f) {
                    value = 300.0f;
                }
                raw[frame] = value;
            }
        }

        // Neural F0 tracks contain occasional subharmonic frames (RMVPE at
        // run onsets especially). The sequential octave corrector below seeds
        // from the first voiced frame, so one bad value there used to collapse
        // the whole contour by half. Repair every voiced run against its own
        // median first: outliers get folded back onto the nearest octave of
        // the local pitch, giving the corrector a trustworthy seed.
        repairVoicedRuns(raw);

        // RVC is very sensitive to frame-to-frame F0 jumps. A bare maximum
        // autocorrelation pitch tracker frequently chooses a harmonic (usually
        // an octave away) on one frame, which is heard as a metallic/vocoder
        // or "movie hacker" effect. Also kill single-frame non-octave
        // outliers (chirps) with a voiced-run median before the octave
        // correction. Keep the contour continuous while leaving unvoiced
        // consonants unvoiced.
        raw = median3Smooth(raw);

        float[] result = new float[frames];
        // Singing notes must start on a clean pitch. The first voiced frame
        // after an unvoiced run often picks a subharmonic autocorrelation
        // (e.g. 90 Hz for a 155 Hz vowel), which RVC renders as a creaky,
        // wrong-sounding onset vowel. Bootstrap the pitch from the median of
        // the first few reliable voiced frames so the opening note starts
        // in tune.
        float onsetPitch = 0.0f;
        if (singing > 0.0f) {
            float[] candidates = new float[6];
            int count = 0;
            for (int i = 0; i < raw.length && count < candidates.length; i++) {
                if (raw[i] > 0.0f) {
                    candidates[count++] = raw[i];
                }
            }
            if (count > 0) {
                java.util.Arrays.sort(candidates, 0, count);
                onsetPitch = candidates[count / 2];
            }
        }
        float previous = onsetPitch;
        float[] phraseOffsets = phraseOffsets(frames, emotion, sarcasm);
        for (int frame = 0; frame < raw.length; frame++) {
            float candidate = raw[frame];
            if (candidate <= 0.0f) {
                previous = 0.0f;
                continue;
            }
            if (previous > 0.0f) {
                float corrected = nearestOctave(candidate, previous);
                if (corrected != candidate) {
                    // Correct a likely octave/harmonic error without smoothing
                    // ordinary pitch rises and falls. Smoothing every change
                    // turns real speech inflections into a robotic glide.
                    candidate = previous * 0.25f + corrected * 0.75f;
                }
            }
            if (singing > 0.0f) {
                // No vibrato - singing mode just uses the base pitch contour
                // without added modulation. Natural speech already has micro-
                // variation from the phrase offsets; adding a synthetic sine
                // vibrato made it sound artificial.
            } else {
                // Speech needs a small, slowly changing contour rather than a
                // perfectly quantized staircase. The offsets are interpolated
                // random control points, not a fixed-rate vibrato, so emphasis
                // varies across words without adding a synthetic wobble.
                candidate *= (float) Math.pow(2.0, phraseOffsets[frame] / 12.0);
            }
            if (question && candidate > 0.0f && frames > 1) {
                // Interrogative uptalk: raise the final fifth of voiced frames
                // by about three semitones with a smooth, non-clicking ramp.
                double progress = frame / (double) (frames - 1);
                double rise = Math.max(0.0, Math.min(1.0, (progress - 0.78) / 0.22));
                rise = rise * rise * (3.0 - 2.0 * rise);
                candidate *= (float) Math.pow(2.0, (3.0 * rise) / 12.0);
            }
            result[frame] = candidate;
            previous = candidate;
        }
        return result;
    }

    /**
     * Fold octave-discontinuities out of an F0 track. Low male voices make
     * RMVPE drop entire voiced runs one or two octaves down, and both per-run
     * statistics and sequential trackers are blind to that (runs inherit the
     * error; gaps reset the tracker). Instead, take a running median of
     * log-frequency over +-300 ms - octave flips are brief and one-sided while
     * legitimate prosody drifts slowly - and pull every frame into the nearest
     * octave of that reference.
     */
    private static void repairVoicedRuns(float[] f0) {
        int frames = f0.length;
        float[] reference = new float[frames];
        int radius = 30; // ~300 ms at the 10 ms frame rate
        for (int i = 0; i < frames; i++) {
            float[] window = new float[2 * radius + 1];
            int count = 0;
            for (int k = -radius; k <= radius; k++) {
                int index = i + k;
                if (index >= 0 && index < frames && f0[index] > 0.0f) {
                    window[count++] = f0[index];
                }
            }
            if (count == 0) {
                reference[i] = 0.0f;
                continue;
            }
            java.util.Arrays.sort(window, 0, count);
            reference[i] = window[count / 2];
        }
        for (int i = 0; i < frames; i++) {
            float value = f0[i];
            float ref = reference[i];
            if (value <= 0.0f || ref <= 0.0f) {
                continue;
            }
            while (value > 1.9f * ref) {
                value *= 0.5f;
            }
            while (value < 0.52f * ref) {
                value *= 2.0f;
            }
            f0[i] = value;
        }
    }

    /**
     * Absolute register lift factor. The villager register target (TEAVSRP
     * median ~180 Hz) is fixed; the semitones passed in carry the user pitch,
     * the +8 register lift, and emotion offsets. Removing the +8 lift leaves
     * user+emotion, applied relative to the fixed target instead of on top of
     * whatever the base happens to read.
     */
    private static double registerShift(float[] raw, double shift, double semitones) {
        float baseMedian = medianVoiced(raw);
        if (baseMedian <= 0.0f) {
            return shift;
        }
        double relative = Math.pow(2.0,
                (semitones - SpeechOptions.VILLAGER_REGISTER_SEMITONES) / 12.0);
        return VILLAGER_REGISTER_HZ / baseMedian * relative;
    }

    /** The absolute register the +8 lift targets (TEAVSRP median). */
    private static final double VILLAGER_REGISTER_HZ = 180.0;

    /**
     * Median of the voiced pitchf values, or 0 when nothing is voiced. Used to
     * pad the static-budget graph so padded frames render on the clip's
     * register instead of dragging the whole window to the default ~100 Hz.
     */
    private static float medianVoiced(float[] pitchf) {
        float[] voiced = new float[pitchf.length];
        int count = 0;
        for (float value : pitchf) {
            if (value > 0.0f) {
                voiced[count++] = value;
            }
        }
        if (count == 0) {
            return 0.0f;
        }
        java.util.Arrays.sort(voiced, 0, count);
        return voiced[count / 2];
    }

    /** Linear-interpolate an F0 track onto a new frame count. */    private static float[] resampleFrames(float[] f0, int targetFrames) {
        int sourceFrames = f0.length;
        if (sourceFrames == targetFrames || sourceFrames == 0) {
            return f0;
        }
        float[] resampled = new float[targetFrames];
        for (int frame = 0; frame < targetFrames; frame++) {
            double position = frame * (double) (sourceFrames - 1)
                    / Math.max(1, targetFrames - 1);
            int left = Math.min(sourceFrames - 1, (int) position);
            int right = Math.min(sourceFrames - 1, left + 1);
            double amount = position - left;
            resampled[frame] = (float) (f0[left] * (1.0 - amount) + f0[right] * amount);
        }
        return resampled;
    }

    private static float[] phraseOffsets(int frames, String emotion, double sarcasm) {
        float[] offsets = new float[Math.max(0, frames)];
        if (frames == 0) {
            return offsets;
        }
        // Reduced depth to avoid artificial wobble. The original values were
        // too strong and created a vibrato-like effect. Keep them subtle.
        double depth = 0.08;
        if ("happy".equalsIgnoreCase(emotion)) depth = 0.15;
        if ("angry".equalsIgnoreCase(emotion)) depth = 0.12;
        if ("sad".equalsIgnoreCase(emotion)) depth = 0.06;
        if ("scared".equalsIgnoreCase(emotion)) depth = 0.14;
        // Sarcasm is intentionally flatter and less musically sincere.
        depth *= Math.max(0.0, 1.0 - sarcasm);

        int spacing = Math.max(8, Math.min(18, frames / 8));
        long state = 0x4D4943524F50524FL ^ frames;
        int pointCount = frames / spacing + 2;
        float[] points = new float[pointCount];
        for (int i = 0; i < points.length; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            double unit = ((state & 0x7fffffffL) / (double) 0x7fffffffL) * 2.0 - 1.0;
            points[i] = (float) (unit * depth);
        }
        for (int frame = 0; frame < frames; frame++) {
            double position = frame / (double) spacing;
            int left = Math.min(points.length - 1, (int) position);
            int right = Math.min(points.length - 1, left + 1);
            double amount = position - left;
            offsets[frame] = (float) (points[left] * (1.0 - amount) + points[right] * amount);
        }
        return offsets;
    }

    private static float rms(float[] audio, int start, int end) {
        double sum = 0.0;
        int count = Math.max(0, end - start);
        for (int i = Math.max(0, start); i < end; i++) {
            sum += audio[i] * audio[i];
        }
        return count == 0 ? 0.0f : (float) Math.sqrt(sum / count);
    }

    private static float nearestOctave(float candidate, float reference) {
        float ratio = candidate / reference;
        // Normal conversational pitch movement can easily exceed a fifth.
        // Limit correction to a near-octave discontinuity, which is the common
        // autocorrelation harmonic error, instead of flattening valid emphasis.
        if (ratio > OCTAVE_ERROR_HIGH) {
            float corrected = candidate;
            while (corrected / reference > OCTAVE_ERROR_HIGH) {
                corrected *= 0.5f;
            }
            return corrected;
        }
        if (ratio < OCTAVE_ERROR_LOW) {
            float corrected = candidate;
            while (corrected / reference < OCTAVE_ERROR_LOW) {
                corrected *= 2.0f;
            }
            return corrected;
        }
        return candidate;
    }

    private static float autocorrelationPitch(float[] audio, int start, int end) {
        int minLag = Math.max(1, SAMPLE_RATE / 1100);
        int maxLag = Math.min(end - start - 2, SAMPLE_RATE / 50);
        if (maxLag <= minLag) {
            return 0.0f;
        }

        // Remove DC and apply a Hann window before correlation. This reduces
        // false pitch detections from the RVC output's residual/noise floor.
        double mean = 0.0;
        int length = end - start;
        for (int i = start; i < end; i++) {
            mean += audio[i];
        }
        mean /= Math.max(1, length);
        double best = 0.0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0.0;
            double left = 0.0;
            double right = 0.0;
            for (int i = start; i + lag < end; i++) {
                double a = (audio[i] - mean) * PITCH_WINDOW[i - start];
                double b = (audio[i + lag] - mean) * PITCH_WINDOW[i + lag - start];
                sum += a * b;
                left += a * a;
                right += b * b;
            }
            double normalized = sum / (Math.sqrt(left * right) + 1e-9);
            if (normalized > best) {
                best = normalized;
                bestLag = lag;
            }
        }
        // A weak correlation is usually frication or breath being assigned a
        // made-up fundamental. Requiring stronger periodicity prevents RVC from
        // carrying a perfectly voiced pitch through every consonant.
        return best < 0.52 || bestLag == 0 ? 0.0f : SAMPLE_RATE / (float) bestLag;
    }

    private static long quantizePitch(float frequency) {
        if (frequency <= 0.0f) {
            return 1;
        }
        double min = 1127.0 * Math.log(1.0 + 50.0 / 700.0);
        double max = 1127.0 * Math.log(1.0 + 1100.0 / 700.0);
        double mel = 1127.0 * Math.log(1.0 + frequency / 700.0);
        double value = (mel - min) * 254.0 / (max - min) + 1.0;
        return Math.max(1, Math.min(255, Math.round(value)));
    }

    /**
     * Deterministic per-clip seed derived from the audio itself, so each line
     * draws its own RVC posterior noise while the same line re-renders
     * identically (keeps the persistent voice cache stable).
     */
    static long noiseSeed(float[] samples) {
        long seed = 0xC0FFEE0DDBA5E11L;
        // Visit ~32 uniformly spaced samples (all of them for short clips) so
        // two lines with identical openings cannot collide, while the cost
        // stays constant for long audio.
        int stride = Math.max(1, samples.length / 32);
        for (int i = 0; i < samples.length; i += stride) {
            seed ^= Float.floatToIntBits(samples[i]);
            seed *= 0x100000001B3L;
        }
        if (samples.length > 0 && (samples.length - 1) % stride != 0) {
            seed ^= Float.floatToIntBits(samples[samples.length - 1]);
            seed *= 0x100000001B3L;
        }
        return seed ^ (long) samples.length * 0x9E3779B97F4A7C15L;
    }

    /**
     * Per-frame excitation scale derived from local RMS energy. Quiet frames
     * (pauses, breath, unvoiced consonants) carry less random excitation,
     * cutting the steady hiss RVC otherwise synthesizes into the gaps between
     * words; loud voiced frames keep full-strength noise so the timbre does
     * not thin out.
     */
    static float[] noiseGains(float[] samples, int sampleRate, int frames) {
        float[] gains = new float[frames];
        if (frames <= 0) {
            return gains;
        }
        int radius = Math.max(64, sampleRate / 26);
        float[] energies = new float[frames];
        float maximum = 0.0f;
        for (int frame = 0; frame < frames; frame++) {
            int center = (int) ((frame + 0.5) * samples.length / (double) frames);
            int start = Math.max(0, center - radius);
            int end = Math.min(samples.length, center + radius);
            energies[frame] = rms(samples, start, end);
            maximum = Math.max(maximum, energies[frame]);
        }
        for (int frame = 0; frame < frames; frame++) {
            float normalized = maximum <= 1.0e-6f ? 0.0f : energies[frame] / maximum;
            gains[frame] = 0.35f + 0.65f * (float) Math.sqrt(normalized);
        }
        return gains;
    }

    /**
     * 3-tap median over voiced runs only. Autocorrelation occasionally picks
     * a single-frame outlier (e.g. 1.3x the local pitch) that is not an octave
     * error; RVC renders those one-frame spikes as a short chirp/crackle. The
     * median removes the outlier without touching real rises and falls, which
     * span many frames, and never bridges unvoiced gaps.
     */
    static float[] median3Smooth(float[] raw) {
        if (raw.length < 3) {
            return raw;
        }
        float[] out = raw.clone();
        int runStart = -1;
        for (int i = 0; i <= raw.length; i++) {
            boolean voiced = i < raw.length && raw[i] > 0.0f;
            if (voiced) {
                if (runStart < 0) runStart = i;
            } else if (runStart >= 0) {
                smoothVoicedRun(out, raw, runStart, i - 1);
                runStart = -1;
            }
        }
        return out;
    }

    private static void smoothVoicedRun(float[] out, float[] raw, int start, int end) {
        if (end - start + 1 < 3) {
            return;
        }
        float[] window = new float[3];
        for (int i = start; i <= end; i++) {
            window[0] = i > start ? raw[i - 1] : raw[i];
            window[1] = raw[i];
            window[2] = i < end ? raw[i + 1] : raw[i];
            java.util.Arrays.sort(window);
            out[i] = window[1];
        }
    }

    private static void smoothJitterRun(float[] out, int start, int end) {
        if (end - start + 1 < 5) {
            return;
        }
        float[] coeffs = new float[]{0.1f, 0.2f, 0.4f, 0.2f, 0.1f};
        for (int i = start; i <= end; i++) {
            double sum = 0.0;
            double weightSum = 0.0;
            for (int k = -2; k <= 2; k++) {
                int index = i + k;
                if (index >= start && index <= end) {
                    float w = coeffs[k + 2];
                    sum += out[index] * w;
                    weightSum += w;
                }
            }
            out[i] = (float) (sum / weightSum);
        }
    }

    /**
     * Fit the RVC output to the expected clip length. When the graph returns
     * slightly fewer samples, pad with a short tail fade instead of a hard
     * zero edge so truncation cannot click; extra samples are simply cut.
     */
    static float[] fitOutput(float[] output, int expected) {
        if (output.length == expected) {
            return output;
        }
        float[] fitted = new float[expected];
        int copy = Math.min(output.length, expected);
        System.arraycopy(output, 0, fitted, 0, copy);
        if (copy < expected) {
            int fade = Math.min(256, copy);
            for (int i = 0; i < fade; i++) {
                float t = (fade - i) / (float) fade;
                fitted[copy - fade + i] *= t * t;
            }
        }
        return fitted;
    }

    private static FloatBuffer directFloatBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static LongBuffer directLongBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    /** Debug helper for -Drvc.dump: write a tensor as raw little-endian data. */
    private static void dumpTensor(java.nio.file.Path path, FloatBuffer buffer)
            throws java.io.IOException {
        FloatBuffer copy = buffer.duplicate();
        copy.rewind();
        ByteBuffer bytes = ByteBuffer.allocate(copy.remaining() * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (copy.hasRemaining()) {
            bytes.putFloat(copy.get());
        }
        java.nio.file.Files.write(path, bytes.array());
    }

    private static void dumpTensor(java.nio.file.Path path, LongBuffer buffer)
            throws java.io.IOException {
        LongBuffer copy = buffer.duplicate();
        copy.rewind();
        ByteBuffer bytes = ByteBuffer.allocate(copy.remaining() * Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (copy.hasRemaining()) {
            bytes.putLong(copy.get());
        }
        java.nio.file.Files.write(path, bytes.array());
    }

    private static float[] resample(float[] input, int sourceRate, int destinationRate) {
        // Multiply in double precision: long clips can overflow int before
        // the division, collapsing the destination length to one sample
        // (same bug previously fixed in WavAudio.resampled).
        int length = Math.max(1, (int) Math.round(
                input.length * (double) destinationRate / sourceRate));
        return AudioDsp.resample(input, length);
    }

    @Override
    public void close() throws Exception {
        try {
            rvcSession.close();
        } finally {
            if (rmvpe != null) {
                rmvpe.close();
            }
        }
    }
}
