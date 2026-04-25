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
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;

public class TextureManager {
    private static final int TEXTURE_SIZE = 16;
    private int textureArrayId;
    private final Map<String, Integer> textureToIndex = new HashMap<>();
    private final List<String> texturePaths = new ArrayList<>();

    public void loadTextures(String directoryPath) {
        try {
            List<Path> files = Files.walk(Paths.get(directoryPath))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".png"))
                    .collect(Collectors.toList());

            for (Path path : files) {
                String fileName = path.getFileName().toString();
                String name = fileName.substring(0, fileName.lastIndexOf('.'));
                textureToIndex.put(name, texturePaths.size());
                texturePaths.add(path.toString());
            }

            if (texturePaths.isEmpty()) {
                System.err.println("No textures found in " + directoryPath);
                return;
            }

            textureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

            // Allocate storage for the texture array
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, texturePaths.size());

            for (int i = 0; i < texturePaths.size(); i++) {
                loadAndUploadTexture(texturePaths.get(i), i);
            }

            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

            glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadAndUploadTexture(String path, int layer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            ByteBuffer image = STBImage.stbi_load(path, w, h, comp, 4);
            if (image == null) {
                System.err.println("Failed to load texture: " + path + " - " + STBImage.stbi_failure_reason());
                return;
            }

            if (w.get(0) != TEXTURE_SIZE || h.get(0) != TEXTURE_SIZE) {
                // If it's a vertical strip (animated), we just take the first frame (top 16x16)
                if (w.get(0) == TEXTURE_SIZE && h.get(0) > TEXTURE_SIZE) {
                    // This is an animated texture, just use the first frame
                } else {
                    // System.out.println("Texture " + path + " is not 16x16 (" + w.get(0) + "x" + h.get(0) + ")");
                }
            }

            // By specifying 16x16 in glTexSubImage3D, OpenGL will only read the first 16x16 pixels 
            // if the buffer is provided correctly, but we should handle the pixel row alignment.
            glPixelStorei(GL_UNPACK_ROW_LENGTH, w.get(0));
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, TEXTURE_SIZE, TEXTURE_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, image);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            
            STBImage.stbi_image_free(image);
        }
    }

    public int getTextureArrayId() {
        return textureArrayId;
    }

    public int getTextureIndex(String name) {
        return textureToIndex.getOrDefault(name, -1);
    }
    
    public int getTextureCount() {
        return texturePaths.size();
    }
}
