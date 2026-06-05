package com.voxel.biome;

import java.util.Random;

public class BiomeTaiga extends Biome {
    public enum Type { NORMAL, MEGA }

    private final Type type;

    public BiomeTaiga(String name, BiomeProperties props, Type type) {
        super(name, props);
        this.type = type;
        this.treesPerChunk = 10;

        if (type == Type.MEGA) {
            this.grassPerChunk = 7;
        }
    }

    @Override
    public Category getCategory() { return Category.TAIGA; }

    @Override
    public float getTemperature(int x, int z) {
        return Math.min(0.25f, super.getTemperature(x, z));
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        if (type == Type.MEGA) return 7; // Mega spruce
        return 7; // Spruce
    }

    @Override
    public int getRandomGrassFeature(Random rand) {
        return rand.nextBoolean() ? 35 : 64; // Grass or fern
    }
}
