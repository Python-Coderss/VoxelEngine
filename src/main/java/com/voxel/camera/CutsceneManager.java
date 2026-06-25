package com.voxel.camera;

import org.joml.Vector3f;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Manages cutscenes and dynamic camera shots.
 * Supports FOLLOW, ORBIT, FIXED, CINEMATIC, and DYNAMIC shot types
 * with smooth transitions and interpolation.
 */
public class CutsceneManager {

    public enum ShotType {
        FOLLOW,      // Over-the-shoulder behind player
        ORBIT,       // Orbits around a target point
        FIXED,       // Fixed world position, looks at target
        CINEMATIC,   // Scripted keyframe path
        DYNAMIC      // Context-aware (combat zoom, speed lines, etc.)
    }

    /** A single camera shot definition. */
    public static class CameraShot {
        public ShotType type;
        public float duration;         // seconds, 0 = indefinite
        public Vector3f target;        // what the camera looks at
        public Vector3f cameraOffset;  // offset from target for FOLLOW/ORBIT
        public Vector3f fixedPosition; // world position for FIXED shots
        public List<Vector3f> path;    // keyframe positions for CINEMATIC
        public List<Float> timestamps; // normalized [0,1] timestamps for path
        public float fov;             // field of view override (0 = default)
        public boolean lockPlayerInput;
        public String label;           // debug label

        public CameraShot(ShotType type, float duration) {
            this.type = type;
            this.duration = duration;
            this.target = new Vector3f();
            this.cameraOffset = new Vector3f(0, 1.5f, 4.0f);
            this.fixedPosition = new Vector3f();
            this.path = new ArrayList<>();
            this.timestamps = new ArrayList<>();
            this.fov = 0;
            this.lockPlayerInput = false;
            this.label = "";
        }

        /** Convenience: create a FOLLOW shot. */
        public static CameraShot follow(float duration, float distance, float height, float sideOffset) {
            CameraShot shot = new CameraShot(ShotType.FOLLOW, duration);
            shot.cameraOffset.set(sideOffset, height, distance);
            return shot;
        }

        /** Convenience: create a FIXED shot. */
        public static CameraShot fixed(float duration, float camX, float camY, float camZ, float lookX, float lookY, float lookZ) {
            CameraShot shot = new CameraShot(ShotType.FIXED, duration);
            shot.fixedPosition.set(camX, camY, camZ);
            shot.target.set(lookX, lookY, lookZ);
            return shot;
        }

        /** Convenience: create an ORBIT shot. */
        public static CameraShot orbit(float duration, float radius, float height, float startAngle, float endAngle) {
            CameraShot shot = new CameraShot(ShotType.ORBIT, duration);
            shot.cameraOffset.set(radius, height, startAngle);
            shot.target.set(0, 0, 0); // will be set at runtime
            // Pack end angle in unused field
            shot.fixedPosition.set(endAngle, 0, 0);
            return shot;
        }

        /** Convenience: create a CINEMATIC path shot. */
        public static CameraShot cinematic(float duration, List<Vector3f> path, List<Float> timestamps) {
            CameraShot shot = new CameraShot(ShotType.CINEMATIC, duration);
            shot.path = new ArrayList<>(path);
            shot.timestamps = new ArrayList<>(timestamps);
            return shot;
        }
    }

    // ── State ──
    private final Queue<CameraShot> shotQueue = new ArrayDeque<>();
    private CameraShot activeShot = null;
    private float shotTimer = 0.0f;
    private boolean isActive = false;
    private float transitionDuration = 0.3f;
    private float transitionTimer = 0.0f;
    private boolean inTransition = false;

    // Previous camera state for smooth transitions
    private Vector3f previousCamPos = new Vector3f();
    private Vector3f previousLookTarget = new Vector3f();
    private float previousFov = 70.0f;

