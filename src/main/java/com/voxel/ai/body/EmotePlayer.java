package com.voxel.ai.body;

import org.joml.Vector3f;

/**
 * Plays one {@link Emote} at a time and exposes pose amounts (all 0..1 or
 * -1..1 oscillators) that the owning entity layers over its base animation
 * each tick. Pure logic — no rendering types.
 */
public final class EmotePlayer {

    private Emote current;
    private float elapsed;
    private final Vector3f pointTarget = new Vector3f();
    private boolean pointValid;

    /** @return true if the emote started (false when a non-loopable emote is mid-play). */
    public boolean play(Emote emote) {
        return play(emote, null);
    }

    public boolean play(Emote emote, Vector3f pointAt) {
        if (emote == null) return false;
        if (current != null && elapsed < current.duration) {
            return false;
        }
        this.current = emote;
        this.elapsed = 0f;
        this.pointValid = pointAt != null;
        if (pointAt != null) this.pointTarget.set(pointAt);
        return true;
    }

    public void cancel() {
        current = null;
        elapsed = 0f;
        pointValid = false;
    }

    public void update(float dt) {
        if (current != null) {
            elapsed += dt;
            if (elapsed >= current.duration) {
                current = null;
                elapsed = 0f;
                pointValid = false;
            }
        }
    }

    public boolean isActive() {
        return current != null;
    }

    public Emote current() {
        return current;
    }

    public boolean isPlaying(Emote emote) {
        return playing(emote) && elapsed < current.duration;
    }

    private boolean playing(Emote emote) {
        return current == emote;
    }

    /** Head pitch oscillator for NOD (-1..1). */
    public float nodPhase() {
        return playing(Emote.NOD)
                ? (float) Math.sin(elapsed / Emote.NOD.duration * Math.PI * 6)
                : 0f;
    }

    /** Head yaw oscillator for HEAD_SHAKE (-1..1). */
    public float shakePhase() {
        return playing(Emote.HEAD_SHAKE)
                ? (float) Math.sin(elapsed / Emote.HEAD_SHAKE.duration * Math.PI * 5)
                : 0f;
    }

    /** Both-arms raise amount for WAVE_FRANTIC (0..1). */
    public float armRaise() {
        if (!playing(Emote.WAVE_FRANTIC)) return 0f;
        return 0.65f + 0.35f * (float) Math.sin(elapsed * 22f);
    }

    /** Fearful posture amount for COWER (0..1 ramps up fast). */
    public float cowerAmount() {
        if (!playing(Emote.COWER)) return 0f;
        return Math.min(1f, elapsed / 0.25f);
    }

    /** Single-arm reach amount for POINT/HAMMER/TUG (0..1 with swing). */
    public float armReachSwing() {
        if (!playing(Emote.POINT) && !playing(Emote.HAMMER) && !playing(Emote.TUG)) return 0f;
        float speed = playing(Emote.HAMMER) ? 26f : 9f;
        return 0.7f + 0.3f * (float) Math.sin(elapsed * speed);
    }

    /**
     * Edge-triggered hop signal for JUMP_CHEER: true exactly when a fresh hop
     * begins (twice per cheer), false otherwise.
     */
    public boolean consumeHop() {
        if (!playing(Emote.JUMP_CHEER)) return false;
        float hopWindowStart = (hopIndex() == 0) ? 0f : Emote.JUMP_CHEER.duration * 0.5f;
        return elapsed >= hopWindowStart && elapsed - dtGuess() < hopWindowStart;
    }

    private int hopIndex() {
        return (elapsed >= Emote.JUMP_CHEER.duration * 0.5f) ? 1 : 0;
    }

    private float dtGuess() {
        return 1f / 60f;
    }

    public boolean isPointing() {
        return playing(Emote.POINT);
    }

    /** World-space referent of an active POINT emote (undefined when not pointing). */
    public Vector3f pointTarget() {
        return pointTarget;
    }

    public boolean hasPointTarget() {
        return pointValid && isPointing();
    }
}
