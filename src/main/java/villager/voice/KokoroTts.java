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
import java.nio.file.DirectoryStream;
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
 * Kokoro-82M neural speech, running fully in-process on ONNX Runtime.
 *
 * <p>This replaces the operating-system voices (SAPI/WinRT) as the primary
 * character voice. Kokoro is an 82M-parameter open TTS model: it sounds
 * dramatically less robotic than the legacy Windows voices and ships as a
 * single ONNX graph plus small style vectors, so no service is called and no
 * Python is needed. The text frontend is {@link MisakiEnG2P}, the pure-Java
 * port of the misaki English G2P that the official Kokoro pipeline uses.</p>
 *
 * <p>Model files are not committed (they are ~350 MB). {@code
 * tools/fetch_kokoro.py} downloads them from the official kokoro-onnx release
 * and misaki dictionaries into {@code dev/voice-models/kokoro}. When the
 * directory or ONNX Runtime is missing, {@link #isAvailable()} turns false and
 * {@code CharacterVoice} keeps using the Windows voices, then recorded clips,
 * then captions — so the game still speaks on any machine.</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dvoxel.voice.kokoro.dir=<dir>} — model directory override.</li>
 *   <li>{@code -Dvoxel.voice.tts=off} — disable all local synthesis.</li>
 * </ul>
 */
public final class KokoroTts implements TextToSpeech {

    /** Kokoro's vocoder output rate. */
    public static final int OUTPUT_RATE = 24000;

    /** Directory containing the graph, config, lexicons and {@code *.bin} styles. */
    public static final String DIRECTORY_PROPERTY = "voxel.voice.kokoro.dir";

    private static final String DEFAULT_DIRECTORY = "dev/voice-models/kokoro";
    private static final String FALLBACK_DIRECTORY = "models/java";

    private static final int STYLE_DIM = 256;

    /** Context window is 512 including the two pad tokens. */
    static final int MAX_TOKENS = 510;