    // Output state
    private Vector3f currentCamPos = new Vector3f();
    private Vector3f currentLookTarget = new Vector3f();
    private float currentFov = 70.0f;
    private boolean inputLocked = false;

    // Orbit state (persistent across orbit shots)
    private float orbitAngle = 0.0f;

    // Dynamic shot context
    private float combatZoom = 0.0f;
    private float speedLines = 0.0f;

    public CutsceneManager() {
        // Initialize camera state to prevent first-frame jump
        previousCamPos.set(0, 1.6f, 0);
        previousLookTarget.set(0, 1.6f, 4);
    }

    // ════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /** Start a cutscene with the given sequence of shots. */
    public void startCutscene(List<CameraShot> shots) {
        shotQueue.clear();
        shotQueue.addAll(shots);
        isActive = true;
        activeShot = null;
        advanceToNextShot();
    }

    /** Start a single-shot cutscene. */
    public void startShot(CameraShot shot) {
        List<CameraShot> single = new ArrayList<>();
        single.add(shot);
        startCutscene(single);
    }

    /** Queue additional shots to play after current ones. */
    public void queueShots(List<CameraShot> shots) {
        shotQueue.addAll(shots);
    }

    /** Update the cutscene manager every frame. */
    public void update(float dt, Vector3f playerPos, Vector3f playerLookTarget, boolean combatMode) {
        if (!isActive || activeShot == null) {
            // Inactive: compute dynamic camera info for non-cutscene modes
            updateDynamicState(dt, playerPos, playerLookTarget, combatMode);
            return;
        }

        if (inTransition) {
            transitionTimer += dt;
            if (transitionTimer >= transitionDuration) {
                inTransition = false;
            }
        }

        shotTimer += dt;

        // Check if current shot has expired
        if (activeShot.duration > 0 && shotTimer >= activeShot.duration) {
            advanceToNextShot();
            if (activeShot == null) return; // cutscene ended
        }

        // Compute camera position for the active shot
        computeShotPosition(activeShot, dt, playerPos, playerLookTarget);
    }

    /** Returns the computed camera position. */
    public Vector3f getCameraPosition() { return currentCamPos; }

    /** Returns the computed look-at target. */
    public Vector3f getLookTarget() { return currentLookTarget; }

    /** Returns the computed FOV. */
    public float getFov() { return currentFov; }

    /** Whether the cutscene is currently active. */
    public boolean isActive() { return isActive; }

    /** Whether player input is locked by the current shot. */
    public boolean isInputLocked() { return inputLocked; }

    /** Skip the current shot / end the cutscene. */
    public void skip() {
        if (!isActive) return;
        advanceToNextShot();
    }

    /** End the cutscene immediately. */
    public void endCutscene() {
        shotQueue.clear();
        activeShot = null;
        isActive = false;
        inTransition = false;
        inputLocked = false;
    }

    /** Get the active shot (for debug display). */
    public CameraShot getActiveShot() { return activeShot; }

    /** Get shot progress [0,1]. */
    public float getShotProgress() {
        if (activeShot == null || activeShot.duration <= 0) return 0;
        return Math.min(1.0f, shotTimer / activeShot.duration);
    }

    // ════════════════════════════════════════════════════════════════
    //  INTERNALS
    // ════════════════════════════════════════════════════════════════

    private void advanceToNextShot() {
        // Store previous state for transition
        previousCamPos.set(currentCamPos);
        previousLookTarget.set(currentLookTarget);

        if (shotQueue.isEmpty()) {
            activeShot = null;
            isActive = false;
            inputLocked = false;
            return;
        }

        activeShot = shotQueue.poll();
        shotTimer = 0.0f;
        inTransition = true;
        transitionTimer = 0.0f;
        inputLocked = activeShot.lockPlayerInput;
    }

