package com.voxel.biome;

import java.util.Random;

public class BiomeDesert extends Biome {
    public BiomeDesert(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 14; // sand
        this.fillerBlockId = 14; // sand
        this.treesPerChunk = -999; // disabled
        this.deadBushPerChunk = 2;
        this.reedsPerChunk = 50;
        this.cactiPerChunk = 10;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        this.desertWellChance = 1000;
        this.fossilChance = 64;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.DESERT; }

    @Override
    public float getTemperature(int x, int z) {
        // Deserts are always hot — clamp to min 0.95 for the hot end of the colormap.
        return Math.max(0.95f, super.getTemperature(x, z));
    }

    @Override
    public float getHumidity(int x, int z) {
        // Deserts are always dry.
        return 0.0f;
    }

    @Override
    public int getRandomTreeFeature(Random rand) { return -1; } // No trees
}
