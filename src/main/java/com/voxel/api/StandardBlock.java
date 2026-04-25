package com.voxel.api;

import java.util.HashMap;
import java.util.Map;

public class StandardBlock extends Block {
    private final Map<Direction, String> sideTextures = new HashMap<>();
    private int transparency = 0;
    private int reflectivity = 0;

    public StandardBlock(String name) {
        super(name);
    }

    public StandardBlock setSideTexture(Direction side, String texture) {
        sideTextures.put(side, texture);
        return this;
    }

    public StandardBlock setTransparency(int transparency) {
        this.transparency = transparency;
        return this;
    }

    public StandardBlock setReflectivity(int reflectivity) {
        this.reflectivity = reflectivity;
        return this;
    }

    @Override
    public String getTexture(Direction side) {
        return sideTextures.getOrDefault(side, super.getTexture(side));
    }

    @Override
    public int getTransparency() {
        return transparency;
    }

    @Override
    public int getReflectivity() {
        return reflectivity;
    }
}
