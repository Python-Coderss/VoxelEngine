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

public class BiomeManager {
    private int grassColormapId;
    private int foliageColormapId;
    private int biomeMapId;
    
    private final PerlinNoise tempNoise = new PerlinNoise(12345);
    private final PerlinNoise humNoise = new PerlinNoise(67890);
    
    public void generateBiomeMap(int worldSize) {
        ByteBuffer buffer = MemoryUtil.memAlloc(worldSize * worldSize * 2);
        for (int z = 0; z < worldSize; z++) {
            for (int x = 0; x < worldSize; x++) {
                float t = (tempNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;
                float h = (humNoise.noise(x, z, 0.002f) + 1.0f) * 0.5f;
                buffer.put((byte) (Math.max(0, Math.min(1, t)) * 255));
                buffer.put((byte) (Math.max(0, Math.min(1, h)) * 255));
            }
        }
        buffer.flip();

        biomeMapId = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(biomeMapId, 1, GL_RG8, worldSize, worldSize);
        glTextureSubImage2D(biomeMapId, 0, 0, 0, worldSize, worldSize, GL_RG, GL_UNSIGNED_BYTE, buffer);
        
        glTextureParameteri(biomeMapId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        MemoryUtil.memFree(buffer);
    }

    public void loadColormaps(String grassPath, String foliagePath) {
        grassColormapId = loadTexture(grassPath);
        foliageColormapId = loadTexture(foliagePath);
    }

    private int loadTexture(String path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            ByteBuffer image = STBImage.stbi_load(path, w, h, comp, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load colormap: " + path + " - " + STBImage.stbi_failure_reason());
            }

            int texId = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(texId, 1, GL_RGBA8, w.get(0), h.get(0));
            glTextureSubImage2D(texId, 0, 0, 0, w.get(0), h.get(0), GL_RGBA, GL_UNSIGNED_BYTE, image);
            
            glTextureParameteri(texId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTextureParameteri(texId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(texId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            STBImage.stbi_image_free(image);
            return texId;
        }
    }

    public int getBiomeMapId() { return biomeMapId; }
    public int getGrassColormapId() { return grassColormapId; }
    public int getFoliageColormapId() { return foliageColormapId; }
}
