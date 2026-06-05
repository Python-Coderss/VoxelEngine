package com.voxel.utils;

import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL45.*;

/**
 * Manages biome-related data, including temperature/humidity maps and colormaps.
 * These are used to dynamically tint voxels like grass and leaves based on their location.
 */
public class BiomeManager {
    // OpenGL IDs for the biome map and colormaps.
    private int grassColormapId;
    private int foliageColormapId;
    private int biomeMapId;
    
    // Biome provider that drives temperature and humidity values for the tint map.
    private BiomeProvider biomeProvider;

    // CPU-side biome map data for sliding window support.
    // 2 bytes per pixel (R=temp, G=humidity). Kept in off-heap memory for direct GPU upload.
    private ByteBuffer biomeData;
    private int biomeWorldSize;

    // Lock to synchronize slideBiomeMap() (gen thread) and uploadBiomeMap() (render thread).
    private final Object biomeLock = new Object();
    
    /**
     * Sets the BiomeProvider that drives temperature/humidity for the tint map.
     * Must be called before generateBiomeMap().
     */
    public void setBiomeProvider(BiomeProvider provider) {
        this.biomeProvider = provider;
    }

    public BiomeProvider getBiomeProvider() {
        return biomeProvider;
    }

    /**
     * Generates a 2D map covering the entire world where each pixel stores
     * Temperature (R channel) and Humidity (G channel).
     * Values are derived from the biome at each location via BiomeProvider.
     * @param worldSize The size of the world (e.g., 2048).
     */
    public void generateBiomeMap(int worldSize) {
        this.biomeWorldSize = worldSize;

        // Free old buffer if it exists
        if (biomeData != null) MemoryUtil.memFree(biomeData);

        // Allocate off-heap memory for the map (2 bytes per pixel: Temperature and Humidity).
        biomeData = MemoryUtil.memAlloc(worldSize * worldSize * 2);
        for (int z = 0; z < worldSize; z++) {
            for (int x = 0; x < worldSize; x++) {
                float[] th = getBiomeTempHumidity(x, z);
                biomeData.put((byte) (Math.max(0, Math.min(1, th[0])) * 255));
                biomeData.put((byte) (Math.max(0, Math.min(1, th[1])) * 255));
            }
        }
        biomeData.flip();

        // 1. Create a 2D texture on the GPU.
        if (biomeMapId != 0) glDeleteTextures(biomeMapId);
        biomeMapId = glCreateTextures(GL_TEXTURE_2D);

        // 2. Allocate storage for the map (RG format, 8 bits per channel).
        glTextureStorage2D(biomeMapId, 1, GL_RG8, worldSize, worldSize);

        // 3. Upload the generated buffer to the texture.
        glTextureSubImage2D(biomeMapId, 0, 0, 0, worldSize, worldSize, GL_RG, GL_UNSIGNED_BYTE, biomeData);
        
        // 4. Set texture parameters (Linear filtering for smooth transitions).
        glTextureParameteri(biomeMapId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Keep biomeData on CPU for sliding window updates.
    }

    /**
     * Slides the biome map to match the new buffer offset.
     * Copies overlapping pixel data from the old region, and generates fresh
     * Perlin noise for newly exposed areas.
     * Must be called on the render thread after this (or before uploading to GPU).
     *
     * @param oldOffsetX Previous buffer origin X (block coords)
     * @param oldOffsetZ Previous buffer origin Z (block coords)
     * @param newOffsetX New buffer origin X (block coords)
     * @param newOffsetZ New buffer origin Z (block coords)
     */
    public void slideBiomeMap(int oldOffsetX, int oldOffsetZ, int newOffsetX, int newOffsetZ) {
        synchronized (biomeLock) {
            if (biomeData == null || biomeWorldSize == 0) return;
            if (oldOffsetX == newOffsetX && oldOffsetZ == newOffsetZ) return;

        int ws = biomeWorldSize;
        int shiftX = newOffsetX - oldOffsetX; // in block coords
        int shiftZ = newOffsetZ - oldOffsetZ;

        // Take a snapshot of the old data before we overwrite it
        ByteBuffer oldData = MemoryUtil.memAlloc(ws * ws * 2);
        biomeData.rewind();
        oldData.put(biomeData);
        oldData.flip();
        biomeData.rewind();

        // Fill the new buffer by either copying overlapping old data or generating fresh
        for (int dz = 0; dz < ws; dz++) {
            for (int dx = 0; dx < ws; dx++) {
                int oldDx = dx + shiftX;
                int oldDz = dz + shiftZ;
                int newIdx = (dx + dz * ws) * 2;

                if (oldDx >= 0 && oldDx < ws && oldDz >= 0 && oldDz < ws) {
                    // Copy from overlapping region
                    int oldIdx = (oldDx + oldDz * ws) * 2;
                    biomeData.put(newIdx, oldData.get(oldIdx));
                    biomeData.put(newIdx + 1, oldData.get(oldIdx + 1));
                } else {
                    // Generate new biome data for the exposed area from BiomeProvider
                    int wx = newOffsetX + dx;
                    int wz = newOffsetZ + dz;
                    float[] th = getBiomeTempHumidity(wx, wz);
                    biomeData.put(newIdx, (byte) (Math.max(0, Math.min(1, th[0])) * 255));
                    biomeData.put(newIdx + 1, (byte) (Math.max(0, Math.min(1, th[1])) * 255));
                }
            }
        }

        MemoryUtil.memFree(oldData);
        }
    }

    /**
     * Uploads the CPU-side biome data to the GPU texture.
     * Must be called on the render thread.
     */
    public void uploadBiomeMap() {
        synchronized (biomeLock) {
            if (biomeData == null || biomeMapId == 0) return;
            biomeData.rewind();
            glTextureSubImage2D(biomeMapId, 0, 0, 0, biomeWorldSize, biomeWorldSize, GL_RG, GL_UNSIGNED_BYTE, biomeData);
        }
    }

    /**
     * Loads the grass and foliage colormaps from PNG files.
     */
    public void loadColormaps(String grassPath, String foliagePath) {
        grassColormapId = loadTexture(grassPath);
        foliageColormapId = loadTexture(foliagePath);
    }

    /** Helper to load a simple 2D texture from a file. */
    private int loadTexture(String path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            // Load pixels using STBImage.
            ByteBuffer image = STBImage.stbi_load(path, w, h, comp, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load colormap: " + path + " - " + STBImage.stbi_failure_reason());
            }

            // Create and initialize the OpenGL texture object.
            int texId = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(texId, 1, GL_RGBA8, w.get(0), h.get(0));
            glTextureSubImage2D(texId, 0, 0, 0, w.get(0), h.get(0), GL_RGBA, GL_UNSIGNED_BYTE, image);
            
            glTextureParameteri(texId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTextureParameteri(texId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(texId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            STBImage.stbi_image_free(image); // Clean up CPU memory.
            return texId;
        }
    }

    /**
     * Returns temperature and humidity from the biome at (x, z),
     * falling back to uniform temperate values if no BiomeProvider is set.
     * Reuses a single float[2] buffer to avoid allocation in hot loops.
     */
    private final float[] tempHumBuf = new float[2];
    private float[] getBiomeTempHumidity(int x, int z) {
        Biome biome = biomeProvider.getBiome(x, z);
        // MC 1.12.2 temperature range is [-0.5, 2.0]. Vanilla clamps to [0,1] for colormap lookup.
        // Use getTemperature()/getHumidity() so biome overrides and noise are respected.
        tempHumBuf[0] = Math.max(0.0f, Math.min(1.0f, biome.getTemperature(x, z)));
        tempHumBuf[1] = Math.max(0.0f, Math.min(1.0f, biome.getHumidity(x, z)));
        return tempHumBuf;
    }

    // Getters for the various texture IDs.
    public int getBiomeMapId() { return biomeMapId; }
    public int getGrassColormapId() { return grassColormapId; }
    public int getFoliageColormapId() { return foliageColormapId; }
}
