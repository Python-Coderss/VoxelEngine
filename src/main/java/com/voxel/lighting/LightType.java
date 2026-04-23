package com.voxel.lighting;

public enum LightType {
    SUN(0),           // Highest priority
    BLOCK(1),         // Static emissive voxels
    DYNAMIC(2);       // Entity-attached lights
    
    public final int priority;
    LightType(int p) { this.priority = p; }
}
