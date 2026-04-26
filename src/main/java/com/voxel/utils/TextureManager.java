package com.voxel.utils;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;

/**
 * Manages the loading and organization of textures.
 * It uses a "Texture Array", which is a single OpenGL object that can store
 * many individual textures of the same size. This allows the GPU to access
 * any texture efficiently using an index.
 */
public class TextureManager {
    // The standard size for Minecraft textures is 16x16 pixels.
    private static final int TEXTURE_SIZE = 16;

    // OpenGL ID for the generated Texture Array
    private int textureArrayId;

    // Maps a texture name (e.g., "stone") to its index in the array.
    private final Map<String, Integer> textureToIndex = new HashMap<>();

    // Stores the file paths of all discovered textures.
    private final List<String> texturePaths = new ArrayList<>();

    /**
     * Finds and loads all .png textures from a directory into an OpenGL Texture Array.
     * @param directoryPath Path to the textures folder.
     */
    public void loadTextures(String directoryPath) {
        try {
            // Find all .png files in the directory and subdirectories
            List<Path> files = Files.walk(Paths.get(directoryPath))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".png"))
                    .collect(Collectors.toList());

            for (Path path : files) {
                String fileName = path.getFileName().toString();
                String name = fileName.substring(0, fileName.lastIndexOf('.'));
                // Store the mapping and path
                textureToIndex.put(name, texturePaths.size());
                texturePaths.add(path.toString());
            }

            if (texturePaths.isEmpty()) {
                throw new RuntimeException("No textures found in " + directoryPath);
            }

            // 1. Create a new texture name (ID)
            textureArrayId = glGenTextures();

            // 2. Bind it as a 2D Texture Array
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

            // 3. Allocate storage for the array (Immutable Storage)
            // 5 mipmap levels, RGBA format, 16x16 size, and N layers (textures)
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, texturePaths.size());

            // 4. Load each image file and upload it to its specific layer in the array
            for (int i = 0; i < texturePaths.size(); i++) {
                loadAndUploadTexture(texturePaths.get(i), i);
            }

            // 5. Set texture filtering parameters
            // NEAREST filtering preserves the "blocky" voxel look
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

            // 6. Generate mipmaps for better rendering at a distance
            glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load textures from directory: " + directoryPath, e);
        }
    }

    /**
     * Loads a single image file from disk and uploads it to a specific layer in the 2D Texture Array.
     */
    private void loadAndUploadTexture(String path, int layer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Allocate memory for image metadata
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            // Load image using STBImage
            ByteBuffer image = STBImage.stbi_load(path, w, h, comp, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load texture: " + path + " - " + STBImage.stbi_failure_reason());
            }

            // Handle animated textures (which are vertical strips) by only reading the first 16x16 frame.
            glPixelStorei(GL_UNPACK_ROW_LENGTH, w.get(0));

            // Upload the pixel data to the GPU at the specified 'layer'
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, TEXTURE_SIZE, TEXTURE_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, image);

            // Reset row length
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            
            // Free the image data after upload
            STBImage.stbi_image_free(image);
        }
    }

    /** @return The OpenGL ID of the texture array. */
    public int getTextureArrayId() {
        return textureArrayId;
    }

    /** @return The index of the texture in the array, or -1 if not found. */
    public int getTextureIndex(String name) {
        return textureToIndex.getOrDefault(name, -1);
    }
    
    /** @return Total number of textures loaded. */
    public int getTextureCount() {
        return texturePaths.size();
    }
}
