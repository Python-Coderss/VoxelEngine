package com.voxel.ai.body;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Priority-stack head gaze, kept as pure math (desired/smoothed angles only)
 * so it is testable without model or GL types. The entity layer applies the
 * returned angles to its head ModelPart.
 */
public final class GazeController {

    private static final float SMOOTH_RATE = 10f;

    private static final class Request {
        final Vector3f point = new Vector3f();
        final float priority;
        float expiresInSeconds;

        Request(Vector3f point, float priority, float holdSeconds) {
            this.point.set(point);
            this.priority = priority;
            this.expiresInSeconds = holdSeconds;
        }
    }

    private final List<Request> requests = new ArrayList<>();
    private float time;
    private long seed = 1;

    public void setSeed(long seed) {
        this.seed = seed == 0 ? 1 : seed;
    }

    public void lookAt(Vector3f worldPoint, float priority, float holdSeconds) {
        if (worldPoint == null) return;
        clearWeakerThan(priority);
        for (Request r : requests) {
            if (r.priority == priority) {
                r.point.set(worldPoint);
                r.expiresInSeconds = Math.max(r.expiresInSeconds, holdSeconds);
                return;
            }
        }
        requests.add(new Request(worldPoint, priority, holdSeconds));
    }

    public void clearWeakerThan(float priority) {
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            if (it.next().priority <= priority) it.remove();
        }
    }

    public boolean hasTargetAtLeast(float priority) {
        for (Request r : requests) {
            if (r.priority >= priority) return true;
        }
        return false;
    }

    /** Ages requests; call once per tick. */
    public void tick(float dt) {
        time += dt;
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request r = it.next();
            r.expiresInSeconds -= dt;
            if (r.expiresInSeconds <= 0f) it.remove();
        }
    }

    /**
     * Resolves the current desired look and advances smoothing.
     *
     * @return {pitchDeg, yawOffsetDeg} where yawOffset is relative to bodyYawDeg
     *         (clamped to ±75°), or idle saccades when no request is active.
     */
    public float[] resolve(Vector3f eyePos, float bodyYawDeg, float dt) {
        Request best = null;
        for (Request r : requests) {
            if (best == null || r.priority > best.priority) best = r;
        }

        float desiredPitch, desiredYawOffset;
        if (best != null) {
            float dx = best.point.x - eyePos.x;
            float dy = best.point.y - eyePos.y;
            float dz = best.point.z - eyePos.z;
            float flat = (float) Math.sqrt(dx * dx + dz * dz);
            desiredPitch = (float) Math.toDegrees(Math.atan2(dy, flat));
            float absYaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            desiredYawOffset = wrap180(absYaw - bodyYawDeg);
            if (desiredYawOffset > 75f) desiredYawOffset = 75f;
            if (desiredYawOffset < -75f) desiredYawOffset = -75f;
        } else {
            desiredPitch = (float) Math.sin(time * 0.7f + seed) * 4f;
            desiredYawOffset = (float) Math.sin(time * 0.5f + seed * 0.37f) * 14f
                    + (float) Math.sin(time * 1.3f + seed * 2.11f) * 6f;
        }

        float k = 1f - (float) Math.exp(-SMOOTH_RATE * dt);
        smoothedPitch += (desiredPitch - smoothedPitch) * k;
        smoothedYaw += (desiredYawOffset - smoothedYaw) * k;
        return new float[]{smoothedPitch, smoothedYaw};
    }

    private float smoothedPitch;
    private float smoothedYaw;

    public static float wrap180(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
