package com.voxel.game;

import villager.voice.MidiNoteSequence;
import villager.voice.VillagerNewsIntro;

import java.util.ArrayList;
import java.util.List;

/**
 * One music cue from the Villager TV guide: a short, editable motif that the
 * station plays under a programme — a news theme, a product sting, a weather
 * jingle, a station ident.
 *
 * <p>Cues are pure data (pitch, start beat, duration in beats, tempo, gain) so
 * they can live in the {@code voice/tv_guide.json} script document, be asserted
 * on in unit tests without an audio device, and be rendered through the exact
 * pipeline the Villager News intro already uses — {@link #toIntro()} turns the
 * cue into a {@link VillagerNewsIntro}, which the clip voice sings in villager
 * syllables.</p>
 *
 * <p>Notes are absolute MIDI pitch numbers; {@code 0} is a valid (very low)
 * pitch, so a cue author should stay within 36..96 for anything audible.</p>
 */
public final class TvMusicCue {

    /** What job the cue does in the schedule. */
    public enum Kind {
        /** Programme titles / opening theme. */
        THEME,
        /** Short punctuation under a headline or a price. */
        STING,
        /** A sung station jingle. */
        JINGLE,
        /** An ident that names the station between programmes. */
        IDENT,
        /** A quiet bed under continuous coverage (score recap, weather). */
        BED
    }

    private final String id;
    private final String name;
    private final Kind kind;
    private final double bpm;
    private final float gain;
    private final int[] pitches;
    private final double[] startBeats;
    private final double[] durationBeats;

    private TvMusicCue(String id, String name, Kind kind, double bpm, float gain,
                       int[] pitches, double[] startBeats, double[] durationBeats) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("cue id must not be empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("cue '" + id + "' must have a name");
        }
        if (kind == null) throw new IllegalArgumentException("cue '" + id + "' needs a kind");
        if (!(bpm > 0.0) || Double.isNaN(bpm) || Double.isInfinite(bpm)) {
            throw new IllegalArgumentException("cue '" + id + "' needs a finite positive bpm");
        }
        if (pitches == null || pitches.length == 0) {
            throw new IllegalArgumentException("cue '" + id + "' must contain at least one note");
        }
        if (startBeats == null || durationBeats == null
                || startBeats.length != pitches.length
                || durationBeats.length != pitches.length) {
            throw new IllegalArgumentException("cue '" + id + "': note arrays must be the same length");
        }
        for (int i = 0; i < pitches.length; i++) {
            if (pitches[i] < 0 || pitches[i] > 127) {
                throw new IllegalArgumentException("cue '" + id + "': pitch " + pitches[i]
                        + " is outside 0..127");
            }
            if (startBeats[i] < 0.0 || durationBeats[i] <= 0.0) {
                throw new IllegalArgumentException("cue '" + id + "': invalid timing at note " + i);
            }
        }
        this.id = id.trim();
        this.name = name.trim();
        this.kind = kind;
        this.bpm = bpm;
        this.gain = Math.max(0f, Math.min(2f, gain));
        this.pitches = pitches.clone();
        this.startBeats = startBeats.clone();
        this.durationBeats = durationBeats.clone();
    }

    public static TvMusicCue of(String id, String name, Kind kind, double bpm, float gain,
                                int[] pitches, double[] startBeats, double[] durationBeats) {
        return new TvMusicCue(id, name, kind, bpm, gain, pitches, startBeats, durationBeats);
    }

    /** Parse a {@code "kind"} string, defaulting to {@link Kind#STING}. */
    public static Kind kindOf(String value) {
        if (value == null) return Kind.STING;
        try {
            return Kind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Kind.STING;
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public double getBpm() { return bpm; }
    public float getGain() { return gain; }
    public int getNoteCount() { return pitches.length; }
    public int getPitch(int index) { return pitches[index]; }
    public double getStartBeat(int index) { return startBeats[index]; }
    public double getDurationBeats(int index) { return durationBeats[index]; }

    /** Length of the cue in seconds at its own tempo. */
    public double getSeconds() {
        double end = 0.0;
        for (int i = 0; i < pitches.length; i++) {
            end = Math.max(end, startBeats[i] + durationBeats[i]);
        }
        return end * 60.0 / bpm;
    }

    /**
     * Stable content identity, so editing a cue in the guide automatically
     * produces a fresh rendered clip instead of reusing a stale cache entry.
     */
    public String cacheKey() {
        StringBuilder out = new StringBuilder();
        out.append("bpm=").append(bpm).append(";gain=").append(gain);
        for (int i = 0; i < pitches.length; i++) {
            out.append(';').append(pitches[i]).append('@').append(startBeats[i])
                    .append('+').append(durationBeats[i]);
        }
        return out.toString();
    }

    /** The cue as an editable note sequence at the shared PPQ grid. */
    public MidiNoteSequence toSequence() {
        int ppq = MidiNoteSequence.DEFAULT_PPQ;
        List<MidiNoteSequence.Note> notes = new ArrayList<>(pitches.length);
        for (int i = 0; i < pitches.length; i++) {
            long start = Math.round(startBeats[i] * ppq);
            long length = Math.max(1L, Math.round(durationBeats[i] * ppq));
            notes.add(new MidiNoteSequence.Note(pitches[i], start, length, 100, 0, "da"));
        }
        return new MidiNoteSequence(ppq, bpm, notes);
    }

    /**
     * The cue as a singable arrangement for the existing news-intro renderer.
     * Rendering still happens in the audio manager — this is pure structure.
     */
    public VillagerNewsIntro toIntro() {
        return VillagerNewsIntro.fromMidi(toSequence());
    }

    @Override
    public String toString() {
        return kind + " '" + name + "' (" + id + ", " + getNoteCount()
                + " notes, " + String.format(java.util.Locale.ROOT, "%.2f", getSeconds()) + "s)";
    }
}
