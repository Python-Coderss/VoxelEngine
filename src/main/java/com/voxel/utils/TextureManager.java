package com.voxel.utils;

import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Uses a 16x16 Texture Array for block/item textures and a separate 64x64 Texture Array for entity textures.
 */
public class TextureManager {
    private static final int TEXTURE_SIZE = 16;         // Block/item textures at native 16x16
    private static final int ENTITY_TEXTURE_SIZE = 64;  // Entity textures at native 64x64 (skins, etc.)

    // ---- Block/Item texture array (16x16) ----
    private int textureArrayId;
    private final Map<String, Integer> textureToIndex = new HashMap<>();
    private final Map<String, Integer> textureToFrameCount = new HashMap<>();
    private final List<String> texturePaths = new ArrayList<>();
    /**
     * WARNING: This is the max number of layers allocated in the 3D texture array. 
     * If the total number of textures (including animation frames) exceeds this,
     * glTexSubImage3D will fail with an out-of-bounds error when trying to upload.
     * Bump this up (e.g., +256 at a time) if you add more blocks/items.
     */
    private static final int MAX_LAYERS = 1300;

    // ---- Entity texture array (64x64) ----
    private int entityTextureArrayId;
    private final Map<String, Integer> entityTextureToIndex = new HashMap<>();
    private final List<String> entityTexturePaths = new ArrayList<>();
    private static final int MAX_ENTITY_LAYERS = 256;

