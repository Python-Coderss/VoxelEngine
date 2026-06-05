package com.voxel.biome;

import java.util.Random;

public class BiomeStoneBeach extends Biome {
    public BiomeStoneBeach(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 2; // stone
        this.fillerBlockId = 2;
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.NONE; }
}
