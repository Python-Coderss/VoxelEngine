package com.voxel.entity;

import org.joml.Vector3f;
import java.util.*;

/**
 * Keyframe-based animation system for entities.
 * Defines animated transformations for ModelParts over time.
 * Supports looping, blending, and per-part keyframes.
 */
public class Animation {

    /** A single keyframe for one model part at a given time. */
    public static class Keyframe {
        public float time;           // normalized [0,1] within the animation
        public Vector3f offset;      // position offset
        public Vector3f rotation;    // euler rotation in degrees
        public Vector3f scale;       // scale (1,1,1 = no scale)
        public boolean visible;      // whether the part is visible

        public Keyframe(float time, Vector3f offset, Vector3f rotation) {
            this.time = time;
            this.offset = new Vector3f(offset);
            this.rotation = new Vector3f(rotation);
            this.scale = new Vector3f(1, 1, 1);
            this.visible = true;
        }

        public Keyframe(float time, Vector3f offset, Vector3f rotation, Vector3f scale, boolean visible) {
            this.time = time;
            this.offset = new Vector3f(offset);
            this.rotation = new Vector3f(rotation);
            this.scale = new Vector3f(scale);
            this.visible = visible;
        }
    }

    /** A track of keyframes for a single model part. */
    public static class PartTrack {
        public String partName;
        public List<Keyframe> keyframes = new ArrayList<>();

        public PartTrack(String partName) {
            this.partName = partName;
        }

        public void addKeyframe(float time, Vector3f offset, Vector3f rotation) {
            keyframes.add(new Keyframe(time, offset, rotation));
            keyframes.sort(Comparator.comparingDouble(k -> k.time));
        }

        public void addKeyframe(float time, Vector3f offset, Vector3f rotation, Vector3f scale, boolean visible) {
            keyframes.add(new Keyframe(time, offset, rotation, scale, visible));
            keyframes.sort(Comparator.comparingDouble(k -> k.time));
        }

        /** Sample the animation at time t, interpolating between keyframes. */
        public Keyframe sample(float t) {
            if (keyframes.isEmpty()) return null;
            if (keyframes.size() == 1) return keyframes.get(0);
            if (t <= keyframes.get(0).time) return keyframes.get(0);
            if (t >= keyframes.get(keyframes.size() - 1).time) return keyframes.get(keyframes.size() - 1);

            // Find the two surrounding keyframes
            for (int i = 0; i < keyframes.size() - 1; i++) {
                Keyframe k0 = keyframes.get(i);
                Keyframe k1 = keyframes.get(i + 1);
                if (t >= k0.time && t <= k1.time) {
                    float localT = (k1.time > k0.time) ? (t - k0.time) / (k1.time - k0.time) : 0;
                    // Linear interpolation
                    Vector3f offset = new Vector3f(k0.offset).lerp(k1.offset, localT);
                    Vector3f rotation = lerpAngles(k0.rotation, k1.rotation, localT);
                    Vector3f scale = new Vector3f(k0.scale).lerp(k1.scale, localT);
                    boolean visible = localT < 0.5 ? k0.visible : k1.visible;
                    return new Keyframe(t, offset, rotation, scale, visible);
                }
            }
            return keyframes.get(keyframes.size() - 1);
        }

        private Vector3f lerpAngles(Vector3f a, Vector3f b, float t) {
            return new Vector3f(
                a.x + shortestAngleDelta(a.x, b.x) * t,
                a.y + shortestAngleDelta(a.y, b.y) * t,
                a.z + shortestAngleDelta(a.z, b.z) * t
            );
        }

        private float shortestAngleDelta(float from, float to) {
            float diff = (to - from) % 360;
            if (diff > 180) diff -= 360;
            if (diff < -180) diff += 360;
            return diff;
        }
    }

    // ── Animation properties ──
    private String name;
    private float duration;       // in seconds
    private boolean looping;
    private Map<String, PartTrack> tracks = new LinkedHashMap<>();

    public Animation(String name, float duration) {
        this.name = name;
        this.duration = duration;
        this.looping = false;
    }

    public Animation(String name, float duration, boolean looping) {
        this.name = name;
        this.duration = duration;
        this.looping = looping;
    }

    // ── Track management ──

    public PartTrack getOrCreateTrack(String partName) {
        return tracks.computeIfAbsent(partName, PartTrack::new);
    }

    public void addKeyframe(String partName, float time, Vector3f offset, Vector3f rotation) {
        getOrCreateTrack(partName).addKeyframe(time, offset, rotation);
    }

    public void addKeyframe(String partName, float time, Vector3f offset, Vector3f rotation, Vector3f scale, boolean visible) {
        getOrCreateTrack(partName).addKeyframe(time, offset, rotation, scale, visible);
    }

    // ── Sampling ──

    /** Sample all tracks at the given time (in seconds). Returns a map of partName → sampled Keyframe. */
    public Map<String, Keyframe> sample(float time) {
        float t = duration > 0 ? time / duration : 0;
        if (looping) {
            t = t % 1.0f;
            if (t < 0) t += 1.0f;
        } else {
            t = Math.min(1.0f, Math.max(0, t));
        }

        Map<String, Keyframe> result = new LinkedHashMap<>();
        for (PartTrack track : tracks.values()) {
            Keyframe kf = track.sample(t);
            if (kf != null) {
                result.put(track.partName, kf);
            }
        }
        return result;
    }

