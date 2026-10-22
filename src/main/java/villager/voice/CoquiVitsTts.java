package villager.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dynamic villager speech from a fixed-voice (non-zero-shot) neural model.
 *
 * <p>This runs the Coqui VITS model trained on VCTK straight through ONNX
 * Runtime in-process: no service, no Python, no voice cloning. The model
 * ships a closed inventory of 108 trained VCTK speakers ({@code p225} ...
 * {@code p376}); every villager is assigned one of those voices
 * deterministically from its entity id, so the same villager always sounds
 * like itself and no arbitrary voice is ever synthesized. A line no recorded
 * clip can speak is rendered here; anything a clip can say keeps the
 * recording (see {@link com.voxel.audio.VillagerAudioManager}).</p>
 *
 * <p>The text frontend is the same {@link MisakiEnG2P} British-IPA G2P the
 * Kokoro character voices use: its phoneme symbols line up with the Coqui
 * phoneme vocabulary, so numbers, contractions and stress marks all survive.
 * The VITS blank convention (blank id between every symbol, including both
 * ends) matches the reference Coqui inference path.</p>
 *
 * <p>Model files are not committed. They live beside the Kokoro bundle in
 * {@code dev/voice-models} (see {@code tools/fetch_kokoro.py} for the Kokoro
 * half); the Coqui files are {@code coqui-vctk-vits.onnx},
 * {@code coqui-vctk-vocab.json}, {@code coqui-vctk-speaker_ids.json} and the
 * training {@code coqui-vctk-config.json}. When the bundle or ONNX Runtime
 * is missing, {@link #isAvailable()} turns false and villagers keep their
 * legacy clip-only voice.</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dvoxel.voice.coqui.dir=<dir>} — model directory override.</li>
 *   <li>{@code -Dvoxel.voice.tts=off} — disable all local synthesis.</li>
 * </ul>
 */
public final class CoquiVitsTts implements TextToSpeech {

    /** VCTK models render wideband speech at this rate. */
    public static final int OUTPUT_RATE = 22050;

    /** Directory holding the Coqui bundle (files, not committed). */
    public static final String DIRECTORY_PROPERTY = "voxel.voice.coqui.dir";

    private static final String DEFAULT_DIRECTORY = "dev/voice-models";
    private static final String MODEL_FILE = "coqui-vctk-vits.onnx";
    private static final String VOCAB_FILE = "coqui-vctk-vocab.json";
    private static final String SPEAKERS_FILE = "coqui-vctk-speaker_ids.json";

    /** Coqui inference defaults: noise variation, duration scale, decoder noise. */
    private static final float BASE_NOISE = 0.667f;
    private static final float BASE_NOISE_W = 0.8f;

