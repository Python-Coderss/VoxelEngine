package villager.voice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Public game API for the Kokoro-82M base TTS with RVC conversion and full
 * per-word emotion control.
 *
 * <p>This is the same full pipeline that produced the {@code samples/full-compare/kokoro/}
 * samples: Kokoro-82M base TTS → RVC villager conversion → natural source mix →
 * denoise → spectral tilt. The difference from {@link VillagerVoice} is that
 * this uses Kokoro as the base TTS and exposes per-word emotion snapshots so
 * each word can have its own emotion, speed, pitch, volume, and tone.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (KokoroVoice voice = new KokoroVoice()) {
 *     // Simple: one emotion for the whole line (like the kokoro samples).
 *     VoiceClip clip = voice.speak("I am haggling you.", "happy");
 *
 *     // Full control: per-word snapshots loaded from JSON.
 *     VoiceClip clip = voice.speakWithPreset(Path.of("dialogue/preset.json"));
 * }
 * }</pre>
 *
 * <h2>Voice-crack guarantee</h2>
 * <p>The original kokoro samples render each whole line in a single Kokoro
 * inference, which is why their intonation is smooth. Splicing audio mid-word
 * (or per syllable) produces audible cracks because each fragment carries its
 * own sentence-level pitch contour. Therefore:
 * <ul>
 *   <li>A line whose words all resolve to the same state is rendered in
 *       exactly one Kokoro call — bit-identical to the original samples.</li>
 *   <li>A line with mixed states is split only at word boundaries where the
 *       state actually changes. Each chunk keeps its words' punctuation and is
 *       rendered through the identical full pipeline, then crossfade-blended
 *       with its neighbors over 60 ms.</li>
 * </ul>
 */
public final class KokoroVoice implements AutoCloseable {
    private final KokoroTts kokoro;
    private final CustomRvcModel rvc;
    private final Path modelDirectory;
    private boolean closed;

    /** Crossfade between emotion-change segments: 60 ms at 24 kHz. */
    private static final int SEGMENT_CROSSFADE = 24000 * 3 / 50;

    /**
     * Load Kokoro + RVC from the default model bundle.
     * Kokoro models are expected in {@code models/java/} alongside the
     * Coqui/RVC models, or in {@code dev/kokoro-eval/} for evaluation.
     */
    public KokoroVoice() throws Exception {
        this(new java.io.File("models" + java.io.File.separator + "java").toPath());
    }

    /**
     * Load Kokoro + RVC from an explicit model directory.
     *
     * @param modelDirectory directory containing kokoro-v1.0.onnx,
     *        kokoro-config.json, *.bin style vectors, and the RVC models
     */
    public KokoroVoice(Path modelDirectory) throws Exception {
        this(modelDirectory, modelDirectory);
    }

    /**
     * Load Kokoro from one directory and RVC models from another.
     * Useful when Kokoro models live in {@code dev/kokoro-eval/} but RVC
     * models are in {@code models/java/}.
     */
    public KokoroVoice(Path kokoroDir, Path rvcDir) throws Exception {
        this.modelDirectory = kokoroDir;
        kokoro = new KokoroTts(kokoroDir);
        rvc = new CustomRvcModel(
                rvcDir.resolve("vec-768-layer-12.onnx"),
                rvcDir.resolve("rvc-villager.onnx"),
                rvcDir.resolve("rmvpe.onnx"),
                rvcDir.resolve("rvc-villager-index.bin"));
    }

    /** Backend currently used by this voice instance (always NEURAL for Kokoro). */
    public VoiceMode getMode() {
        return VoiceMode.NEURAL;
    }

    /**
     * Synthesize with a single emotion for the entire line.
     * This is the simplest mode, equivalent to the kokoro/ samples.
     */
    public VoiceClip speak(String text, String emotion) throws Exception {
        return speak(text, emotion, 1.0, 0.0, 1.0, 0.0);
    }

    /**
     * Synthesize with a single emotion and full per-line controls.
     * Speed, pitch, volume, and tone apply to the whole line.
     */
    public VoiceClip speak(String text, String emotion, double speed, double pitch,
                           double volume, double tone) throws Exception {
        ensureOpen();
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        return renderChunk(text,
                new SyllableSnapshot.SnapshotState(emotion, speed, pitch, volume, tone));
    }

    /**
     * Synthesize with per-word emotion snapshots loaded from a JSON preset.
     * The preset file format is described in {@link SyllableSnapshot}.
     */
    public VoiceClip speakWithPreset(Path presetPath) throws Exception {
        ensureOpen();
        SpeechPreset preset = SpeechPreset.load(presetPath);
        return speakWithSnapshots(preset.line, preset.snapshots);
    }

