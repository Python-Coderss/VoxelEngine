package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for EndPortalLogic focusing on the static eye-counting,
 * portal-fill trigger, and the flag helpers. These avoid full ChunkManager
 * wiring (no actual chunk loading) so they can run in the unit-test JVM
 * without spinning up the GPU-side systems.
 */
public class EndPortalLogicTest {

    private static final int FILLED_BIT = 0x10;
    private static final int FACING_MASK = 0x0F;

    private BlockDataManager frameStub() {
        return new BlockDataManager() {
            @Override
            public String getName(int blockId) {
                if (blockId == 100) return "end_portal_frame";
                if (blockId == 101) return "end_portal";
                return "stone";
            }
            @Override
            public Integer findBlockId(String name) {
                if ("end_portal_frame".equals(name)) return 100;
                if ("end_portal".equals(name)) return 101;
                return 0;
            }
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId > 0;
            }
        };
    }

    @Test
    public void isFrameBlockMatchesEndPortalFrame() {
        BlockDataManager bdm = frameStub();
        assertTrue(EndPortalLogic.isFrameBlock(bdm, 100));
        assertFalse(EndPortalLogic.isFrameBlock(bdm, 101));
        assertFalse(EndPortalLogic.isFrameBlock(bdm, 0));
    }

    @Test
    public void filledFrameFlagsIncludesFilledBit() {
        int flags = EndPortalLogic.filledFrameFlags(2);
        assertEquals(FILLED_BIT | 2, flags);
        assertTrue(EndPortalLogic.isEyeInserted(flags));
        assertEquals(2, EndPortalLogic.frameFacing(flags));
    }

    @Test
    public void unfilledFrameReportsEyeNotInserted() {
        int flags = 0;
        assertFalse(EndPortalLogic.isEyeInserted(flags));
        assertEquals(0, EndPortalLogic.frameFacing(flags));
    }

    @Test
    public void countEyesAroundWalksAllTwelveFrames() {
        java.util.HashMap<Long, Integer> flags = new java.util.HashMap<>();
        java.util.HashMap<Long, Integer> blockIds = new java.util.HashMap<>();
        World world = new World(64) {
            @Override
            public int getVoxel(int x, int y, int z) {
                Integer b = blockIds.get(key(x, y, z));
                return b == null ? 0 : b;
            }
            @Override
            public int getVoxelExtra(int x, int y, int z) {
                Integer f = flags.get(key(x, y, z));
                return f == null ? 0 : f;
            }
            @Override
            public void setVoxelWithFlags(int x, int y, int z, int type, int extra, int flagVal) {
                blockIds.put(key(x, y, z), type);
                flags.put(key(x, y, z), flagVal);
            }
        };
        // Seed 12 frame positions with FILLED bit set.
        seedFrameRoom(flags, blockIds, FILLED_BIT);
        BlockDataManager bdm = frameStub();
        int minX = 0, y = 32, minZ = 0;
        assertEquals(12, EndPortalLogic.countEyesAround(world, bdm, minX, y, minZ));
    }

    @Test
    public void tryFillPortalFiresOnlyWhenTwelveEyesArePresent() {
        java.util.HashMap<Long, Integer> flags = new java.util.HashMap<>();
        java.util.HashMap<Long, Integer> blockIds = new java.util.HashMap<>();
        World world = new World(64) {
            @Override
            public int getVoxel(int x, int y, int z) {
                Integer b = blockIds.get(key(x, y, z));
                return b == null ? 0 : b;
            }
            @Override
            public int getVoxelExtra(int x, int y, int z) {
                Integer f = flags.get(key(x, y, z));
                return f == null ? 0 : f;
            }
            @Override
            public void setVoxelWithFlags(int x, int y, int z, int type, int extra, int flagVal) {
                blockIds.put(key(x, y, z), type);
                flags.put(key(x, y, z), flagVal);
            }
        };
        BlockDataManager bdm = frameStub();
        int minX = 0, y = 32, minZ = 0;
        // Start with 8 frames (bottom + top rows, no side columns).
        for (int dx = 0; dx < 4; dx++) {
            blockIds.put(key(minX + dx, y, minZ), 100);
            flags.put(key(minX + dx, y, minZ), FILLED_BIT);
            blockIds.put(key(minX + dx, y + 4, minZ), 100);
            flags.put(key(minX + dx, y + 4, minZ), FILLED_BIT);
        }
        assertFalse("portal should not open with only 8 frames",
                EndPortalLogic.tryFillPortal(world, bdm, minX, y, minZ));
        // Now fill the side columns: portal should open and the 3×3 interior set.
        for (int dy = 1; dy < 3; dy++) {
            blockIds.put(key(minX, y + dy, minZ), 100);
            flags.put(key(minX, y + dy, minZ), FILLED_BIT);
            blockIds.put(key(minX + 3, y + dy, minZ), 100);
            flags.put(key(minX + 3, y + dy, minZ), FILLED_BIT);
        }
        assertTrue(EndPortalLogic.tryFillPortal(world, bdm, minX, y, minZ));
        // The 3×3 mesh at (x+1..x+2, y+1..y+3, z=minZ) must now be end_portal.
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                assertEquals(101, world.getVoxel(minX + dx, y + dy, minZ));
            }
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }

    private static void seedFrameRoom(java.util.HashMap<Long, Integer> flags,
                                       java.util.HashMap<Long, Integer> blockIds,
                                       int flagBit) {
        int minX = 0, y = 32, minZ = 0;
        for (int dx = 0; dx < 4; dx++) {
            blockIds.put(key(minX + dx, y, minZ), 100);
            flags.put(key(minX + dx, y, minZ), flagBit);
            blockIds.put(key(minX + dx, y + 4, minZ), 100);
            flags.put(key(minX + dx, y + 4, minZ), flagBit);
        }
        for (int dy = 1; dy < 3; dy++) {
            blockIds.put(key(minX, y + dy, minZ), 100);
            flags.put(key(minX, y + dy, minZ), flagBit);
            blockIds.put(key(minX + 3, y + dy, minZ), 100);
            flags.put(key(minX + 3, y + dy, minZ), flagBit);
        }
    }
}