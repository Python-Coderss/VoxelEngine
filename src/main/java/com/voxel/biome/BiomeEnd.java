package com.voxel.biome;

import java.util.Random;

public class BiomeEnd extends Biome {
    public BiomeEnd(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 18; // end_stone
        this.fillerBlockId = 18;
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.NONE; }
}
