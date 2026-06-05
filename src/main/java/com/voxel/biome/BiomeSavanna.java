package com.voxel.biome;

import java.util.Random;

public class BiomeSavanna extends Biome {
    public BiomeSavanna(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 1;
        this.flowersPerChunk = 4;
        this.grassPerChunk = 20;
    }

    @Override
    public Category getCategory() { return Category.SAVANNA; }

    @Override
    public float getTemperature(int x, int z) {
        return Math.max(0.95f, super.getTemperature(x, z));
    }

    @Override
    public float getHumidity(int x, int z) {
        return 0.0f;
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return 9; // Acacia tree
    }
}
