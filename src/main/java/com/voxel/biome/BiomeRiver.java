package com.voxel.biome;

import java.util.Random;

public class BiomeRiver extends Biome {
    public BiomeRiver(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        this.reedsPerChunk = 10;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.RIVER; }
}
