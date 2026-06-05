package com.voxel.biome;

import java.util.Random;

public class BiomeSwamp extends Biome {
    public BiomeSwamp(String name, BiomeProperties props) {
        super(name, props);
        this.treesPerChunk = 2;
        this.flowersPerChunk = 1;
        this.deadBushPerChunk = 1;
        this.mushroomsPerChunk = 8;
        this.reedsPerChunk = 10;
        this.clayPerChunk = 1;
        this.waterlilyPerChunk = 4;
        this.sandPatchesPerChunk = 0;
        this.gravelPatchesPerChunk = 0;
        this.grassPerChunk = 5;
        this.bigMushroomsPerChunk = 1;
        this.fossilChance = 64;
        this.waterColor = 0x617B37; // Swamp water color
    }

    @Override
    public Category getCategory() { return Category.SWAMP; }

    @Override
    public float getTemperature(int x, int z) {
        return Math.max(0.75f, super.getTemperature(x, z));
    }

    @Override
    public float getHumidity(int x, int z) {
        return Math.max(0.85f, properties.rainfall);
    }

    @Override
    public int getRandomTreeFeature(Random rand) {
        return 0; // Oak tree
    }

    @Override
    public int getGrassColor(int x, int z) {
        double noise = GRASS_COLOR_NOISE.noise(x, z, 0.05f);
        return noise < -0.1 ? 0x4C763C : 0x6A7039;
    }

    @Override
    public int getFoliageColor(int x, int z) {
        return 0x6A7039;
    }
}
