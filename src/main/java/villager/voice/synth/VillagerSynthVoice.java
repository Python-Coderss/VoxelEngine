package villager.voice.synth;

import org.json.JSONObject;
import villager.voice.SpeechOptions;
import villager.voice.TextToSpeech;
import villager.voice.VoiceProfile;
import villager.voice.WavAudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parametric villager synthesis, dropped into the existing voice package.
 *
 * <p>Where {@code WindowsSapiTts} borrows a voice installed on the player's
 * machine and {@code ReferenceCorpusVoice} replays recorded clips, this renderer
 * <em>builds</em> the voice: a glottal source pushed through a five-resonator
 * vocal-tract model, driven entirely by the JSON voiceprint. It therefore has no
 * operating-system dependency, no bundled model and no network access --
 * {@link #isAvailable()} is unconditionally true, which is the property the
 * SAPI path cannot offer.</p>
 *
 * <p>It implements {@link TextToSpeech}, so it can be handed to any caller that
 * already works with the OS voice, and it also exposes
 * {@link #render(String, SpeechOptions)} matching {@code ReferenceCorpusVoice},
 * so the two are interchangeable at the call site.</p>
 *
 * <p>The voiceprint is loaded from {@code voice/voiceprint_villager_news.json}
 * on the classpath -- the same file the Python engine reads and writes -- and
 * can be replaced wholesale with {@link #VillagerSynthVoice(VoicePrint)}.</p>
 */
public final class VillagerSynthVoice implements TextToSpeech, AutoCloseable {

    /** Classpath location of the shared voiceprint. */
    public static final String DEFAULT_RESOURCE = "/voice/voiceprint_villager_news.json";

    /** System property that overrides the voiceprint path, for testing. */
    public static final String VOICEPRINT_PROPERTY = "voxel.voice.voiceprint";

    private final VoicePrint voiceprint;

    /** Load the shared voiceprint from the classpath. */
    public VillagerSynthVoice() throws IOException {
        this(loadDefaultVoicePrint());
    }

    /** Use an explicit voiceprint. */
    public VillagerSynthVoice(VoicePrint voiceprint) {
        if (voiceprint == null) {
            throw new IllegalArgumentException("voiceprint must not be null");
        }
        this.voiceprint = voiceprint;
    }

    /** Load a voiceprint from a file on disk. */
    public static VillagerSynthVoice fromFile(Path path) throws IOException {
        return new VillagerSynthVoice(VoicePrint.load(path));
    }

    /** The voiceprint this renderer speaks with. */
    public VoicePrint getVoicePrint() {
        return voiceprint;
    }

    /**
     * Always true: parametric synthesis needs no OS voice, no model file and no
     * network service, so unlike the SAPI path it cannot be missing.
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /** Render one line in the profile's voice. */
    @Override
    public WavAudio render(String text, VoiceProfile profile) throws Exception {
        SpeechOptions options = profile != null ? profile.options() : SpeechOptions.DEFAULT;
        return render(text, options);
    }

    /**
     * Render one line with speech options.
     *
     * <p>{@code speed}, {@code emotion}, {@code tone}, {@code sarcasm} and
     * {@code question} map onto the score's delivery layer. {@code pitchSemitones}
     * and {@code volume} are applied to the voiceprint itself, because they are
     * properties of the instrument rather than of the line.</p>
     */
    public WavAudio render(String text, SpeechOptions options) throws Exception {
        if (options == null) {
            options = SpeechOptions.DEFAULT;
        }
        Map<String, Object> overrides = new LinkedHashMap<String, Object>();
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        Map<String, Object> output = new LinkedHashMap<String, Object>();

        double semitones = options.getPitchSemitones();
        if (Math.abs(semitones) > 1e-6) {
            double base = voiceprint.effective().getDouble("source.f0_mean_hz", 116.0);
            source.put("f0_mean_hz", Double.valueOf(base * Math.pow(2.0, semitones / 12.0)));
        }
        double volume = options.getVolume();
        if (Math.abs(volume - 1.0) > 1e-6 && volume > 0.0) {
            output.put("gain_db",
                    Double.valueOf(20.0 * Math.log10(Math.max(0.05, volume))));
        }
        if (!source.isEmpty()) {
            overrides.put("source", source);
        }
        if (!output.isEmpty()) {
            overrides.put("output", output);
        }

        VoicePrint effectivePrint = overrides.isEmpty()
                ? voiceprint : voiceprint.merged(overrides);

        Map<String, Object> delivery = new LinkedHashMap<String, Object>();
        delivery.put("emotion", options.getEmotion());
        delivery.put("tone", Double.valueOf(options.getTone()));
        delivery.put("sarcasm", Double.valueOf(options.getSarcasm()));
        delivery.put("speed", Double.valueOf(options.getSpeed()));
        delivery.put("question", Boolean.valueOf(options.isQuestion()));

        Map<String, Object> score =
                ProsodyScore.build(text, effectivePrint, delivery, null);
        return FormantSynthesizer.render(score, effectivePrint, null);
    }

    /**
     * Re-voice an existing recording with this voice's timbre.
     *
     * <p>This is the voice-changer direction: rather than synthesising from
     * text, it takes audio that already exists and moves its pitch, duration and
     * formants to match this voiceprint.</p>
     */
    public WavAudio revoice(WavAudio input, PsolaVoiceTransform.Spec spec) {
        return PsolaVoiceTransform.apply(input, spec, voiceprint, null);
    }

    /** Render straight from a score, with no text and no prosody layer. */
    public WavAudio renderScore(Map<String, Object> score) {
        return FormantSynthesizer.render(score, voiceprint, null);
    }

    /** Read a score JSON file and render it. */
    public WavAudio renderScoreFile(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return renderScore(VoicePrint.jsonToMap(new JSONObject(text)));
    }

    @Override
    public void close() {
        // Nothing to release: no native handles, no cached audio, no threads.
    }

    /**
     * Load the shared voiceprint, honouring {@code -Dvoxel.voice.voiceprint}.
     *
     * <p>Falls back to the built-in defaults if the resource is missing, so a
     * stripped-down build still speaks rather than failing at startup.</p>
     */
    public static VoicePrint loadDefaultVoicePrint() throws IOException {
        String override = System.getProperty(VOICEPRINT_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return VoicePrint.load(java.nio.file.Paths.get(override.trim()));
        }
        InputStream stream = VillagerSynthVoice.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (stream == null) {
            return VoicePrint.defaults();
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return VoicePrint.fromJson(new String(buffer.toByteArray(),
                    StandardCharsets.UTF_8));
        } finally {
            stream.close();
        }
    }
}
