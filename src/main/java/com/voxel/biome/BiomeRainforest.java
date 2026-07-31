package com.voxel.biome;

import java.util.Random;

/**
 * Beta 1.7.3 Rainforest biome.
 * Dense wet forest with high tree density and lush vegetation.
 * Beta 1.7.3 original: biome ID 0.
 */
public class BiomeRainforest extends Biome {
    public BiomeRainforest(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 20;
        this.flowersPerChunk = 4;
        this.grassPerChunk = 15;
        this.mushroomsPerChunk = 1;
        this.waterColor = 0x1E97F2;
    }

    @Override
    public Category getCategory() { return Category.JUNGLE; }

    @Override
    public int getRandomTreeFeature(Random rand) {
        // Beta rainforest: mostly big trees occasionally
        return rand.nextInt(5) == 0 ? 4 : 0; // Mostly oak, sometimes big oak
    }
}