    /**
     * Synthesize with per-word emotion snapshots (programmatic).
     * Each word gets its own emotion, speed, pitch, volume, and tone; words
     * sharing a state are grouped so the line splits as few times as possible.
     */
    public VoiceClip speakWithSnapshots(String text, List<SyllableSnapshot> snapshots)
            throws Exception {
        ensureOpen();
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        List<Word> words = splitWords(text);
        if (words.isEmpty()) {
            return speak(text, "neutral");
        }

        // Resolve the effective state for each word.
        SyllableSnapshot.SnapshotState previous = null;
        for (Word word : words) {
            previous = SyllableSnapshot.resolveForWord(snapshots, word.matchText, previous);
            word.state = previous;
        }

        // Fast path: uniform state -> ONE Kokoro call for the whole line,
        // preserving the original punctuation. Bit-identical to the original
        // kokoro samples, so no cracks are possible.
        SyllableSnapshot.SnapshotState first = words.get(0).state;
        boolean uniform = true;
        for (Word word : words) {
            if (!word.state.sameAs(first)) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return renderChunk(text, first);
        }

        // Mixed states: group runs of same-state words into segments.
        List<Segment> segments = new ArrayList<Segment>();
        int start = 0;
        for (int i = 1; i < words.size(); i++) {
            if (!words.get(i).state.sameAs(words.get(i - 1).state)) {
                segments.add(new Segment(start, i));
                start = i;
            }
        }
        segments.add(new Segment(start, words.size()));

        // Render each segment through the identical full pipeline, then
        // crossfade-concatenate. Splices sit at word boundaries only.
        List<float[]> pieces = new ArrayList<float[]>();
        for (int s = 0; s < segments.size(); s++) {
            Segment segment = segments.get(s);
            StringBuilder chunk = new StringBuilder();
            for (int i = segment.start; i < segment.end; i++) {
                if (chunk.length() > 0) {
                    chunk.append(' ');
                }
                chunk.append(words.get(i).renderText);
            }
            // Give non-terminal chunks a prosodic landing so Kokoro produces a
            // natural falling contour instead of a mid-sentence dangling pitch.
            String chunkText = chunk.toString();
            if (s < segments.size() - 1 && !endsWithTerminal(chunkText)) {
                chunkText = chunkText + ",";
            }
            float[] audio = renderChunk(chunkText, words.get(segment.start).state).getSamples();
            if (audio.length > 0) {
                pieces.add(audio);
            }
        }
        if (pieces.size() == 1) {
            return new VoiceClip(new WavAudio(KokoroTts.OUTPUT_RATE, pieces.get(0)));
        }
        float[] output = AudioDsp.concat(pieces, SEGMENT_CROSSFADE);
        return new VoiceClip(new WavAudio(KokoroTts.OUTPUT_RATE, output));
    }