    public void loadTextures(String... directoryPaths) {
        List<Path> allFiles = new ArrayList<>();

        for (String directoryPath : directoryPaths) {
            File dir = new File(directoryPath);
            if (!dir.exists()) {
                System.err.println("Texture directory not found: " + directoryPath);
                continue;
            }

            try {
            	
            	Path[] files2 = Files.walk(Paths.get(directoryPath))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".png"))
                    .toArray(Path[]::new);

            	allFiles.addAll(Arrays.asList(files2));

            } catch (IOException e) {
                throw new RuntimeException("Failed to scan textures: " + directoryPath, e);
            }
        }

        for (Path path : allFiles) {
            String fileName = path.getFileName().toString();
            String name = fileName.substring(0, fileName.lastIndexOf('.'));

            if (!textureToIndex.containsKey(name)) {
                textureToIndex.put(name, texturePaths.size());
                texturePaths.add(path.toString());
            }
        }

        if (texturePaths.isEmpty()) return;        /*
         * WARNING: MAX_LAYERS must be >= total unique textures + animation frame layers.
         * Animated textures (vertical strips like portal.png, water_still.png) consume
         * multiple consecutive layers, so the actual layer usage exceeds texturePaths.size().
         * The check below uses a conservative estimate (texturePaths.size() + 100 buffer
         * for animation frames). Increase MAX_LAYERS if this warning triggers.
         */
        if (texturePaths.size() + 100 > MAX_LAYERS) {
            System.err.println("WARNING: " + texturePaths.size() + " + ~100 animation frames may exceed MAX_LAYERS (" + MAX_LAYERS + ")! Increase MAX_LAYERS in TextureManager.java");
        }

        if (textureArrayId == 0) {
            textureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8,
                    TEXTURE_SIZE, TEXTURE_SIZE, MAX_LAYERS);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

        for (int i = 0; i < texturePaths.size(); i++) {
            loadAndUploadTexture(texturePaths.get(i), i, TEXTURE_SIZE);
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    /**
     * Loads a texture, detecting animated vertical strips (height > width).
     * Each frame (textureSize x textureSize) is uploaded to consecutive layers.
     */
    private void loadAndUploadTexture(String path, int layer, int textureSize) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img == null) return;

            int originalWidth = img.getWidth();
            int originalHeight = img.getHeight();

            // Detect animated textures: vertical strips where height > width
            int frameCount;
            if (originalHeight > textureSize && originalWidth == textureSize && originalHeight % textureSize == 0) {
                frameCount = originalHeight / textureSize;
            } else {
                frameCount = 1;
            }

            // Store the frame count (only for block textures)
            String fileName = new File(path).getName();
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            textureToFrameCount.put(name, frameCount);

            if (frameCount > 1) {
                // Animated texture: upload each frame to consecutive layers
                for (int f = 0; f < frameCount; f++) {
                    BufferedImage frame = img.getSubimage(0, f * textureSize, textureSize, textureSize);
                    uploadFrameToLayer(frame, layer + f, textureSize);
                }
            } else {
                // Non-animated: scale to target size if needed
                if (img.getWidth() != textureSize || img.getHeight() != textureSize) {
                    BufferedImage scaled = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(img, 0, 0, textureSize, textureSize, null);
                    g.dispose();
                    img = scaled;
                }
                uploadFrameToLayer(img, layer, textureSize);
            }
        } catch (IOException e) {
            System.err.println("Failed to upload texture: " + path);
        }
    }

    private void uploadFrameToLayer(BufferedImage img, int layer, int textureSize) {
        int[] pixels = new int[textureSize * textureSize];
        img.getRGB(0, 0, textureSize, textureSize, pixels, 0, textureSize);

        ByteBuffer buffer = MemoryUtil.memAlloc(textureSize * textureSize * 4);
        for (int y = 0; y < textureSize; y++) {
            for (int x = 0; x < textureSize; x++) {
                int pixel = pixels[y * textureSize + x];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
                buffer.put((byte) (pixel & 0xFF));         // B
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, textureSize, textureSize, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);
    }

    // ========================================================================
    // Entity Texture Loading (64x64 array)
    // ========================================================================

    public void loadEntityTextures(String directoryPath) {
        File dir = new File(directoryPath);
        if (!dir.exists()) {
            System.err.println("Entity texture directory not found: " + directoryPath);
            return;
        }

        try {
            Path[] files = Files.walk(Paths.get(directoryPath))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".png"))
                    .toArray(Path[]::new);

            for (Path path : files) {
                String fileName = path.getFileName().toString();
                String name = fileName.substring(0, fileName.lastIndexOf('.'));

                if (!entityTextureToIndex.containsKey(name)) {
                    entityTextureToIndex.put(name, entityTexturePaths.size());
                    entityTexturePaths.add(path.toString());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan entity textures: " + directoryPath, e);
        }

        if (entityTexturePaths.isEmpty()) return;

        if (entityTextureArrayId == 0) {
            entityTextureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, entityTextureArrayId);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8,
                    ENTITY_TEXTURE_SIZE, ENTITY_TEXTURE_SIZE, MAX_ENTITY_LAYERS);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, entityTextureArrayId);

        for (int i = 0; i < entityTexturePaths.size(); i++) {
            loadAndUploadTexture(entityTexturePaths.get(i), i, ENTITY_TEXTURE_SIZE);
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    // ========================================================================
    // Public accessors
    // ========================================================================

    public int getTextureArrayId() {
        return textureArrayId;
    }

    public int getEntityTextureArrayId() {
        return entityTextureArrayId;
    }

    public int getTextureIndex(String name) {
        return textureToIndex.getOrDefault(name, -1);
    }

    /**
     * Returns the number of animation frames for a texture.
     * Returns 1 for non-animated textures.
     */
    public int getFrameCount(String name) {
        return textureToFrameCount.getOrDefault(name, 1);
    }

    /**
     * Returns the texture name for a given index, or null if not found.
     */
    public String getTextureNameByIndex(int index) {
        for (Map.Entry<String, Integer> entry : textureToIndex.entrySet()) {
            if (entry.getValue() == index) {
                return entry.getKey();
            }
        }
        return null;
    }

    public int getEntityTextureIndex(String name) {
        return entityTextureToIndex.getOrDefault(name, -1);
    }
    
    public int getTextureCount() {
        return texturePaths.size();
    }
}
