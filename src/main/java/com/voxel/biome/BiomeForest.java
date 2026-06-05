package com.voxel.biome;

import java.util.Random;

public class BiomeForest extends Biome {
    public enum Type { NORMAL, FLOWER, BIRCH, ROOFED }

    private final Type type;

    public BiomeForest(String name, BiomeProperties props, Type type) {
        super(name, props);
        this.type = type;

        switch (type) {
            case FLOWER:
                this.treesPerChunk = 6;
                this.flowersPerChunk = 100;
                this.grassPerChunk = 1;
                flowers.clear();
                addFlower(34, 10); // poppy
                addFlower(121, 8);  // dandelion
                addFlower(62, 6);  // tulip
                addFlower(63, 4);  // azure bluet
                break;
            case BIRCH:
                this.treesPerChunk = 10;
                this.flowersPerChunk = 2;
                this.grassPerChunk = 2;
                break;
            case ROOFED:
                this.treesPerChunk = -999;
                this.flowersPerChunk = 2;
                this.grassPerChunk = 2;
                this.bigMushroomsPerChunk = 2;
                break;
            default:
                this.treesPerChunk = 10;
                this.flowersPerChunk = 2;
                this.grassPerChunk = 2;
                break;
        }
    }

    public Type getForestType() { return type; }

    @Override
    public Category getCategory() { return Category.FOREST; }

    @Override
    public int getRandomTreeFeature(Random rand) {
        switch (type) {
            case BIRCH: return 6; // Birch
            case ROOFED: return 9; // Dark Oak
            case FLOWER: return rand.nextInt(5) <= 3 ? 0 : 4; // Oak or Big Oak
            default:
                return rand.nextInt(10) < 2 ? 4 : 0; // Mostly oak, sometimes big oak
        }
    }
}
