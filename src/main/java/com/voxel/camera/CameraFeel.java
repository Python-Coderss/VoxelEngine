package com.voxel.camera;

/**
 * Gameplay camera "juice" that is independent of the renderer:
 *
 * <ul>
 *   <li><b>Trauma-based shake</b> — callers push an impulse (a hit, a landing,
 *       a block break) with {@link #addTrauma(float)}; the shake decays over
 *       ~0.7 s and the amplitude follows {@code trauma²}, so the first frame is
 *       snappy and the tail fades out instead of stopping abruptly.
 *       The noise is a sum of incommensurate sines rather than
 *       {@code Math.random()}, so the motion is smooth, deterministic and
 *       frame-rate independent (the old per-frame jitter was neither).</li>
 *   <li><b>Head bob</b> — a vertical + lateral sway driven by the player's
 *       horizontal speed, at most a few centimetres. Cancelled in the air and
 *       whenever {@link #setBobEnabled(boolean)} is off (third person,
 *       cutscenes, detached cameras).</li>
 *   <li><b>Speed FOV kick</b> — a few degrees of extra field of view while
 *       sprinting, smoothed with an exponential so it eases in and out. The
 *       renderer and every mouse-unprojection path read the same FOV through
 *       {@link CameraController#getFovDegrees()}, so picking stays exact.</li>
 * </ul>
 *
 * The class holds no GL state and is safe to drive from the render thread while
 * the logic thread pushes trauma ({@code addTrauma} is the only mutator used
 * cross-thread and it defers through a volatile accumulator).
 */
public final class CameraFeel {

    // ── Shake ────────────────────────────────────────────────────────────────
    /** Trauma is clamped to this; amplitude is trauma². */
    public static final float MAX_TRAUMA = 1.0f;
    private static final float TRAUMA_DECAY_PER_SECOND = 1.5f;
    /** Base shake frequency (rad/s scale); the second harmonic sits ~2.4x this. */
    private static final float SHAKE_FREQUENCY = 15.0f;
    private static final float MAX_YAW_DEGREES = 1.5f;
    private static final float MAX_PITCH_DEGREES = 1.1f;
    private static final float MAX_ROLL_DEGREES = 1.8f;
    private static final float MAX_OFFSET_BLOCKS = 0.035f;

    // ── Head bob ─────────────────────────────────────────────────────────────
    /** Step cycles per second (one cycle = left+right footfall). */
    private static final float WALK_BOB_HZ = 1.8f;
    private static final float SPRINT_BOB_HZ = 2.6f;
    /** Horizontal speed (blocks per tick) that produces a full-strength bob. */
    private static final float FULL_BOB_SPEED = 0.18f;
    private static final float BOB_VERTICAL = 0.045f;
    private static final float BOB_LATERAL = 0.030f;
    private static final float BOB_SMOOTH_RATE = 10.0f;

    // ── FOV ──────────────────────────────────────────────────────────────────
    private static final float WALK_FOV_KICK = 1.5f;
    private static final float SPRINT_FOV_KICK = 6.0f;
    private static final float FOV_SMOOTH_RATE = 6.0f;

    private static final float TAU = (float) (Math.PI * 2.0);

    /** Impulses queued by the logic thread, drained by {@link #update(float)}. */
    private volatile float pendingTrauma;

    private float trauma;
    private float time;

    private float speed;
    private boolean onGround;
    private boolean sprinting;
    private boolean bobEnabled = true;
    private boolean fovEnabled = true;

    private float bobPhase;
    private float bobAmount;
    private float fovOffset;

    // ── Inputs (render thread) ───────────────────────────────────────────────

    /** Current locomotion state, sampled once per rendered frame. */
    public void setLocomotion(float horizontalSpeed, boolean onGround, boolean sprinting) {
        this.speed = horizontalSpeed;
        this.onGround = onGround;
        this.sprinting = sprinting;
    }

    /** Head bob is only sensible in first person; off for orbit/cutscene cameras. */
    public void setBobEnabled(boolean enabled) { this.bobEnabled = enabled; }

    /** FOV kick is suppressed while a cinematic owns the lens. */
    public void setFovEnabled(boolean enabled) { this.fovEnabled = enabled; }

