package com.voxel.lighting;

/**
 * Defines the various types of light sources in the engine.
 * Each type has a priority used during light propagation.
 */
public enum LightType {
    /**
     * Global sunlight coming from above.
     */
    SUN(0),
    
    /**
     * Static light emitted from specific blocks (e.g., torches, lanterns).
     */
    BLOCK(1),

    /**
     * Lights that can move, typically attached to entities (currently unused).
     */
    DYNAMIC(2);

    /** The priority of the light type. Lower values usually mean higher importance. */
    public final int priority;

    /**
     * Constructor for the enum.
     * @param p The priority level.
     */
    LightType(int p) { this.priority = p; }
}
