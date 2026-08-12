package com.voxel.game;

import com.voxel.World;
import com.voxel.world.ChunkManager;
import com.voxel.utils.BlockDataManager;

import java.nio.ByteBuffer;

/**
 * Renders a top-down world map texture from loaded chunk surface data.
 * Includes player marker, smooth zoom, and improved color palette.
 * Press M to toggle. WASD/arrows to pan, scroll to zoom.
 * Also supports a minimap overlay (rendered separately by the HUD).
 */
public class WorldMapRenderer {

    private static final int TEX_SIZE = 512;
    private final ByteBuffer mapPixels = ByteBuffer.allocateDirect(TEX_SIZE * TEX_SIZE * 4);
    private int mapTextureId = 0;
    private boolean dirty = true;
    private int frameCounter = 0;

    // Smooth zoom interpolation
    private float currentZoom = 1.0f;
    private float targetZoom = 1.0f;
    private static final float ZOOM_LERP_SPEED = 8.0f;

    // Player marker animation
    private float markerPulse = 0f;
    private boolean markerDirection = true;

    // Minimap configuration
    private static final int MINIMAP_SIZE = 256;
    private final ByteBuffer minimapPixels = ByteBuffer.allocateDirect(MINIMAP_SIZE * MINIMAP_SIZE * 4);
    private int minimapTextureId = 0;

    /** Builds/updates the map texture. Call once per tick when map is open. */
    public void update(World world, ChunkManager chunkManager, BlockDataManager bdm,
                       float playerX, float playerZ, float mapZoom, float dt) {
        frameCounter++;

        // Smooth zoom interpolation
        targetZoom = mapZoom;
        float zoomDiff = targetZoom - currentZoom;
        if (Math.abs(zoomDiff) > 0.001f) {
            currentZoom += zoomDiff * Math.min(1.0f, dt * ZOOM_LERP_SPEED);
            dirty = true;
        }

        // Marker pulse animation
        markerPulse += dt * 3.0f;
        if (markerPulse > 1.0f) {
            markerPulse = 0f;
            dirty = true;
        }

        // Only rebuild every 4th tick (~5 Hz) to keep it cheap
        if (!dirty && frameCounter % 4 != 0) return;
        dirty = false;

        int half = TEX_SIZE / 2;
        // Each pixel covers mapZoom blocks at the current zoom level
        float blocksPerPixel = 2.0f * currentZoom;

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

        // Draw player marker at center
        drawPlayerMarker(half, half, cx, cz);

        // Update minimap
        updateMinimap(world, bdm, playerX, playerZ, 4.0f);
    }

    /**
     * Draw an animated player marker at the given pixel position
     */
    private void drawPlayerMarker(int px, int py, int worldX, int worldZ) {
        // Draw outer ring (pulsing)
        float pulseAlpha = 0.5f + 0.3f * (float) Math.sin(markerPulse * Math.PI * 2);
        int ringSize = 6;
        for (int dy = -ringSize; dy <= ringSize; dy++) {
            for (int dx = -ringSize; dx <= ringSize; dx++) {
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist >= ringSize - 1 && dist <= ringSize) {
                    int drawX = px + dx;
                    int drawY = py + dy;
                    if (drawX >= 0 && drawX < TEX_SIZE && drawY >= 0 && drawY < TEX_SIZE) {
                        int idx = (drawY * TEX_SIZE + drawX) * 4;
                        mapPixels.put(idx, (byte) 255);
                        mapPixels.put(idx + 1, (byte) 255);
                        mapPixels.put(idx + 2, (byte) 255);
                        mapPixels.put(idx + 3, (byte) (int) (255 * pulseAlpha));
                    }
                }
            }
        }

