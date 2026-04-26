package com.voxel.utils;

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
    
    // Noise generators used to create procedural temperature and humidity.
    private final PerlinNoise tempNoise = new PerlinNoise(12345);
    private final PerlinNoise humNoise = new PerlinNoise(67890);
    
    /**
     * Generates a 2D map covering the entire world where each pixel stores
     * Temperature (R channel) and Humidity (G channel).
     * @param worldSize The size of the world (e.g., 2048).
     */
    public void generateBiomeMap(int worldSize) {
        // Allocate off-heap memory for the map (2 bytes per pixel: Temperature and Humidity).
        ByteBuffer buffer = MemoryUtil.memAlloc(worldSize * worldSize * 2);
        for (int z = 0; z < worldSize; z++) {
            for (int x = 0; x < worldSize; x++) {
                // Generate temperature and humidity using Perlin noise.
                float t = (tempNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;
                float h = (humNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;

                // Scale to [0, 255] and store in the buffer.
                buffer.put((byte) (Math.max(0, Math.min(1, t)) * 255));
                buffer.put((byte) (Math.max(0, Math.min(1, h)) * 255));
            }
        }
        buffer.flip();

        // 1. Create a 2D texture on the GPU.
        biomeMapId = glCreateTextures(GL_TEXTURE_2D);

        // 2. Allocate storage for the map (RG format, 8 bits per channel).
        glTextureStorage2D(biomeMapId, 1, GL_RG8, worldSize, worldSize);

        // 3. Upload the generated buffer to the texture.
        glTextureSubImage2D(biomeMapId, 0, 0, 0, worldSize, worldSize, GL_RG, GL_UNSIGNED_BYTE, buffer);
        
        // 4. Set texture parameters (Linear filtering for smooth transitions).
        glTextureParameteri(biomeMapId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Free the CPU-side memory.
        MemoryUtil.memFree(buffer);
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

    // Getters for the various texture IDs.
    public int getBiomeMapId() { return biomeMapId; }
    public int getGrassColormapId() { return grassColormapId; }
    public int getFoliageColormapId() { return foliageColormapId; }
}
