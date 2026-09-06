package com.voxel.audio;

import com.voxel.entity.VillagerEntity;
import villager.voice.SpeechOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Improved dialogue layer that sits on top of the built-in lines and the
 * editable {@link DialogueCatalog}.
 *
 * <p>What it adds over plain {@link VillagerDialogue#chooseLine}:</p>
 * <ul>
 *   <li><b>Temperament</b> — each villager gets a stable personality
 *       (chatty↔quiet, grumpy↔cheerful) derived from their entity id, so the
 *       same villager always sounds like the same person.</li>
 *   <li><b>Mood</b> — fear and wariness spike on damage/threat stimuli and
 *       decay over time; mood colors both the delivery (via
 *       {@link SpeechOptions}) and which lines get picked.</li>
 *   <li><b>Memory</b> — recently spoken lines are excluded so villagers stop
 *       repeating themselves in back-to-back chats; interaction counts
 *       influence long-term familiarity so tone warms up over time.</li>
 *   <li><b>Line identity</b> — a (profession, period) bucketed pool merges the
 *       built-in table with the editable catalog; a common id space lets the
 *       caller know exactly which line was chosen.</li>
 * </ul>
 *
 * <p>All selection math is deterministic given the same inputs (no wall clock),
 * so unit tests and replays behave identically. Pure-Java-8.</p>
 */
public final class DialogueDirector {

    /** Stable per-villager personality, derived from the entity id. */
    public static final class Temperament {
        /** 0..1 — likelihood of making small talk when idle. */
        public final double chattiness;
        /** -1..1 — negative = grumpy, positive = cheerful. */
        public final double cheer;

        Temperament(double chattiness, double cheer) {
            this.chattiness = chattiness;
            this.cheer = cheer;
        }

        static Temperament forSeed(long seed) {
            Random rng = new Random(seed * 1000003L + 17L);
            // Triangular-ish distribution: most villagers are middling.
            double chat = (rng.nextDouble() + rng.nextDouble()) / 2.0;
            double cheer = (rng.nextDouble() * 2.0 - 1.0) * 0.8;
            return new Temperament(0.25 + chat * 0.75, cheer);
        }
    }

    /**
     * Current emotional state. Both axes decay toward zero over time.
     * Package-private fields, mutated only through the stimulus/decay API.
     */
    public static final class Mood {
        /** 0..1 — fear spikes when hurt or near threats, decays when safe. */
        float fear;
        /** 0..1 — wariness toward the player, spikes on repeated interactions. */
        float wary;
        /** Seconds since the last interaction with anyone. */
        float sinceSpoken;

        /** Normalized mood used for line filtering and speech coloring. */
        public double fearLevel() { return clamp(fear); }
        public double warinessLevel() { return clamp(wary); }

        /** 0..1 mood score: positive when calm+cheerful, negative when afraid. */
        public double score() {
            return clamp(0.5 - fear * 0.7 - wary * 0.2 + sinceSpoken * 0.05);
        }

        void decay(float dt) {
            fear = Math.max(0f, fear - dt * FEAR_DECAY_PER_SECOND);
            wary = Math.max(0f, wary - dt * WARY_DECAY_PER_SECOND);
            sinceSpoken += dt;
        }
    }

    private static final float FEAR_DECAY_PER_SECOND = 0.05f;
    private static final float WARY_DECAY_PER_SECOND = 0.02f;
    private static final int REMEMBERED_LINES = 6;
    private static final float WARY_PER_INTERACTION = 0.15f;

    /** Cached state per villager; entries are tiny so no eviction is needed. */
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** Per-villager memory (package-private for tests). */
    static final class State {
        final Temperament temperament;
        final Mood mood = new Mood();
        /** Ids of recently chosen lines (ring buffer semantics). */
        final List<String> recent = new ArrayList<>();
        int interactions;

        State(long id) {
            this.temperament = Temperament.forSeed(id);
        }
    }

    // ── Public stimulus hooks (called from brains/combat) ──────────────────

    /** Called when this villager takes damage: big fear spike + wariness. */
    public static void onHurt(int villagerId) {
        State s = state(villagerId);
        s.mood.fear = 1.0f;
        s.mood.wary = Math.min(1f, s.mood.wary + WARY_PER_INTERACTION * 2f);
    }

    /** Called when a threat is (or was recently) visible nearby. */
    public static void onThreatSeen(int villagerId, float severity) {
        State s = state(villagerId);
        s.mood.fear = (float) clamp(Math.max(s.mood.fear, 0.6 + 0.4 * clamp(severity)));
    }

    /**
     * Gossip contagion: a neighbor shouted about danger. Milder than seeing
     * the threat directly — enough to color the next lines and keep a crowd
     * wary after the screamer calms down.
     */
    public static void onGossip(int villagerId, float severity) {
        State s = state(villagerId);
        float lift = (float) clamp(0.25 + 0.35 * clamp(severity));
        s.mood.fear = (float) Math.min(1.0, Math.max(s.mood.fear, lift * 0.7));
        s.mood.wary = (float) clamp(s.mood.wary + lift * 0.3);
    }

    /** Called after each spoken interaction; repeated chats breed wariness. */
    public static void onSpokenTo(int villagerId) {
        State s = state(villagerId);
        s.interactions++;
        s.mood.sinceSpoken = 0f;
        s.mood.wary = (float) clamp(s.mood.wary + WARY_PER_INTERACTION
                * (1f - s.temperament.chattiness * 0.6f));
    }

    /** Advance mood decay; call once per tick per villager. */
    public static void tick(int villagerId, float dt) {
        state(villagerId).mood.decay(dt);
    }

    /** Test seam: forget all per-villager state. */
    static void reset() {
        STATES.clear();
    }

    /** Test seam: direct access to one villager's mutable state. */
    static State state(int id) {
        State s = STATES.get(id);
        if (s == null) {
            s = new State(id);
            STATES.put(id, s);
        }
        return s;
    }

    /** Test seam: seed a villager's mood directly. */
    static void seedMood(int villagerId, float fear, float wary, float sinceSpoken) {
        Mood m = state(villagerId).mood;
        m.fear = fear;
        m.wary = wary;
        m.sinceSpoken = sinceSpoken;
    }

    // ── Selection ──────────────────────────────────────────────────────────

    /**
     * Choose a line for a live villager, merging the built-in table with the
     * editable catalog. Never returns null.
     */
    public static DialogueLine choose(VillagerEntity villager, String period,
                                      int interactionIndex, List<DialogueLine> catalog) {
        if (villager == null) {
            return new DialogueLine("fallback", "Hmm...", "NITWIT", period, 0,
                    SpeechOptions.DEFAULT);
        }
        return choose(villager.id, villager.getProfession().name(), period,
                interactionIndex, catalog);
    }

    /**
     * Entity-free core selection, used by tooling and tests: same behavior
     * keyed by villager id + profession name instead of a live entity.
     */
    public static DialogueLine choose(int villagerId, String profession, String period,
                                      int interactionIndex, List<DialogueLine> catalog) {
        State s = state(villagerId);
        List<DialogueLine> pool = new ArrayList<DialogueLine>();
        pool.addAll(catalog == null ? Collections.<DialogueLine>emptyList() : catalog);
        pool.addAll(builtinLines(profession, period));

        List<DialogueLine> candidates = filter(pool, s, profession, period);
        DialogueLine pick = candidates.get(
                Math.floorMod(pickSeed(villagerId, interactionIndex, s), candidates.size()));

        remember(s, pick);
        onSpokenTo(villagerId);
        return new DialogueLine(pick.getId(), pick.getText(), pick.getProfession(),
                pick.getPeriod(), pick.getVariant(), optionsFor(s, pick.getOptions()));
    }

    /** Apply temperament + mood coloring to a line's authored options. */
    static SpeechOptions optionsFor(State s, SpeechOptions authored) {
        Mood mood = s.mood;
        double cheer = s.temperament.cheer;
        double fear = mood.fearLevel();

        double tone = authored.getTone();
        if (tone == 0.0) tone = cheer * 0.5 - fear * 0.8;

        String emotion = authored.getEmotion();
        if ("neutral".equals(emotion) || emotion == null || emotion.isEmpty()) {
            emotion = fear > 0.45 ? "scared" : cheer < -0.4 ? "sad" : "neutral";
        } else if (fear > 0.45 && !"angry".equals(emotion)) {
            emotion = "scared";
        }

        double volume = authored.getVolume();
        double speed = authored.getSpeed();
        if (fear > 0.3) {
            volume = clamp(volume * (1.0 + fear * 0.35), 0.1, 2.0);
            speed = clamp(speed * (1.0 + fear * 0.25), 0.5, 2.0);
        }

        double pitch = authored.getPitchSemitones() + fear * 1.5 + cheer * 0.8;
        double sarcasm = authored.getSarcasm() > 0
                ? authored.getSarcasm() : Math.max(0, -cheer) * 0.3 * clamp(mood.score() < 0 ? 1 : 0.4);
        boolean question = authored.isQuestion();

        return new SpeechOptions(speed, pitch, volume, tone,
                authored.getNaturalSourceMix(), emotion, authored.getSinging(),
                sarcasm, question);
    }

    /** Filter the pool by profession/period, mood, and no-repeat memory. */
    private static List<DialogueLine> filter(List<DialogueLine> pool, State s,
                                             String profession, String period) {
        List<DialogueLine> exact = new ArrayList<DialogueLine>();
        for (DialogueLine line : pool) {
            if (!matches(line.getProfession(), profession)) continue;
            if (!matches(line.getPeriod(), period)) continue;
            exact.add(line);
        }

        double fear = s.mood.fearLevel();
        List<DialogueLine> preferred = new ArrayList<DialogueLine>();
        List<DialogueLine> fallback = new ArrayList<DialogueLine>();
        for (DialogueLine line : exact) {
            String text = line.getText().toLowerCase(Locale.ROOT);
            boolean negative = text.contains("help") || text.contains("run")
                    || text.contains("danger") || text.contains("hide")
                    || text.contains("attack") || text.contains("no!");
            boolean moodMatch = fear > 0.5 ? negative : fear < 0.2 ? !negative : true;
            if (moodMatch) preferred.add(line); else fallback.add(line);
        }

        // No-repeat: prefer candidates not spoken recently.
        List<DialogueLine> source = preferred.isEmpty() ? fallback : preferred;
        List<DialogueLine> fresh = new ArrayList<DialogueLine>();
        for (DialogueLine line : source) {
            if (!s.recent.contains(line.getId())) fresh.add(line);
        }
        List<DialogueLine> result = fresh.isEmpty() ? source : fresh;
        return result.isEmpty() ? exact : result;
    }

    private static boolean matches(String value, String expected) {
        return DialogueCatalog.ANY.equals(value) || value.equalsIgnoreCase(expected);
    }

    private static int pickSeed(int id, int interactionIndex, State s) {
        long seed = id * 31L + interactionIndex * 17L
                + s.interactions * 13L
                + (long) (s.mood.fearLevel() * 7.0)
                + (long) (s.mood.warinessLevel() * 11.0);
        return (int) (seed ^ (seed >>> 32));
    }

    private static void remember(State s, DialogueLine line) {
        s.recent.add(line.getId());
        while (s.recent.size() > REMEMBERED_LINES) {
            s.recent.remove(0);
        }
    }

    /** Built-in lines mirrored from VillagerDialogue, keyed for mood bucketing. */
    private static List<DialogueLine> builtinLines(String profession, String period) {
        // VillagerDialogue already implements the (profession, period) matrix;
        // render it through a synthetic villager-less call by profession name.
        return VillagerDialogue.builtinLinesFor(profession, period);
    }

    private static double clamp(double v) {
        return v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : v > max ? max : v;
    }

    private DialogueDirector() {
    }
}
