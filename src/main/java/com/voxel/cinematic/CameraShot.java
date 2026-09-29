package com.voxel.cinematic;

import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * One camera shot in a cinematic sequence — the unit of MCSM-style direction.
 *
 * A shot owns a camera path (start → end position), what it looks at (start →
 * end look-at points, optionally a tracked subject that keeps moving while the
 * shot plays), a lens (FOV) and an optional dutch roll. Sequences of shots are
 * played by {@link CinematicSystem}; between shots the camera CUTS (film edit),
 * within a shot it moves with an eased dolly/arc.
 *
 * Framing presets follow film conventions:
 *  - WIDE / ESTABLISHING — full scene, subject small in frame (FOV ~85)
 *  - MEDIUM — subject from the knees/waist up (FOV ~60)
 *  - CLOSE_UP — the MCSM signature: tight on the face, prop or block (FOV ~38)
 *  - OVER_THE_SHOULDER — viewer in foreground corner, subject beyond
 *  - LOW_ANGLE — hero shot from below
 *  - DOLLY / ORBIT / PUSH_IN — moves over the shot's duration
 */
public class CameraShot {

    public enum Ease {
        /** Smoothstep in/out — slow camera drifts. */
        SMOOTH,
        /** Constant speed — tracking moves. */
        LINEAR,
        /** Ease-out: fast start settling into the frame (MCSM snap-in). */
        EASE_OUT
    }

    public float duration = 2.0f;

    // Camera path (world space)
    public Vector3f fromPos = new Vector3f();
    public Vector3f toPos = new Vector3f();

    // Look-at: either fixed from→to points, or a tracked subject (re-evaluated
    // every tick so the camera keeps framing a moving player/mob).
    public Vector3f lookFrom = new Vector3f();
    public Vector3f lookTo = new Vector3f();
    public Supplier<Vector3f> trackSubject = null;

    // Lens + dutch
    public float fromFov = 90.0f;
    public float toFov = 90.0f;
    public float fromRoll = 0.0f;
    public float toRoll = 0.0f;

    public Ease ease = Ease.SMOOTH;

    /** Sampled camera state for a given progress t in [0,1]. */
    public static class Sample {
        public final Vector3f pos;
        public final Vector3f lookAt;
        public final float fov;
        public final float roll;

        public Sample(Vector3f pos, Vector3f lookAt, float fov, float roll) {
            this.pos = pos;
            this.lookAt = lookAt;
            this.fov = fov;
            this.roll = roll;
        }
    }

    /** Sample the shot at normalized time t (0..1). */
    public Sample sample(float t) {
        float e;
        switch (ease) {
            case LINEAR:   e = t; break;
            case EASE_OUT: e = 1.0f - (1.0f - t) * (1.0f - t); break;
            default:       e = t * t * (3.0f - 2.0f * t); break;
        }
        Vector3f pos = new Vector3f(fromPos).lerp(toPos, e);
        Vector3f look = trackSubject != null
            ? new Vector3f(trackSubject.get())
            : new Vector3f(lookFrom).lerp(lookTo, e);
        float fov = fromFov + (toFov - fromFov) * e;
        float roll = fromRoll + (toRoll - fromRoll) * e;
        return new Sample(pos, look, fov, roll);
    }

    // ── Convenience builders (film framing presets) ───────────────────────────

    /** Camera positions around a subject at the given azimuth (deg) and radius. */
    private static Vector3f orbitPos(Vector3f subject, float azimuthDeg, float radius, float height) {
        double a = Math.toRadians(azimuthDeg);
        return new Vector3f(
            subject.x + (float) Math.cos(a) * radius,
            subject.y + height,
            subject.z + (float) Math.sin(a) * radius
        );
    }

    /** Wide establishing shot, slow drift across the frame. */
    public static CameraShot wide(Vector3f subject, float fromAzimuth, float toAzimuth,
                                  float radius, float height, float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        s.fromPos.set(orbitPos(subject, fromAzimuth, radius, height));
        s.toPos.set(orbitPos(subject, toAzimuth, radius, height));
        s.lookFrom.set(subject);
        s.lookTo.set(subject);
        s.fromFov = s.toFov = 85.0f;
        return s;
    }

