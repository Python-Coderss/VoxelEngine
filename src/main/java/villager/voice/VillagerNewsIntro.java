package villager.voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Editable, note-by-note Villager News intro arrangement.
 *
 * The bundled score is a community-made starting point, not an official score.
 * Notes are rendered as short sung syllables through the existing Java voice
 * backend, then placed on an exact beat grid. Edit the JSON asset to tune the
 * melody, timing, syllables, or title line without changing Java code.
 */
public final class VillagerNewsIntro {
    public static final String DEFAULT_RESOURCE = "voice/villager_news_intro.json";
    public static final String DEFAULT_MIDI_RESOURCE = "voice/villager_news_intro.mid";
    // The supplied transcription is written around C5 in the treble melody.
    // Keeping that written C5 at zero lets the voice profile's explicit pitch
    // offset remain a useful global adjustment without retaining the old D4
    // anchor from the discarded D-minor approximation.
    private static final int REFERENCE_MIDI = 72;

    private final String title;
    private final String key;
    private final double bpm;
    private final int version;
    private final String attribution;
    private final String sourceUrl;
    private final String sourceFingerprint;
    private final List<Note> notes;

    private VillagerNewsIntro(String title, String key, double bpm, int version,
                              String attribution, String sourceUrl,
                              String sourceFingerprint, List<Note> notes) {
        this.title = title;
        this.key = key;
        this.bpm = bpm;
        this.version = version;
        this.attribution = attribution;
        this.sourceUrl = sourceUrl;
        this.sourceFingerprint = sourceFingerprint;
        this.notes = Collections.unmodifiableList(new ArrayList<Note>(notes));
    }

    public static VillagerNewsIntro loadDefault() throws IOException {
        InputStream midi = VillagerNewsIntro.class.getClassLoader()
                .getResourceAsStream(DEFAULT_MIDI_RESOURCE);
        if (midi != null) {
            try (InputStream stream = midi) {
                return fromMidi(MidiNoteSequence.read(stream));
            }
        }
        InputStream input = VillagerNewsIntro.class.getClassLoader()
                .getResourceAsStream(DEFAULT_RESOURCE);
        if (input == null) {
            throw new IOException("Missing packaged intro assets: " + DEFAULT_MIDI_RESOURCE
                    + " and " + DEFAULT_RESOURCE);
        }
        try (InputStream stream = input) {
            return fromJson(new String(readAll(stream), StandardCharsets.UTF_8));
        }
    }

    /** Convert the highest note at each onset into the monophonic sung melody. */
    public static VillagerNewsIntro fromMidi(MidiNoteSequence midi) {
        if (midi == null) throw new IllegalArgumentException("midi must not be null");
        List<Note> converted = new ArrayList<Note>();
        for (MidiNoteSequence.Note source : midi.melody()) {
            double start = source.getStartTick() / (double) midi.getResolution();
            double duration = source.getDurationTicks() / (double) midi.getResolution();
            converted.add(new Note("da", pitchName(source.getPitch()), source.getPitch(),
                    start, Math.max(1.0 / midi.getResolution(), duration),
                    0.86, source.getVelocity() / 127.0, false));
        }
        if (converted.isEmpty()) {
            throw new IllegalArgumentException("MIDI contains no playable notes");
        }
        return new VillagerNewsIntro(
                "Villager News MIDI Intro", "MIDI", midi.getBpm(), 3,
                "User-provided MIDI arrangement; editable community material.",
                "voice/villager_news_intro.mid", midi.fingerprint(), converted);
    }

