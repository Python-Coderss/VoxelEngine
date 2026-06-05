package com.voxel.biome;

import java.util.Random;

public class BiomeSnow extends Biome {
    private final boolean superIcy; // Ice Plains Spikes

    public BiomeSnow(String name, BiomeProperties props, boolean superIcy) {
        super(name, props);
        this.superIcy = superIcy;
        this.enableSnow = true;
        this.treesPerChunk = 0;
    }

    @Override
    public Category getCategory() { return Category.ICY; }

    @Override
    public float getTemperature(int x, int z) {
        // Snow biomes must stay cold — clamp to max 0.0 so the tint map
        // always samples the cold end of the grass/foliage colormap.
        return Math.min(0.0f, super.getTemperature(x, z));
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return 7; // Spruce
    }
}
