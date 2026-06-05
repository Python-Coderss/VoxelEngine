package com.voxel.biome;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for all biomes in the game.
 * Ported from Minecraft 1.12.2 - contains 60+ biomes including mutated variants.
 */
public class BiomeRegistry {

    private static final Map<Integer, Biome> biomeById = new HashMap<>();
    private static final Map<String, Biome> biomeByName = new HashMap<>();
    private static boolean initialized = false;

    public static final int BIOME_VOID = 127;

    // Biome IDs for reference
    public static final int OCEAN = 0;
    public static final int PLAINS = 1;
    public static final int DESERT = 2;
    public static final int EXTREME_HILLS = 3;
    public static final int FOREST = 4;
    public static final int TAIGA = 5;
    public static final int SWAMPLAND = 6;
    public static final int RIVER = 7;
    public static final int HELL = 8;
    public static final int SKY = 9;
    public static final int FROZEN_OCEAN = 10;
    public static final int FROZEN_RIVER = 11;
    public static final int ICE_FLATS = 12;
    public static final int ICE_MOUNTAINS = 13;
    public static final int MUSHROOM_ISLAND = 14;
    public static final int MUSHROOM_ISLAND_SHORE = 15;
    public static final int BEACHES = 16;
    public static final int DESERT_HILLS = 17;
    public static final int FOREST_HILLS = 18;
    public static final int TAIGA_HILLS = 19;
    public static final int SMALLER_EXTREME_HILLS = 20;
    public static final int JUNGLE = 21;
    public static final int JUNGLE_HILLS = 22;
    public static final int JUNGLE_EDGE = 23;
    public static final int DEEP_OCEAN = 24;
    public static final int STONE_BEACH = 25;
    public static final int COLD_BEACH = 26;
    public static final int BIRCH_FOREST = 27;
    public static final int BIRCH_FOREST_HILLS = 28;
    public static final int ROOFED_FOREST = 29;
    public static final int TAIGA_COLD = 30;
    public static final int TAIGA_COLD_HILLS = 31;
    public static final int REDWOOD_TAIGA = 32;
    public static final int REDWOOD_TAIGA_HILLS = 33;
    public static final int EXTREME_HILLS_WITH_TREES = 34;
    public static final int SAVANNA = 35;
    public static final int SAVANNA_ROCK = 36;
    public static final int MESA = 37;
    public static final int MESA_ROCK = 38;
    public static final int MESA_CLEAR_ROCK = 39;

    // Mutated biome IDs
    public static final int MUTATED_PLAINS = 129;
    public static final int MUTATED_DESERT = 130;
    public static final int MUTATED_EXTREME_HILLS = 131;
    public static final int MUTATED_FOREST = 132;
    public static final int MUTATED_TAIGA = 133;
    public static final int MUTATED_SWAMPLAND = 134;
    public static final int MUTATED_ICE_FLATS = 140;
    public static final int MUTATED_JUNGLE = 149;
    public static final int MUTATED_JUNGLE_EDGE = 151;
    public static final int MUTATED_BIRCH_FOREST = 155;
    public static final int MUTATED_BIRCH_FOREST_HILLS = 156;
    public static final int MUTATED_ROOFED_FOREST = 157;
    public static final int MUTATED_TAIGA_COLD = 158;
    public static final int MUTATED_REDWOOD_TAIGA = 160;
    public static final int MUTATED_REDWOOD_TAIGA_HILLS = 161;
    public static final int MUTATED_EXTREME_HILLS_WITH_TREES = 162;
    public static final int MUTATED_SAVANNA = 163;
    public static final int MUTATED_SAVANNA_ROCK = 164;
    public static final int MUTATED_MESA = 165;
    public static final int MUTATED_MESA_ROCK = 166;
    public static final int MUTATED_MESA_CLEAR_ROCK = 167;

