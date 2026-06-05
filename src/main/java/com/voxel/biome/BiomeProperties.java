package com.voxel.biome;

/**
 * Configuration properties for a biome, ported from Minecraft 1.12.2 BiomeProperties.
 * Uses a fluent builder pattern for easy configuration.
 */
public class BiomeProperties {

    public final String name;
    public float baseHeight = 0.1f;
    public float heightVariation = 0.2f;
    public float temperature = 0.5f;
    public float rainfall = 0.5f;
    public int waterColor = 0x3F76E4;
    public boolean enableSnow = false;
    public boolean disableRain = false;
    public String baseBiome = null;

    public BiomeProperties(String name) {
        this.name = name;
    }

    public BiomeProperties setBaseHeight(float baseHeight) {
        this.baseHeight = baseHeight;
        return this;
    }

    public BiomeProperties setHeightVariation(float heightVariation) {
        this.heightVariation = heightVariation;
        return this;
    }

    public BiomeProperties setTemperature(float temperature) {
        this.temperature = temperature;
        return this;
    }

    public BiomeProperties setRainfall(float rainfall) {
        this.rainfall = rainfall;
        return this;
    }

    public BiomeProperties setWaterColor(int waterColor) {
        this.waterColor = waterColor;
        return this;
    }

    public BiomeProperties setSnowEnabled() {
        this.enableSnow = true;
        return this;
    }

    public BiomeProperties setRainDisabled() {
        this.disableRain = true;
        return this;
    }

    public BiomeProperties setBaseBiome(String baseBiome) {
        this.baseBiome = baseBiome;
        return this;
    }
}
