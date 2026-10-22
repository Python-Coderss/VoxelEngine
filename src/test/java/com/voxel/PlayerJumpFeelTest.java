package com.voxel;

import com.voxel.utils.BlockDataManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Response-feel tests for the player's jump:
 *
 * <ul>
 *   <li><b>Coyote time</b> — a jump pressed a couple of ticks after walking off
 *       a ledge still fires.</li>
 *   <li><b>Jump buffering</b> — a press made just before touching down fires on
 *       landing instead of being swallowed.</li>
 *   <li><b>No double jump</b> — buffering must not turn into an air jump.</li>
 *   <li><b>Landing impact</b> — reported once per landing so the camera can
 *       scale its kick by fall height.</li>
 * </ul>
 *
 * The ground is a 16x16 slab at y=0 in chunk column (0,0,0); the player starts
 * above it and is driven with fixed 50 ms ticks, so every assertion is exact.
 */
public class PlayerJumpFeelTest {

    private static final float TICK = 0.05f;

    @Test
    public void jumpStillFiresJustAfterWalkingOffALedge() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        fillGround(world, 1);

        Player player = new Player(8.5, 1.0, 8.5);
        player.update(TICK, world, blocks());          // grounded, arms coyote window
        assertTrue(player.isOnGround());
        assertEquals(10f, player.getCoyoteTimer() * 100f, 0.6f);

        // Remove the block under the player: they are now airborne, but inside
        // the coyote window.
        world.setVoxelInPool(0, 8, 0, 8, 0);
        player.update(TICK, world, blocks());
        assertTrue("player should be airborne", !player.isOnGround());

        player.jump(world, blocks());
        player.update(TICK, world, blocks());

        assertTrue("coyote jump did not fire: vy=" + player.getVelocity().y,
                player.getVelocity().y > 0.2f);
    }

    @Test
    public void jumpPressedJustBeforeLandingFiresOnTouchdown() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        fillGround(world, 1);

        Player player = new Player(8.5, 10.0, 8.5);
        // Fall to one tick above the slab, long after the coyote window died.
        int guard = 0;
        while (player.getPosition().y > 2.0f && guard++ < 100) {
            player.update(TICK, world, blocks());
        }
        assertTrue(player.getPosition().y > 1.0f);
        assertTrue("still airborne before the buffered press", !player.isOnGround());
        assertEquals("coyote window must be closed for this test", 0f,
                player.getCoyoteTimer(), 0f);

        player.jump(world, blocks()); // pressed slightly early
        boolean bounced = false;
        for (int i = 0; i < 4; i++) {
            player.update(TICK, world, blocks());
            if (player.getVelocity().y > 0.2f) bounced = true;
        }
        assertTrue("buffered jump was swallowed on landing", bounced);
    }

    @Test
    public void bufferedPressDoesNotBecomeAnAirJump() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        fillGround(world, 1);

        Player player = new Player(8.5, 1.0, 8.5);
        player.update(TICK, world, blocks());
        player.jump(world, blocks());
        player.update(TICK, world, blocks());           // jump away
        // Wait for the apex.
        int guard = 0;
        while (player.getVelocity().y >= 0f && guard++ < 100) {
            player.update(TICK, world, blocks());
        }

        player.jump(world, blocks());                   // mid-air press
        player.update(TICK, world, blocks());
        assertTrue("mid-air press must not re-jump: vy=" + player.getVelocity().y,
                player.getVelocity().y < 0f);
    }

    @Test
    public void landingImpactIsReportedOnceAndScalesWithFallHeight() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        fillGround(world, 1);

        Player player = new Player(8.5, 8.0, 8.5);
        for (int i = 0; i < 100 && !player.isOnGround(); i++) {
            player.update(TICK, world, blocks());
        }
        assertTrue(player.isOnGround());

        float impact = player.consumeLandingImpact();
        assertTrue("fall impact should be non-trivial: " + impact, impact > 0.4f);
        assertEquals("impact must be consumed by the first read", 0f,
                player.consumeLandingImpact(), 0f);
    }

    @Test
    public void aNormalJumpHasASmallerImpactThanALongFall() {
        World world = new World(1);
        world.setChunkSlot(0, 0, 0, 0);
        fillGround(world, 1);

        Player hop = new Player(8.5, 1.0, 8.5);
        hop.update(TICK, world, blocks());
        hop.jump(world, blocks());
        float hopImpact = 0f;
        for (int i = 0; i < 40; i++) {
            hop.update(TICK, world, blocks());
            if (hop.isOnGround()) {
                hopImpact = hop.consumeLandingImpact();
                break;
            }
        }

        Player drop = new Player(8.5, 8.0, 8.5);
        float dropImpact = 0f;
        for (int i = 0; i < 100; i++) {
            drop.update(TICK, world, blocks());
            if (drop.isOnGround()) {
                dropImpact = drop.consumeLandingImpact();
                break;
            }
        }

        assertTrue("hop should land", hopImpact > 0f);
        assertTrue("drop should land", dropImpact > 0f);
        assertTrue("a long fall must hit harder: hop=" + hopImpact + " drop=" + dropImpact,
                dropImpact > hopImpact * 1.5f);
    }

    private static void fillGround(World world, int y) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.setVoxelInPool(0, x, y - 1, z, 1);
            }
        }
    }

    private static BlockDataManager blocks() {
        return new BlockDataManager() {
            @Override
            public boolean isFullBlock(int blockId) {
                return blockId == 1;
            }
        };
    }
}
