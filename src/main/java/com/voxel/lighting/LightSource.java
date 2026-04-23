package com.voxel.lighting;

import org.joml.Vector3i;
import org.joml.Vector3f;

public class LightSource implements Comparable<LightSource> {
    public Vector3i position;
    public Vector3f color;        // RGB [0-1]
    public float intensity;       // Base brightness [0-15]
    public float radius;          // Max propagation distance
    public LightType type;        // SUN, BLOCK, DYNAMIC
    public int entityID;          // -1 if static (changed from 0xFFFFFFFF for Java idiomatic)
    
    public LightSource(Vector3i position, Vector3f color, float intensity, float radius, LightType type) {
        this.position = position;
        this.color = color;
        this.intensity = intensity;
        this.radius = radius;
        this.type = type;
        this.entityID = -1;
    }

    @Override
    public int compareTo(LightSource other) {
        // Priority: intensity → type
        if (this.intensity != other.intensity) {
            return Float.compare(other.intensity, this.intensity); // Descending
        }
        return Integer.compare(this.type.priority, other.type.priority);
    }
}
