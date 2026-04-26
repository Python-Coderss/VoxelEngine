package com.voxel.lighting;

import org.joml.Vector3i;
import org.joml.Vector3f;

/**
 * Represents a source of light in the world.
 * Light sources are used as starting points for the light propagation algorithm.
 */
public class LightSource implements Comparable<LightSource> {
    /** The world coordinates where the light originates. */
    public Vector3i position;
    
    /** The RGB color of the light source, where each channel is in the range [0.0, 1.0]. */
    public Vector3f color;

    /** The base brightness of the light, typically on a scale of 0 to 15 (matching Minecraft's style). */
    public float intensity;

    /** The maximum distance this light can travel before becoming too dim to see. */
    public float radius;

    /** The category of light source (Sun, Block, etc.). */
    public LightType type;

    /** ID of the entity this light is attached to, or -1 if it's a static world light. */
    public int entityID;

    /**
     * Constructs a new LightSource.
     * @param position World coordinates.
     * @param color RGB color.
     * @param intensity Initial brightness.
     * @param radius Maximum propagation distance.
     * @param type Category of the light.
     */
    public LightSource(Vector3i position, Vector3f color, float intensity, float radius, LightType type) {
        this.position = position;
        this.color = color;
        this.intensity = intensity;
        this.radius = radius;
        this.type = type;
        this.entityID = -1;
    }

    /**
     * Compares two light sources to determine which should be prioritized.
     * Brighter sources are processed first.
     */
    @Override
    public int compareTo(LightSource other) {
        // Compare intensities in descending order
        if (this.intensity != other.intensity) {
            return Float.compare(other.intensity, this.intensity);
        }
        // If intensities are equal, compare by type priority
        return Integer.compare(this.type.priority, other.type.priority);
    }
}