    public String getName() { return name; }
    public float getDuration() { return duration; }
    public boolean isLooping() { return looping; }

    // ── Built-in animations ──

    /** Creates a simple walk cycle animation. */
    public static Animation createWalkCycle(String entityType) {
        Animation anim = new Animation(entityType + "_walk", 0.8f, true);

        // Leg swing: 35° amplitude (no duplicate at t=1.0 since looping wraps)
        anim.addKeyframe("left_leg", 0.0f, new Vector3f(), new Vector3f(-35, 0, 0));
        anim.addKeyframe("left_leg", 0.25f, new Vector3f(), new Vector3f(35, 0, 0));
        anim.addKeyframe("left_leg", 0.5f, new Vector3f(), new Vector3f(-35, 0, 0));
        anim.addKeyframe("left_leg", 0.75f, new Vector3f(), new Vector3f(35, 0, 0));

        anim.addKeyframe("right_leg", 0.0f, new Vector3f(), new Vector3f(35, 0, 0));
        anim.addKeyframe("right_leg", 0.25f, new Vector3f(), new Vector3f(-35, 0, 0));
        anim.addKeyframe("right_leg", 0.5f, new Vector3f(), new Vector3f(35, 0, 0));
        anim.addKeyframe("right_leg", 0.75f, new Vector3f(), new Vector3f(-35, 0, 0));

        // Arm swing (opposite to legs)
        anim.addKeyframe("left_arm", 0.0f, new Vector3f(), new Vector3f(35, 0, -5));
        anim.addKeyframe("left_arm", 0.25f, new Vector3f(), new Vector3f(-35, 0, -5));
        anim.addKeyframe("left_arm", 0.5f, new Vector3f(), new Vector3f(35, 0, -5));
        anim.addKeyframe("left_arm", 0.75f, new Vector3f(), new Vector3f(-35, 0, -5));

        anim.addKeyframe("right_arm", 0.0f, new Vector3f(), new Vector3f(-35, 0, 5));
        anim.addKeyframe("right_arm", 0.25f, new Vector3f(), new Vector3f(35, 0, 5));
        anim.addKeyframe("right_arm", 0.5f, new Vector3f(), new Vector3f(-35, 0, 5));
        anim.addKeyframe("right_arm", 0.75f, new Vector3f(), new Vector3f(35, 0, 5));

        // Body bob (no duplicate at t=1.0)
        anim.addKeyframe("body", 0.0f, new Vector3f(0, -0.1f, 0), new Vector3f());
        anim.addKeyframe("body", 0.5f, new Vector3f(0, 0.1f, 0), new Vector3f());

        return anim;
    }

    /** Creates an attack animation (arm swing). */
    public static Animation createAttackAnimation() {
        Animation anim = new Animation("attack", 0.3f, false);
        anim.addKeyframe("right_arm", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("right_arm", 0.15f, new Vector3f(0, 0.2f, 0), new Vector3f(-90, 0, 10));
        anim.addKeyframe("right_arm", 0.3f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("left_arm", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("left_arm", 0.15f, new Vector3f(), new Vector3f(-30, 0, 5));
        anim.addKeyframe("left_arm", 0.3f, new Vector3f(), new Vector3f(0, 0, 0));
        return anim;
    }

    /** Creates a roll/spin animation. */
    public static Animation createRollAnimation() {
        Animation anim = new Animation("roll", 0.6f, false);
        anim.addKeyframe("body", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("body", 0.25f, new Vector3f(0, 0.4f, 0), new Vector3f(90, 0, 0));
        anim.addKeyframe("body", 0.5f, new Vector3f(0, 0.4f, 0), new Vector3f(180, 0, 0));
        anim.addKeyframe("body", 0.75f, new Vector3f(0, 0.4f, 0), new Vector3f(270, 0, 0));
        anim.addKeyframe("body", 1.0f, new Vector3f(), new Vector3f(360, 0, 0));

        // Tuck limbs during roll
        anim.addKeyframe("left_leg", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("left_leg", 0.5f, new Vector3f(0, 0.3f, 0.1f), new Vector3f(60, 0, 0));
        anim.addKeyframe("left_leg", 1.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("right_leg", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("right_leg", 0.5f, new Vector3f(0, 0.3f, 0.1f), new Vector3f(60, 0, 0));
        anim.addKeyframe("right_leg", 1.0f, new Vector3f(), new Vector3f(0, 0, 0));
        return anim;
    }

    /** Creates a death/fall animation. */
    public static Animation createDeathAnimation() {
        Animation anim = new Animation("death", 1.0f, false);
        anim.addKeyframe("body", 0.0f, new Vector3f(), new Vector3f(0, 0, 0));
        anim.addKeyframe("body", 0.5f, new Vector3f(0, 0.2f, 0), new Vector3f(45, 0, 0));
        anim.addKeyframe("body", 1.0f, new Vector3f(0, 0.1f, 0), new Vector3f(90, 0, 0));
        return anim;
    }
}
