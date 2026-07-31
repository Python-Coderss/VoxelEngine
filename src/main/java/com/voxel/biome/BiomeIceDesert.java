package com.voxel.biome;

import java.util.Random;

/**
 * Beta 1.7.3 Ice Desert biome.
 * Frozen wasteland with sand/ice surfaces, snow cover, no trees.
 * Beta 1.7.3 original: biome ID 9.
 */
public class BiomeIceDesert extends Biome {
    public BiomeIceDesert(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 14;  // sand
        this.fillerBlockId = 14; // sand
        this.enableSnow = true;
        this.treesPerChunk = 0;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        this.deadBushPerChunk = 0;
        this.waterColor = 0xA4C8FF;
    }

    @Override
    public Category getCategory() { return Category.ICY; }

    @Override
    public float getTemperature(int x, int z) {
        return Math.min(0.0f, super.getTemperature(x, z));
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return -1; // No trees in ice desert
    }
}
