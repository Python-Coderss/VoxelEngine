package com.voxel.biome;

import java.util.Random;

public class BiomeHell extends Biome {
    public BiomeHell(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 20; // netherrack
        this.fillerBlockId = 20;
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.NONE; }
}
