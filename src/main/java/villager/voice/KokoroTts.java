package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kokoro-82M base TTS, entirely in Java 8 via ONNX Runtime.
 *
 * Reuses the shipped cmudict G2P ({@link CoquiFrontend#wordToIpa}) mapped onto
 * Kokoro's IPA vocabulary. Emotional delivery comes from L2-normalized blends
 * of 256-dim style vectors plus a per-emotion rate factor; the graph itself is
 * one session call returning finished 24 kHz samples (vocoder included).
 * Model files live beside the rest of the bundle: {@code kokoro-v1.0.onnx},
 * {@code kokoro-config.json}, and one {@code <voice>.bin} per style vector.
 */
public final class KokoroTts implements NeuralBaseTts {
    public static final int OUTPUT_RATE = 24000;
    private static final int STYLE_DIM = 256;
    /** Context window is 512 including the two pad tokens. */
    static final int MAX_TOKENS = 510;
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?\u2026])\\s+");
    private static final float TRIM_THRESHOLD = 0.01f;
    private static final int TRIM_MARGIN = (int) (OUTPUT_RATE * 0.06);
    private static final int EDGE_FADE = OUTPUT_RATE / 200;

    private final OrtEnvironment environment;
    private final OrtSession session;
    final Map<String, Integer> vocab;
    private final Map<String, float[][]> voices = new LinkedHashMap<String, float[][]>();
    final MisakiEnG2P g2p;
    private final String tokenInputName;
    /** When set, every line uses this style expression regardless of emotion. */
    private volatile String forcedExpression;

    public KokoroTts(Path modelDir) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setMemoryPatternOptimization(false);
            session = environment.createSession(
                    modelDir.resolve("kokoro-v1.0.onnx").toString(), options);
        }
        JSONObject config = new JSONObject(new String(Files.readAllBytes(
                modelDir.resolve("kokoro-config.json")), StandardCharsets.UTF_8));
        JSONObject vocabJson = config.getJSONObject("vocab");
        vocab = new LinkedHashMap<String, Integer>();
        for (String key : vocabJson.keySet()) {
            vocab.put(key, vocabJson.getInt(key));
        }
        // British English G2P (misaki port): produces Kokoro phonemes with
        // correct stress marks and en-GB vowels from gb_gold/gb_silver.json.
        g2p = new MisakiEnG2P(modelDir);
        try (java.nio.file.DirectoryStream<Path> entries =
                     Files.newDirectoryStream(modelDir, "*.bin")) {
            for (Path bin : entries) {
                loadVoice(bin);
            }
        }
        tokenInputName = session.getInputNames().contains("input_ids")
                ? "input_ids" : "tokens";
    }

    private void loadVoice(Path bin) throws IOException {
        String name = bin.getFileName().toString().replace(".bin", "")
                .toLowerCase(Locale.ROOT);
        long bytes = Files.size(bin);
        int rows = (int) (bytes / (STYLE_DIM * 4L));
        if (rows == 0) {
            return;
        }
        try (FileChannel channel = FileChannel.open(bin)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            FloatBuffer floats = mapped.asFloatBuffer();
            float[][] data = new float[rows][STYLE_DIM];
            for (int row = 0; row < rows; row++) {
                floats.get(data[row]);
            }
            voices.put(name, data);
        }
    }

    @Override
    public WavAudio synthesize(String text, double speed) {
        return synthesize(text, speed, "neutral");
    }

    @Override
    public synchronized WavAudio synthesize(String text, double speed, String emotion) {
        try {
            return render(text, speed, emotion);
        } catch (Exception error) {
            throw new IllegalStateException("Kokoro synthesis failed: " + error, error);
        }
    }

    private WavAudio render(String text, double speed, String emotion) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        Expression expression = forcedExpression != null
                ? new Expression(forcedExpression, 1.0) : expressionFor(emotion);
        // The interface contract is "higher speed = slower speech" (a duration
        // multiplier), while Kokoro's speed input means speaking rate.
        double kokoroRate = expression.rate / Math.max(0.1, speed);

        List<float[]> pieces = new ArrayList<float[]>();
        int total = 0;
        for (String sentence : SENTENCE_SPLIT.split(text.trim())) {
            if (sentence.trim().isEmpty()) {
                continue;
            }
            List<Integer> tokens = tokenizeSentence(sentence.trim());
            if (tokens.isEmpty()) {
                continue;
            }
            float[] audio = infer(tokens, blendStyle(expression.blend,
                    tokens.size()), (float) kokoroRate);
            pieces.add(audio);
            total += audio.length;
            if (total > 0 && audio.length > 0) {
                total += (int) (OUTPUT_RATE * 0.12); // inter-sentence gap
            }
        }
        if (pieces.isEmpty()) {
            return new WavAudio(OUTPUT_RATE, new float[0]);
        }
        float[] output = new float[total];
        int offset = 0;
        for (float[] piece : pieces) {
            System.arraycopy(piece, 0, output, offset, piece.length);
            offset += piece.length + (int) (OUTPUT_RATE * 0.12);
        }
        return new WavAudio(OUTPUT_RATE, output);
    }

    /** Eval-harness hook: pin every line to one style expression (null = auto). */
    public void setForcedExpression(String expression) {
        this.forcedExpression = expression;
    }

    private static final class Expression {
        final String blend;
        final double rate;

        Expression(String blend, double rate) {
            this.blend = blend;
            this.rate = rate;
        }
    }

    private static Expression expressionFor(String emotion) {        String e = emotion == null ? "neutral" : emotion.toLowerCase(Locale.ROOT);
        // am_michael is the chosen villager base; other emotions tint it
        // instead of replacing it entirely.
        if ("happy".equals(e)) {
            return new Expression("am_michael:0.8+af_bella:0.2", 1.06);
        }
        if ("angry".equals(e)) {
            return new Expression("am_michael:0.7+am_fenrir:0.3", 1.14);
        }
        if ("sad".equals(e)) {
            return new Expression("am_michael:0.8+bm_george:0.2", 0.86);
        }
        if ("scared".equals(e)) {
            return new Expression("am_michael:0.7+af_nicole:0.3", 0.92);
        }
        return new Expression("am_michael", 1.0);
    }

    /**
     * Weighted style-vector blend, renormalized so blended magnitudes match
     * the weighted average of the source norms (raw blends lose energy and
     * degrade the audio).
     */
    final float[] blendStyle(String expression, int tokenCount) {
        String[] parts = expression.split("\\+");
        float[] blended = new float[STYLE_DIM];
        double normSum = 0.0;
        double weightSum = 0.0;
        for (String part : parts) {
            String[] entry = part.trim().split(":");
            String name = entry[0].toLowerCase(Locale.ROOT);
            float weight = entry.length > 1 ? Float.parseFloat(entry[1]) : 1.0f;
            float[][] table = voices.get(name);
            if (table == null) {
                throw new IllegalArgumentException(
                        "Kokoro voice vector missing from bundle: " + name);
            }
            float[] row = table[Math.min(Math.max(tokenCount, 1), table.length) - 1];
            for (int i = 0; i < STYLE_DIM; i++) {
                blended[i] += weight * row[i];
            }
            weightSum += weight;
            double sq = 0.0;
            for (int i = 0; i < STYLE_DIM; i++) {
                sq += row[i] * (double) row[i];
            }
            normSum += weight * Math.sqrt(sq);
        }
        double sq = 0.0;
        for (int i = 0; i < STYLE_DIM; i++) {
            sq += blended[i] * (double) blended[i];
        }
        double scale = normSum / Math.max(weightSum * Math.sqrt(sq), 1e-9);
        for (int i = 0; i < STYLE_DIM; i++) {
            blended[i] *= scale;
        }
        return blended;
    }

    List<Integer> tokenizeSentence(String sentence) {
        // The G2P emits the exact phoneme stream Kokoro expects: British
        // phonemes, stress marks before stressed vowels, spaces between words
        // and punctuation preserved. Every character maps to a vocab token.
        String phonemes = g2p.phonemize(sentence);
        List<Integer> ids = new ArrayList<Integer>(phonemes.length());
        for (int i = 0; i < phonemes.length(); i++) {
            Integer id = vocab.get(String.valueOf(phonemes.charAt(i)));
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.size() > MAX_TOKENS) {
            throw new IllegalArgumentException(
                    "Sentence exceeds the Kokoro context window: " + sentence);
        }
        return ids;
    }

    final float[] infer(List<Integer> tokens, float[] style, float rate)
            throws Exception {
        int count = tokens.size();
        LongBuffer tokenBuffer = directLongBuffer(count + 2);
        tokenBuffer.put(0L);
        for (int id : tokens) {
            tokenBuffer.put((long) id);
        }
        tokenBuffer.put(0L);
        tokenBuffer.flip();

        FloatBuffer styleBuffer = directFloatBuffer(STYLE_DIM);
        styleBuffer.put(style).flip();

        FloatBuffer rateBuffer = directFloatBuffer(1);
        rateBuffer.put(rate).flip();

        try (OnnxTensor tokenTensor = OnnxTensor.createTensor(environment, tokenBuffer,
                     new long[]{1, count + 2});
             OnnxTensor styleTensor = OnnxTensor.createTensor(environment, styleBuffer,
                     new long[]{1, STYLE_DIM});
             OnnxTensor rateTensor = OnnxTensor.createTensor(environment, rateBuffer,
                     new long[]{1})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put(tokenInputName, tokenTensor);
            inputs.put("style", styleTensor);
            inputs.put("speed", rateTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                FloatBuffer buffer = output.getFloatBuffer().duplicate();
                buffer.rewind();
                float[] samples = new float[buffer.remaining()];
                buffer.get(samples);
                return condition(samples);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Kokoro inference failed: " + error, error);
        }
    }

    private static float[] condition(float[] samples) {
        double sum = 0.0;
        int finite = 0;
        for (int i = 0; i < samples.length; i++) {
            float sample = samples[i];
            if (!Float.isFinite(sample)) {
                samples[i] = 0.0f;
            } else {
                sum += sample;
                finite++;
            }
        }
        if (finite == 0) {
            return new float[0];
        }
        float dc = (float) (sum / finite);
        for (int i = 0; i < samples.length; i++) {
            samples[i] -= dc;
        }
        int first = 0;
        int last = samples.length - 1;
        while (first < last && Math.abs(samples[first]) < TRIM_THRESHOLD) {
            first++;
        }
        while (last > first && Math.abs(samples[last]) < TRIM_THRESHOLD) {
            last--;
        }
        int start = Math.max(0, first - TRIM_MARGIN);
        int end = Math.min(samples.length, last + 1 + TRIM_MARGIN);
        float[] result = start > 0 || end < samples.length
                ? Arrays.copyOfRange(samples, start, end)
                : samples;
        AudioDsp.fadeEdges(result, Math.min(EDGE_FADE, result.length / 4));
        return result;
    }

    private static LongBuffer directLongBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    private static FloatBuffer directFloatBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private volatile boolean closed;

    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        session.close();
    }
}
