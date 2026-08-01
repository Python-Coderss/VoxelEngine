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

        float[] pitchf = estimatePitch(samples, modelFrames, pitchSemitones);
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

    private static float[] estimatePitch(float[] audio, int frames, double semitones) {
        float[] result = new float[frames];
        double shift = Math.pow(2.0, semitones / 12.0);
        for (int frame = 0; frame < frames; frame++) {
            int center = (int) ((frame + 0.5) * audio.length / (double) frames);
            int radius = 1024;
            int start = Math.max(0, center - radius);
            int end = Math.min(audio.length, center + radius);
            result[frame] = (float) (autocorrelationPitch(audio, start, end) * shift);
        }
        return result;
    }

    private static float autocorrelationPitch(float[] audio, int start, int end) {
        int minLag = Math.max(1, SAMPLE_RATE / 1100);
        int maxLag = Math.min(end - start - 2, SAMPLE_RATE / 50);
        if (maxLag <= minLag) {
            return 0.0f;
        }
        double best = 0.0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0.0;
            double left = 0.0;
            double right = 0.0;
            for (int i = start; i + lag < end; i++) {
                double a = audio[i];
                double b = audio[i + lag];
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
        return best < 0.25 || bestLag == 0 ? 0.0f : SAMPLE_RATE / (float) bestLag;
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
