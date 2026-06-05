package com.voxel.biome;

import java.util.Random;

public class BiomeOcean extends Biome {
    public BiomeOcean(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.OCEAN; }
}
