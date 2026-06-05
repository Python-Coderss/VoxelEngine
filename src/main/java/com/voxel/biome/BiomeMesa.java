package com.voxel.biome;

import java.util.Random;

public class BiomeMesa extends Biome {
    private final boolean bryce;
    private final boolean hasTrees;

    public BiomeMesa(String name, BiomeProperties props, boolean hasTrees, boolean bryce) {
        super(name, props);
        this.bryce = bryce;
        this.hasTrees = hasTrees;
        this.topBlockId = 14; // sand (red sand in real MC)
        this.fillerBlockId = 65; // hardened clay
        this.deadBushPerChunk = hasTrees ? 0 : 20;
        this.cactiPerChunk = hasTrees ? 0 : 5;
        this.treesPerChunk = hasTrees ? 20 : -999;
        this.grassPerChunk = 0;
        this.flowersPerChunk = 0;
        this.reedsPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.MESA; }

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
        return hasTrees ? 0 : -1; // Oak tree in wooded variants
    }
}