    /** Push an impulse (0..1 recommended). Thread-safe with the render thread. */
    public void addTrauma(float amount) {
        if (!(amount > 0f)) return;
        pendingTrauma = Math.min(MAX_TRAUMA, pendingTrauma + amount);
    }

    // ── Per-frame update ─────────────────────────────────────────────────────

    public void update(float dt) {
        if (!(dt > 0f)) return;
        if (dt > 0.1f) dt = 0.1f; // never let a hitch turn into a violent kick
        time += dt;

        float queued = pendingTrauma;
        if (queued > 0f) {
            pendingTrauma = 0f;
            trauma = Math.min(MAX_TRAUMA, trauma + queued);
        }
        trauma = Math.max(0f, trauma - TRAUMA_DECAY_PER_SECOND * dt);

        // Walk cycle: strength follows horizontal speed while grounded.
        float speedFactor = onGround ? Math.min(1.15f, Math.max(0f, speed / FULL_BOB_SPEED)) : 0f;
        float bobTarget = bobEnabled ? Math.min(1f, speedFactor) : 0f;
        bobAmount += (bobTarget - bobAmount) * (1f - (float) Math.exp(-BOB_SMOOTH_RATE * dt));
        if (bobAmount < 0.0005f) bobAmount = 0f;
        if (bobAmount > 0f) {
            bobPhase += dt * TAU * (sprinting ? SPRINT_BOB_HZ : WALK_BOB_HZ) * speedFactor;
            if (bobPhase > TAU * 1000f) bobPhase -= TAU * 1000f;
        }

        // Speed FOV: eased so the lens widens as the sprint winds up.
        float fovTarget = (fovEnabled && onGround)
                ? bobAmount * (sprinting ? SPRINT_FOV_KICK : WALK_FOV_KICK)
                : 0f;
        fovOffset += (fovTarget - fovOffset) * (1f - (float) Math.exp(-FOV_SMOOTH_RATE * dt));
    }

    // ── Shake outputs ────────────────────────────────────────────────────────

    public float getTrauma() { return trauma; }

    /** Squared trauma is the perceptual shake envelope. */
    private float envelope() { return trauma * trauma; }

    /** Sum of incommensurate sines: smooth, deterministic, ~[-1, 1]. */
    private static float noise(float t, float seed) {
        return 0.6f * (float) Math.sin(t + seed)
             + 0.4f * (float) Math.sin(t * 2.37f + seed * 3.7f);
    }

    public float getShakeYawDegrees() {
        return noise(time * SHAKE_FREQUENCY, 0.0f) * envelope() * MAX_YAW_DEGREES;
    }

    public float getShakePitchDegrees() {
        return noise(time * SHAKE_FREQUENCY, 4.1f) * envelope() * MAX_PITCH_DEGREES;
    }

    /** Dutch-angle component, in degrees — the part players read as "impact". */
    public float getShakeRollDegrees() {
        return noise(time * SHAKE_FREQUENCY * 0.83f, 9.7f) * envelope() * MAX_ROLL_DEGREES;
    }

    public float getShakeOffsetX() {
        return noise(time * SHAKE_FREQUENCY, 13.3f) * envelope() * MAX_OFFSET_BLOCKS;
    }

    public float getShakeOffsetY() {
        return noise(time * SHAKE_FREQUENCY, 17.9f) * envelope() * MAX_OFFSET_BLOCKS;
    }

    public float getShakeOffsetZ() {
        return noise(time * SHAKE_FREQUENCY, 23.1f) * envelope() * MAX_OFFSET_BLOCKS;
    }

    // ── Bob / FOV outputs ────────────────────────────────────────────────────

    public float getBobAmount() { return bobAmount; }

    /** Vertical eye offset in blocks (positive = up). */
    public float getBobY() { return (float) Math.sin(bobPhase * 2.0) * BOB_VERTICAL * bobAmount; }

    /** Lateral eye offset in blocks, along the camera's right vector. */
    public float getBobX() { return (float) Math.cos(bobPhase) * BOB_LATERAL * bobAmount; }

    public float getFovOffsetDegrees() { return fovOffset; }

    /** Drop all motion — used when the camera snaps (teleport, respawn, scene cut). */
    public void reset() {
        pendingTrauma = 0f;
        trauma = 0f;
        bobPhase = 0f;
        bobAmount = 0f;
        fovOffset = 0f;
    }
}