    /** True when the text already ends with sentence punctuation. */
    private static boolean endsWithTerminal(String text) {
        String value = text.trim();
        while (value.endsWith("\"") || value.endsWith("'") || value.endsWith(")")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.endsWith(".") || value.endsWith("!") || value.endsWith("?")
                || value.endsWith(",") || value.endsWith("\u2026")
                || value.endsWith(":") || value.endsWith(";");
    }

    /**
     * Render one chunk of text with one emotion state through the exact
     * original pipeline: Kokoro base -> RVC -> natural mix -> energy mask ->
     * unvoiced boost -> denoise -> tilt -> normalize. Both the single-emotion
     * path and every mixed-emotion segment use this same chain.
     */
    private VoiceClip renderChunk(String chunkText,
                                  SyllableSnapshot.SnapshotState state) throws Exception {
        SpeechOptions options = state.toSpeechOptions();
        // Match VillagerSynthesizer: when no explicit question flag is set,
        // punctuation supplies the interrogative cue ("...golem?" gets the rise).
        if (!options.isQuestion() && SpeechOptions.looksLikeQuestion(chunkText)) {
            options = options.withQuestion(true);
        }
        WavAudio base = kokoro.synthesize(chunkText, options.getEffectiveSpeed(),
                state.emotion);
        WavAudio converted = rvc.convert(base, options.getEffectivePitchSemitones(),
                options.getSinging(), options.getEmotion(), options.getSarcasm(),
                options.isQuestion());
        // Natural source mix (same as VillagerSynthesizer pipeline).
        mixNaturalSource(converted, base, options.getEffectiveNaturalSourceMix());
        WavAudio sourceAtOutputRate = base.resampled(converted.sampleRate);
        AudioDsp.applySourceEnergyMask(converted.samples, sourceAtOutputRate.samples,
                converted.sampleRate);
        AudioDsp.applyUnvoicedSourceBoost(converted.samples, sourceAtOutputRate.samples,
                converted.sampleRate, options.getEffectiveNaturalSourceMix(), 0.60);
        AudioDsp.applySpeechDenoise(converted.samples, converted.sampleRate);
        AudioDsp.fadeEdges(converted.samples,
                Math.min(converted.sampleRate / 100, converted.samples.length / 5));
        AudioDsp.applyToneTilt(converted.samples, options.getEffectiveSpectralTilt());
        AudioDsp.normalizePeak(converted.samples, 0.85f);
        AudioDsp.applyGain(converted.samples, options.getEffectiveVolume());
        AudioDsp.normalizePeak(converted.samples, 0.98f);
        return new VoiceClip(converted.resampled(24000));
    }

    /** One whitespace-delimited word of the line. */
    private static final class Word {
        /** Raw text including any trailing punctuation (what gets rendered). */
        final String renderText;
        /** Punctuation-stripped lowercase text (what snapshots match against). */
        final String matchText;
        SyllableSnapshot.SnapshotState state;

        Word(String renderText, String matchText) {
            this.renderText = renderText;
            this.matchText = matchText;
        }
    }

    private static List<Word> splitWords(String text) {
        List<Word> words = new ArrayList<Word>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String match = token.toLowerCase(Locale.ROOT)
                    .replaceAll("^[\"'\\u201c(]+", "")
                    .replaceAll("[.,!?\\u2026:;\"'\\u201d)]+$", "");
            if (!match.isEmpty()) {
                words.add(new Word(token, match));
            }
        }
        return words;
    }

    /** A contiguous run of words sharing one state. */
    private static final class Segment {
        final int start;
        final int end; // exclusive

        Segment(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /** Blend natural VITS/Kokoro source with RVC-converted audio. */
    private static void mixNaturalSource(WavAudio converted, WavAudio base, double mix) {
        double naturalMix = Math.max(0.0, Math.min(0.5, mix));
        WavAudio natural = base.resampled(converted.sampleRate);
        int count = Math.min(converted.samples.length, natural.samples.length);
        for (int i = 0; i < count; i++) {
            converted.samples[i] = converted.samples[i] * (float) (1.0 - naturalMix)
                    + natural.samples[i] * (float) naturalMix;
        }
    }

    /** Close the underlying ONNX sessions. */
    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        rvc.close();
        kokoro.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("KokoroVoice is already closed");
        }
    }

    /** Load per-word emotion snapshots from a JSON preset file. */
    public static final class SpeechPreset {
        public final String line;
        public final List<SyllableSnapshot> snapshots;

        SpeechPreset(String line, List<SyllableSnapshot> snapshots) {
            this.line = line;
            this.snapshots = snapshots;
        }

        /**
         * Load a JSON preset file. Expected format:
         * <pre>{@code
         * {
         *   "line": "I am haggling you.",
         *   "snapshots": [
         *     {"text": "I",        "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.0},
         *     {"text": "am",       "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.1},
         *     {"text": "haggling", "emotion": "happy",   "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.3},
         *     {"text": "you",      "emotion": "neutral", "speed": 1.0, "pitch": 0.0, "volume": 1.0, "tone": 0.0}
         *   ]
         * }
         * }</pre>
         */
        public static SpeechPreset load(Path path) throws Exception {
            String content = new String(Files.readAllBytes(path),
                    java.nio.charset.StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            String line = json.optString("line", "");
            List<SyllableSnapshot> snapshots = new ArrayList<SyllableSnapshot>();
            if (json.has("snapshots")) {
                Object snapshotsObj = json.get("snapshots");
                if (snapshotsObj instanceof JSONObject) {
                    snapshots.add(parseSnapshot((JSONObject) snapshotsObj));
                } else {
                    for (Object obj : json.getJSONArray("snapshots")) {
                        snapshots.add(parseSnapshot((JSONObject) obj));
                    }
                }
            }
            return new SpeechPreset(line, snapshots);
        }

        private static SyllableSnapshot parseSnapshot(JSONObject json) {
            return new SyllableSnapshot(
                    json.optString("text", ""),
                    json.optString("emotion", "neutral"),
                    json.optDouble("speed", 1.0),
                    json.optDouble("pitch", 0.0),
                    json.optDouble("volume", 1.0),
                    json.optDouble("tone", 0.0));
        }
    }
}
