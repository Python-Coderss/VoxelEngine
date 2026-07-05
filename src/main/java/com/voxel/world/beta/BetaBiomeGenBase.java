package com.voxel.world.beta;

import java.util.Random;

/**
 * Simplified port of Beta 1.7.3's BiomeGenBase.
 * Contains just the biome types and the temperature/humidity → biome lookup table.
 * Preserves the exact bug-for-bug biome distribution of Beta 1.7.3.
 */
public class BetaBiomeGenBase {
    public static final int RAINFOREST = 0;
    public static final int SWAMPLAND = 1;
    public static final int SEASONAL_FOREST = 2;
    public static final int FOREST = 3;
    public static final int SAVANNA = 4;
    public static final int SHRUBLAND = 5;
    public static final int TAIGA = 6;
    public static final int DESERT = 7;
    public static final int PLAINS = 8;
    public static final int ICE_DESERT = 9;
    public static final int TUNDRA = 10;
    public static final int HELL = 11;
    public static final int SKY = 12;

    public static final String[] NAMES = {
        "Rainforest", "Swampland", "Seasonal Forest", "Forest",
        "Savanna", "Shrubland", "Taiga", "Desert",
        "Plains", "Ice Desert", "Tundra", "Hell", "Sky"
    };

    /** Top block for each biome (Beta 1.7.3 block IDs) */
    public static final int[] TOP_BLOCKS = {
        2, // Rainforest: grass
        2, // Swampland: grass
        2, // Seasonal Forest: grass
        2, // Forest: grass
        2, // Savanna: grass (actually desert subclass but uses grass)
        2, // Shrubland: grass
        2, // Taiga: grass
        12, // Desert: sand
        2, // Plains: grass
        12, // Ice Desert: sand
        2, // Tundra: grass
        87, // Hell: netherrack
        1, // Sky: stone (end stone equivalent)
    };

    /** Filler block for each biome (Beta 1.7.3 block IDs) */
    public static final int[] FILLER_BLOCKS = {
        3,  // Rainforest: dirt
        3,  // Swampland: dirt
        3,  // Seasonal Forest: dirt
        3,  // Forest: dirt
        3,  // Savanna: dirt
        3,  // Shrubland: dirt
        3,  // Taiga: dirt
        12, // Desert: sand
        3,  // Plains: dirt
        12, // Ice Desert: sand
        3,  // Tundra: dirt
        87, // Hell: netherrack
        1,  // Sky: stone
    };

    public static final boolean[] ENABLE_SNOW = {
        false, false, false, false, false, false,
        true,  // Taiga
        false, false,
        true,  // Ice Desert
        true,  // Tundra
        false, false
    };

    public static final boolean[] ENABLE_RAIN = {
        true, true, true, true, true, true,
        true,
        false, // Desert
        true,
        false, // Ice Desert
        true,
        false, // Hell
        false, // Sky
    };

    // 64x64 biome lookup table (Beta 1.7.3 exact)
    private static int[] biomeLookupTable = new int[4096];

    static {
        generateBiomeLookup();
    }

    /**
     * Generates the exact Beta 1.7.3 biome lookup table.
     * Uses the same bug-for-bug biome decision tree.
     */
    private static void generateBiomeLookup() {
        for (int var0 = 0; var0 < 64; ++var0) {
            for (int var1 = 0; var1 < 64; ++var1) {
                biomeLookupTable[var0 + var1 * 64] = getBiome((float) var0 / 63.0F, (float) var1 / 63.0F);
            }
        }
    }

    /**
     * Exact port of Beta 1.7.3's getBiomeFromLookup.
     */
    public static int getBiomeFromLookup(double var0, double var2) {
        int var4 = (int) (var0 * 63.0D);
        int var5 = (int) (var2 * 63.0D);
        return biomeLookupTable[var4 + var5 * 64];
    }

    /**
     * Exact port of Beta 1.7.3's getBiome.
     * Uses the temperature/humidity decision tree with ALL original thresholds.
     */
    public static int getBiome(float temp, float humidity) {
        humidity *= temp;
        return temp < 0.1F ? TUNDRA :
               (humidity < 0.2F ?
                   (temp < 0.5F ? TUNDRA :
                    (temp < 0.95F ? SAVANNA : DESERT)) :
               (humidity > 0.5F && temp < 0.7F ? SWAMPLAND :
                   (temp < 0.5F ? TAIGA :
                    (temp < 0.97F ?
                        (humidity < 0.35F ? SHRUBLAND : FOREST) :
                        (humidity < 0.45F ? PLAINS :
                         (humidity < 0.9F ? SEASONAL_FOREST : RAINFOREST))))));
    }

    /**
     * Get a tree generator based on biome. Port of getRandomWorldGenForTrees.
     * In Beta 1.7.3, forest/taiga/rainforest use WorldGenBigTree occasionally.
     * Returns: 0=small tree, 1=big tree (for now - simplified)
     */
    public static int getRandomTreeType(int biome, Random rand) {
        if (biome == RAINFOREST || biome == SWAMPLAND) {
            return rand.nextInt(3) == 0 ? 1 : 0;
        }
        return rand.nextInt(10) == 0 ? 1 : 0;
    }
}
