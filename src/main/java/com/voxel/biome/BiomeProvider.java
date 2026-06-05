package com.voxel.biome;

import com.voxel.utils.PerlinNoise;

/**
 * Provides biome distribution using Minecraft-style noise-based climate system.
 * Maps (x,z) coordinates to biomes based on temperature, humidity, and terrain height.
 */
public class BiomeProvider {

    private final PerlinNoise tempNoise;
    private final PerlinNoise humidityNoise;
    private final PerlinNoise continentalNoise;
    private final long seed;

    public BiomeProvider(long seed) {
        this.seed = seed;
        this.tempNoise = new PerlinNoise(seed + 1000);
        this.humidityNoise = new PerlinNoise(seed + 2000);
        this.continentalNoise = new PerlinNoise(seed + 3000);
    }

    /**
     * Gets the biome at the given world coordinates.
     * Uses noise-based temperature, humidity, and continentalness to select biomes
     * in a Minecraft-style distribution.
     */
    public Biome getBiome(int x, int z) {
        float temp = getTemperature(x, z);
        float humidity = getHumidity(x, z);
        float continental = getContinentalness(x, z);

        return selectBiome(temp, humidity, continental, x, z);
    }

    /**
     * Gets temperature value at (x, z).
     * Ranges from approximately 0.0 (cold) to 1.0 (hot).
     * Uses latitude-like scaling plus noise variation.
     */
    public float getTemperature(int x, int z) {
        // Base temperature varies with latitude (distance from equator at z=0)
        float latScale = (float) Math.sin(z * 0.0015f) * 0.5f + 0.5f;
        float noiseVariation = (tempNoise.noise(x, z, 0.003f) + 1.0f) * 0.5f;
        return Math.max(0.0f, Math.min(1.0f, latScale * 0.7f + noiseVariation * 0.3f));
    }

    /**
     * Gets humidity value at (x, z).
     * Ranges from approximately 0.0 (dry) to 1.0 (wet).
     */
    public float getHumidity(int x, int z) {
        float noise = (humidityNoise.noise(x, z, 0.003f) + 1.0f) * 0.5f;
        return Math.max(0.0f, Math.min(1.0f, noise));
    }

    /**
     * Gets continentalness value at (x, z).
     * Higher values = more continental (land); lower = more oceanic.
     */
    public float getContinentalness(int x, int z) {
        float noise = continentalNoise.noise(x, z, 0.0015f);
        return noise;
    }

    /**
     * Gets the terrain base height at (x, z) based on biome and continentalness.
     */
    public float getTerrainHeight(int x, int z) {
        Biome biome = getBiome(x, z);
        float continental = getContinentalness(x, z);
        float noise = continentalNoise.noise(x, z, 0.005f) * 0.5f;

        float baseHeight = biome.getBaseHeight();
        float variation = biome.getHeightVariation();

        float height = baseHeight + noise * variation;
        return height;
    }

    /**
     * Selects a biome based on temperature, humidity, and continentalness.
     * Mirrors Minecraft 1.12.2 biome distribution logic.
     */
    private Biome selectBiome(float temp, float humidity, float continental, int x, int z) {
        // Ocean/land decision based on continentalness
        if (continental < -0.3f) {
            // Ocean biome
            if (continental < -0.6f) return BiomeRegistry.getBiome(BiomeRegistry.DEEP_OCEAN);
            return BiomeRegistry.getBiome(BiomeRegistry.OCEAN);
        }

        if (continental < -0.15f) {
            // Beach/shore
            if (temp > 0.5f) return BiomeRegistry.getBiome(BiomeRegistry.BEACHES);
            return BiomeRegistry.getBiome(BiomeRegistry.STONE_BEACH);
        }

        // Land biomes - temperature and humidity decide
        double scaledTemp = temp * 4.0;
        double scaledHumidity = humidity * 4.0;

        if (scaledTemp < 0.5) {
            // Icy/cold
            if (scaledHumidity < 1.0) return BiomeRegistry.getBiome(BiomeRegistry.ICE_FLATS);
            if (scaledHumidity < 2.0) return BiomeRegistry.getBiome(BiomeRegistry.TAIGA_COLD);
            if (scaledHumidity < 3.0) {
                if (temp < 0.1f) return BiomeRegistry.getBiome(BiomeRegistry.ICE_MOUNTAINS);
                return BiomeRegistry.getBiome(BiomeRegistry.TAIGA);
            }
            return BiomeRegistry.getBiome(BiomeRegistry.REDWOOD_TAIGA);
        }

        if (scaledTemp < 1.5) {
            // Cold/cool
            if (scaledHumidity < 1.0) return BiomeRegistry.getBiome(BiomeRegistry.TAIGA);
            if (scaledHumidity < 2.0) {
                if (temp < 0.3f) return BiomeRegistry.getBiome(BiomeRegistry.TAIGA);
                return BiomeRegistry.getBiome(BiomeRegistry.EXTREME_HILLS);
            }
            if (scaledHumidity < 3.0) return BiomeRegistry.getBiome(BiomeRegistry.FOREST);
            return BiomeRegistry.getBiome(BiomeRegistry.TAIGA);
        }

        if (scaledTemp < 2.5) {
            // Warm/temperate
            if (scaledHumidity < 1.0) return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            if (scaledHumidity < 2.0) {
                if (humidity > 0.5f && temp > 0.7f) return BiomeRegistry.getBiome(BiomeRegistry.FOREST);
                return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            }
            if (scaledHumidity < 3.0) {
                if (humidity > 0.7f) return BiomeRegistry.getBiome(BiomeRegistry.FOREST);
                return BiomeRegistry.getBiome(BiomeRegistry.BIRCH_FOREST);
            }
            if (temp > 0.8f && humidity > 0.8f) return BiomeRegistry.getBiome(BiomeRegistry.SWAMPLAND);
            return BiomeRegistry.getBiome(BiomeRegistry.FOREST);
        }

        if (scaledTemp < 3.5) {
            // Hot/dry
            if (scaledHumidity < 1.0) return BiomeRegistry.getBiome(BiomeRegistry.DESERT);
            if (scaledHumidity < 1.5) return BiomeRegistry.getBiome(BiomeRegistry.SAVANNA);
            if (scaledHumidity < 2.0) {
                if (humidity > 0.5f) return BiomeRegistry.getBiome(BiomeRegistry.JUNGLE);
                return BiomeRegistry.getBiome(BiomeRegistry.SAVANNA);
            }
            return BiomeRegistry.getBiome(BiomeRegistry.JUNGLE);
        }

        // Hottest
        if (scaledHumidity < 1.0) return BiomeRegistry.getBiome(BiomeRegistry.DESERT);
        if (scaledHumidity < 2.0) {
            if (temp > 0.9f) return BiomeRegistry.getBiome(BiomeRegistry.MESA);
            return BiomeRegistry.getBiome(BiomeRegistry.SAVANNA);
        }
        return BiomeRegistry.getBiome(BiomeRegistry.JUNGLE);
    }

    /**
     * Gets the biome ID at the given position.
     */
    public int getBiomeId(int x, int z) {
        Biome biome = getBiome(x, z);
        return BiomeRegistry.getId(biome);
    }
}
