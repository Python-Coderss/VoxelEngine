package com.voxel.game;

import com.voxel.World;
import com.voxel.world.ChunkManager;
import com.voxel.utils.BlockDataManager;

import java.nio.ByteBuffer;

/**
 * Renders a top-down world map texture from loaded chunk surface data.
 * Scrollable with pan, zoomable. Press M to toggle.
 */
public class WorldMapRenderer {

    private static final int TEX_SIZE = 512;
    private final ByteBuffer mapPixels = ByteBuffer.allocateDirect(TEX_SIZE * TEX_SIZE * 4);
    private int mapTextureId = 0;
    private boolean dirty = true;
    private int frameCounter = 0;

    /** Builds/updates the map texture. Call once per tick when map is open. */
    public void update(World world, ChunkManager chunkManager, BlockDataManager bdm,
                       float playerX, float playerZ) {
        frameCounter++;
        // Only rebuild every 4th tick (~5 Hz) to keep it cheap
        if (!dirty && frameCounter % 4 != 0) return;
        dirty = false;

        int half = TEX_SIZE / 2;
        // Each pixel covers mapZoom blocks at the current zoom level (read from ctx)
        // For the texture we render at a fixed resolution; zoom is handled by the quad scale.
        float blocksPerPixel = 2.0f; // 1 chunk ≈ 8 pixels

        // Centre on player's current position
        int cx = (int) Math.floor(playerX);
        int cz = (int) Math.floor(playerZ);

        for (int py = 0; py < TEX_SIZE; py++) {
            for (int px = 0; px < TEX_SIZE; px++) {
                int wx = cx + (int) ((px - half) * blocksPerPixel);
                int wz = cz + (int) ((py - half) * blocksPerPixel);

                // Find surface height
                int wy = findSurface(world, wx, wz, 0, 255);
                int blockId = wy >= 0 ? world.getVoxel(wx, wy, wz) : 0;
                String name = bdm.getName(blockId);

                int r, g, b;
                if (blockId == 0 || wy < 0) {
                    r = 24; g = 28; b = 48; // unexplored / void
                } else if (name.contains("water") || blockId == 15) {
                    r = 32; g = 96; b = 192;
                } else if (name.contains("grass") || name.contains("leaves")) {
                    r = 64; g = 160; b = 48;
                } else if (name.contains("sand")) {
                    r = 224; g = 208; b = 128;
                } else if (name.contains("snow") || name.contains("ice")) {
                    r = 240; g = 240; b = 248;
                } else if (name.contains("stone") || name.contains("ore") || name.contains("cobble")) {
                    r = 128; g = 128; b = 128;
                } else if (name.contains("dirt")) {
                    r = 128; g = 96; b = 48;
                } else if (name.contains("log") || name.contains("wood")) {
                    r = 96; g = 64; b = 32;
                } else if (name.contains("lava")) {
                    r = 224; g = 96; b = 16;
                } else {
                    // Unknown — light grey
                    r = 160; g = 160; b = 160;
                }

                int idx = (py * TEX_SIZE + px) * 4;
                mapPixels.put(idx, (byte) r);
                mapPixels.put(idx + 1, (byte) g);
                mapPixels.put(idx + 2, (byte) b);
                mapPixels.put(idx + 3, (byte) 255);
            }
        }
    }

    private static int findSurface(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }

    public int getTextureId() { return mapTextureId; }
    public void setTextureId(int id) { this.mapTextureId = id; }

    /** Returns the RGBA pixel buffer for GL upload. */
    public ByteBuffer getPixels() { mapPixels.position(0); return mapPixels; }
    public int getTexSize() { return TEX_SIZE; }

    /** Force a full rebuild next update. */
    public void markDirty() { dirty = true; }
}
