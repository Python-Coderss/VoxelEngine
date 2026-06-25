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
    private static final int MAX_LAYERS = 2048;

    // ---- Entity texture array (64x64) ----
    private int entityTextureArrayId;
    private final Map<String, Integer> entityTextureToIndex = new HashMap<>();
    private final List<String> entityTexturePaths = new ArrayList<>();
    private static final int MAX_ENTITY_LAYERS = 512;

    public void loadTextures(String... directoryPaths) {
        // Collect unique texture names -> full paths
        java.util.LinkedHashMap<String, String> nameToPath = new java.util.LinkedHashMap<>();

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

                for (Path path : files2) {
                    String fileName = path.getFileName().toString();
                    String name = fileName.substring(0, fileName.lastIndexOf('.'));
                    // putIfAbsent: first occurrence wins (prefer earlier directories)
                    if (!nameToPath.containsKey(name)) {
                        nameToPath.put(name, path.toString());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan textures: " + directoryPath, e);
            }
        }

        if (nameToPath.isEmpty()) return;

        // First pass: detect frame counts for each texture
        java.util.Map<String, Integer> frameCounts = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> entry : nameToPath.entrySet()) {
            int fc = detectFrameCount(entry.getValue());
            frameCounts.put(entry.getKey(), fc);
        }

        // Second pass: assign layer offsets = cumulative sum of frame counts
        // textureToIndex now stores the LAYER OFFSET, not the list index
        int totalLayers = 0;
        for (java.util.Map.Entry<String, String> entry : nameToPath.entrySet()) {
            String name = entry.getKey();
            textureToIndex.put(name, totalLayers);
            texturePaths.add(entry.getValue());
            int fc = frameCounts.get(name);
            textureToFrameCount.put(name, fc);
            totalLayers += fc;
        }

        if (totalLayers + 100 > MAX_LAYERS) {
            System.err.println("WARNING: " + totalLayers + " layers (incl. animation frames) may exceed MAX_LAYERS (" + MAX_LAYERS + ")! Increase MAX_LAYERS in TextureManager.java");
        }

        if (textureArrayId == 0) {
            textureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8,
                    TEXTURE_SIZE, TEXTURE_SIZE, MAX_LAYERS);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

        // Upload each texture to its layer offset (which accounts for animation frame spacing)
        for (java.util.Map.Entry<String, String> entry : nameToPath.entrySet()) {
            String name = entry.getKey();
            String path = entry.getValue();
            int layerOffset = textureToIndex.get(name);
            // loadAndUploadTexture will detect frame count again and upload all frames consecutively
            loadAndUploadTexture(path, layerOffset, TEXTURE_SIZE);
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    /**
     * Quick pre-scan to detect animated vertical strip frame count without uploading.
     */
    private int detectFrameCount(String path) {
        try {
            java.awt.image.BufferedImage img = ImageIO.read(new File(path));
            if (img == null) return 1;
            int w = img.getWidth();
            int h = img.getHeight();
            if (h > TEXTURE_SIZE && w == TEXTURE_SIZE && h % TEXTURE_SIZE == 0) {
                return h / TEXTURE_SIZE;
            }
        } catch (IOException e) {
            // ignore
        }
        return 1;
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

            // Frame count is already stored during pre-scan; no need to store again

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

    /**
     * Loads specific files into the entity texture array (scaling to 64x64).
     * Called for item textures that should appear on 3D crafting entities.
     * @return the entity texture index for the loaded texture, or -1 on failure
     */
    public int loadItemAsEntityTexture(String filePath, String name) {
        if (entityTextureToIndex.containsKey(name)) {
            return entityTextureToIndex.get(name);
        }

        File f = new File(filePath);
        if (!f.exists()) {
            System.err.println("Item texture not found for entity: " + filePath);
            return -1;
        }

        int idx = entityTexturePaths.size();
        entityTextureToIndex.put(name, idx);
        entityTexturePaths.add(filePath);

        // Allocate texture array on first use
        if (entityTextureArrayId == 0) {
            entityTextureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, entityTextureArrayId);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8,
                    ENTITY_TEXTURE_SIZE, ENTITY_TEXTURE_SIZE, MAX_ENTITY_LAYERS);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, entityTextureArrayId);
        loadAndUploadTexture(filePath, idx, ENTITY_TEXTURE_SIZE);
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

        return idx;
    }
    
        public int getTextureCount() {
        return texturePaths.size();
    }

    // ========================================================================
    // Destroy Stage Textures (Minecraft block break overlay, 10 frames)
    // ========================================================================

    private int destroyStageArrayId = 0;
    private static final int DESTROY_STAGE_COUNT = 10;

    /**
     * Loads the destroy_stage_0 through destroy_stage_9 textures into a dedicated
     * 16x16 texture array for block break overlay rendering.
     * Path: src/main/resources/assets/minecraft/textures/blocks/destroy_stage_<n>.png
     */
    public void loadDestroyStages(String blocksDir) {
        if (destroyStageArrayId == 0) {
            destroyStageArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, destroyStageArrayId);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, DESTROY_STAGE_COUNT);
        }
        glBindTexture(GL_TEXTURE_2D_ARRAY, destroyStageArrayId);

        for (int i = 0; i < DESTROY_STAGE_COUNT; i++) {
            String path = blocksDir + "/destroy_stage_" + i + ".png";
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists()) {
                    System.err.println("Destroy stage texture not found: " + path);
                    continue;
                }
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) continue;

                // Scale to TEXTURE_SIZE if needed
                if (img.getWidth() != TEXTURE_SIZE || img.getHeight() != TEXTURE_SIZE) {
                    java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(img, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, null);
                    g.dispose();
                    img = scaled;
                }

                int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
                img.getRGB(0, 0, TEXTURE_SIZE, TEXTURE_SIZE, pixels, 0, TEXTURE_SIZE);

                java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(TEXTURE_SIZE * TEXTURE_SIZE * 4);
                for (int y = 0; y < TEXTURE_SIZE; y++) {
                    for (int x = 0; x < TEXTURE_SIZE; x++) {
                        int pixel = pixels[y * TEXTURE_SIZE + x];
                        buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                        buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
                        buffer.put((byte) (pixel & 0xFF));         // B
                        buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
                    }
                }
                buffer.flip();
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, TEXTURE_SIZE, TEXTURE_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                org.lwjgl.system.MemoryUtil.memFree(buffer);

            } catch (Exception e) {
                System.err.println("Failed to load destroy stage: " + path + " - " + e.getMessage());
            }
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    public int getDestroyStageArrayId() {
        return destroyStageArrayId;
    }

    public int getDestroyStageCount() {
        return DESTROY_STAGE_COUNT;
    }
}
