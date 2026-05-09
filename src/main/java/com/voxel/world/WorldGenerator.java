package com.voxel.world;

import com.voxel.utils.PerlinNoise;

/**
 * Procedural world generator using layered noise.
 */
public class WorldGenerator {
    private final PerlinNoise continentalness;
    private final PerlinNoise erosion;
    private final PerlinNoise peaksValleys;
    private final PerlinNoise detail;

    private final PerlinNoise tempNoise;
    private final PerlinNoise humNoise;

    public WorldGenerator(long seed) {
        this.continentalness = new PerlinNoise(seed);
        this.erosion = new PerlinNoise(seed + 1);
        this.peaksValleys = new PerlinNoise(seed + 2);
        this.detail = new PerlinNoise(seed + 3);
        
        // Consistent with BiomeManager's seeds for visual alignment
        this.tempNoise = new PerlinNoise(12345);
        this.humNoise = new PerlinNoise(67890);
    }

    /**
     * Gets the terrain height at the given world coordinates.
     */
    public int getHeight(int x, int z) {
        // ... (rest of height logic remains the same)
        float c = (continentalness.noise(x, z, 0.0005f) + 1.0f) * 0.5f;
        float e = (erosion.noise(x, z, 0.002f) + 1.0f) * 0.5f;
        float pv = (peaksValleys.noise(x, z, 0.01f) + 1.0f) * 0.5f;
        float d = (detail.noise(x, z, 0.05f) + 1.0f) * 0.5f;

        float baseHeight = 64; 
        float terrain = 0;
        if (c < 0.4f) {
            terrain = baseHeight - (0.4f - c) * 64;
        } else {
            float landFactor = (c - 0.4f) / 0.6f;
            terrain = baseHeight + landFactor * 64;
            float mountainFactor = Math.max(0, (e - 0.3f) / 0.7f);
            terrain += mountainFactor * pv * 64;
        }
        terrain += d * 4;

        return (int) Math.max(1, Math.min(255, terrain));
    }

    /**
     * Gets the biome-specific block type at a given position.
     */
    public int getBlockType(int x, int y, int z, int height) {
        if (y > height) {
            return y <= 62 ? 5 : 0; // Water if below sea level, else Air
        }
        
        // Sample Biome Data
        float t = (tempNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;
        float h = (humNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;

        if (y == height) {
            if (y <= 63) return 14; // Sand near/below water (ID 14 is Sand)
            
            // Desert: High temp, Low humidity
            if (t > 0.7f && h < 0.3f) return 14; // Sand
            
            // Tundra/Snow: Low temp
            if (t < 0.3f) return 20; // White block (using as Snow placeholder)
            
            return 1; // Grass
        }
        
        if (y > height - 4) {
            // Desert: Dirt becomes Sand
            if (t > 0.7f && h < 0.3f) return 14; 
            return 13; // Dirt (ID 13)
        }
        
        return 2; // Stone
    }
}