    public static void init() {
        if (initialized) return;
        initialized = true;

        // --- Standard Biomes ---
        register(OCEAN, new Biome("Ocean", new BiomeProperties("Ocean")
            .setBaseHeight(-1.0f).setHeightVariation(0.1f)));
        register(PLAINS, new BiomePlains("Plains", new BiomeProperties("Plains")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(0.8f).setRainfall(0.4f), false));
        register(DESERT, new BiomeDesert("Desert", new BiomeProperties("Desert")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled()));
        register(EXTREME_HILLS, new BiomeHills("Extreme Hills", new BiomeProperties("Extreme Hills")
            .setBaseHeight(1.0f).setHeightVariation(0.5f)
            .setTemperature(0.2f).setRainfall(0.3f)));
        register(FOREST, new BiomeForest("Forest", new BiomeProperties("Forest")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.7f).setRainfall(0.8f), BiomeForest.Type.NORMAL));
        register(TAIGA, new BiomeTaiga("Taiga", new BiomeProperties("Taiga")
            .setBaseHeight(0.2f).setHeightVariation(0.2f)
            .setTemperature(0.25f).setRainfall(0.8f), BiomeTaiga.Type.NORMAL));
        register(SWAMPLAND, new BiomeSwamp("Swampland", new BiomeProperties("Swampland")
            .setBaseHeight(-0.2f).setHeightVariation(0.1f)
            .setTemperature(0.8f).setRainfall(0.9f)));
        register(RIVER, new BiomeRiver("River", new BiomeProperties("River")
            .setBaseHeight(-0.5f).setHeightVariation(0.0f)));
        register(HELL, new BiomeHell("Hell", new BiomeProperties("Hell")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled()));
        register(SKY, new BiomeEnd("Sky", new BiomeProperties("Sky")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.5f).setRainfall(0.5f).setRainDisabled()));
        register(FROZEN_OCEAN, new BiomeOcean("Frozen Ocean", new BiomeProperties("FrozenOcean")
            .setBaseHeight(-1.0f).setHeightVariation(0.1f)
            .setTemperature(0.0f).setRainfall(0.5f).setSnowEnabled()));
        register(FROZEN_RIVER, new BiomeRiver("Frozen River", new BiomeProperties("FrozenRiver")
            .setBaseHeight(-0.5f).setHeightVariation(0.0f)
            .setTemperature(0.0f).setRainfall(0.5f).setSnowEnabled()));
        register(ICE_FLATS, new BiomeSnow("Ice Plains", new BiomeProperties("Ice Plains")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(0.0f).setRainfall(0.5f).setSnowEnabled(), false));
        register(ICE_MOUNTAINS, new BiomeSnow("Ice Mountains", new BiomeProperties("Ice Mountains")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.0f).setRainfall(0.5f).setSnowEnabled(), false));
        register(MUSHROOM_ISLAND, new BiomeMushroomIsland("Mushroom Island", new BiomeProperties("Mushroom Island")
            .setBaseHeight(0.2f).setHeightVariation(0.3f)
            .setTemperature(0.9f).setRainfall(1.0f)));
        register(MUSHROOM_ISLAND_SHORE, new BiomeMushroomIsland("Mushroom Island Shore", new BiomeProperties("Mushroom Island Shore")
            .setBaseHeight(0.0f).setHeightVariation(0.025f)
            .setTemperature(0.9f).setRainfall(1.0f)));
        register(BEACHES, new BiomeBeach("Beach", new BiomeProperties("Beach")
            .setBaseHeight(0.0f).setHeightVariation(0.025f)
            .setTemperature(0.8f).setRainfall(0.4f)));
        register(DESERT_HILLS, new BiomeDesert("Desert Hills", new BiomeProperties("Desert Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled()));
        register(FOREST_HILLS, new BiomeForest("Forest Hills", new BiomeProperties("Forest Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.7f).setRainfall(0.8f), BiomeForest.Type.NORMAL));
        register(TAIGA_HILLS, new BiomeTaiga("Taiga Hills", new BiomeProperties("Taiga Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.25f).setRainfall(0.8f), BiomeTaiga.Type.NORMAL));
        register(SMALLER_EXTREME_HILLS, new BiomeHills("Extreme Hills Edge", new BiomeProperties("Extreme Hills Edge")
            .setBaseHeight(0.8f).setHeightVariation(0.3f)
            .setTemperature(0.2f).setRainfall(0.3f)));
        register(JUNGLE, new BiomeJungle("Jungle", new BiomeProperties("Jungle")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.95f).setRainfall(0.9f), false));
        register(JUNGLE_HILLS, new BiomeJungle("Jungle Hills", new BiomeProperties("Jungle Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.95f).setRainfall(0.9f), false));
        register(JUNGLE_EDGE, new BiomeJungle("Jungle Edge", new BiomeProperties("Jungle Edge")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.95f).setRainfall(0.9f), true));
        register(DEEP_OCEAN, new BiomeOcean("Deep Ocean", new BiomeProperties("Deep Ocean")
            .setBaseHeight(-1.8f).setHeightVariation(0.1f)));
        register(STONE_BEACH, new BiomeStoneBeach("Stone Beach", new BiomeProperties("Stone Beach")
            .setBaseHeight(0.1f).setHeightVariation(0.8f)
            .setTemperature(0.2f).setRainfall(0.3f)));
        register(COLD_BEACH, new BiomeBeach("Cold Beach", new BiomeProperties("Cold Beach")
            .setBaseHeight(0.0f).setHeightVariation(0.025f)
            .setTemperature(0.05f).setRainfall(0.3f).setSnowEnabled()));
        register(BIRCH_FOREST, new BiomeForest("Birch Forest", new BiomeProperties("Birch Forest")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.6f).setRainfall(0.6f), BiomeForest.Type.BIRCH));
        register(BIRCH_FOREST_HILLS, new BiomeForest("Birch Forest Hills", new BiomeProperties("Birch Forest Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.6f).setRainfall(0.6f), BiomeForest.Type.BIRCH));
        register(ROOFED_FOREST, new BiomeForest("Roofed Forest", new BiomeProperties("Roofed Forest")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.7f).setRainfall(0.8f), BiomeForest.Type.ROOFED));
        register(TAIGA_COLD, new BiomeTaiga("Cold Taiga", new BiomeProperties("Cold Taiga")
            .setBaseHeight(0.2f).setHeightVariation(0.2f)
            .setTemperature(-0.5f).setRainfall(0.4f).setSnowEnabled(), BiomeTaiga.Type.NORMAL));
        register(TAIGA_COLD_HILLS, new BiomeTaiga("Cold Taiga Hills", new BiomeProperties("Cold Taiga Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(-0.5f).setRainfall(0.4f).setSnowEnabled(), BiomeTaiga.Type.NORMAL));
        register(REDWOOD_TAIGA, new BiomeTaiga("Mega Taiga", new BiomeProperties("Mega Taiga")
            .setBaseHeight(0.2f).setHeightVariation(0.2f)
            .setTemperature(0.3f).setRainfall(0.8f), BiomeTaiga.Type.MEGA));
        register(REDWOOD_TAIGA_HILLS, new BiomeTaiga("Mega Taiga Hills", new BiomeProperties("Mega Taiga Hills")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(0.3f).setRainfall(0.8f), BiomeTaiga.Type.MEGA));
        register(EXTREME_HILLS_WITH_TREES, new BiomeHills("Extreme Hills+", new BiomeProperties("Extreme Hills+")
            .setBaseHeight(1.0f).setHeightVariation(0.5f)
            .setTemperature(0.2f).setRainfall(0.3f)));
        register(SAVANNA, new BiomeSavanna("Savanna", new BiomeProperties("Savanna")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(1.2f).setRainfall(0.0f).setRainDisabled()));
        register(SAVANNA_ROCK, new BiomeSavanna("Savanna Plateau", new BiomeProperties("Savanna Plateau")
            .setBaseHeight(1.0f).setHeightVariation(0.03f)
            .setTemperature(1.0f).setRainfall(0.0f).setRainDisabled()));
        register(MESA, new BiomeMesa("Mesa", new BiomeProperties("Mesa")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled(), false, false));
        register(MESA_ROCK, new BiomeMesa("Mesa Plateau F", new BiomeProperties("Mesa Plateau F")
            .setBaseHeight(1.5f).setHeightVariation(0.025f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled(), false, true));
        register(MESA_CLEAR_ROCK, new BiomeMesa("Mesa Plateau", new BiomeProperties("Mesa Plateau")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled(), true, false));

        // --- Void ---
        register(BIOME_VOID, new BiomeVoid("The Void", new BiomeProperties("The Void")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.5f).setRainfall(0.5f).setRainDisabled()));

        // --- Mutated Biomes (M variants from 1.12.2) ---
        register(MUTATED_PLAINS, new BiomePlains("Sunflower Plains", new BiomeProperties("Sunflower Plains")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(0.8f).setRainfall(0.4f).setBaseBiome("plains"), true));
        register(MUTATED_DESERT, new BiomeDesert("Desert M", new BiomeProperties("Desert M")
            .setBaseHeight(0.225f).setHeightVariation(0.25f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled().setBaseBiome("desert")));
        register(MUTATED_EXTREME_HILLS, new BiomeHills("Extreme Hills M", new BiomeProperties("Extreme Hills M")
            .setBaseHeight(1.0f).setHeightVariation(0.5f)
            .setTemperature(0.2f).setRainfall(0.3f).setBaseBiome("extreme_hills")));
        register(MUTATED_FOREST, new BiomeForest("Flower Forest", new BiomeProperties("Flower Forest")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(0.7f).setRainfall(0.8f).setBaseBiome("forest"), BiomeForest.Type.FLOWER));
        register(MUTATED_TAIGA, new BiomeTaiga("Taiga M", new BiomeProperties("Taiga M")
            .setBaseHeight(0.3f).setHeightVariation(0.4f)
            .setTemperature(0.25f).setRainfall(0.8f).setBaseBiome("taiga"), BiomeTaiga.Type.NORMAL));
        register(MUTATED_SWAMPLAND, new BiomeSwamp("Swampland M", new BiomeProperties("Swampland M")
            .setBaseHeight(-0.1f).setHeightVariation(0.3f)
            .setTemperature(0.8f).setRainfall(0.9f).setBaseBiome("swampland")));
        register(MUTATED_ICE_FLATS, new BiomeSnow("Ice Plains Spikes", new BiomeProperties("Ice Plains Spikes")
            .setBaseHeight(0.125f).setHeightVariation(0.05f)
            .setTemperature(0.0f).setRainfall(0.5f).setSnowEnabled().setBaseBiome("ice_flats"), true));
        register(MUTATED_JUNGLE, new BiomeJungle("Jungle M", new BiomeProperties("Jungle M")
            .setBaseHeight(0.2f).setHeightVariation(0.4f)
            .setTemperature(0.95f).setRainfall(0.9f).setBaseBiome("jungle"), false));
        register(MUTATED_JUNGLE_EDGE, new BiomeJungle("Jungle Edge M", new BiomeProperties("Jungle Edge M")
            .setBaseHeight(0.2f).setHeightVariation(0.4f)
            .setTemperature(0.95f).setRainfall(0.9f).setBaseBiome("jungle_edge"), true));
        register(MUTATED_BIRCH_FOREST, new BiomeForest("Birch Forest M", new BiomeProperties("Birch Forest M")
            .setBaseHeight(0.2f).setHeightVariation(0.4f)
            .setTemperature(0.6f).setRainfall(0.6f).setBaseBiome("birch_forest"), BiomeForest.Type.BIRCH));
        register(MUTATED_BIRCH_FOREST_HILLS, new BiomeForest("Birch Forest Hills M", new BiomeProperties("Birch Forest Hills M")
            .setBaseHeight(0.55f).setHeightVariation(0.5f)
            .setTemperature(0.6f).setRainfall(0.6f).setBaseBiome("birch_forest_hills"), BiomeForest.Type.BIRCH));
        register(MUTATED_ROOFED_FOREST, new BiomeForest("Roofed Forest M", new BiomeProperties("Roofed Forest M")
            .setBaseHeight(0.2f).setHeightVariation(0.4f)
            .setTemperature(0.7f).setRainfall(0.8f).setBaseBiome("roofed_forest"), BiomeForest.Type.ROOFED));
        register(MUTATED_TAIGA_COLD, new BiomeTaiga("Cold Taiga M", new BiomeProperties("Cold Taiga M")
            .setBaseHeight(0.3f).setHeightVariation(0.4f)
            .setTemperature(-0.5f).setRainfall(0.4f).setSnowEnabled().setBaseBiome("taiga_cold"), BiomeTaiga.Type.NORMAL));
        register(MUTATED_REDWOOD_TAIGA, new BiomeTaiga("Mega Taiga M", new BiomeProperties("Mega Taiga M")
            .setBaseHeight(0.3f).setHeightVariation(0.4f)
            .setTemperature(0.3f).setRainfall(0.8f).setBaseBiome("redwood_taiga"), BiomeTaiga.Type.MEGA));
        register(MUTATED_REDWOOD_TAIGA_HILLS, new BiomeTaiga("Mega Taiga Hills M", new BiomeProperties("Mega Taiga Hills M")
            .setBaseHeight(0.55f).setHeightVariation(0.5f)
            .setTemperature(0.3f).setRainfall(0.8f).setBaseBiome("redwood_taiga_hills"), BiomeTaiga.Type.MEGA));
        register(MUTATED_EXTREME_HILLS_WITH_TREES, new BiomeHills("Extreme Hills+ M", new BiomeProperties("Extreme Hills+ M")
            .setBaseHeight(1.0f).setHeightVariation(0.5f)
            .setTemperature(0.2f).setRainfall(0.3f).setBaseBiome("extreme_hills_with_trees")));
        register(MUTATED_SAVANNA, new BiomeSavanna("Savanna M", new BiomeProperties("Savanna M")
            .setBaseHeight(0.3625f).setHeightVariation(1.225f)
            .setTemperature(1.1f).setRainfall(0.0f).setRainDisabled().setBaseBiome("savanna")));
        register(MUTATED_SAVANNA_ROCK, new BiomeSavanna("Savanna Plateau M", new BiomeProperties("Savanna Plateau M")
            .setBaseHeight(1.05f).setHeightVariation(1.21f)
            .setTemperature(1.0f).setRainfall(0.0f).setRainDisabled().setBaseBiome("savanna_rock")));
        register(MUTATED_MESA, new BiomeMesa("Mesa (Bryce)", new BiomeProperties("Mesa (Bryce)")
            .setBaseHeight(0.1f).setHeightVariation(0.2f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled().setBaseBiome("mesa"), true, false));
        register(MUTATED_MESA_ROCK, new BiomeMesa("Mesa Plateau F M", new BiomeProperties("Mesa Plateau F M")
            .setBaseHeight(0.45f).setHeightVariation(0.3f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled().setBaseBiome("mesa_rock"), false, true));
        register(MUTATED_MESA_CLEAR_ROCK, new BiomeMesa("Mesa Plateau M", new BiomeProperties("Mesa Plateau M")
            .setBaseHeight(0.525f).setHeightVariation(0.45f)
            .setTemperature(2.0f).setRainfall(0.0f).setRainDisabled().setBaseBiome("mesa_clear_rock"), true, false));
    }

    private static void register(int id, Biome biome) {
        biomeById.put(id, biome);
        biomeByName.put(biome.name.toLowerCase(), biome);
    }

    public static Biome getBiome(int id) {
        if (!initialized) init();
        return biomeById.get(id);
    }

    public static Biome getBiome(String name) {
        if (!initialized) init();
        return biomeByName.get(name.toLowerCase());
    }

    public static int getId(Biome biome) {
        if (!initialized) init();
        for (Map.Entry<Integer, Biome> entry : biomeById.entrySet()) {
            if (entry.getValue() == biome) return entry.getKey();
        }
        return 0;
    }

    public static Biome getBiomeForTemperature(double temp, double humidity, double height) {
        if (!initialized) init();
        // Simplified biome selection based on temperature and humidity
        if (height < -0.5) return biomeById.get(OCEAN);
        if (temp < 0.1) return biomeById.get(ICE_FLATS);
        if (temp < 0.3) return biomeById.get(TAIGA);
        if (temp < 0.5) return biomeById.get(FOREST);
        if (humidity < 0.2) return biomeById.get(SAVANNA);
        if (humidity < 0.4) return biomeById.get(PLAINS);
        if (humidity < 0.7) return biomeById.get(FOREST);
        if (humidity >= 0.9 && temp > 0.7) return biomeById.get(JUNGLE);
        return biomeById.get(FOREST);
    }

    public static int getBiomeCount() {
        return biomeById.size();
    }

    public static Map<Integer, Biome> getBiomeMap() {
        return biomeById;
    }
}
