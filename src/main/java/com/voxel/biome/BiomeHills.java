package com.voxel.biome;

import java.util.Random;

public class BiomeHills extends Biome {
    public BiomeHills(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 0;
    }

    @Override
    public Category getCategory() { return Category.EXTREME_HILLS; }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return rand.nextInt(3) == 0 ? 7 : -1; // 1/3 chance of spruce
    }
}
