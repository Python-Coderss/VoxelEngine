package com.voxel.api;

public class Block {
    private final String name;

    public Block(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Returns the texture name for the given side.
     * Default implementation returns the same texture for all sides.
     */
    public String getTexture(Direction side) {
        return name;
    }

    public int getTransparency() {
        return 0; // 0 = Opaque
    }

    public int getReflectivity() {
        return 0; // 0 = Non-reflective
    }
}
