package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests BeaconLogic pyramid-level detection. We mock the chunk-pool
 * voxel reads via a subclass override so we don't have to spin up the
 * full ChunkManager gen thread.
 */
public class BeaconLogicTest {

    private BlockDataManager bdm;
    private World world;

    private static final int IRON    = 137;
    private static final int GOLD    = 138;
    private static final int DIAMOND = 139;
    private static final int EMERALD = 140;
    private static final int BEACON  = 457;

    @Before
    public void setUp() {
        bdm = new BlockDataManager() {
            @Override
            public String getName(int blockId) {
                if (blockId == IRON)    return "iron_block";
                if (blockId == GOLD)    return "gold_block";
                if (blockId == DIAMOND) return "diamond_block";
                if (blockId == EMERALD) return "emerald_block";
                if (blockId == BEACON)  return "beacon";
                return "stone";
            }
            @Override
            public Integer findBlockId(String name) {
                if ("iron_block".equals(name))    return IRON;
                if ("gold_block".equals(name))    return GOLD;
                if ("diamond_block".equals(name)) return DIAMOND;
                if ("emerald_block".equals(name)) return EMERALD;
                return 0;
            }
        };
        BeaconLogic.resetForTests();
        BeaconLogic.VALID_PYRAMID_BLOCKS.clear();
        BeaconLogic.VALID_PYRAMID_BLOCKS.add(IRON);
        BeaconLogic.VALID_PYRAMID_BLOCKS.add(GOLD);
        BeaconLogic.VALID_PYRAMID_BLOCKS.add(DIAMOND);
        BeaconLogic.VALID_PYRAMID_BLOCKS.add(EMERALD);

        java.util.HashMap<Long, Integer> blockIds = new java.util.HashMap<>();
        world = new World(64) {
            @Override
            public int getVoxel(int x, int y, int z) {
                Integer v = blockIds.get(key(x, y, z));
                return v == null ? 0 : v;
            }
        };
        // Stash the local map as an attached instance so the test methods
        // can mutate it.
        this.blockIds = blockIds;
    }

    private java.util.HashMap<Long, Integer> blockIds;
    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private void fillPyramid(int beaconX, int beaconY, int beaconZ, int level) {
        // Clear any prior blocks in the test region.
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++)
                for (int dy = -8; dy <= 8; dy++)
                    blockIds.remove(key(beaconX + dx, beaconY + dy, beaconZ + dz));
        for (int tier = 1; tier <= level; tier++) {
            int size = 1 + 2 * tier;
            int y0 = beaconY - tier;
            int x0 = beaconX - (size - 1) / 2;
            int z0 = beaconZ - (size - 1) / 2;
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    blockIds.put(key(x0 + dx, y0, z0 + dz), tier % 2 == 0 ? IRON : GOLD);
                }
            }
        }
    }

    @Test
    public void noPyramidReturnsLevelZero() {
        int level = BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0);
        assertEquals(0, level);
    }

    @Test
    public void levelOneDetected() {
        fillPyramid(0, 64, 0, 1);
        assertEquals(1, BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0));
    }

    @Test
    public void levelThreeDetected() {
        fillPyramid(0, 64, 0, 3);
        assertEquals(3, BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0));
    }

    @Test
    public void levelFourDetected() {
        fillPyramid(0, 64, 0, 4);
        assertEquals(4, BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0));
    }

    @Test
    public void missingTierDowngradesToLowerLevel() {
        // Build tier 3 but pull one block out of tier 2: detection should fall
        // back to 1 (only tier 1 is intact).
        fillPyramid(0, 64, 0, 3);
        // Remove the centre tier-2 block.
        blockIds.remove(key(0, 64 - 2, 0));
        assertEquals("missing tier-2 block falls back to tier 1",
                1, BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0));
    }

    @Test
    public void invalidMetalDoesNotCount() {
        // Build a tier-1 pyramid but replace the centre block with stone.
        fillPyramid(0, 64, 0, 1);
        blockIds.put(key(0, 64 - 1, 0), 2); // stone
        assertEquals(0, BeaconLogic.pyramidLevel(world, bdm, 0, 64, 0));
    }

    @Test
    public void buffRadiusScalesWithLevel() {
        assertEquals(20f, BeaconLogic.buffRadius(1), 0.01f);
        assertEquals(30f, BeaconLogic.buffRadius(2), 0.01f);
        assertEquals(40f, BeaconLogic.buffRadius(3), 0.01f);
        assertEquals(50f, BeaconLogic.buffRadius(4), 0.01f);
    }

    @Test
    public void buffDurationScalesWithLevel() {
        assertEquals(9f,  BeaconLogic.buffDurationSeconds(1), 0.01f);
        assertEquals(11f, BeaconLogic.buffDurationSeconds(2), 0.01f);
        assertEquals(13f, BeaconLogic.buffDurationSeconds(3), 0.01f);
        assertEquals(15f, BeaconLogic.buffDurationSeconds(4), 0.01f);
    }
}