    private void computeShotPosition(CameraShot shot, float dt, Vector3f playerPos, Vector3f playerLookTarget) {
        Vector3f rawPos = new Vector3f();
        Vector3f rawTarget = new Vector3f();
        float t = shot.duration > 0 ? Math.min(1.0f, shotTimer / shot.duration) : 0;

        switch (shot.type) {
            case FOLLOW:
                computeFollowShot(shot, playerPos, playerLookTarget, rawPos, rawTarget);
                break;
            case ORBIT:
                // Initialize orbit angle from shot startAngle on first frame
                if (shotTimer < dt) orbitAngle = shot.cameraOffset.z;
                computeOrbitShot(shot, playerPos, dt, rawPos, rawTarget);
                break;
            case FIXED:
                rawPos.set(shot.fixedPosition);
                rawTarget.set(shot.target);
                break;
            case CINEMATIC:
                computeCinematicShot(shot, t, rawPos, rawTarget);
                break;
            case DYNAMIC:
                computeDynamicShot(shot, playerPos, playerLookTarget, dt, rawPos, rawTarget);
                break;
        }

        // Smooth transition from previous state
        if (inTransition && transitionDuration > 0) {
            float tr = Math.min(1.0f, transitionTimer / transitionDuration);
            float smoothTr = tr * tr * (3.0f - 2.0f * tr); // smoothstep
            currentCamPos.lerp(rawPos, smoothTr);
            currentLookTarget.lerp(rawTarget, smoothTr);
        } else {
            currentCamPos.set(rawPos);
            currentLookTarget.set(rawTarget);
        }

        // FOV
        if (shot.fov > 0) {
            currentFov = shot.fov;
        }
    }

    private void computeFollowShot(CameraShot shot, Vector3f playerPos, Vector3f playerLookTarget,
                                    Vector3f outPos, Vector3f outTarget) {
        // Compute the look direction from the player
        Vector3f lookDir = new Vector3f(playerLookTarget).sub(playerPos).normalize();
        Vector3f right = new Vector3f(lookDir).cross(new Vector3f(0, 1, 0)).normalize();

        // Target is the player at eye height + side offset
        Vector3f target = new Vector3f(playerPos).add(0, 1.35f, 0);
        target.add(new Vector3f(right).mul(shot.cameraOffset.x));

        // Camera is behind the player at the specified distance/height
        float distance = shot.cameraOffset.z;
        float height = shot.cameraOffset.y;
        Vector3f desired = new Vector3f(target).sub(new Vector3f(lookDir).mul(distance));
        desired.y += height - 1.35f;

        outPos.set(resolveCameraCollision(target, desired));
        outTarget.set(target);
    }

    private void computeOrbitShot(CameraShot shot, Vector3f playerPos, float dt,
                                   Vector3f outPos, Vector3f outTarget) {
        float radius = shot.cameraOffset.x;
        float height = shot.cameraOffset.y;
        float startAngle = shot.cameraOffset.z;
        float endAngle = shot.fixedPosition.x;

        // Interpolate angle over the shot duration
        float t = shot.duration > 0 ? Math.min(1.0f, shotTimer / shot.duration) : 0;
        float targetAngle = startAngle + (endAngle - startAngle) * t;

        // Smooth rotation
        orbitAngle += (targetAngle - orbitAngle) * Math.min(1.0f, dt * 2.0f);

        outTarget.set(playerPos).add(0, 1.0f, 0);
        outPos.set(
            outTarget.x + (float)Math.sin(orbitAngle) * radius,
            outTarget.y + height,
            outTarget.z + (float)Math.cos(orbitAngle) * radius
        );
    }

