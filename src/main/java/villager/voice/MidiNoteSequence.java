package villager.voice;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Editable note model backed by standard Java Sound MIDI. No third-party MIDI
 * dependency is required. Tick positions are absolute and durations are in
 * MIDI ticks; the default PPQ is 480.
 */
public final class MidiNoteSequence {
    public static final int DEFAULT_PPQ = 480;
    public static final String DEFAULT_RESOURCE = "voice/villager_news_intro.mid";

    private final int resolution;
    private double bpm;
    private final List<Note> notes;

    public MidiNoteSequence(int resolution, double bpm, List<Note> notes) {
        if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");
        if (!(bpm > 0.0) || Double.isNaN(bpm) || Double.isInfinite(bpm)) {
            throw new IllegalArgumentException("bpm must be finite and positive");
        }
        this.resolution = resolution;
        this.bpm = bpm;
        this.notes = new ArrayList<Note>(notes == null
                ? Collections.<Note>emptyList() : notes);
        sortNotes();
    }

    public static MidiNoteSequence loadDefault() throws IOException {
        InputStream input = MidiNoteSequence.class.getClassLoader()
                .getResourceAsStream(DEFAULT_RESOURCE);
        if (input == null) throw new IOException("Missing MIDI resource: " + DEFAULT_RESOURCE);
        try (InputStream stream = input) {
            return read(stream);
        }
    }

    public static MidiNoteSequence load(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        try {
            return fromSequence(MidiSystem.getSequence(path.toFile()));
        } catch (InvalidMidiDataException error) {
            throw new IOException("Invalid MIDI file: " + path, error);
        }
    }

    public static MidiNoteSequence read(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        try {
            return fromSequence(MidiSystem.getSequence(input));
        } catch (InvalidMidiDataException error) {
            throw new IOException("Invalid MIDI data", error);
        }
    }