    /** Split long lines here so one inference never holds a whole paragraph. */
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?\\u2026])\\s+");
    /** Cap per inference: VITS attention stays sharp on short windows. */
    private static final int MAX_PHONEMES_PER_PART = 360;
    /** 0.1 s gap between sentences, matching the old base-TTS join. */
    private static final int SENTENCE_GAP = OUTPUT_RATE / 10;

    private static final float TRIM_THRESHOLD = 0.004f;
    private static final int TRIM_MARGIN = (int) (OUTPUT_RATE * 0.05);
    private static final int EDGE_FADE = OUTPUT_RATE / 200;

    private final Path modelDir;

    private volatile OrtEnvironment environment;
    private volatile OrtSession session;
    private volatile Map<String, Integer> vocab;
    private volatile int blankId;
    private volatile List<String> speakerNames;
    private volatile List<Integer> speakerIds;
    private volatile MisakiEnG2P g2p;
    private volatile Boolean available;

    public CoquiVitsTts(Path modelDir) {
        this.modelDir = modelDir;
    }

    /** The model directory: property override, else the fetched bundle. */
    public static Path defaultDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(DEFAULT_DIRECTORY);
    }

    /** True unless {@code -Dvoxel.voice.tts=off} was passed. */
    public static boolean isEnabled() {
        return KokoroTts.isEnabled();
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
                    System.err.println("CoquiVitsTts: unavailable (" + error + ")");
                    ok = false;
                }
            }
            available = ok;
            return ok;
        }
    }

    /** Cheap file check so a missing bundle is a quiet "no", not an error. */
    private boolean bundlePresent() {
        return Files.isRegularFile(modelDir.resolve(MODEL_FILE))
                && Files.isRegularFile(modelDir.resolve(VOCAB_FILE))
                && Files.isRegularFile(modelDir.resolve(SPEAKERS_FILE))
                && Files.isRegularFile(KokoroTts.defaultDirectory().resolve("gb_gold.json"));
    }

    private static boolean onnxRuntimePresent() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment", false,
                    CoquiVitsTts.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public WavAudio render(String text, VoiceProfile profile) throws IOException {
        SpeechOptions options = profile == null ? SpeechOptions.DEFAULT : profile.options();
        return renderLine(text, options, 0);
    }

    /**
     * Render one line in the fixed voice assigned to {@code voiceSeed}
     * (normally the villager's entity id).
     */
    public WavAudio renderLine(String text, SpeechOptions options, int voiceSeed)
            throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("cannot synthesize an empty line");
        }
        if (!isAvailable()) {
            throw new IOException("the Coqui bundle is not installed");
        }
        SpeechOptions delivery = options == null ? SpeechOptions.DEFAULT : options;
        String speaker = speakerForSeed(voiceSeed);
        int sid = speakerIdForSeed(voiceSeed);
        try {
            return synthesize(text.trim(), delivery, speaker, sid);
        } catch (Exception error) {
            throw new IOException("Coqui synthesis failed: " + error, error);
        }
    }

    /** Stable cache tag: engine plus the fixed voice a seed resolves to. */
    public String cacheTag(int voiceSeed) {
        return "coqui:" + speakerForSeed(voiceSeed);
    }

    /**
     * The fixed VCTK voice for a seed. Only {@code pNNN} entries are used
     * (the inventory's stray non-speaker rows are skipped), sorted by number
     * so the mapping is stable across runs.
     */
    public String speakerForSeed(int voiceSeed) {
        List<String> names = speakerNames;
        if (names == null || names.isEmpty()) return "p225";
        return names.get(Math.floorMod(voiceSeed, names.size()));
    }

    private int speakerIdForSeed(int voiceSeed) {
        List<String> names = speakerNames;
        List<Integer> ids = speakerIds;
        if (names == null || ids == null || names.isEmpty()) return 1;
        return ids.get(Math.floorMod(voiceSeed, names.size()));
    }

    /** Sorted fixed-voice inventory (for tests and diagnostics). */
    List<String> speakerInventory() {
        List<String> names = speakerNames;
        return names == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(names);
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private synchronized void ensureLoaded() throws Exception {
        if (session != null) return;
        Path model = modelDir.resolve(MODEL_FILE);

        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setMemoryPatternOptimization(false);
            session = environment.createSession(model.toString(), options);
        } finally {
            options.close();
        }

        JSONArray array = new JSONArray(new String(
                Files.readAllBytes(modelDir.resolve(VOCAB_FILE)), StandardCharsets.UTF_8));
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (int i = 0; i < array.length(); i++) {
            map.put(array.getString(i), i);
        }
        vocab = map;
        Integer blank = map.get("<BLNK>");
        blankId = blank == null ? array.length() - 1 : blank;

        JSONObject speakers = new JSONObject(new String(
                Files.readAllBytes(modelDir.resolve(SPEAKERS_FILE)), StandardCharsets.UTF_8));
        List<String> names = new ArrayList<String>();
        List<Integer> ids = new ArrayList<Integer>();
        List<Speaker> parsed = new ArrayList<Speaker>();
        for (String key : speakers.keySet()) {
            String name = key.trim();
            if (!name.matches("p[0-9]+")) continue;
            parsed.add(new Speaker(name,
                    Integer.parseInt(name.substring(1)), speakers.getInt(key)));
        }
        Collections.sort(parsed, new java.util.Comparator<Speaker>() {
            public int compare(Speaker a, Speaker b) {
                return a.number - b.number;
            }
        });
        for (Speaker speaker : parsed) {
            names.add(speaker.name);
            ids.add(speaker.id);
        }
        if (names.isEmpty()) {
            throw new IOException("no VCTK speakers in " + modelDir.resolve(SPEAKERS_FILE));
        }
        speakerNames = names;
        speakerIds = ids;

        // The English frontend dictionaries ship with the Kokoro bundle.
        g2p = new MisakiEnG2P(KokoroTts.defaultDirectory());

        System.out.println("CoquiVitsTts: loaded " + MODEL_FILE
                + " with " + names.size() + " fixed voices from " + modelDir);
    }

    private static final class Speaker {
        final String name;
        final int number;
        final int id;

        Speaker(String name, int number, int id) {
            this.name = name;
            this.number = number;
            this.id = id;
        }
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    private WavAudio synthesize(String text, SpeechOptions options,
                                String speaker, int sid) throws Exception {
        List<float[]> pieces = new ArrayList<float[]>();
        int total = 0;
        for (String sentence : SENTENCE_SPLIT.split(KokoroTts.normalize(text))) {
            if (sentence.trim().isEmpty()) continue;
            for (String part : splitForContext(sentence.trim())) {
                List<Integer> tokens = tokenize(part);
                if (tokens.isEmpty()) continue;
                float[] audio = infer(tokens, sid, scales(options));
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
        return new WavAudio(OUTPUT_RATE, shape(output, options));
    }

    /**
     * Full delivery control on top of the model's own emotion scales: pitch,
     * spectral tilt, sarcasm, interrogative lift and volume. The model already
     * stretches or hurries the line from {@link SpeechOptions#getEffectiveSpeed()}
     * (length scale), so pitch here is the only extra duration factor: it
     * resamples, exactly like the recorded-clip voice, so a -4 semitone line
     * is both lower and slightly longer — a breath, not a chipmunk.
     */
    static float[] shape(float[] samples, SpeechOptions options) {
        if (samples.length == 0) return samples;
        double pitch = clamp(options.getPitchSemitones()
                + emotionPitch(options.getEmotion()), -6.0, 6.0);
        if (Math.abs(pitch) > 1.0e-3) {
            // Shorter output = higher pitch: +12 semitones halves the duration.
            double factor = Math.pow(2.0, pitch / 12.0);
            int length = Math.max(1, (int) Math.round(samples.length / factor));
            samples = AudioDsp.resample(samples, length);
        }
        AudioDsp.applyToneTilt(samples, options.getEffectiveSpectralTilt());
        if (options.getSarcasm() > 0.0) {
            AudioDsp.applyGain(samples, 1.0 - options.getSarcasm() * 0.08);
        }
        if (options.isQuestion()) {
            AudioDsp.applyQuestionEnding(samples);
        }
        AudioDsp.applyGain(samples, options.getEffectiveVolume());
        AudioDsp.normalizePeak(samples, 0.95f);
        AudioDsp.fadeEdges(samples, Math.min(EDGE_FADE, samples.length / 4));
        return samples;
    }

    /** Emotion's small pitch contribution, matching the recorded-clip voice. */
    private static double emotionPitch(String emotion) {
        if ("happy".equals(emotion)) return 1.0;
        if ("sad".equals(emotion)) return -1.5;
        if ("angry".equals(emotion)) return 0.8;
        if ("scared".equals(emotion)) return 2.0;
        return 0.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * A sentence longer than the context window is split at word boundaries
     * so long villager lines never smear VITS attention.
     */
    private List<String> splitForContext(String sentence) {
        List<String> parts = new ArrayList<String>();
        if (tokenize(sentence).size() <= MAX_PHONEMES_PER_PART) {
            parts.add(sentence);
            return parts;
        }
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (tokenize(candidate).size() > MAX_PHONEMES_PER_PART && current.length() > 0) {
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

    /** Misaki IPA output mapped onto the Coqui phoneme vocabulary. */
    List<Integer> tokenize(String sentence) {
        String phonemes = g2p.phonemize(sentence);
        List<Integer> ids = new ArrayList<Integer>(phonemes.length());
        for (int i = 0; i < phonemes.length();) {
            int code = phonemes.codePointAt(i);
            i += Character.charCount(code);
            Integer id = vocab.get(new String(Character.toChars(code)));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /**
     * Emotion-aware VITS scales. The delivery rides the model's own noise
     * inputs (angry crisper, sad/scared breathier, happy brighter) instead of
     * post-processing, and the line duration follows the profile speed: a
     * length scale above 1 stretches, below 1 hurries.
     */
    static float[] scales(SpeechOptions options) {
        String emotion = options.getEmotion();
        float noise = BASE_NOISE;
        float noiseW = BASE_NOISE_W;
        if ("angry".equals(emotion)) {
            noise = 0.50f;
            noiseW = 0.70f;
        } else if ("sad".equals(emotion)) {
            noise = 0.85f;
            noiseW = 0.95f;
        } else if ("scared".equals(emotion)) {
            noise = 0.80f;
            noiseW = 0.90f;
        } else if ("happy".equals(emotion)) {
            noise = 0.72f;
            noiseW = 0.85f;
        }
        double speed = options.getEffectiveSpeed();
        float length = (float) (1.0 / Math.max(0.5, Math.min(2.0, speed)));
        if (length < 0.5f) length = 0.5f;
        if (length > 2.0f) length = 2.0f;
        return new float[]{noise, length, noiseW};
    }

    private float[] infer(List<Integer> tokens, int sid, float[] scales) throws Exception {
        int count = tokens.size();
        // VITS blank convention: a blank between every symbol, both ends.
        LongBuffer tokenBuffer = directLongBuffer(count * 2 + 1);
        tokenBuffer.put((long) blankId);
        for (int id : tokens) {
            tokenBuffer.put((long) id);
            tokenBuffer.put((long) blankId);
        }
        tokenBuffer.flip();

        LongBuffer lengthBuffer = directLongBuffer(1);
        lengthBuffer.put((long) (count * 2 + 1));
        lengthBuffer.flip();

        FloatBuffer scalesBuffer = directFloatBuffer(3);
        scalesBuffer.put(scales).flip();

        LongBuffer sidBuffer = directLongBuffer(1);
        sidBuffer.put((long) sid);
        sidBuffer.flip();

        try (OnnxTensor tokenTensor = OnnxTensor.createTensor(environment, tokenBuffer,
                     new long[]{1, count * 2 + 1});
             OnnxTensor lengthTensor = OnnxTensor.createTensor(environment, lengthBuffer,
                     new long[]{1});
             OnnxTensor scalesTensor = OnnxTensor.createTensor(environment, scalesBuffer,
                     new long[]{3});
             OnnxTensor sidTensor = OnnxTensor.createTensor(environment, sidBuffer,
                     new long[]{1})) {
            Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
            inputs.put("input", tokenTensor);
            inputs.put("input_lengths", lengthTensor);
            inputs.put("scales", scalesTensor);
            inputs.put("sid", sidTensor);
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

    /**
     * Output conditioning shared with the artifact audit: strip DC, trim
     * digital-silence edges with a margin, fade the cuts, normalize the peak.
     * Every stage only removes non-signal — no EQ, no gating that could pump.
     */
    static float[] condition(float[] samples) {
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
                ? java.util.Arrays.copyOfRange(samples, start, end) : samples;
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

    /** Release the ONNX session (the model is not needed after shutdown). */
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
