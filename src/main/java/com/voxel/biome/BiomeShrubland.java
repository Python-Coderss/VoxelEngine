package com.voxel.biome;

import java.util.Random;

/**
 * Beta 1.7.3 Shrubland biome.
 * Sparse vegetation with scattered bushes, few trees, lots of grass.
 * Beta 1.7.3 original: biome ID 5.
 */
public class BiomeShrubland extends Biome {
    public BiomeShrubland(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 2;
        this.flowersPerChunk = 6;
        this.grassPerChunk = 12;
        this.deadBushPerChunk = 3;
        this.waterColor = 0x4DA6FF;
    }

    @Override
    public Category getCategory() { return Category.PLAINS; }

    @Override
    public int getRandomTreeFeature(Random rand) {
        // Shrubland: very rare small trees
        return rand.nextInt(4) == 0 ? 0 : -1;
    }
}
