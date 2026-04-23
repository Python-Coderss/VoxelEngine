package com.voxel.lighting;

import org.joml.Vector3i;
import org.joml.Vector3f;

public class LightNode {
    public Vector3i position;
    public Vector3f accumulatedColor; // RGB light at this voxel
    public float distance;             // Distance from source
    public int sourceID;               // Which light source
    
    public LightNode(Vector3i position, Vector3f color, float distance, int sourceID) {
        this.position = position;
        this.accumulatedColor = color;
        this.distance = distance;
        this.sourceID = sourceID;
    }

    // Priority: brighter/closer nodes processed first
    public float getPriority() {
        return accumulatedColor.length() / (1.0f + distance);
    }
}
