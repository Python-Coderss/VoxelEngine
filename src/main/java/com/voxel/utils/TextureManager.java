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
 * Uses a unified 64x64 Texture Array to support both high-res skins and blocks.
 */
public class TextureManager {
    private static final int TEXTURE_SIZE = 64;

    private int textureArrayId;
    private final Map<String, Integer> textureToIndex = new HashMap<>();
    private final Map<String, Integer> textureToFrameCount = new HashMap<>();
    private final List<String> texturePaths = new ArrayList<>();
    private static final int MAX_LAYERS = 1024;

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

        if (texturePaths.isEmpty()) return;

        if (textureArrayId == 0) {
            textureArrayId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

            glTexStorage3D(GL_TEXTURE_2D_ARRAY, 5, GL_RGBA8,
                    TEXTURE_SIZE, TEXTURE_SIZE, 1100);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

        for (int i = 0; i < texturePaths.size(); i++) {
            loadAndUploadTexture(texturePaths.get(i), i);
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    /**
     * Loads a texture, detecting animated vertical strips (height > width).
     * Each frame (TEXTURE_SIZE x TEXTURE_SIZE) is uploaded to consecutive layers.
     */
    private void loadAndUploadTexture(String path, int layer) {
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img == null) return;

            int originalWidth = img.getWidth();
            int originalHeight = img.getHeight();

            // Detect animated textures: vertical strips where height > width
            // Each frame is TEXTURE_SIZE x TEXTURE_SIZE
            int frameCount;
            if (originalHeight > TEXTURE_SIZE && originalWidth == TEXTURE_SIZE && originalHeight % TEXTURE_SIZE == 0) {
                frameCount = originalHeight / TEXTURE_SIZE;
            } else {
                frameCount = 1;
            }

            // Store the frame count for this texture (use the name from textureToIndex)
            String fileName = new File(path).getName();
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            textureToFrameCount.put(name, frameCount);

            if (frameCount > 1) {
                // Animated texture: upload each frame to consecutive layers
                for (int f = 0; f < frameCount; f++) {
                    BufferedImage frame = img.getSubimage(0, f * TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
                    uploadFrameToLayer(frame, layer + f);
                }
            } else {
                // Non-animated: scale to 64x64 if needed, upload to single layer
                if (img.getWidth() != TEXTURE_SIZE || img.getHeight() != TEXTURE_SIZE) {
                    BufferedImage scaled = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(img, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, null);
                    g.dispose();
                    img = scaled;
                }
                uploadFrameToLayer(img, layer);
            }
        } catch (IOException e) {
            System.err.println("Failed to upload texture: " + path);
        }
    }

    private void uploadFrameToLayer(BufferedImage img, int layer) {
        int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        img.getRGB(0, 0, TEXTURE_SIZE, TEXTURE_SIZE, pixels, 0, TEXTURE_SIZE);

        ByteBuffer buffer = MemoryUtil.memAlloc(TEXTURE_SIZE * TEXTURE_SIZE * 4);
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
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, TEXTURE_SIZE, TEXTURE_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);
    }

    public void loadEntityTextures(String directoryPath) {
        loadTextures(directoryPath);
    }

    public int getTextureArrayId() {
        return textureArrayId;
    }

    public int getEntityTextureArrayId() {
        return textureArrayId;
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
        return getTextureIndex(name);
    }
    
    public int getTextureCount() {
        return texturePaths.size();
    }
}
