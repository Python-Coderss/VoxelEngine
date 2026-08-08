package com.voxel;

import com.voxel.utils.BlockDataManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the "fall through the map" bug: World.getVoxel returns
 * 0 (air) for unloaded chunks, so without a loaded-chunk guard gravity would
 * pull the player straight through terrain that has not generated yet.
 * The fix holds the player at the loaded boundary (soft stop, no fall damage)
 * instead of letting them fall or walk into unloaded space.
 */
public class PlayerFallThroughTest {

    /**
     * Player starts inside the loaded section (cy=1, y 16..31) and falls toward
     * the unloaded section below (cy=0). Gravity must stop them at the boundary
     * (y≈16) instead of letting them plummet through the missing terrain.
     */
    @Test
    public void holdsPlayerAtLoadedBoundaryInsteadOfFallingThrough() {
        World world = new World(1);
        world.setChunkSlot(0, 1, 0, 0); // section cy=1 loaded, cy=0 (below) unloaded

        Player player = new Player(0.5, 20.0, 0.5);

        for (int i = 0; i < 60; i++) {
            player.update(0.05f, world, fullBlockData());
            assertTrue("player fell into the unloaded section on tick " + i,
                    player.getPosition().y >= 15.9f);
        }
        // Hovers just above the boundary: the last pre-step position before the
        // feet would enter block 15 (unloaded). Fixed-point steps are 1/256, so
        // this is 16.0..16.1 rather than exactly 16.0.
        float y = player.getPosition().y;
        assertTrue("player hovered at y=" + y + " (should be just above the boundary)",
                y >= 16.0f && y <= 16.1f);
        assertEquals(0.0, player.getVelocityD().y, 0.0);
    }

    /** The soft stop must not deal fall damage (fallDistance is reset while held). */
    @Test
    public void noFallDamageWhileHeldAtLoadedBoundary() {
        World world = new World(1);
        world.setChunkSlot(0, 1, 0, 0); // cy=1 loaded, cy=0 (below) unloaded

        Player player = new Player(0.5, 20.0, 0.5);
        for (int i = 0; i < 60; i++) {
            player.update(0.05f, world, fullBlockData());
        }
        assertEquals(20.0f, player.getHealth(), 0.001f);
    }

    /** Falling below the buffer bottom (no chunk allocated at all, ry<0 -> EMPTY) is held too. */
    @Test
    public void holdsPlayerAboveTheBottomOfTheBuffer() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0); // only section cy=0 exists

        Player player = new Player(0.5, 5.0, 0.5);
        for (int i = 0; i < 60; i++) {
            player.update(0.05f, world, fullBlockData());
            assertTrue("player fell below the world bottom on tick " + i,
                    player.getPosition().y >= 0.0f);
        }
        assertEquals(0.0, player.getVelocityD().y, 0.0);
    }

    /** Player stands inside the loaded column at x=15 and is pushed toward unloaded chunk x=1. */
    @Test
    public void blocksHorizontalMovementIntoUnloadedChunk() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0); // chunk column x=0 loaded, x=1 unloaded

        Player player = new Player(15.0, 1.0, 0.5);
        player.getVelocityD().x = 5.0; // strong push into the unloaded column

        player.update(0.05f, world, fullBlockData());

        assertEquals(15.0f, player.getPosition().x, 0.001f);
        assertEquals(0.0, player.getVelocityD().x, 0.0);
    }

    /** Sanity check: the guard must NOT disturb normal physics inside loaded chunks. */
    @Test
    public void normalFallingAndLandingStillWorksInLoadedChunk() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        world.setVoxelInPool(0, 0, 0, 0, 1); // ground block at absolute (0,0,0)

        Player player = new Player(0.5, 10.0, 0.5);

        for (int i = 0; i < 40; i++) {
            player.update(0.05f, world, fullBlockData());
        }

        // Fixed-point physics stops at the last pre-collision step (≈1.05), not
        // exactly on the block top — the key assertion is that the player stops
        // above the block and is grounded, instead of falling through.
        float y = player.getPosition().y;
        assertTrue("player landed at y=" + y + " (should be just above the block)",
                y >= 1.0f && y <= 1.2f);
        assertTrue(player.isOnGround());
        assertEquals(0.0, player.getVelocityD().y, 0.0);
    }

    private static BlockDataManager fullBlockData() {
        return new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId == 1;
            }
        };
    }
}
