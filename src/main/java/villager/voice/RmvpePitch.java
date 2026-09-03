package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RMVPE F0 estimator, pure Java via ONNX Runtime.
 *
 * Mirrors the reference inference exactly: 16 kHz mono audio, Hann-windowed
 * STFT (window 1024, hop 160, center/reflect padding), an HTK 128-band mel
 * filterbank (30-8000 Hz, coefficients baked by librosa into
 * {@code rmvpe-mel.f32}), log-clamped mel passed to {@code rmvpe.onnx}, and a
 * 9-bin local-average decode of the 360 x 20-cent salience matrix. Frames are
 * 10 ms apart, matching the RVC generator's frame rate.
 */
public final class RmvpePitch implements AutoCloseable {
    public static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 1024;
    private static final int HOP = 160;
    private static final int MEL_BANDS = 128;
    private static final int FFT_BINS = WINDOW / 2 + 1;
    private static final int N_CLASS = 360;
    private static final double CENTS_OFFSET = 1997.3794084376191;
    private static final float CONFIDENCE_FLOOR = 0.03f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;
    private final float[] melBank;
    private final float[] window;
    private final double[] centsMapping;
    private final float[] fftReal = new float[WINDOW];
    private final float[] fftImag = new float[WINDOW];

    public RmvpePitch(Path onnxPath, Path melBankPath) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setMemoryPatternOptimization(false);
            options.setIntraOpNumThreads(2);
            session = environment.createSession(onnxPath.toString(), options);
        }
        inputName = session.getInputNames().iterator().next();
        outputName = session.getOutputNames().iterator().next();
        melBank = loadFloats(melBankPath, MEL_BANDS * FFT_BINS);
        window = new float[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / WINDOW));
        }
        centsMapping = new double[N_CLASS + 8];
        for (int i = 0; i < N_CLASS; i++) {
            centsMapping[i + 4] = 20.0 * i + CENTS_OFFSET;
        }
        centsMapping[0] = centsMapping[1] = centsMapping[2] = centsMapping[3] = centsMapping[4];
        centsMapping[N_CLASS + 4] = centsMapping[N_CLASS + 5]
                = centsMapping[N_CLASS + 6] = centsMapping[N_CLASS + 7]
                = centsMapping[N_CLASS + 3];
    }

    private static float[] loadFloats(Path path, int expected) throws IOException {
        long bytes = Files.size(path);
        if (bytes != expected * 4L) {
            throw new IOException(path + ": expected " + expected + " floats, got "
                    + (bytes / 4));
        }
        try (FileChannel channel = FileChannel.open(path)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            FloatBuffer buffer = mapped.asFloatBuffer();
            float[] data = new float[expected];
            buffer.get(data);
            return data;
        }
    }

    /**
     * Estimate F0 in Hz for every 10 ms frame; unvoiced frames return zero.
     * The returned length is the native frame count (audio length / hop + 1).
     */
    public synchronized float[] pitch(float[] audio16k) throws Exception {
        int frameCount = audio16k.length / HOP + 1;
        float[] mel = melSpectrogram(audio16k, frameCount);

        int paddedFrames = 32 * ((frameCount - 1) / 32 + 1);
        FloatBuffer melBuffer = ByteBuffer.allocateDirect(MEL_BANDS * paddedFrames * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int band = 0; band < MEL_BANDS; band++) {
            melBuffer.put(mel, band * frameCount, frameCount);
            for (int t = frameCount; t < paddedFrames; t++) {
                melBuffer.put(0.0f);
            }
        }
        melBuffer.rewind();

        float[][] salience;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, melBuffer,
                new long[]{1, MEL_BANDS, paddedFrames})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put(inputName, input);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                long[] shape = output.getInfo().getShape();
                int rows = (int) shape[1];
                int classes = (int) shape[2];
                FloatBuffer buffer = output.getFloatBuffer().duplicate();
                buffer.rewind();
                salience = new float[frameCount][classes];
                for (int t = 0; t < frameCount; t++) {
                    buffer.position(t * classes);
                    buffer.get(salience[t]);
                }
                if (rows < frameCount) {
                    throw new IllegalStateException("RMVPE returned fewer frames than requested");
                }
            }
        }

        float[] f0 = new float[frameCount];
        for (int t = 0; t < frameCount; t++) {
            float confidence = 0.0f;
            int peak = 0;
            for (int c = 0; c < N_CLASS; c++) {
                if (salience[t][c] > confidence) {
                    confidence = salience[t][c];
                    peak = c;
                }
            }
            if (confidence < CONFIDENCE_FLOOR) {
                continue;
            }
            double weightSum = 0.0;
            double centSum = 0.0;
            for (int k = -4; k <= 4; k++) {
                int salienceBin = peak + k;
                int mappingBin = peak + 4 + k;
                // Bins past the edges behave like the zero-padded reference.
                double weight = salienceBin >= 0 && salienceBin < N_CLASS
                        ? salience[t][salienceBin] : 0.0;
                centSum += weight * centsMapping[mappingBin];
                weightSum += weight;
            }
            if (weightSum > 0.0f) {
                f0[t] = (float) (10.0 * Math.pow(2.0, (centSum / weightSum) / 1200.0));
            }
        }
        return f0;
    }

    /** Log-mel spectrogram, layout [band * frameCount + frame]. */
    private float[] melSpectrogram(float[] audio, int frameCount) {
        float[] padded = new float[audio.length + WINDOW];
        System.arraycopy(audio, 0, padded, WINDOW / 2, audio.length);
        for (int i = 0; i < WINDOW / 2; i++) {
            padded[i] = reflect(audio, WINDOW / 2 - i);
            padded[WINDOW / 2 + audio.length + i] = reflect(audio, audio.length - 2 - i);
        }

        float[] magnitudes = new float[FFT_BINS];
        float[] mel = new float[MEL_BANDS * frameCount];
        for (int t = 0; t < frameCount; t++) {
            int offset = t * HOP;
            for (int i = 0; i < WINDOW; i++) {
                fftReal[i] = padded[offset + i] * window[i];
                fftImag[i] = 0.0f;
            }
            fft(fftReal, fftImag);
            for (int b = 0; b < FFT_BINS; b++) {
                magnitudes[b] = (float) Math.sqrt(
                        fftReal[b] * (double) fftReal[b]
                                + fftImag[b] * (double) fftImag[b]);
            }
            for (int m = 0; m < MEL_BANDS; m++) {
                double sum = 0.0;
                int base = m * FFT_BINS;
                for (int b = 0; b < FFT_BINS; b++) {
                    sum += melBank[base + b] * magnitudes[b];
                }
                mel[m * frameCount + t] =
                        (float) Math.log(Math.max(sum, 1e-5f));
            }
        }
        return mel;
    }

    private static float reflect(float[] audio, int index) {
        if (index < 0) {
            index = -index;
        }
        if (index >= audio.length) {
            index = 2 * (audio.length - 1) - index;
        }
        return audio[Math.max(0, Math.min(audio.length - 1, index))];
    }

    /** In-place radix-2 FFT (n must be a power of two). */
    static void fft(float[] real, float[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float swap = real[i];
                real[i] = real[j];
                real[j] = swap;
                swap = imag[i];
                imag[i] = imag[j];
                imag[j] = swap;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double curReal = 1.0;
                double curImag = 0.0;
                for (int k = 0; k < length / 2; k++) {
                    int even = start + k;
                    int odd = even + length / 2;
                    float oddReal = real[odd] * (float) curReal
                            - imag[odd] * (float) curImag;
                    float oddImag = real[odd] * (float) curImag
                            + imag[odd] * (float) curReal;
                    real[odd] = real[even] - oddReal;
                    imag[odd] = imag[even] - oddImag;
                    real[even] += oddReal;
                    imag[even] += oddImag;
                    double nextReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = nextReal;
                }
            }
        }
    }

    /** Dev-only: expose the mel front-end for fixture comparison. */
    float[] debugMel(float[] audio16k) {
        return melSpectrogram(audio16k, audio16k.length / HOP + 1);
    }

    /** Dev-only: full pipeline, returning the raw salience matrix. */
    synchronized float[][] debugSalience(float[] audio16k) throws Exception {
        int frameCount = audio16k.length / HOP + 1;
        float[] mel = melSpectrogram(audio16k, frameCount);
        int paddedFrames = 32 * ((frameCount - 1) / 32 + 1);
        FloatBuffer melBuffer = ByteBuffer.allocateDirect(MEL_BANDS * paddedFrames * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int band = 0; band < MEL_BANDS; band++) {
            melBuffer.put(mel, band * frameCount, frameCount);
            for (int t = frameCount; t < paddedFrames; t++) {
                melBuffer.put(0.0f);
            }
        }
        melBuffer.rewind();
        try (OnnxTensor input = OnnxTensor.createTensor(environment, melBuffer,
                new long[]{1, MEL_BANDS, paddedFrames})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put(inputName, input);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                long[] shape = output.getInfo().getShape();
                int rows = (int) shape[1];
                int classes = (int) shape[2];
                FloatBuffer buffer = output.getFloatBuffer().duplicate();
                buffer.rewind();
                float[][] salience = new float[frameCount][classes];
                for (int t = 0; t < frameCount; t++) {
                    buffer.position(t * classes);
                    buffer.get(salience[t]);
                }
                return salience;
            }
        }
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
