package com.voxel.biome;

import java.util.Random;

public class BiomeVoid extends Biome {
    public BiomeVoid(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = -999;
        this.flowersPerChunk = 0;
        this.grassPerChunk = 0;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.NONE; }
}
