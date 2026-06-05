package com.voxel.biome;

import java.util.Random;

public class BiomeJungle extends Biome {
    private final boolean isEdge;

    public BiomeJungle(String name, BiomeProperties props, boolean isEdge) {
        super(name, props);
        this.isEdge = isEdge;
        if (isEdge) {
            this.treesPerChunk = 2;
        } else {
            this.treesPerChunk = 50;
            this.vinePerChunk = 50;
            this.melonPerChunk = 1;
        }
        this.grassPerChunk = 25;
        this.flowersPerChunk = 4;
    }

    @Override
    public Category getCategory() { return Category.JUNGLE; }

    @Override
    public float getTemperature(int x, int z) {
        return 0.95f;
    }

    @Override
    public float getHumidity(int x, int z) {
    	
        return 0.9f;
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        if (isEdge) return 0; // Oak for edge
        int r = rand.nextInt(10);
        if (r < 1) return 4;  // Big oak
        if (r < 3) return 0;  // Oak
        return 8; // Jungle tree
    }

    @Override
    public int getRandomGrassFeature(Random rand) {
        return rand.nextInt(4) == 0 ? 64 : 35; // Fern or grass
    }
}
