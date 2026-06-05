package com.voxel.biome;

import java.util.Random;

public class BiomeBeach extends Biome {
    public BiomeBeach(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 14; // sand
        this.fillerBlockId = 14;
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.NONE; }
}
