package com.voxel.biome;

import java.util.Random;

/**
 * Beta 1.7.3 Tundra biome.
 * Cold grassland with scattered snow, no trees, some grass.
 * Beta 1.7.3 original: biome ID 10.
 */
public class BiomeTundra extends Biome {
    public BiomeTundra(String name, BiomeProperties props) {
        super(name, props);
        this.topBlockId = 1;   // grass_block
        this.fillerBlockId = 13; // dirt
        this.enableSnow = true;
        this.treesPerChunk = 0;
        this.flowersPerChunk = 1;
        this.grassPerChunk = 3;
        this.deadBushPerChunk = 0;
        this.waterColor = 0x8CA8CC;
    }

    @Override
    public Category getCategory() { return Category.ICY; }

    @Override
    public float getTemperature(int x, int z) {
        return Math.min(0.05f, super.getTemperature(x, z));
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return -1; // No trees in tundra
    }
}