    public static MidiNoteSequence fromSequence(Sequence sequence) {
        int resolution = sequence.getResolution() > 0
                ? sequence.getResolution() : DEFAULT_PPQ;
        double bpm = 120.0;
        List<Note> result = new ArrayList<Note>();
        Map<Integer, Deque<OpenNote>> open = new HashMap<Integer, Deque<OpenNote>>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof MetaMessage) {
                    MetaMessage meta = (MetaMessage) message;
                    if (meta.getType() == 0x51 && meta.getData().length == 3) {
                        int micros = ((meta.getData()[0] & 255) << 16)
                                | ((meta.getData()[1] & 255) << 8)
                                | (meta.getData()[2] & 255);
                        if (micros > 0) bpm = 60000000.0 / micros;
                    }
                    continue;
                }
                if (!(message instanceof ShortMessage)) continue;
                ShortMessage shortMessage = (ShortMessage) message;
                int command = shortMessage.getCommand();
                int pitch = shortMessage.getData1();
                int key = (shortMessage.getChannel() << 8) | pitch;
                if (command == ShortMessage.NOTE_ON && shortMessage.getData2() > 0) {
                    Deque<OpenNote> queue = open.get(key);
                    if (queue == null) {
                        queue = new ArrayDeque<OpenNote>();
                        open.put(key, queue);
                    }
                    queue.addLast(new OpenNote(event.getTick(), shortMessage.getData2(),
                            shortMessage.getChannel()));
                } else if (command == ShortMessage.NOTE_OFF
                        || (command == ShortMessage.NOTE_ON && shortMessage.getData2() == 0)) {
                    Deque<OpenNote> queue = open.get(key);
                    if (queue != null && !queue.isEmpty()) {
                        OpenNote start = queue.removeFirst();
                        result.add(new Note(pitch, start.tick,
                                Math.max(1, event.getTick() - start.tick),
                                start.velocity, start.channel, "da"));
                    }
                }
            }
        }
        return new MidiNoteSequence(resolution, bpm, result);
    }

    public int getResolution() { return resolution; }
    public double getBpm() { return bpm; }
    public void setBpm(double value) {
        if (!(value > 0.0) || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("bpm must be finite and positive");
        }
        bpm = value;
    }
    public List<Note> getNotes() { return notes; }
    public void sortNotes() { notes.sort(Comparator.comparingLong(Note::getStartTick)); }

    public long getEndTick() {
        long end = 0;
        for (Note note : notes) end = Math.max(end, note.getEndTick());
        return end;
    }

    /** Stable content identity for cache invalidation and editor dirty state. */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((resolution + ":" + bpm).getBytes(StandardCharsets.UTF_8));
            for (Note note : notes) {
                digest.update((note.pitch + ":" + note.startTick + ":"
                        + note.durationTicks + ":" + note.velocity + ":"
                        + note.channel + ":" + note.text + ";")
                        .getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public void add(Note note) {
        if (note == null) throw new IllegalArgumentException("note must not be null");
        notes.add(note);
        sortNotes();
    }

    public void remove(Note note) { notes.remove(note); }

    /** Highest note at each onset, the explicit policy used for sung preview/runtime. */
    public List<Note> melody() {
        List<Note> result = new ArrayList<Note>();
        List<Note> sorted = new ArrayList<Note>(notes);
        sorted.sort(Comparator.comparingLong(Note::getStartTick).thenComparingInt(Note::getPitch).reversed());
        long lastTick = Long.MIN_VALUE;
        for (Note note : sorted) {
            if (note.getStartTick() != lastTick) {
                result.add(note);
                lastTick = note.getStartTick();
            }
        }
        result.sort(Comparator.comparingLong(Note::getStartTick));
        return result;
    }

    public Sequence toSequence() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, resolution);
        Track tempo = sequence.createTrack();
        tempo.add(new MidiEvent(new MetaMessage(0x03, "Villager News MIDI".getBytes(),
                "Villager News MIDI".length()), 0));
        int micros = (int) Math.max(1, Math.min(0xFFFFFF, Math.round(60000000.0 / bpm)));
        byte[] tempoData = {(byte) (micros >> 16), (byte) (micros >> 8), (byte) micros};
        tempo.add(new MidiEvent(new MetaMessage(0x51, tempoData, 3), 0));
        Track track = sequence.createTrack();
        for (Note note : notes) {
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, note.channel, note.pitch, note.velocity);
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, note.channel, note.pitch, 0);
            track.add(new MidiEvent(on, note.startTick));
            track.add(new MidiEvent(off, note.getEndTick()));
        }
        return sequence;
    }

    public void save(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            MidiSystem.write(toSequence(), 1, path.toFile());
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not write MIDI: " + path, error);
        }
    }

    private static final class OpenNote {
        final long tick; final int velocity; final int channel;
        OpenNote(long tick, int velocity, int channel) {
            this.tick = tick; this.velocity = velocity; this.channel = channel;
        }
    }

    public static final class Note {
        private int pitch;
        private long startTick;
        private long durationTicks;
        private int velocity;
        private int channel;
        private String text;

        public Note(int pitch, long startTick, long durationTicks,
                    int velocity, int channel, String text) {
            if (pitch < 0 || pitch > 127) throw new IllegalArgumentException("pitch must be 0..127");
            if (startTick < 0 || durationTicks <= 0) throw new IllegalArgumentException("invalid note timing");
            this.pitch = pitch; this.startTick = startTick; this.durationTicks = durationTicks;
            this.velocity = Math.max(1, Math.min(127, velocity));
            this.channel = Math.max(0, Math.min(15, channel));
            this.text = text == null || text.trim().isEmpty() ? "da" : text.trim();
        }
        public int getPitch() { return pitch; }
        public long getStartTick() { return startTick; }
        public long getDurationTicks() { return durationTicks; }
        public long getEndTick() { return startTick + durationTicks; }
        public int getVelocity() { return velocity; }
        public int getChannel() { return channel; }
        public String getText() { return text; }
        public void setPitch(int value) { pitch = Math.max(0, Math.min(127, value)); }
        public void setStartTick(long value) { startTick = Math.max(0, value); }
        public void setDurationTicks(long value) { durationTicks = Math.max(1, value); }
        public void setVelocity(int value) { velocity = Math.max(1, Math.min(127, value)); }
        public void setText(String value) { text = value == null || value.trim().isEmpty() ? "da" : value.trim(); }
    }
}
