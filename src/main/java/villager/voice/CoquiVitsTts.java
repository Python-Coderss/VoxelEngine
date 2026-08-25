package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural Coqui VCTK VITS base TTS, entirely in Java 8.
 *
 * Text is converted to the model's phoneme ids by {@link CoquiFrontend}
 * (cmudict G2P, no eSpeak), then run through the exported
 * {@code coqui-vctk-vits.onnx} graph via ONNX Runtime with speaker
 * {@code p226} and the Coqui inference scales {@code [0.667, 1.0, 0.8]}.
 * Sentences are synthesized separately and joined with a 0.1 s silence gap
 * (2205 samples), exactly the validated {@code villager_line.py} recipe:
 * "parts are joined with a 0.1 s silence gap at the base sample rate
 * (22.05 kHz)". The old 10000-sample (0.45 s) Coqui pad made multi-sentence
 * lines plodding and did not match the reference. Output is 22050 Hz.
 *
 * The graph is the same one Python's reference pipeline uses
 * ({@code tts_models/en/vctk/vits}), so the natural, non-robotic prosody of
 * the Python baseline is preserved without any Python, eSpeak, subprocess, or
 * network access at runtime.
 */
public final class CoquiVitsTts implements NeuralBaseTts {
    public static final int OUTPUT_RATE = 22050;
    /** 0.1 s silence at 22050 Hz, per the validated reference recipe. */
    public static final int PAD_SILENCE_SAMPLES = 2205;
    private static final long SPEAKER_P226 = 2L;
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?\\u2026])\\s+");

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final CoquiFrontend frontend;
    private final long[] sid;
    private final Map<String, String> inputMapping;

    public CoquiVitsTts(Path modelDir) throws Exception {
        frontend = new CoquiFrontend(
                modelDir.resolve("coqui-vctk-vocab.json"),
                modelDir.resolve("cmudict.dict"));
        sid = new long[]{SPEAKER_P226};
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // Dynamic-length audio graph; disable ORT's memory-pattern planner
            // so it does not retain a fixed input shape between requests.
            options.setMemoryPatternOptimization(false);
            session = environment.createSession(
                    modelDir.resolve("coqui-vctk-vits.onnx").toString(), options);
        }
        inputMapping = resolveInputs();
    }

    @Override
    public synchronized WavAudio synthesize(String text, double speed) {
        return synthesize(text, speed, "neutral");
    }

    @Override
    public WavAudio synthesize(String text, double speed, String emotion) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        String trimmed = text.trim();
        String[] sentences = SENTENCE_SPLIT.split(trimmed);
        List<float[]> pieces = new ArrayList<float[]>();
        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) {
                continue;
            }
            float[] audio = synthesizeSentence(sentence.trim(), speed, emotion);
            if (audio.length > 0) {
                pieces.add(audio);
            }
            pieces.add(new float[PAD_SILENCE_SAMPLES]);
        }
        if (!pieces.isEmpty()) {
            pieces.remove(pieces.size() - 1); // drop the final tail pad
        }
        int total = 0;
        for (float[] piece : pieces) {
            total += piece.length;
        }
        float[] output = new float[total];
        int offset = 0;
        for (float[] piece : pieces) {
            System.arraycopy(piece, 0, output, offset, piece.length);
            offset += piece.length;
        }
        return new WavAudio(OUTPUT_RATE, output);
    }

    private float[] synthesizeSentence(String sentence, double speed, String emotion) {
        int[] ids = frontend.textToIds(sentence);
        if (ids.length == 0) {
            return new float[0];
        }
        LongBuffer inputBuffer = directLongBuffer(ids.length);
        for (int id : ids) {
            inputBuffer.put(id);
        }
        inputBuffer.flip();
        LongBuffer lengthBuffer = directLongBuffer(1);
        lengthBuffer.put(ids.length).flip();
        // Emotion shifts how the base *sounds* before RVC ever sees it: angry
        // turns the noise scales down (crisper), sad/scared up (breathier),
        // like the reference's emotional delivery.
        float[] emotionScales = scalesForEmotion(emotion);
        FloatBuffer scalesBuffer = directFloatBuffer(3);
        scalesBuffer.put(new float[]{emotionScales[0], (float) Math.max(0.1, speed),
                emotionScales[1]}).flip();
        LongBuffer sidBuffer = directLongBuffer(1);
        sidBuffer.put(sid).flip();

        try (OnnxTensor input = OnnxTensor.createTensor(environment, inputBuffer,
                     new long[]{1, ids.length});
             OnnxTensor lengths = OnnxTensor.createTensor(environment, lengthBuffer,
                     new long[]{1});
             OnnxTensor scales = OnnxTensor.createTensor(environment, scalesBuffer,
                     new long[]{3});
             OnnxTensor speaker = OnnxTensor.createTensor(environment, sidBuffer,
                     new long[]{1})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<String, OnnxTensor>();
            inputs.put(inputMapping.get("input"), input);
            inputs.put(inputMapping.get("input_lengths"), lengths);
            inputs.put(inputMapping.get("scales"), scales);
            inputs.put(inputMapping.get("sid"), speaker);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                FloatBuffer buffer = output.getFloatBuffer().duplicate();
                buffer.rewind();
                float[] samples = new float[buffer.remaining()];
                buffer.get(samples);
                return samples;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Coqui VITS inference failed: " + error, error);
        }
    }

    /**
     * @return {noiseScale, noiseScaleW} for the base synthesis. The Coqui
     *         defaults (0.667, 0.8) stay neutral; anger cuts both for a crisp
     *         delivery, fear and sadness raise them for a breathier one, and
     *         happy brightens the voicing slightly.
     */
    private static float[] scalesForEmotion(String emotion) {
        if ("angry".equalsIgnoreCase(emotion)) {
            return new float[]{0.62f, 0.75f};
        }
        if ("happy".equalsIgnoreCase(emotion)) {
            return new float[]{0.72f, 0.85f};
        }
        if ("sad".equalsIgnoreCase(emotion)) {
            return new float[]{0.75f, 0.90f};
        }
        if ("scared".equalsIgnoreCase(emotion)) {
            return new float[]{0.74f, 0.88f};
        }
        return new float[]{0.667f, 0.8f};
    }

    /** Map the required roles to the actual graph input names. */
    private Map<String, String> resolveInputs() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        List<String> names = new ArrayList<String>(session.getInputNames());
        for (String name : names) {
            if (name.contains("length")) {
                mapping.put("input_lengths", name);
            } else if (name.contains("scale")) {
                mapping.put("scales", name);
            } else if (name.contains("sid") || name.contains("speaker")) {
                mapping.put("sid", name);
            } else if (name.contains("input")) {
                mapping.put("input", name);
            }
        }
        if (mapping.size() != 4) {
            throw new IllegalStateException("Unexpected Coqui VITS graph inputs: " + names);
        }
        return mapping;
    }

    private static LongBuffer directLongBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
    }

    private static FloatBuffer directFloatBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
