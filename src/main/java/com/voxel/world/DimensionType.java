package com.voxel.world;

/**
 * Enum representing the different dimensions in the game.
 */
public enum DimensionType {
    OVERWORLD(0, "overworld", 64),      // Grass plains, standard terrain
    NETHER(1, "nether", 32),             // Cave-like, low ceiling, red fog
    END(2, "end", 48),                   // Floating islands, dark with purple fog
    AETHER(3, "aether", 96);             // Floating islands, bright sky
    
    public final int id;
    public final String name;
    public final int baseHeight; // Default terrain height for this dimension
    
    DimensionType(int id, String name, int baseHeight) {
        this.id = id;
        this.name = name;
        this.baseHeight = baseHeight;
    }
    
    public static DimensionType fromId(int id) {
        for (DimensionType dim : values()) {
            if (dim.id == id) return dim;
        }
        return OVERWORLD;
    }
}
