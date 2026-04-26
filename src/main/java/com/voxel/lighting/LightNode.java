package com.voxel.lighting;

import org.joml.Vector3i;
import org.joml.Vector3f;

/**
 * Represents a point in space (a voxel) during the light propagation process.
 * It tracks the position, the light color reaching this point, and metadata about the source.
 */
public class LightNode {
    /** The world coordinates of this light node. */
    public Vector3i position;
    
    /** The RGB light color that has reached this voxel. Each component is usually in range [0, 1]. */
    public Vector3f accumulatedColor;

    /** The distance from the original light source. Used for attenuation. */
    public float distance;

    /** The unique identifier of the light source that this node originated from. */
    public int sourceID;

    /**
     * Constructs a new LightNode.
     * @param position World coordinates.
     * @param color Current light color at this position.
     * @param distance Distance from the source.
     * @param sourceID ID of the parent light source.
     */
    public LightNode(Vector3i position, Vector3f color, float distance, int sourceID) {
        this.position = position;
        this.accumulatedColor = color;
        this.distance = distance;
        this.sourceID = sourceID;
    }

    /**
     * Calculates the processing priority for this node in the light engine's queue.
     * Brighter nodes and nodes closer to the source are processed first.
     * @return The priority value.
     */
    public float getPriority() {
        // Higher length (brightness) and lower distance results in higher priority.
        return accumulatedColor.length() / (1.0f + distance);
    }
}
