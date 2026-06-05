package com.voxel.biome;

import java.util.Random;

public class BiomeMushroomIsland extends Biome {
    public BiomeMushroomIsland(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 66; // mycelium
        this.treesPerChunk = -999;
        this.grassPerChunk = 0;
        this.flowersPerChunk = 0;
        this.mushroomsPerChunk = 8;
        this.bigMushroomsPerChunk = 1;
        flowers.clear();
    }

    @Override
    public Category getCategory() { return Category.MUSHROOM; }
}
