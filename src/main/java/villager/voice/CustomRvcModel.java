package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
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
    private final Random random = new Random(0x56494C4C41474552L);

    public CustomRvcModel(java.nio.file.Path contentModel, java.nio.file.Path rvcModel)
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
        int modelFrames = contentFrames * 2;

        // Use flat buffers with explicit shapes. Passing Java multidimensional
        // arrays to a reused OrtSession can make ONNX Runtime retain the shape
        // of a previous variable-length request and collapse a later input to
        // a {1} dimension inside the RVC convolution graph.
        FloatBuffer phoneBuffer = directFloatBuffer(modelFrames * CONTENT_DIMENSION);
        for (int frame = 0; frame < modelFrames; frame++) {
            float[] original = content[Math.min(contentFrames - 1, frame / 2)];
            phoneBuffer.put(original);
        }
        phoneBuffer.flip();

        float[] pitchf = estimatePitch(samples, modelFrames, pitchSemitones, singing,
                emotion, sarcasm, question);
        LongBuffer pitchBuffer = directLongBuffer(modelFrames);
        for (int i = 0; i < modelFrames; i++) {
            pitchBuffer.put(quantizePitch(pitchf[i]));
        }
        pitchBuffer.flip();

        FloatBuffer noiseBuffer = directFloatBuffer(NOISE_CHANNELS * modelFrames);
        for (int channel = 0; channel < NOISE_CHANNELS; channel++) {
            for (int frame = 0; frame < modelFrames; frame++) {
                noiseBuffer.put((float) random.nextGaussian());
            }
        }
        noiseBuffer.flip();

        LongBuffer lengthBuffer = directLongBuffer(1);
        lengthBuffer.put(modelFrames).flip();
        FloatBuffer pitchfBuffer = directFloatBuffer(pitchf.length);
        pitchfBuffer.put(pitchf).flip();
        LongBuffer speakerBuffer = directLongBuffer(1);
        speakerBuffer.put(0L).flip();

        try (OnnxTensor phoneTensor = OnnxTensor.createTensor(environment, phoneBuffer,
                     new long[]{1, modelFrames, CONTENT_DIMENSION});
             OnnxTensor lengthTensor = OnnxTensor.createTensor(environment, lengthBuffer,
                     new long[]{1});
             OnnxTensor pitchTensor = OnnxTensor.createTensor(environment, pitchBuffer,
                     new long[]{1, modelFrames});
             OnnxTensor pitchfTensor = OnnxTensor.createTensor(environment, pitchfBuffer,
                     new long[]{1, modelFrames});
             OnnxTensor speakerTensor = OnnxTensor.createTensor(environment, speakerBuffer,
                     new long[]{1});
             OnnxTensor noiseTensor = OnnxTensor.createTensor(environment, noiseBuffer,
                     new long[]{1, NOISE_CHANNELS, modelFrames})) {
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
                float[] output = new float[outputBuffer.remaining()];
                outputBuffer.get(output);
                // The ONNX graph returns normalized float samples. WavAudio
                // already converts [-1, 1] floats to 16-bit PCM, so do not
                // divide by 32767 here.
                int expected = samples.length;
                if (output.length != expected) {
                    float[] fitted = new float[expected];
                    System.arraycopy(output, 0, fitted, 0, Math.min(output.length, expected));
                    output = fitted;
                }
                return new WavAudio(SAMPLE_RATE, output)
                        .resampled(VillagerSynthesizer.DEFAULT_SAMPLE_RATE);
            }
        }
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

    private static float[] estimatePitch(float[] audio, int frames, double semitones,
                                         double singing, String emotion, double sarcasm,
                                         boolean question) {
        float[] raw = new float[frames];
        float[] energies = new float[frames];
        double shift = Math.pow(2.0, semitones / 12.0);
        float maximumEnergy = 0.0f;
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
        float voicingFloor = maximumEnergy * 0.14f;
        for (int frame = 0; frame < frames; frame++) {
            int center = (int) ((frame + 0.5) * audio.length / (double) frames);
            int start = Math.max(0, center - PITCH_RADIUS);
            int end = Math.min(audio.length, center + PITCH_RADIUS);
            if (energies[frame] >= voicingFloor) {
                raw[frame] = (float) (autocorrelationPitch(audio, start, end) * shift);
            }
        }

        // RVC is very sensitive to frame-to-frame F0 jumps. A bare maximum
        // autocorrelation pitch tracker frequently chooses a harmonic (usually
        // an octave away) on one frame, which is heard as a metallic/vocoder
        // or "movie hacker" effect. Keep the contour continuous while leaving
        // unvoiced consonants unvoiced.
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
                // Restrained musical vibrato on the F0 contour. The modulation
                // fades in over the first ~150 ms so the opening phoneme keeps
                // its natural onset: starting the sine at full depth on the
                // first syllable bends the initial vowel ("I" becomes "uh" or
                // "them").
                double elapsed = frame * (audio.length / (double) SAMPLE_RATE)
                        / Math.max(1, frames - 1);
                double onset = Math.min(1.0, Math.max(0.0, elapsed / 0.15));
                double vibrato = Math.sin(2.0 * Math.PI * 5.2 * elapsed);
                double cents = singing * 38.0 * vibrato * onset;
                candidate *= (float) Math.pow(2.0, cents / 1200.0);
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

    private static float[] phraseOffsets(int frames, String emotion, double sarcasm) {
        float[] offsets = new float[Math.max(0, frames)];
        if (frames == 0) {
            return offsets;
        }
        double depth = 0.16;
        if ("happy".equalsIgnoreCase(emotion)) depth = 0.48;
        if ("angry".equalsIgnoreCase(emotion)) depth = 0.36;
        if ("sad".equalsIgnoreCase(emotion)) depth = 0.12;
        if ("scared".equalsIgnoreCase(emotion)) depth = 0.42;
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

    private static FloatBuffer directFloatBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static LongBuffer directLongBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    private static float[] resample(float[] input, int sourceRate, int destinationRate) {
        int length = Math.max(1, Math.round(input.length * destinationRate / (float) sourceRate));
        return AudioDsp.resample(input, length);
    }

    @Override
    public void close() throws Exception {
        rvcSession.close();
    }
}
