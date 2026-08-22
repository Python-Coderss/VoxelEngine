package com.voxel.game;

import com.voxel.World;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * Headless harness for the PAC/cursor {@link BlockInteraction#raycastBlock}.
 *
 * The DDA voxel traversal (Amanatides &amp; Woo) replaced the old fixed-step
 * march. The crucial properties to pin down are: it must hit the first solid
 * cell along the cursor ray, it must not tunnel through a block corner, and
 * fluids must be transparent by default (so blocks behind water/lava stay
 * clickable) while {@code includeFluids=true} (bucket scooping) stops at the
 * fluid itself.
 *
 * None of this needs a live GL context: when {@code ctx.cursorRayOverride} is
 * set, {@code raycastBlock} reads only the override ray and {@code ctx.world}.
 * The world is a stub that returns a controlled solid-block layout, so the
 * traversal can be asserted exactly.
 */
public class BlockInteractionRaycastTest {

    /** Block IDs used by the stub world. */
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 15;
    private static final int LAVA = 21;

    /**
     * A {@link World} whose {@code getVoxel} is driven by a small handcrafted
     * voxel layout, so the DDA traversal can be asserted without a chunk pool
     * or a ChunkManager. Only {@code getVoxel} is exercised by raycastBlock.
     */
    private static final class StubWorld extends World {
        StubWorld() { super(8); }

        @Override
        public int getVoxel(int x, int y, int z) {
            // A 1-block-thick stone wall at x=10 (y,z in 0..15), air elsewhere.
            if (x == 10 && y >= 0 && y < 16 && z >= 0 && z < 16) return STONE;
            // A water column at x=8 (so a +X ray hits water before the wall).
            if (x == 8 && y >= 0 && y < 16 && z >= 0 && z < 16) return WATER;
            // A lava block at x=9, y=0, z=0 only (diagonal aim target).
            if (x == 9 && y == 0 && z == 0) return LAVA;
            return AIR;
        }
    }

    /** Builds a BlockInteraction bound to a stub world + a cursor ray. */
    private static BlockInteraction harness(float ox, float oy, float oz,
                                             float dx, float dy, float dz) {
        GameContext ctx = new GameContext();
        ctx.world = new StubWorld();
        ctx.cursorRayOverride = new float[]{ox, oy, oz, dx, dy, dz};
        return new BlockInteraction(ctx);
    }

    @Test
    public void hitsSolidWallStraightOn() {
        // Origin at (5,5,5), aiming +X. Wall at x=10 → hit (10,5,5), adjacent (9,5,5).
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 1f, 0f, 0f);
        int[] hit = bi.raycastBlock(20.0f);
        assertNotNull("ray should hit the stone wall", hit);
        assertEquals(10, hit[0]);
        assertEquals(5, hit[1]);
        assertEquals(5, hit[2]);
        assertEquals(9, hit[3]); // adjacent (entry face) cell
    }

    @Test
    public void returnsNullWhenRayMissesAllSolids() {
        // Aim +Y straight up from below the wall — no solid above in this stub.
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 0f, 1f, 0f);
        assertNull(bi.raycastBlock(20.0f));
    }

    @Test
    public void doesNotTunnelThroughCorner() {
        // Aim diagonally NE; the wall at x=10 spans all z, so the first solid
        // crossed must be at x=10 with the z the ray reached at that moment.
        // The old fixed-step march could skip a glancing corner hit; the DDA
        // touches every cell boundary in order, so this is the regression guard.
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 1f, 0f, 1f);
        int[] hit = bi.raycastBlock(20.0f);
        assertNotNull(hit);
        assertEquals(10, hit[0]);
        assertEquals("DDA must hit the wall at x=10 regardless of z drift", 10, hit[0]);
    }

    @Test
    public void fluidsAreTransparentByDefault() {
        // +X ray from (5,5,5): water at x=8, wall at x=10. Default must skip
        // the water and return the wall behind it.
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 1f, 0f, 0f);
        int[] hit = bi.raycastBlock(20.0f, false);
        assertNotNull("fluids should be transparent", hit);
        assertEquals(10, hit[0]);
    }

    @Test
    public void includeFluidsStopsAtWater() {
        // Same ray, but bucket-scooping mode must stop at the water itself.
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 1f, 0f, 0f);
        int[] hit = bi.raycastBlock(20.0f, true);
        assertNotNull(hit);
        assertEquals("bucket scoop must target the fluid", 8, hit[0]);
    }

    @Test
    public void includeFluidsStopsAtLava() {
        // Lava-only world (no water in the path) so the bucket stops at lava.
        GameContext ctx = new GameContext();
        ctx.world = new World(8) {
            @Override public int getVoxel(int x, int y, int z) {
                return (x == 9 && y == 0 && z == 0) ? LAVA : AIR;
            }
        };
        ctx.cursorRayOverride = new float[]{5.5f, 0.5f, 0.5f, 1f, 0f, 0f};
        BlockInteraction bi = new BlockInteraction(ctx);
        int[] hit = bi.raycastBlock(20.0f, true);
        assertNotNull(hit);
        assertEquals(9, hit[0]);
        assertEquals(0, hit[1]);
        assertEquals(0, hit[2]);
    }

    @Test
    public void outOfRangeReturnsNull() {
        // Wall at x=10, ray starts at x=5.5 → ~4.5 blocks away. Max dist 3 must miss.
        BlockInteraction bi = harness(5.5f, 5.5f, 5.5f, 1f, 0f, 0f);
        assertNull(bi.raycastBlock(3.0f));
    }
}
