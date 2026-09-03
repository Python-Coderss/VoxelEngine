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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dev-only evaluation harness: renders raw Kokoro-82M base audio (no RVC) so
 * it can be compared side by side against the Coqui VITS base produced by
 * {@code Main --base-only}.
 */
public final class KokoroSampleGen {
    public static final int SAMPLE_RATE = 24000;
    private static final int STYLE_DIM = 256;
    private static final int MAX_TOKENS = 510;
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?\u2026])\\s+");
    private static final int SENTENCE_GAP = (int) (SAMPLE_RATE * 0.15);
    private static final float TRIM_THRESHOLD = 0.01f;
    private static final int TRIM_MARGIN = (int) (SAMPLE_RATE * 0.06);
    private static final int EDGE_FADE = SAMPLE_RATE / 200;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final Map<String, Integer> vocab;
    private final Map<String, float[][]> voices = new LinkedHashMap<String, float[][]>();
    private final CoquiFrontend g2p;

    public KokoroSampleGen(Path modelDir, Path coquiVocab, Path cmudict) throws Exception {
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
        g2p = new CoquiFrontend(coquiVocab, cmudict);
    }

    public void loadVoice(String name, Path bin) throws IOException {
        long bytes = Files.size(bin);
        int rows = (int) (bytes / (STYLE_DIM * 4L));
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

    /** Blend voices with weights; L2-renormalized to the weighted norm average. */
    public float[] blendStyle(String expression, int tokenCount) {
        String[] parts = expression.split("\\+");
        float[] blended = new float[STYLE_DIM];
        double normSum = 0.0;
        double weightSum = 0.0;
        for (String part : parts) {
            String[] entry = part.trim().split(":");
            String name = entry[0];
            float weight = entry.length > 1 ? Float.parseFloat(entry[1]) : 1.0f;
            float[][] table = voices.get(name);
            if (table == null) {
                throw new IllegalArgumentException("voice not loaded: " + name);
            }
            float[] row = table[Math.min(Math.max(tokenCount, 1), table.length) - 1];
            for (int i = 0; i < STYLE_DIM; i++) {
                blended[i] += weight * row[i];
            }
            weightSum += weight;
            double sq = 0.0;
            for (int i = 0; i < STYLE_DIM; i++) {
                sq += row[i] * row[i];
            }
            normSum += weight * Math.sqrt(sq);
        }
        double sq = 0.0;
        for (int i = 0; i < STYLE_DIM; i++) {
            sq += blended[i] * blended[i];
        }
        double scale = normSum / Math.max(weightSum * Math.sqrt(sq), 1e-9);
        for (int i = 0; i < STYLE_DIM; i++) {
            blended[i] *= scale;
        }
        return blended;
    }

    public List<Integer> tokenize(String text) {
        String[] sentences = SENTENCE_SPLIT.split(text.trim());
        List<Integer> ids = new ArrayList<Integer>();
        for (String sentence : sentences) {
            if (ids.size() > 0) {
                ids.add(vocab.get("."));
                ids.add(vocab.get(" "));
            }
            appendSentence(ids, sentence.trim());
        }
        if (!ids.isEmpty() && ids.get(ids.size() - 1) == vocab.get(" ")) {
            ids.remove(ids.size() - 1);
        }
        if (ids.size() > MAX_TOKENS) {
            throw new IllegalArgumentException("line too long for one window: "
                    + ids.size() + " tokens");
        }
        return ids;
    }

    private void appendSentence(List<Integer> ids, String sentence) {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= sentence.length(); i++) {
            char c = i < sentence.length() ? sentence.charAt(i) : ' ';
            boolean letterOrApostrophe = Character.isLetter(c)
                    || (c == '\'' && word.length() > 0);
            if (letterOrApostrophe && i < sentence.length()) {
                word.append(Character.toLowerCase(c));
                continue;
            }
            if (word.length() > 0) {
                if (ids.size() > 0 && ids.get(ids.size() - 1) != vocab.get(" ")) {
                    ids.add(vocab.get(" "));
                }
                for (String symbol : g2p.wordToIpa(word.toString())) {
                    addSymbol(ids, symbol);
                }
                word.setLength(0);
            }
            if (i < sentence.length() && !Character.isLetter(c)) {
                if (c == ' ') {
                    if (ids.size() > 0 && ids.get(ids.size() - 1) != vocab.get(" ")) {
                        ids.add(vocab.get(" "));
                    }
                } else if (c == '\'') {
                    // trailing possessive apostrophe: drop it
                } else {
                    addSymbol(ids, String.valueOf(c));
                }
            }
        }
    }

    private void addSymbol(List<Integer> ids, String symbol) {
        String mapped = mapToKokoro(symbol);
        if (mapped == null) {
            return;
        }
        for (int i = 0; i < mapped.length(); i++) {
            Integer id = vocab.get(String.valueOf(mapped.charAt(i)));
            if (id != null) {
                ids.add(id);
            }
        }
    }

    /** Coqui-style IPA onto Kokoro's token set (ER uses ɜ/ɚ, not ɝ). */
    private static String mapToKokoro(String symbol) {
        if ("\u025d".equals(symbol)) {
            return "\u025c";
        }
        if ("\u0251".equals(symbol)) {
            return "\u0251";
        }
        return symbol;
    }

    public synchronized WavAudio synthesize(String text, String voiceExpression,
                                            double speed) throws Exception {
        List<Integer> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return new WavAudio(SAMPLE_RATE, new float[0]);
        }
        int count = tokens.size();
        float[] style = blendStyle(voiceExpression, count);

        LongBuffer tokenBuffer = directLongBuffer(count + 2);
        tokenBuffer.put(0L);
        for (int id : tokens) {
            tokenBuffer.put((long) id);
        }
        tokenBuffer.put(0L);
        tokenBuffer.flip();

        FloatBuffer styleBuffer = directFloatBuffer(STYLE_DIM);
        styleBuffer.put(style).flip();

        FloatBuffer speedBuffer = directFloatBuffer(1);
        speedBuffer.put((float) speed).flip();

        String tokenInput = session.getInputNames().contains("input_ids")
                ? "input_ids" : "tokens";
        try (OnnxTensor tokenTensor = OnnxTensor.createTensor(environment, tokenBuffer,
                     new long[]{1, count + 2});
             OnnxTensor styleTensor = OnnxTensor.createTensor(environment, styleBuffer,
                     new long[]{1, STYLE_DIM});
             OnnxTensor speedTensor = OnnxTensor.createTensor(environment, speedBuffer,
                     new long[]{1})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put(tokenInput, tokenTensor);
            inputs.put("style", styleTensor);
            inputs.put("speed", speedTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                FloatBuffer buffer = output.getFloatBuffer().duplicate();
                buffer.rewind();
                float[] samples = new float[buffer.remaining()];
                buffer.get(samples);
                return new WavAudio(SAMPLE_RATE, condition(samples));
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

    public void close() throws Exception {
        session.close();
    }

    private static LongBuffer directLongBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    private static FloatBuffer directFloatBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** args: <kokoroModelDir> <coquiVocabJson> <cmudict> <outDir> */
    public static void main(String[] args) throws Exception {
        Path modelDir = Paths.get(args[0]);
        Path coquiVocab = Paths.get(args[1]);
        Path cmudict = Paths.get(args[2]);
        Path outDir = Paths.get(args[3]);
        Files.createDirectories(outDir);

        String[] lines = {
                "I am haggling you.",
                "The crops failed again this season.",
                "Did you see the size of that golem?",
                "Get away from my shop!",
                "Fine, fine. Take it for thirty emeralds.",
        };
        String[] expressions = {
                "neutral-am_michael",
                "happy-af_heart:0.65+af_bella:0.35",
                "angry-am_fenrir:0.7+am_adam:0.3",
        };

        KokoroSampleGen gen = new KokoroSampleGen(modelDir, coquiVocab, cmudict);
        gen.loadVoice("am_michael", modelDir.resolve("am_michael.bin"));
        gen.loadVoice("af_heart", modelDir.resolve("af_heart.bin"));
        gen.loadVoice("af_bella", modelDir.resolve("af_bella.bin"));
        gen.loadVoice("am_fenrir", modelDir.resolve("am_fenrir.bin"));
        gen.loadVoice("am_adam", modelDir.resolve("am_adam.bin"));
        try {
            for (String expression : expressions) {
                String label = expression.substring(expression.indexOf('-') + 1)
                        .replaceAll("[^a-z0-9]+", "_");
                for (int i = 0; i < lines.length; i++) {
                    long start = System.nanoTime();
                    String voice = expression.substring(expression.indexOf('-') + 1);
                    WavAudio audio = gen.synthesize(lines[i], voice, 1.0);
                    AudioDsp.normalizePeak(audio.samples, 0.9f);
                    Path out = outDir.resolve("kokoro_" + label + "_line" + (i + 1) + ".wav");
                    audio.write(out);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    System.out.println(String.format("%s  %.2fs audio in %.2fs",
                            out.getFileName(), audio.samples.length / (double) SAMPLE_RATE,
                            seconds));
                }
            }
        } finally {
            gen.close();
        }
    }
}