    public static VillagerNewsIntro load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        return fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    /** Public for editor/tool tests and future external asset tooling. */
    public static VillagerNewsIntro fromJson(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("intro asset is empty");
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.getJSONArray("notes");
            List<Note> parsed = new ArrayList<Note>();
            double previousStart = -1.0;
            for (int i = 0; i < array.length(); i++) {
                Note note = parseNote(array.getJSONObject(i), i);
                if (note.startBeat < previousStart) {
                    throw new IOException("notes must be ordered by startBeat (index " + i + ")");
                }
                parsed.add(note);
                previousStart = note.startBeat;
            }
            if (parsed.isEmpty()) {
                throw new IOException("intro asset must contain at least one note");
            }
            double bpm = root.optDouble("bpm", 120.0);
            if (!Double.isFinite(bpm) || bpm <= 0.0) {
                throw new IOException("bpm must be finite and greater than zero");
            }
            int version = root.optInt("version", 1);
            if (version < 1) {
                throw new IOException("version must be positive");
            }
            String source = json;
            return new VillagerNewsIntro(
                    root.optString("title", "Villager News Theme - Opening Intro"),
                    root.optString("key", "D minor"),
                    bpm,
                    version,
                    root.optString("attribution", "Community arrangement; see sourceUrl"),
                    root.optString("sourceUrl", ""),
                    sha256(source),
                    parsed);
        } catch (JSONException error) {
            throw new IOException("invalid Villager News intro JSON", error);
        }
    }

    public String getTitle() { return title; }
    public String getKey() { return key; }
    public double getBpm() { return bpm; }
    public int getVersion() { return version; }
    public String getAttribution() { return attribution; }
    public String getSourceUrl() { return sourceUrl; }
    public List<Note> getNotes() { return notes; }

    /** This arrangement requires the neural backend's arbitrary-text voice. */
    public boolean supports(VoiceMode mode) {
        return mode == VoiceMode.NEURAL;
    }

    public double getEndBeat() {
        double end = 0.0;
        for (Note note : notes) {
            end = Math.max(end, note.startBeat + note.durationBeats);
        }
        return end;
    }

    /** Include the complete asset fingerprint so edits never reuse stale audio. */
    public String cacheKey(SpeechOptions profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        return "villager-news-intro-v" + version + "-" + sourceFingerprint
                + ";" + profile.cacheKey();
    }

    /**
     * Render the arrangement with the existing neural/reference voice backend.
     * Each note is fitted to its beat duration and staccato-gated before mixing.
     */
    public WavAudio render(VillagerSynthesizer synthesizer, SpeechOptions profile)
            throws Exception {
        if (synthesizer == null) {
            throw new IllegalArgumentException("synthesizer must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        if (!supports(synthesizer.getMode())) {
            throw new IllegalStateException("Villager News intro requires neural mode; "
                    + "reference mode has no matching note syllable clips");
        }
        return renderNotes(profile, (text, options) -> synthesizer.render(text, options));
    }

    /** Render through the game-facing voice wrapper. */
    public WavAudio render(VillagerVoice voice, SpeechOptions profile)
            throws Exception {
        if (voice == null) {
            throw new IllegalArgumentException("voice must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        if (!supports(voice.getMode())) {
            throw new IllegalStateException("Villager News intro requires neural mode; "
                    + "reference mode has no matching note syllable clips");
        }
        return renderNotes(profile, (text, options) -> {
            VoiceClip clip = voice.speak(text, options);
            return new WavAudio(clip.getSampleRate(), clip.getSamples());
        });
    }

    private WavAudio renderNotes(SpeechOptions profile, NoteRenderer renderer)
            throws Exception {
        int sampleRate = VillagerSynthesizer.DEFAULT_SAMPLE_RATE;
        double playbackSpeed = profile.getEffectiveSpeed();
        double secondsPerBeat = 60.0 / (bpm * playbackSpeed);
        int totalSamples = Math.max(1, (int) Math.ceil(
                getEndBeat() * secondsPerBeat * sampleRate + sampleRate * 0.18));
        float[] mixed = new float[totalSamples];
        double singing = Math.max(0.85, profile.getSinging());
        for (Note note : notes) {
            if (note.isRest()) {
                continue;
            }
            // The asset pitches are absolute MIDI notes. SpeechOptions pitch is
            // relative to the D4 arrangement tonic, so D4 is zero semitones.
            double pitch = note.midi - REFERENCE_MIDI + profile.getPitchSemitones();
            // Preserve the written score pitches. The overall profile may
            // adjust loudness/singing and an explicit global semitone offset,
            // but emotion/mood prosody must not retune a notated melody.
            SpeechOptions noteOptions = new SpeechOptions(
                    1.0, pitch, profile.getVolume(), 0.0, 0.0,
                    "neutral", singing, 0.0, false)
                    .withQuestion(false);
            WavAudio rendered = renderer.render(note.text, noteOptions);
            float[] trimmed = trimSilence(rendered.samples);
            int noteSamples = Math.max(1, (int) Math.round(
                    note.durationBeats * secondsPerBeat * sampleRate * note.gate));
            float[] fitted = AudioDsp.resample(trimmed, noteSamples);
            AudioDsp.fadeEdges(fitted, Math.min(sampleRate / 100, fitted.length / 5));
            int offset = Math.max(0, (int) Math.round(
                    note.startBeat * secondsPerBeat * sampleRate));
            for (int i = 0; i < fitted.length && offset + i < mixed.length; i++) {
                mixed[offset + i] += fitted[i] * note.velocity;
            }
        }
        AudioDsp.normalizePeak(mixed, 0.88f);
        AudioDsp.fadeEdges(mixed, Math.min(sampleRate / 80, mixed.length / 5));
        return new WavAudio(sampleRate, mixed);
    }

    @FunctionalInterface
    private interface NoteRenderer {
        WavAudio render(String text, SpeechOptions options) throws Exception;
    }

    private static String pitchName(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[Math.floorMod(midi, 12)] + (midi / 12 - 1);
    }

    private static Note parseNote(JSONObject json, int index) throws IOException {
        double start = json.optDouble("startBeat", Double.NaN);
        double duration = json.optDouble("durationBeats", Double.NaN);
        double gate = json.optDouble("gate", 0.82);
        double velocity = json.optDouble("velocity", 0.9);
        if (!Double.isFinite(start) || start < 0.0
                || !Double.isFinite(duration) || duration <= 0.0
                || !Double.isFinite(gate) || gate <= 0.0 || gate > 1.0
                || !Double.isFinite(velocity) || velocity < 0.0 || velocity > 1.0) {
            throw new IOException("invalid timing/gate/velocity at note index " + index);
        }
        String pitchName = json.optString("pitch", "REST").trim();
        boolean rest = "REST".equalsIgnoreCase(pitchName);
        int midi = rest ? -1 : parsePitch(pitchName, index);
        String text = json.optString("text", "da").trim();
        if (!rest && text.isEmpty()) {
            throw new IOException("singing note text must not be empty at index " + index);
        }
        return new Note(text, pitchName, midi, start, duration, gate, velocity, rest);
    }

    private static int parsePitch(String value, int index) throws IOException {
        if (value.length() < 2) {
            throw new IOException("invalid pitch at note index " + index + ": " + value);
        }
        char letter = Character.toUpperCase(value.charAt(0));
        int semitone;
        switch (letter) {
            case 'C': semitone = 0; break;
            case 'D': semitone = 2; break;
            case 'E': semitone = 4; break;
            case 'F': semitone = 5; break;
            case 'G': semitone = 7; break;
            case 'A': semitone = 9; break;
            case 'B': semitone = 11; break;
            default: throw new IOException("invalid pitch at note index " + index + ": " + value);
        }
        int position = 1;
        if (position < value.length()
                && (value.charAt(position) == '#' || value.charAt(position) == 'b')) {
            semitone += value.charAt(position) == '#' ? 1 : -1;
            position++;
        }
        try {
            int octave = Integer.parseInt(value.substring(position));
            int midi = (octave + 1) * 12 + semitone;
            if (midi < 0 || midi > 127) throw new NumberFormatException();
            return midi;
        } catch (NumberFormatException error) {
            throw new IOException("invalid pitch at note index " + index + ": " + value);
        }
    }

    private static float[] trimSilence(float[] samples) {
        if (samples.length == 0) return new float[]{0.0f};
        float peak = AudioDsp.peak(samples);
        float threshold = Math.max(0.008f, peak * 0.08f);
        int first = 0;
        while (first < samples.length && Math.abs(samples[first]) < threshold) first++;
        int last = samples.length - 1;
        while (last > first && Math.abs(samples[last]) < threshold) last--;
        int padding = Math.min(samples.length / 100, VillagerSynthesizer.DEFAULT_SAMPLE_RATE / 200);
        first = Math.max(0, first - padding);
        last = Math.min(samples.length - 1, last + padding);
        float[] result = new float[last - first + 1];
        System.arraycopy(samples, first, result, 0, result.length);
        return result;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    /** One editable sequencer event. MIDI is -1 for a rest. */
    public static final class Note {
        private final String text;
        private final String pitchName;
        private final int midi;
        private final double startBeat;
        private final double durationBeats;
        private final double gate;
        private final double velocity;
        private final boolean rest;

        private Note(String text, String pitchName, int midi, double startBeat,
                     double durationBeats, double gate, double velocity, boolean rest) {
            this.text = text;
            this.pitchName = pitchName;
            this.midi = midi;
            this.startBeat = startBeat;
            this.durationBeats = durationBeats;
            this.gate = gate;
            this.velocity = velocity;
            this.rest = rest;
        }

        public String getText() { return text; }
        public String getPitchName() { return pitchName; }
        public int getMidi() { return midi; }
        public double getStartBeat() { return startBeat; }
        public double getDurationBeats() { return durationBeats; }
        public double getGate() { return gate; }
        public double getVelocity() { return velocity; }
        public boolean isRest() { return rest; }
    }
}