    /** Fallback style when a profile's voice file is not in the bundle. */
    static final String FALLBACK_VOICE = "af_heart";

    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?\u2026])\\s+");
    private static final Pattern WORD_SPLIT = Pattern.compile("\\s+");
    /** Currency and percent are read aloud, not left as symbols to the G2P. */
    private static final Pattern CURRENCY =
            Pattern.compile("\\$([0-9][0-9,]*(?:\\.[0-9]+)?)");
    private static final Pattern PERCENT =
            Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)%");
    private static final Pattern AMPERSAND = Pattern.compile("&");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t]+");

    private static final float TRIM_THRESHOLD = 0.01f;
    private static final int TRIM_MARGIN = (int) (OUTPUT_RATE * 0.06);
    private static final int EDGE_FADE = OUTPUT_RATE / 200;
    private static final int SENTENCE_GAP = (int) (OUTPUT_RATE * 0.12);

    private final Path modelDir;

    private volatile OrtEnvironment environment;
    private volatile OrtSession session;
    private volatile Map<String, Integer> vocab;
    private volatile Map<String, float[][]> voices;
    private volatile MisakiEnG2P g2p;
    private volatile String tokenInputName;
    private volatile Boolean available;

    public KokoroTts(Path modelDir) {
        this.modelDir = modelDir;
    }

    /** The model directory: property override, then the fetched bundle, then the old bundle. */
    public static Path defaultDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        Path fetched = Paths.get(DEFAULT_DIRECTORY);
        if (Files.isDirectory(fetched)) return fetched;
        return Paths.get(FALLBACK_DIRECTORY);
    }

    /** True unless {@code -Dvoxel.voice.tts=off} was passed. */
    public static boolean isEnabled() {
        String property = System.getProperty(WindowsSapiTts.ENABLED_PROPERTY, "auto");
        return !(property != null && property.trim().equalsIgnoreCase("off"));
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            boolean ok = isEnabled() && onnxRuntimePresent() && bundlePresent();
            if (ok) {
                try {
                    ensureLoaded();
                    ok = session != null;
                } catch (Throwable error) {
                    // No model, no native runtime, not enough memory: fall back.
                    System.err.println("KokoroTts: unavailable (" + error + ")");
                    ok = false;
                }
            }
            available = ok;
            return ok;
        }
    }

    /** Cheap file check so a missing bundle is a quiet "no", not an error. */
    private boolean bundlePresent() {
        return findModel() != null
                && findFirst("kokoro-config.json", "config.json") != null
                && Files.isRegularFile(modelDir.resolve("gb_gold.json"));
    }

    private static boolean onnxRuntimePresent() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment", false,
                    KokoroTts.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public WavAudio render(String text, VoiceProfile profile) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("cannot synthesize an empty line");
        }
        if (!isAvailable()) {
            throw new IOException("the Kokoro model is not installed");
        }
        VoiceProfile voice = profile == null ? VoiceProfile.NARRATOR : profile;
        String style = styleName(voice);
        double rate = clamp(1.0 + voice.ratePercent() / 100.0, 0.65, 1.5);
        try {
            return synthesize(text.trim(), rate, style);
        } catch (Exception error) {
            throw new IOException("Kokoro synthesis failed: " + error, error);
        }
    }

    /** Stable cache tag: engine plus the style vector the role resolves to. */
    public String cacheTag(VoiceProfile profile) {
        VoiceProfile voice = profile == null ? VoiceProfile.NARRATOR : profile;
        return "kokoro:" + styleName(voice);
    }

    /** The style vector name for a role, falling back if the bundle lacks it. */
    public String styleName(VoiceProfile profile) {
        Map<String, float[][]> loaded = voices;
        String wanted = profile == null ? "" : profile.kokoroVoice();
        if (loaded != null && wanted != null && loaded.containsKey(wanted)) {
            return wanted;
        }
        if (loaded != null && loaded.containsKey(FALLBACK_VOICE)) {
            return FALLBACK_VOICE;
        }
        if (loaded != null && !loaded.isEmpty()) {
            return loaded.keySet().iterator().next();
        }
        return FALLBACK_VOICE;
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private synchronized void ensureLoaded() throws Exception {
        if (session != null) return;
        Path model = findModel();
        Path config = findFirst("kokoro-config.json", "config.json");
        Path gold = modelDir.resolve("gb_gold.json");
        Path silver = modelDir.resolve("gb_silver.json");
        if (model == null || config == null || !Files.isRegularFile(gold)) {
            throw new IOException("Kokoro bundle incomplete in " + modelDir
                    + " (need " + DEFAULT_DIRECTORY + "/kokoro-v1.0.onnx, "
                    + "kokoro-config.json and gb_gold.json; run tools/fetch_kokoro.py)");
        }

        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setMemoryPatternOptimization(false);
            session = environment.createSession(model.toString(), options);
        } finally {
            options.close();
        }

        JSONObject json = new JSONObject(new String(
                Files.readAllBytes(config), StandardCharsets.UTF_8));
        JSONObject vocabJson = json.getJSONObject("vocab");
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        for (String key : vocabJson.keySet()) {
            map.put(key, vocabJson.getInt(key));
        }
        vocab = map;

        // The G2P needs both dictionaries; gb_silver is optional (gold alone
        // still pronounces every common word, the silver tier adds names).
        g2p = new MisakiEnG2P(modelDir);

        Map<String, float[][]> loadedVoices = new LinkedHashMap<String, float[][]>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(modelDir, "*.bin")) {
            for (Path bin : entries) {
                float[][] table = loadVoice(bin);
                if (table == null) continue;
                String name = bin.getFileName().toString().toLowerCase(Locale.ROOT)
                        .replace(".bin", "");
                loadedVoices.put(name, table);
            }
        }
        if (loadedVoices.isEmpty()) {
            throw new IOException("no Kokoro voice vectors (*.bin) in " + modelDir);
        }
        voices = loadedVoices;

        tokenInputName = session.getInputNames().contains("input_ids")
                ? "input_ids" : "tokens";
        System.out.println("KokoroTts: loaded " + model.getFileName()
                + " with " + loadedVoices.size() + " voices from " + modelDir);
    }

    private Path findModel() {
        String configured = System.getProperty("voxel.voice.kokoro.model");
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim());
            if (Files.isRegularFile(path)) return path;
        }
        return findFirst("kokoro-v1.0.onnx", "kokoro-v1.0.int8.onnx", "kokoro.onnx");
    }

    private Path findFirst(String... names) {
        for (String name : names) {
            Path path = modelDir.resolve(name);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    /**
     * Reads a raw little-endian float style table. The fetch script stores each
     * voice as {@code rows * 256} floats; {@code voices-v1.0.bin} itself is a
     * zip of .npy files and is ignored here (its row count is not plausible).
     */
    private static float[][] loadVoice(Path bin) throws IOException {
        long bytes = Files.size(bin);
        if (bytes <= 0 || bytes % (STYLE_DIM * 4L) != 0) return null;
        int rows = (int) (bytes / (STYLE_DIM * 4L));
        if (rows < 1 || rows > 600) return null;
        try (FileChannel channel = FileChannel.open(bin)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            FloatBuffer floats = mapped.asFloatBuffer();
            float[][] data = new float[rows][STYLE_DIM];
            for (int row = 0; row < rows; row++) {
                floats.get(data[row]);
            }
            return data;
        }
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    private WavAudio synthesize(String text, double rate, String style) throws Exception {
        List<float[]> pieces = new ArrayList<float[]>();
        int total = 0;
        for (String sentence : SENTENCE_SPLIT.split(normalize(text))) {
            if (sentence.trim().isEmpty()) continue;
            for (String part : splitForContext(sentence.trim())) {
                List<Integer> tokens = tokenize(part);
                if (tokens.isEmpty()) continue;
                float[] audio = infer(tokens, styleRow(style, tokens.size()),
                        (float) rate);
                if (audio.length == 0) continue;
                pieces.add(audio);
                total += audio.length + SENTENCE_GAP;
            }
        }
        if (pieces.isEmpty()) {
            throw new IOException("the line produced no phonemes: " + text);
        }
        float[] output = new float[total];
        int offset = 0;
        for (float[] piece : pieces) {
            System.arraycopy(piece, 0, output, offset, piece.length);
            offset += piece.length + SENTENCE_GAP;
        }
        return new WavAudio(OUTPUT_RATE, output);
    }

    /** Expands the symbols a reader would say out loud. */
    static String normalize(String text) {
        String line = CURRENCY.matcher(text).replaceAll("$1 dollars");
        line = PERCENT.matcher(line).replaceAll("$1 percent");
        line = AMPERSAND.matcher(line).replaceAll(" and ");
        return WHITESPACE.matcher(line).replaceAll(" ");
    }

    /**
     * A sentence longer than the context window is split at word boundaries so
     * short authored lines never hit the 510-token limit.
     */
    private List<String> splitForContext(String sentence) {
        List<String> parts = new ArrayList<String>();
        if (tokenize(sentence).size() <= MAX_TOKENS) {
            parts.add(sentence);
            return parts;
        }
        StringBuilder current = new StringBuilder();
        for (String word : WORD_SPLIT.split(sentence)) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (tokenize(candidate).size() > MAX_TOKENS && current.length() > 0) {
                parts.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    List<Integer> tokenize(String sentence) {
        String phonemes = g2p.phonemize(sentence);
        List<Integer> ids = new ArrayList<Integer>(phonemes.length());
        for (int i = 0; i < phonemes.length(); i++) {
            Integer id = vocab.get(String.valueOf(phonemes.charAt(i)));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private float[] styleRow(String style, int tokenCount) {
        Map<String, float[][]> loaded = voices;
        float[][] table = loaded.get(style);
        if (table == null) table = loaded.get(FALLBACK_VOICE);
        if (table == null) table = loaded.values().iterator().next();
        int row = Math.min(Math.max(tokenCount, 1), table.length) - 1;
        return table[row];
    }

    private float[] infer(List<Integer> tokens, float[] style, float rate)
            throws Exception {
        int count = tokens.size();
        // Kokoro expects a pad token at both ends; the style row is selected by
        // the number of phoneme tokens, exactly as the reference pipeline does.
        LongBuffer tokenBuffer = directLongBuffer(count + 2);
        tokenBuffer.put(0L);
        for (int id : tokens) tokenBuffer.put((long) id);
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
        if (finite == 0) return new float[0];
        float dc = (float) (sum / finite);
        for (int i = 0; i < samples.length; i++) samples[i] -= dc;

        int first = 0;
        int last = samples.length - 1;
        while (first < last && Math.abs(samples[first]) < TRIM_THRESHOLD) first++;
        while (last > first && Math.abs(samples[last]) < TRIM_THRESHOLD) last--;
        int start = Math.max(0, first - TRIM_MARGIN);
        int end = Math.min(samples.length, last + 1 + TRIM_MARGIN);
        float[] result = start > 0 || end < samples.length
                ? Arrays.copyOfRange(samples, start, end) : samples;
        AudioDsp.fadeEdges(result, Math.min(EDGE_FADE, result.length / 4));
        AudioDsp.normalizePeak(result, 0.95f);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Releases the ONNX session (the model is not needed after shutdown). */
    public synchronized void close() {
        OrtSession current = session;
        session = null;
        available = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // Closing a session cannot fail in a way the game cares about.
            }
        }
    }
}