        // Draw inner marker (diamond shape)
        int innerSize = 3;
        for (int dy = -innerSize; dy <= innerSize; dy++) {
            for (int dx = -innerSize; dx <= innerSize; dx++) {
                if (Math.abs(dx) + Math.abs(dy) <= innerSize) {
                    int drawX = px + dx;
                    int drawY = py + dy;
                    if (drawX >= 0 && drawX < TEX_SIZE && drawY >= 0 && drawY < TEX_SIZE) {
                        int idx = (drawY * TEX_SIZE + drawX) * 4;
                        // Red marker
                        mapPixels.put(idx, (byte) 255);
                        mapPixels.put(idx + 1, (byte) 50);
                        mapPixels.put(idx + 2, (byte) 50);
                        mapPixels.put(idx + 3, (byte) 255);
                    }
                }
            }
        }

        // Draw direction indicator (small triangle pointing up)
        int dirSize = 2;
        for (int dy = -innerSize - 2; dy < -innerSize; dy++) {
            int halfWidth = (innerSize + 2 + dy + innerSize);
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                int drawX = px + dx;
                int drawY = py + dy;
                if (drawX >= 0 && drawX < TEX_SIZE && drawY >= 0 && drawY < TEX_SIZE) {
                    int idx = (drawY * TEX_SIZE + drawX) * 4;
                    mapPixels.put(idx, (byte) 255);
                    mapPixels.put(idx + 1, (byte) 50);
                    mapPixels.put(idx + 2, (byte) 50);
                    mapPixels.put(idx + 3, (byte) 255);
                }
            }
        }
    }

    /**
     * Update the minimap texture (smaller, lower resolution version for HUD overlay)
     */
    private void updateMinimap(World world, BlockDataManager bdm,
                               float playerX, float playerZ, float blocksPerPixel) {
        int half = MINIMAP_SIZE / 2;
        int cx = (int) Math.floor(playerX);
        int cz = (int) Math.floor(playerZ);

        for (int py = 0; py < MINIMAP_SIZE; py++) {
            for (int px = 0; px < MINIMAP_SIZE; px++) {
                int wx = cx + (int) ((px - half) * blocksPerPixel);
                int wz = cz + (int) ((py - half) * blocksPerPixel);

                int wy = findSurface(world, wx, wz, 0, 255);
                int blockId = wy >= 0 ? world.getVoxel(wx, wy, wz) : 0;
                String name = bdm.getName(blockId);

                int r, g, b;
                if (blockId == 0 || wy < 0) {
                    r = 24; g = 28; b = 48;
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
                    r = 160; g = 160; b = 160;
                }

                int idx = (py * MINIMAP_SIZE + px) * 4;
                minimapPixels.put(idx, (byte) r);
                minimapPixels.put(idx + 1, (byte) g);
                minimapPixels.put(idx + 2, (byte) b);
                minimapPixels.put(idx + 3, (byte) 255);
            }
        }

        // Draw player dot on minimap
        int dotSize = 4;
        for (int dy = -dotSize; dy <= dotSize; dy++) {
            for (int dx = -dotSize; dx <= dotSize; dx++) {
                if (dx * dx + dy * dy <= dotSize * dotSize) {
                    int drawX = half + dx;
                    int drawY = half + dy;
                    if (drawX >= 0 && drawX < MINIMAP_SIZE && drawY >= 0 && drawY < MINIMAP_SIZE) {
                        int idx = (drawY * MINIMAP_SIZE + drawX) * 4;
                        minimapPixels.put(idx, (byte) 255);
                        minimapPixels.put(idx + 1, (byte) 50);
                        minimapPixels.put(idx + 2, (byte) 50);
                        minimapPixels.put(idx + 3, (byte) 255);
                    }
                }
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

    /** Get current interpolated zoom level */
    public float getCurrentZoom() { return currentZoom; }

    // Minimap support
    public int getMinimapTextureId() { return minimapTextureId; }
    public void setMinimapTextureId(int id) { this.minimapTextureId = id; }
    public ByteBuffer getMinimapPixels() { minimapPixels.position(0); return minimapPixels; }
    public int getMinimapSize() { return MINIMAP_SIZE; }
}
