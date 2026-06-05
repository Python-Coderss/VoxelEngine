package com.voxel.biome;

import java.util.Random;

public class BiomePlains extends Biome {
    private final boolean sunflowers;

    public BiomePlains(String name, BiomeProperties props, boolean sunflowers) {
        super(name, props);
        this.sunflowers = sunflowers;
        this.treesPerChunk = 0;
        this.flowersPerChunk = 4;
        this.grassPerChunk = 10;

        if (sunflowers) {
            this.flowersPerChunk = 100;
            this.grassPerChunk = 1;
            flowers.clear();
            addFlower(34, 10); // poppy
            addFlower(121, 8);  // dandelion
        }
    }

    @Override
    public Category getCategory() { return Category.PLAINS; }

    @Override
    public int getRandomTreeFeature(Random rand) { return -1; } // No trees
}