    private void computeCinematicShot(CameraShot shot, float t,
                                       Vector3f outPos, Vector3f outTarget) {
        if (shot.path.isEmpty()) return;

        // Find the two keyframes surrounding t
        int idx = 0;
        for (int i = 0; i < shot.timestamps.size() - 1; i++) {
            if (t >= shot.timestamps.get(i)) {
                idx = i;
            }
        }

        int nextIdx = Math.min(idx + 1, shot.path.size() - 1);
        float t0 = shot.timestamps.get(idx);
        float t1 = shot.timestamps.get(Math.min(idx + 1, shot.timestamps.size() - 1));
        float localT = (t1 > t0) ? (t - t0) / (t1 - t0) : 0;

        // Catmull-Rom or linear interpolation
        Vector3f p0 = shot.path.get(Math.max(0, idx - 1));
        Vector3f p1 = shot.path.get(idx);
        Vector3f p2 = shot.path.get(nextIdx);
        Vector3f p3 = shot.path.get(Math.min(nextIdx + 1, shot.path.size() - 1));

        float s = localT;
        float s2 = s * s;
        float s3 = s2 * s;

        // Catmull-Rom spline
        outPos.set(
            0.5f * ((2.0f * p1.x) + (-p0.x + p2.x) * s + (2.0f * p0.x - 5.0f * p1.x + 4.0f * p2.x - p3.x) * s2 + (-p0.x + 3.0f * p1.x - 3.0f * p2.x + p3.x) * s3),
            0.5f * ((2.0f * p1.y) + (-p0.y + p2.y) * s + (2.0f * p0.y - 5.0f * p1.y + 4.0f * p2.y - p3.y) * s2 + (-p0.y + 3.0f * p1.y - 3.0f * p2.y + p3.y) * s3),
            0.5f * ((2.0f * p1.z) + (-p0.z + p2.z) * s + (2.0f * p0.z - 5.0f * p1.z + 4.0f * p2.z - p3.z) * s2 + (-p0.z + 3.0f * p1.z - 3.0f * p2.z + p3.z) * s3)
        );

        // Look target: use the next path point or interpolate between two
        if (nextIdx + 1 < shot.path.size()) {
            outTarget.set(shot.path.get(nextIdx + 1));
        } else {
            outTarget.set(shot.path.get(nextIdx));
        }
    }

    private void computeDynamicShot(CameraShot shot, Vector3f playerPos, Vector3f playerLookTarget,
                                     float dt, Vector3f outPos, Vector3f outTarget) {
        // Dynamic shots adapt to context (combat, movement speed, etc.)
        // For now, uses FOLLOW behavior with dynamic distance based on combat state
        Vector3f lookDir = new Vector3f(playerLookTarget).sub(playerPos).normalize();
        Vector3f right = new Vector3f(lookDir).cross(new Vector3f(0, 1, 0)).normalize();

        float dynamicDist = shot.cameraOffset.z + combatZoom * 2.0f;
        float dynamicHeight = shot.cameraOffset.y - combatZoom * 0.5f;

        Vector3f target = new Vector3f(playerPos).add(0, 1.35f, 0);
        target.add(new Vector3f(right).mul(shot.cameraOffset.x));

        Vector3f desired = new Vector3f(target).sub(new Vector3f(lookDir).mul(dynamicDist));
        desired.y += dynamicHeight - 1.35f;

        outPos.set(resolveCameraCollision(target, desired));
        outTarget.set(target);
    }

    private void updateDynamicState(float dt, Vector3f playerPos, Vector3f playerLookTarget, boolean combatMode) {
        // Gradually adjust combat zoom
        float targetZoom = combatMode ? 1.0f : 0.0f;
        combatZoom += (targetZoom - combatZoom) * Math.min(1.0f, dt * 3.0f);
    }

    /** Resolve camera collision: slide camera in front of blocking blocks. */
    private Vector3f resolveCameraCollision(Vector3f origin, Vector3f desired) {
        // Collision against blocks would need world access.
        // For now, just return desired position (collision is handled in BlockInteraction).
        return new Vector3f(desired);
    }

    /** Utility: smoothstep interpolation. */
    public static float smoothstep(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    /** Utility: Catmull-Rom 1D interpolation. */
    public static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * t + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
    }
}
