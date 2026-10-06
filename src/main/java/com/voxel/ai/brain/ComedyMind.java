package com.voxel.ai.brain;

import java.util.Random;

/**
 * The shared "dumb human" psyche: a stable per-entity personality plus the
 * attention span of a golden retriever. Both villagers and hostile mobs run
 * on this, which is why a confident hunter and a panicking farmer make the
 * same kinds of mistakes.
 *
 * <p>Pure decision helpers over an injected {@link Random}; the brain classes
 * own all world access. Deterministic given a seed, so unit tests pin exact
 * behavior. Java 8 only.</p>
 */
public final class ComedyMind {

    /** The kinds of dumb-human beats a mind can produce. */
    public enum Event {
        NONE,
        /** Spotted something shiny; abandons the task to investigate. */
        DISTRACT,
        /** Forgets what it was doing, mid-sentence. */
        FORGET,
        /** Stops to admire nothing in particular. */
        ADMIRE,
        /** Loiters after the player like a lost tourist. */
        FOLLOW_PLAYER,
        /** Picks a pointless argument with a neighbor. */
        ARGUE,
        /** Waves enthusiastically at the wrong person. */
        WRONG_WAVE
    }

    /** 0..1 — how close danger gets before bravado becomes screaming. */
    public final float boldness;
    /** 0..1 — how often shiny things win over the current task. */
    public final float distractibility;
    /** 0..1 — social nonsense: arguments, loitering, wrong waves. */
    public final float drama;
    /** 0..1 — how often work goes proudly wrong. */
    public final float incompetence;

    private float attentionLeft;
    private float comedyCooldown;
    private boolean spooked;

    private ComedyMind(float boldness, float distractibility,
                       float drama, float incompetence) {
        this.boldness = boldness;
        this.distractibility = distractibility;
        this.drama = drama;
        this.incompetence = incompetence;
        this.attentionLeft = attentionSpan();
        this.comedyCooldown = 4f;
    }

    /** Personality derived from the entity id: stable across ticks. */
    public static ComedyMind forEntity(int id) {
        Random rng = new Random(id * 2654435761L + 7919L);
        return new ComedyMind(
                clamp01((rng.nextFloat() + rng.nextFloat()) * 0.5f),
                clamp01(0.25f + rng.nextFloat() * 0.75f),
                clamp01((rng.nextFloat() + rng.nextFloat()) * 0.5f),
                clamp01(0.2f + rng.nextFloat() * 0.8f));
    }

    /** Explicit personality for tests. */
    public static ComedyMind of(float boldness, float distractibility,
                                float drama, float incompetence) {
        return new ComedyMind(clamp01(boldness), clamp01(distractibility),
                clamp01(drama), clamp01(incompetence));
    }

    // ── Attention span ───────────────────────────────────────────────────

    /** Total attention span in seconds for one task. */
    public float attentionSpan() {
        return 18f - distractibility * 13f;
    }

    /** Start a fresh task with a fresh attention span. */
    public void resetAttention() {
        attentionLeft = attentionSpan();
        spooked = false;
    }

    /** Advance the timers. */
    public void tick(float dt) {
        attentionLeft -= dt;
        comedyCooldown -= dt;
    }

    /** True once the current task has outlived the mind's attention span. */
    public boolean attentionExpired() {
        return attentionLeft <= 0f;
    }

    // ── Comedy beats ─────────────────────────────────────────────────────

    /** Per-second comedy event rate; escalating chaos compounds the dumb. */
    float comedyRate(float chaos) {
        return (0.02f + distractibility * 0.05f + drama * 0.03f)
                * (0.6f + clamp01(chaos) * 1.8f);
    }

    /**
     * Roll for the next comedy beat. Context flags drop beats that cannot
     * happen right now (an argument needs a friend, loitering needs the
     * player). Cooldown keeps comedy occasional early and relentless late.
     */
    public Event poll(float dt, float chaos, Random rng,
                      boolean hasFriend, boolean playerVisible) {
        if (comedyCooldown > 0f) {
            return Event.NONE;
        }
        float chance = Math.min(0.75f, comedyRate(chaos) * Math.max(0f, dt));
        if (rng.nextFloat() >= chance) {
            return Event.NONE;
        }
        // Weighted pick over the contextually possible beats.
        float argue = hasFriend ? drama * 1.2f : 0f;
        float follow = playerVisible ? 0.3f + drama * 0.7f : 0f;
        float wrongWave = hasFriend ? drama * 0.5f : 0f;
        float distract = 0.4f + distractibility * 1.1f;
        float forget = 0.3f + distractibility * 0.8f;
        float admire = 0.2f + drama * 0.4f;
        float total = argue + follow + wrongWave + distract + forget + admire;
        float roll = rng.nextFloat() * total;
        Event event;
        if ((roll -= argue) <= 0f) event = Event.ARGUE;
        else if ((roll -= follow) <= 0f) event = Event.FOLLOW_PLAYER;
        else if ((roll -= wrongWave) <= 0f) event = Event.WRONG_WAVE;
        else if ((roll -= distract) <= 0f) event = Event.DISTRACT;
        else if ((roll -= forget) <= 0f) event = Event.FORGET;
        else event = Event.ADMIRE;
        // Dumber mobs (and a chaotic village) get back to being dumb faster.
        comedyCooldown = (16f - clamp01(chaos) * 9f) * (0.6f + rng.nextFloat() * 0.9f);
        return event;
    }

    // ── Confident-then-cowardly ──────────────────────────────────────────

    /** Whether this mind struts toward danger before panicking about it. */
    public boolean shouldBluff(Random rng, float chaos) {
        return !spooked && rng.nextFloat() < (0.25f + boldness * 0.55f)
                * (0.8f + clamp01(chaos) * 0.6f);
    }

    /** Distance at which bravado turns into a scream (closer = bolder). */
    public float cowardDistance() {
        return 2.5f + (1f - boldness) * 4.5f;
    }

    /** How long a strut lasts before the act collapses anyway. */
    public float bluffSeconds(Random rng) {
        return 1.2f + boldness * 2.4f + rng.nextFloat() * 1.2f;
    }

    /** Mark the bluff as blown; prevents immediate re-strutting. */
    public void spook() {
        spooked = true;
    }

    public boolean isSpooked() {
        return spooked;
    }

    // ── Herd behavior ────────────────────────────────────────────────────

    /**
     * Whether this mob follows the crowd instead of thinking for itself
     * (conga lines, rubbernecking pile-ups). More likely when dramatic and
     * in a chaotic scene.
     */
    public boolean herdFollow(Random rng, float chaos) {
        return rng.nextFloat() < (0.2f + drama * 0.5f) * (0.6f + clamp01(chaos) * 0.9f);
    }

    /** How long a comedy action holds before the mob reconsiders life. */
    public float comedyHoldSeconds(Random rng) {
        return 1.6f + rng.nextFloat() * 2.8f;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }
}
