package com.voxel.cinematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A timed list of spoken lines for a cinematic scene — the missing half of an
 * MCSM cutscene, where shots carry dialogue, not just title cards.
 *
 * <p>Each line has a scene-relative time, a speaker and its text. As the scene
 * plays, {@link CinematicSystem} asks for the lines whose time has just passed
 * and speaks them through {@link com.voxel.ai.speech.CharacterVoice}: the
 * player, narrator, builders and bosses each get their own voice, and the
 * caption is always the text that was actually spoken.</p>
 *
 * <p>Pure and allocation-light so it is unit-tested directly without a GL
 * context.</p>
 */
public final class CinematicScript {

    /** One spoken line at a scene-relative time. */
    public static final class Line {
        public final float at;
        public final String speaker;
        public final String text;

        public Line(float at, String speaker, String text) {
            this.at = at;
            this.speaker = speaker == null || speaker.isEmpty() ? "Narrator" : speaker;
            this.text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return at + "s " + speaker + ": " + text;
        }
    }

    private final List<Line> lines = new ArrayList<>();

    /** Append a line. Returns this for chaining. */
    public CinematicScript at(float seconds, String speaker, String text) {
        lines.add(new Line(seconds, speaker, text));
        return this;
    }

    /**
     * The player thinking to themselves during a cutscene. Monologue is spoken
     * in Jesse's own voice (Male Jesse unless the player picked the other
     * Jesse) and is never rate-limited, because an inner thought belongs to
     * the beat that triggered it.
     */
    public CinematicScript monologue(float seconds, String text) {
        return at(seconds, MONOLOGUE_SPEAKER, text);
    }

    /** Speaker label that marks a line as player monologue. */
    public static final String MONOLOGUE_SPEAKER = "Jesse";

    /** True when a line is the player's inner voice. */
    public static boolean isMonologue(Line line) {
        return line != null && MONOLOGUE_SPEAKER.equalsIgnoreCase(line.speaker);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    /**
     * The lines whose time lies in {@code (prevTime, nowTime]}. A line at time
     * 0 additionally fires on the very first window (when {@code prevTime} is
     * still 0), then never again. Consecutive real windows are (t1,t2],
     * (t2,t3], ... so every line fires exactly once.
     */
    public List<Line> due(float prevTime, float nowTime) {
        List<Line> due = null;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean fire = line.at > prevTime && line.at <= nowTime;
            if (!fire && line.at <= 0f && prevTime <= 0f && nowTime >= 0f) {
                fire = true; // time-0 cue on the first tick
            }
            if (fire) {
                if (due == null) due = new ArrayList<>();
                due.add(line);
            }
        }
        return due == null ? Collections.<Line>emptyList() : due;
    }

    /** Convenience: a one-line script. */
    public static CinematicScript of(String speaker, String text) {
        return new CinematicScript().at(0f, speaker, text);
    }

    /**
     * A two-speaker exchange: {@code speakerA} opens at {@code start}, and
     * {@code speakerB} answers {@code gap} seconds later (one beat after the
     * first line has had time to be read). The shot/reverse-shot script for a
     * boss taunt, a builder greeting, or a narrator framing a reveal.
     */
    public static CinematicScript exchange(float start, float gap,
                                           String speakerA, String lineA,
                                           String speakerB, String lineB) {
        if (gap < 0.1f) gap = 0.1f; // never stack two lines on the same tick
        return new CinematicScript()
                .at(start, speakerA, lineA)
                .at(start + gap, speakerB, lineB);
    }

    /**
     * An alternating conversation: line {@code i} is spoken {@code gap} seconds
     * after the previous one, alternating speakers (even = A, odd = B). Lines
     * are handed to {@code LiveCaptions} in time order so a multi-line scene
     * reads like a scripted exchange rather than a wall of text.
     */
    public static CinematicScript conversation(float start, float gap,
                                               String speakerA, String speakerB,
                                               String... lines) {
        if (gap < 0.1f) gap = 0.1f;
        CinematicScript s = new CinematicScript();
        if (lines == null) return s;
        for (int i = 0; i < lines.length; i++) {
            s.at(start + i * gap, (i % 2 == 0) ? speakerA : speakerB, lines[i]);
        }
        return s;
    }
}
