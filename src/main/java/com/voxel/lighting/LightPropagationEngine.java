package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import java.util.List;

/**
 * 14-Directional Occlusion Baker.
 * Purged old BFS lighting system.
 * Propagates sky visibility down from world height.
 */
public class LightPropagationEngine {
    private final World world;
    private final BlockDataManager blockDataManager;

    // 14 Directions covering the upper hemisphere
    public static final float[][] OCC_DIRS = {
        {0.0f, 1.0f, 0.0f},
        {0.707f, 0.707f, 0.0f}, {-0.707f, 0.707f, 0.0f}, {0.0f, 0.707f, 0.707f}, {0.0f, 0.707f, -0.707f},
        {0.5f, 0.707f, 0.5f}, {-0.5f, 0.707f, 0.5f}, {0.5f, 0.707f, -0.5f}, {-0.5f, 0.707f, -0.5f},
        {0.866f, 0.5f, 0.0f}, {-0.866f, 0.5f, 0.0f}, {0.0f, 0.5f, 0.866f}, {0.0f, 0.5f, -0.866f},
        {0.0f, 0.3f, 0.0f} // Lower angle fill
    };

    public LightPropagationEngine(World world, BlockDataManager blockDataManager) {
        this.world = world;
        this.blockDataManager = blockDataManager;
    }

    /**
     * Bakes 14-bit occlusion for a chunk slot.
     * Propagates downward from sky.
     */
    public void bakeChunkOcclusion(int slot, int cx, int cy, int cz) {
        short[] occPool = world.getOcclusionPool();
        int baseIdx = slot << 12;

        for (int ly = 15; ly >= 0; ly--) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int worldX = (cx << 4) + lx;
                    int worldY = (cy << 4) + ly;
                    int worldZ = (cz << 4) + lz;

                    int idx = baseIdx | (lx | (ly << 4) | (lz << 8));
                    
                    if (isBlocked(worldX, worldY, worldZ)) {
                        occPool[idx] = 0;
                        continue;
                    }

                    int mask = 0;
                    for (int d = 0; d < 14; d++) {
                        if (checkSkyVisibility(worldX, worldY, worldZ, d)) {
                            mask |= (1 << d);
                        }
                    }
                    occPool[idx] = (short) mask;
                }
            }
        }
    }

    private boolean checkSkyVisibility(int x, int y, int z, int dirIdx) {
        float[] d = OCC_DIRS[dirIdx];
        float curX = x + 0.5f, curY = y + 0.5f, curZ = z + 0.5f;
        
        // Raymarch up to find sky (cheap)
        for (int i = 1; i < 32; i++) {
            int nx = (int) (curX + d[0] * i);
            int ny = (int) (curY + d[1] * i);
            int nz = (int) (curZ + d[2] * i);
            
            if (ny >= 2048) return true; // Reached sky
            if (isBlocked(nx, ny, nz)) return false;
        }
        return true;
    }

    private boolean isBlocked(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 2048 || y >= 2048 || z >= 2048) return false;
        int blockId = world.getVoxel(x, y, z);
        return blockId > 0 && blockDataManager.isFullBlock(blockId);
    }

    public void propagateAllLights(List<LightSource> sources) {
        // BFS Legacy Purged. Now handled via analytic rays in shader.
    }
}