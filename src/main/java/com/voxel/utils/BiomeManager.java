package com.voxel.utils;

import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    // 2 bytes per texel (R=temp, G=humidity). Kept in off-heap memory for direct GPU upload.
    private ByteBuffer biomeData;
    private int biomeWorldSize;

    // ── Per-chunk incremental fill state ──
    // The map is populated one chunk column at a time (as chunks generate):
    // each 16×16-block column covers a TILE_TEXELS×TILE_TEXELS texel region
    // (4×4 at BIOME_MAP_SCALE=4). dirtyTiles holds the tile indices written
    // since the last upload so uploadBiomeMap() only re-sends those 4×4 regions
    // via glTextureSubImage2D instead of re-uploading the entire 512×512 map.
    private static final int TILE_TEXELS = 4;
    private final Set<Integer> dirtyTiles = new HashSet<>();
    private boolean fullMapDirty = false;
    private ByteBuffer tileScratch; // lazily allocated 4×4×2 scratch for per-tile uploads

    /**
     * Each biome-map texel covers a BIOME_MAP_SCALE × BIOME_MAP_SCALE block
     * region. The underlying temperature/humidity climate noise is low
     * frequency (features span tens to hundreds of blocks), so a 4× coarser
     * map is visually identical after the GPU's bilinear filtering while
     * costing 16× fewer climate lookups to generate and slide.
     * Must stay in sync with the shader's u_BiomeMap UV divisor.
     */
    private static final int BIOME_MAP_SCALE = 4;

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
     * Generates CPU-side biome data for the given world size (full-map bake,
     * world offset 0). Used for reference/tests; the runtime path fills the map
     * incrementally per chunk via fillBiomeDataForChunk().
     * Safe to call from any thread (no OpenGL calls).
     * @param worldSize The size of the world (e.g., 2048).
     */
    public void generateBiomeData(int worldSize) {
        synchronized (biomeLock) {
            int texels = worldSize / BIOME_MAP_SCALE;
            this.biomeWorldSize = texels;

            // Free old buffer if it exists
            if (biomeData != null) MemoryUtil.memFree(biomeData);

            // Allocate off-heap memory for the map (2 bytes per texel: Temperature and Humidity).
            biomeData = MemoryUtil.memAlloc(texels * texels * 2);
            for (int tz = 0; tz < texels; tz++) {
                for (int tx = 0; tx < texels; tx++) {
                    float[] th = getBiomeTempHumidity(
                            tx * BIOME_MAP_SCALE + BIOME_MAP_SCALE / 2,
                            tz * BIOME_MAP_SCALE + BIOME_MAP_SCALE / 2);
                    biomeData.put((byte) (Math.max(0, Math.min(1, th[0])) * 255));
                    biomeData.put((byte) (Math.max(0, Math.min(1, th[1])) * 255));
                }
            }
            biomeData.flip();
            fullMapDirty = true;
            dirtyTiles.clear();
            // GPU texture creation is deferred to uploadBiomeMap() on the render thread.
        }
    }

    /**
     * Fills the biome-map tile for one chunk column (16×16 blocks = a
     * TILE_TEXELS×TILE_TEXELS texel region in buffer-relative space). Called
     * from the gen thread as each column is created, so the tint map is
     * populated incrementally alongside terrain instead of being baked up
     * front. Any tile not yet covered by a generated chunk stays neutral
     * (128,128) — the renderer never sees those texels anyway because no
     * voxels exist there.
     *
     * @param cx      absolute chunk X
     * @param cz      absolute chunk Z
     * @param offsetX current buffer origin X (block coords)
     * @param offsetZ current buffer origin Z (block coords)
     */
    public void fillBiomeDataForChunk(int cx, int cz, int offsetX, int offsetZ) {
        synchronized (biomeLock) {
            if (biomeData == null || biomeWorldSize == 0 || biomeProvider == null) return;
            int relCX = cx - (offsetX >> 4); // buffer-relative chunk coords
            int relCZ = cz - (offsetZ >> 4);
            int tx0 = relCX * TILE_TEXELS;
            int tz0 = relCZ * TILE_TEXELS;
            if (tx0 < 0 || tz0 < 0 || tx0 + TILE_TEXELS > biomeWorldSize || tz0 + TILE_TEXELS > biomeWorldSize) return;

            for (int lz = 0; lz < TILE_TEXELS; lz++) {
                for (int lx = 0; lx < TILE_TEXELS; lx++) {
                    int tx = tx0 + lx, tz = tz0 + lz;
                    int wx = offsetX + tx * BIOME_MAP_SCALE + BIOME_MAP_SCALE / 2;
                    int wz = offsetZ + tz * BIOME_MAP_SCALE + BIOME_MAP_SCALE / 2;
                    float[] th = getBiomeTempHumidity(wx, wz);
                    int idx = (tx + tz * biomeWorldSize) * 2;
                    biomeData.put(idx, (byte) (Math.max(0, Math.min(1, th[0])) * 255));
                    biomeData.put(idx + 1, (byte) (Math.max(0, Math.min(1, th[1])) * 255));
                }
            }
            dirtyTiles.add((tz0 / TILE_TEXELS) * (biomeWorldSize / TILE_TEXELS) + (tx0 / TILE_TEXELS));
        }
    }

    /** Package-private readback for tests: raw byte pair (temp, humidity) at a texel. */
    byte[] getBiomeTexelBytes(int tx, int tz) {
        synchronized (biomeLock) {
            if (biomeData == null || tx < 0 || tz < 0 || tx >= biomeWorldSize || tz >= biomeWorldSize) return new byte[]{0, 0};
            int idx = (tx + tz * biomeWorldSize) * 2;
            return new byte[]{biomeData.get(idx), biomeData.get(idx + 1)};
        }
    }

    /**
     * Installs a tiny neutral map so the renderer has a valid texture immediately.
     * The full world-sized map is generated asynchronously after the first terrain
     * becomes visible, keeping biome noise out of the boot critical path.
     */
    public void generateFallbackBiomeData(int worldSize) {
        synchronized (biomeLock) {
            int texels = worldSize / BIOME_MAP_SCALE;
            this.biomeWorldSize = texels;
            if (biomeData != null) MemoryUtil.memFree(biomeData);
            biomeData = MemoryUtil.memAlloc(texels * texels * 2);
            for (int i = 0; i < texels * texels; i++) {
                biomeData.put((byte) 128).put((byte) 128);
            }
            biomeData.flip();
            fullMapDirty = true;
            dirtyTiles.clear();
        }
    }

    /**
     * Fills a procedural temperature/humidity gradient for the main-menu 3D
     * panorama. The real biome provider doesn't exist until a world is created,
     * so this bakes smooth value noise directly (no BiomeProvider needed) to
     * give the menu terrain varied grass/foliage colors. Safe to call from any
     * thread; upload via {@link #uploadBiomeMap()} on the GL thread. The data is
     * overwritten tile-by-tile once the real world generates its chunks.
     */
    public void createPanoramaBiomeData(int worldSize) {
        synchronized (biomeLock) {
            int texels = worldSize / BIOME_MAP_SCALE;
            this.biomeWorldSize = texels;
            if (biomeData != null) MemoryUtil.memFree(biomeData);
            biomeData = MemoryUtil.memAlloc(texels * texels * 2);
            final int N = 16;
            float[] lattice = new float[N * N * 2];
            java.util.Random rnd = new java.util.Random(0xC0FFEE);
            for (int i = 0; i < lattice.length; i++) lattice[i] = rnd.nextFloat();
            for (int tz = 0; tz < texels; tz++) {
                for (int tx = 0; tx < texels; tx++) {
                    float u = tx / (float) texels * N, v = tz / (float) texels * N;
                    float temp = panValueNoise(lattice, N, u, v, 0);
                    float hum = panValueNoise(lattice, N, u + 37.7f, v + 11.3f, N * N);
                    biomeData.put((byte) (Math.max(0, Math.min(1, temp)) * 255));
                    biomeData.put((byte) (Math.max(0, Math.min(1, hum)) * 255));
                }
            }
            biomeData.flip();
            fullMapDirty = true;
            dirtyTiles.clear();
        }
    }

    /** Bilinear value noise over a square lattice (plain data, no GL). */
    private static float panValueNoise(float[] lattice, int n, float u, float v, int offset) {
        int x0 = ((int) Math.floor(u)) % n, z0 = ((int) Math.floor(v)) % n;
        if (x0 < 0) x0 += n;
        if (z0 < 0) z0 += n;
        int x1 = (x0 + 1) % n, z1 = (z0 + 1) % n;
        float fu = u - (float) Math.floor(u), fv = v - (float) Math.floor(v);
        float su = fu * fu * (3f - 2f * fu), sv = fv * fv * (3f - 2f * fv);
        float a = lattice[offset + z0 * n + x0], b = lattice[offset + z0 * n + x1];
        float c = lattice[offset + z1 * n + x0], d = lattice[offset + z1 * n + x1];
        return (a * (1 - su) + b * su) * (1 - sv) + (c * (1 - su) + d * su) * sv;
    }

    /**
     * Slides the biome map to match the new buffer offset.
     * Copies overlapping pixel data from the old region; newly exposed areas
     * stay neutral until their chunks generate and fill them via
     * fillBiomeDataForChunk(). The whole map is re-uploaded once (fullMapDirty).
     * Must be called on the gen thread; uploadBiomeMap() runs on the render thread.
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

        int ws = biomeWorldSize; // texels per side
        // Texel-space shift (recenters move in chunk-aligned steps, so these
        // divide evenly; any remainder is handled by the overlap test below).
        int shiftX = (newOffsetX - oldOffsetX) / BIOME_MAP_SCALE;
        int shiftZ = (newOffsetZ - oldOffsetZ) / BIOME_MAP_SCALE;

        // Take a snapshot of the old data before we overwrite it
        ByteBuffer oldData = MemoryUtil.memAlloc(ws * ws * 2);
        biomeData.rewind();
        oldData.put(biomeData);
        oldData.flip();
        biomeData.rewind();

        // Fill the new buffer by copying overlapping old data; exposed strips
        // stay neutral until their chunks generate and call fillBiomeDataForChunk.
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
                    // Exposed: neutral until the chunk covering it generates.
                    biomeData.put(newIdx, (byte) 128);
                    biomeData.put(newIdx + 1, (byte) 128);
                }
            }
        }

        MemoryUtil.memFree(oldData);
        fullMapDirty = true; // every texel moved; re-upload the whole map once
        dirtyTiles.clear();
        }
    }

    /**
     * Uploads the CPU-side biome data to the GPU texture.
     * Creates the texture if it doesn't exist yet. Must be called on the render thread.
     */
    public void uploadBiomeMap() {
        synchronized (biomeLock) {
            if (biomeData == null) return;

            // Lazy texture creation: deferred from generateBiomeData() to avoid GL calls off-thread
            if (biomeMapId == 0) {
                biomeMapId = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorage2D(biomeMapId, 1, GL_RG8, biomeWorldSize, biomeWorldSize);
                glTextureParameteri(biomeMapId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTextureParameteri(biomeMapId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(biomeMapId, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }

            if (fullMapDirty) {
                // One-shot full upload (boot fallback, dimension switch, recenter slide).
                biomeData.rewind();
                glTextureSubImage2D(biomeMapId, 0, 0, 0, biomeWorldSize, biomeWorldSize, GL_RG, GL_UNSIGNED_BYTE, biomeData);
                fullMapDirty = false;
                dirtyTiles.clear();
                return;
            }

            // Incremental: upload only the 4×4 texel tiles written since the last frame.
            if (dirtyTiles.isEmpty()) return;
            if (tileScratch == null) tileScratch = MemoryUtil.memAlloc(TILE_TEXELS * TILE_TEXELS * 2);
            int tilesPerSide = biomeWorldSize / TILE_TEXELS;
            for (int tile : dirtyTiles) {
                int tx0 = (tile % tilesPerSide) * TILE_TEXELS;
                int tz0 = (tile / tilesPerSide) * TILE_TEXELS;
                tileScratch.clear();
                for (int lz = 0; lz < TILE_TEXELS; lz++) {
                    int rowBase = ((tz0 + lz) * biomeWorldSize + tx0) * 2;
                    for (int lx = 0; lx < TILE_TEXELS; lx++) {
                        tileScratch.put(biomeData.get(rowBase + lx * 2));
                        tileScratch.put(biomeData.get(rowBase + lx * 2 + 1));
                    }
                }
                tileScratch.flip();
                glTextureSubImage2D(biomeMapId, 0, tx0, tz0, TILE_TEXELS, TILE_TEXELS, GL_RG, GL_UNSIGNED_BYTE, tileScratch);
            }
            dirtyTiles.clear();
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
        BiomeProvider provider = biomeProvider;
        if (provider != null) {
            Biome biome = provider.getBiome(x, z);
            if (biome != null) {
                // MC 1.12.2 temperature range is [-0.5, 2.0]. Vanilla clamps to [0,1] for colormap lookup.
                // Use getTemperature()/getHumidity() so biome overrides and noise are respected.
                tempHumBuf[0] = Math.max(0.0f, Math.min(1.0f, biome.getTemperature(x, z)));
                tempHumBuf[1] = Math.max(0.0f, Math.min(1.0f, biome.getHumidity(x, z)));
                return tempHumBuf;
            }
        }
        // Fallback: uniform temperate values when the provider is unset or the
        // registry momentarily cannot resolve a biome (never NPE on the gen thread).
        tempHumBuf[0] = 0.7f;
        tempHumBuf[1] = 0.5f;
        return tempHumBuf;
    }

    // Getters for the various texture IDs.
    public int getBiomeMapId() { return biomeMapId; }
    public int getGrassColormapId() { return grassColormapId; }
    public int getFoliageColormapId() { return foliageColormapId; }
}
