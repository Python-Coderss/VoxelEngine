package com.voxel.ai;

import org.joml.Vector3f;

/**
 * One perceivable event in the world. Published onto a {@link StimulusBus} by
 * emitters (combat, perception, speech) and consumed by {@link MobBrain}s.
 */
public final class Stimulus {

    public enum Type {
        THREAT_SEEN,
        DAMAGE_TAKEN,
        SPEECH_HEARD,
        POINT_GESTURE,
        NOVEL_EVENT
    }

    public final Type type;
    /** Entity id of whoever caused or emitted the stimulus (-1 if environmental). */
    public final int sourceId;
    /** World position the stimulus originated from. */
    public final Vector3f position;
    /** Arbitrary intensity scale for prioritization (damage amount, threat level...). */
    public final float severity;
    /** Optional free-form payload (spoken text, referent description). */
    public final String payload;
    /** Logic-clock timestamp in milliseconds. */
    public final long whenMillis;

    public Stimulus(Type type, int sourceId, Vector3f position,
                    float severity, String payload, long whenMillis) {
        this.type = type;
        this.sourceId = sourceId;
        this.position = position == null ? new Vector3f() : new Vector3f(position);
        this.severity = severity;
        this.payload = payload;
        this.whenMillis = whenMillis;
    }
}
