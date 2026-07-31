package com.voxel.biome;

import java.util.Random;

/**
 * Beta 1.7.3 Seasonal Forest biome.
 * Temperate forest with mixed tree types and seasonal color variation.
 * Beta 1.7.3 original: biome ID 2.
 */
public class BiomeSeasonalForest extends Biome {
    public BiomeSeasonalForest(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 12;
        this.flowersPerChunk = 3;
        this.grassPerChunk = 8;
        this.waterColor = 0x3F76E4;
    }

    @Override
    public Category getCategory() { return Category.FOREST; }

    @Override
    public int getRandomTreeFeature(Random rand) {
        // Seasonal forest: mix of birch and oak
        return rand.nextInt(3) == 0 ? 6 : 0; // 1/3 birch, 2/3 oak
    }
}
