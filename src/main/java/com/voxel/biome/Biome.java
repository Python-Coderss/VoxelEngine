package com.voxel.biome;

import com.voxel.utils.PerlinNoise;
import java.util.Random;

/**
 * Base class for all biomes, ported from Minecraft 1.12.2.
 * Defines biome properties: temperature, rainfall, height, colors, and decoration rules.
 */
public class Biome {

    public static final PerlinNoise TEMP_NOISE = new PerlinNoise(12345);
    public static final PerlinNoise HUM_NOISE = new PerlinNoise(67890);
    public static final PerlinNoise GRASS_COLOR_NOISE = new PerlinNoise(8765);
    public static final PerlinNoise FOLIAGE_COLOR_NOISE = new PerlinNoise(4321);

    public final String name;
    public final BiomeProperties properties;

    // Terrain generation blocks
    public int topBlockId = 1;   // grass_block
    public int fillerBlockId = 13; // dirt
    public int stoneBlockId = 2; // stone

    // Decorator per-chunk counts (set by each biome subclass)
    public int treesPerChunk = 0;
    public int flowersPerChunk = 2;
    public int grassPerChunk = 1;
    public int deadBushPerChunk = 0;
    public int mushroomsPerChunk = 0;
    public int reedsPerChunk = 0;
    public int cactiPerChunk = 0;
    public int waterlilyPerChunk = 0;
    public int sandPatchesPerChunk = 3;
    public int gravelPatchesPerChunk = 1;
    public int clayPerChunk = 1;
    public int bigMushroomsPerChunk = 0;
    public int melonPerChunk = 0;
    public int pumpkinPerChunk = 0;
    public int vinePerChunk = 0;
    public int desertWellChance = 0;  // 1-in-X chance
    public int fossilChance = 0;
    public boolean enableSnow = false;
    public boolean enableRain = true;
    public int waterColor = 0x3F76E4; // Default blue

    public Biome(String name, BiomeProperties properties) {
        this.name = name;
        this.properties = properties;
        applyProperties();
        addDefaultFlowers();
    }

    private void applyProperties() {
        if (properties == null) return;
        if (properties.enableSnow) this.enableSnow = true;
        if (properties.disableRain) this.enableRain = false;
        if (properties.waterColor != 0) this.waterColor = properties.waterColor;
    }

    protected void addDefaultFlowers() {
        // Dandelion (weight 20) and Poppy (weight 10)
        addFlower(121, 20); // dandelion block ID
        addFlower(34, 10); // poppy block ID
    }

    /** Flower entries: array of [blockId, weight] pairs */
    public java.util.List<int[]> flowers = new java.util.ArrayList<>();

    public void addFlower(int blockId, int weight) {
        flowers.add(new int[]{blockId, weight});
    }

    /**
     * Picks a random flower from the weighted list.
     */
    public int pickRandomFlower(Random rand) {
        if (flowers.isEmpty()) return 0;
        int totalWeight = 0;
        for (int[] entry : flowers) totalWeight += entry[1];
        int r = rand.nextInt(totalWeight);
        for (int[] entry : flowers) {
            r -= entry[1];
            if (r < 0) return entry[0];
        }
        return flowers.get(flowers.size() - 1)[0];
    }

    /**
     * Returns the grass block color tint for this biome at (x, z).
     */
    public int getGrassColor(int x, int z) {
        float t = getTemperature(x, z);
        float h = getHumidity(x, z);
        return getGrassColor(t, h);
    }

    /**
     * Returns the foliage block color tint for this biome at (x, z).
     */
    public int getFoliageColor(int x, int z) {
        float t = getTemperature(x, z);
        float h = getHumidity(x, z);
        return getFoliageColor(t, h);
    }

    /**
     * Computes grass color from temperature and humidity, matching vanilla Minecraft.
     */
    public static int getGrassColor(float temp, float humidity) {
        humidity *= temp;
        int r = (int)(84.0 * temp);
        int g = (int)(108.0 * humidity);
        int b = (int)(46.0 * temp);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Computes foliage color from temperature and humidity, matching vanilla Minecraft.
     */
    public static int getFoliageColor(float temp, float humidity) {
        humidity *= temp;
        int r = (int)(68.0 * temp);
        int g = (int)(94.0 * humidity);
        int b = (int)(36.0 * temp);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Gets temperature value for biome coloring at the given position.
     * Vanilla MC 1.12.2 adds small position-based noise for natural variation.
     */
    public float getTemperature(int x, int z) {
        float noise = (float) TEMP_NOISE.noise(x, z, 0.0225f);
        return properties.temperature + noise * 0.05f;
    }

    /**
     * Gets humidity (rainfall) value for biome coloring at the given position.
     * Vanilla MC 1.12.2 does NOT add noise to rainfall — only to temperature.
     */
    public float getHumidity(int x, int z) {
        return properties.rainfall;
    }

    /**
     * Gets the base height of this biome.
     */
    public float getBaseHeight() {
        return properties.baseHeight;
    }

    /**
     * Gets the height variation for this biome.
     */
    public float getHeightVariation() {
        return properties.heightVariation;
    }

    /**
     * Creates a grass generator for this biome.
     */
    public int getGrassBlockId(Random rand) {
        return 35; // tallgrass block ID (default)
    }

    // ---- Category enum (for biome grouping) ----
    public enum Category {
        NONE, TAIGA, EXTREME_HILLS, JUNGLE, MESA, PLAINS, SAVANNA, ICY,
        DESERT, FOREST, MUSHROOM, OCEAN, RIVER, SWAMP, UNDERGROUND
    }

    public Category getCategory() {
        return Category.NONE;
    }

    /**
     * Returns a random tree feature generator for this biome.
     * Override in subclasses.
     */
    public int getRandomTreeFeature(Random rand) {
        return 0; // No tree by default; 0 means "choose default oak"
    }

    /**
     * Returns a random grass generator for this biome.
     */
    public int getRandomGrassFeature(Random rand) {
        return 35; // tallgrass block ID
    }
}