    /** Medium shot — subject waist-up, gentle push-in. */
    public static CameraShot medium(Vector3f subject, float azimuthDeg, float radius,
                                    float height, float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        s.fromPos.set(orbitPos(subject, azimuthDeg, radius, height));
        s.toPos.set(orbitPos(subject, azimuthDeg, radius * 0.82f, height * 0.92f));
        s.lookFrom.set(subject);
        s.lookTo.set(subject);
        s.fromFov = 60.0f;
        s.toFov = 55.0f;
        return s;
    }

    /** Close-up — the MCSM signature cut: tight on the subject with a long-ish
     *  lens and a slow creep closer. subject should be the prop/face point. */
    public static CameraShot closeUp(Vector3f subject, float azimuthDeg, float radius,
                                     float height, float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        s.fromPos.set(orbitPos(subject, azimuthDeg, radius, height));
        s.toPos.set(orbitPos(subject, azimuthDeg, radius * 0.8f, height * 0.9f));
        s.lookFrom.set(subject);
        s.lookTo.set(subject);
        s.fromFov = 38.0f;
        s.toFov = 34.0f;
        s.ease = Ease.EASE_OUT;
        return s;
    }

    /** Low-angle hero shot from below, slight upward drift. */
    public static CameraShot lowAngle(Vector3f subject, float azimuthDeg, float radius,
                                      float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        s.fromPos.set(orbitPos(subject, azimuthDeg, radius, -0.8f));
        s.toPos.set(orbitPos(subject, azimuthDeg + 12.0f, radius * 0.9f, -0.5f));
        s.lookFrom.set(subject);
        s.lookTo.set(subject);
        s.fromFov = s.toFov = 55.0f;
        s.fromRoll = 4.0f;
        s.toRoll = 2.0f;
        return s;
    }

    /** Over-the-shoulder: viewer's shoulder in the near corner, subject beyond. */
    public static CameraShot overTheShoulder(Vector3f viewer, Vector3f subject, float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        Vector3f dir = new Vector3f(subject).sub(viewer);
        if (dir.lengthSquared() < 1e-4f) dir.set(1, 0, 0);
        dir.normalize();
        Vector3f right = new Vector3f(-dir.z, 0, dir.x).normalize();
        Vector3f shoulder = new Vector3f(viewer)
            .add(0, 1.55f, 0)
            .fma(-0.45f, dir)
            .fma(0.55f, right);
        s.fromPos.set(shoulder);
        s.toPos.set(new Vector3f(shoulder).fma(0.25f, dir));
        s.lookFrom.set(subject);
        s.lookTo.set(subject);
        s.fromFov = s.toFov = 50.0f;
        return s;
    }

    /** Straight dolly along from→to while looking at a fixed (or tracked) point. */
    public static CameraShot dolly(Vector3f from, Vector3f to, Vector3f lookAt, float fov, float duration) {
        CameraShot s = new CameraShot();
        s.duration = duration;
        s.fromPos.set(from);
        s.toPos.set(to);
        s.lookFrom.set(lookAt);
        s.lookTo.set(lookAt);
        s.fromFov = s.toFov = fov;
        return s;
    }

    /** Orbit around a center at fixed radius/height through an angular span. */
    public static CameraShot orbit(Vector3f center, float radius, float height,
                                   float fromAzimuth, float toAzimuth, float duration) {
        CameraShot s = wide(center, fromAzimuth, toAzimuth, radius, height, duration);
        s.fromFov = s.toFov = 70.0f;
        return s;
    }

    /** Make this shot track a moving subject (the look-at follows it every tick). */
    public CameraShot tracking(Supplier<Vector3f> subject) {
        this.trackSubject = subject;
        return this;
    }

    /** Give the shot a dutch angle (degrees of roll). */
    public CameraShot dutch(float from, float to) {
        this.fromRoll = from;
        this.toRoll = to;
        return this;
    }

    /** Set the lens explicitly. */
    public CameraShot lens(float from, float to) {
        this.fromFov = from;
        this.toFov = to;
        return this;
    }
}
