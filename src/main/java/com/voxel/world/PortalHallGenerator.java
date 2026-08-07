package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;

/**
 * Fixed ancient-builder transit dimension: a stone testing facility surrounds a
 * central portal hall, with portals to the four existing playable dimensions.
 */
public final class PortalHallGenerator extends WorldGenerator {
    private static final int STONE = 2;
    private static final int BRICKS = 131;
    private static final int OBSIDIAN = 16;
    private static final int GLOWSTONE = 17;
    private static final int NETHER_PORTAL = 19;
    private static final int AETHER_PORTAL = 106;
    private static final int END_STONE = 18;
    private static final int COMMAND_BLOCK = 275;

    public PortalHallGenerator(BlockDataManager blockDataManager) {
        super(0, blockDataManager);
    }

    @Override
    public int getHeight(int x, int y, int z) {
        return 72;
    }

    @Override
    public int getBlockType(int x, int y, int z) {
        if (y < 64 || y > 72) return 0;
        if (y == 64) return BRICKS;
        if (x == 0 && y == 65 && z == 0) return COMMAND_BLOCK;
        int portal = portalVoxelAt(x, y, z);
        if (portal != 0) return portal;
        if (Math.abs(x) == 48 || Math.abs(z) == 48) return STONE;
        if (y <= 68 && (Math.abs(x) <= 48 && Math.abs(z) <= 48)) return STONE;
        return 0;
    }

    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        if (cx != 0 || cz != 0 || cy != 4) return;

        // Decorations are encoded by getBlockType so neighboring chunks do not
        // need to be written from this section's thread.
        world.setVoxel(0, 65, 0, COMMAND_BLOCK);
    }

    private int portalVoxelAt(int x, int y, int z) {
        int[][] portals = {{-20, OBSIDIAN, NETHER_PORTAL}, {-8, GLOWSTONE, AETHER_PORTAL},
            {8, END_STONE, NETHER_PORTAL}, {20, STONE, AETHER_PORTAL}};
        for (int[] p : portals) {
            int sx = p[0];
            if (z != 0 || x < sx || x > sx + 3 || y < 65 || y > 69) continue;
            boolean frame = y == 65 || y == 69 || x == sx || x == sx + 3;
            if (frame) return p[1];
            return p[2];
        }
        return 0;
    }

}
