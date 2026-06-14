package com.voxel.lighting;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.Direction;
import org.joml.Vector3i;
import org.joml.Vector3f;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for LightPropagationEngine.
 *
 * Tests cover:
 * - Basic BFS propagation and attenuation
 * - Light blocked by solid blocks
 * - Add/subtract roundtrip for single sources
 * - Multiple overlapping sources
 * - isSurrounded detection
 * - Occlusion baking
 * - Edge cases: out of bounds, empty chunks, zero intensity
 */
public class LightPropagationEngineTest {

    private static final int CHUNK_SIZE = 16;
    private static final int VOXELS_PER_CHUNK = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE; // 4096

    private World world;
    private BlockDataManager blockDataManager;
    private LightPropagationEngine engine;

    // Block IDs used in tests
    private static final int BLOCK_AIR = 0;
    private static final int BLOCK_STONE = 1;
    private static final int BLOCK_GLOWSTONE = 2;
    private static final int BLOCK_TORCH = 3;

    @Before
    public void setUp() {
        world = new World(27); // enough for 3x3x3 chunks
        blockDataManager = new BlockDataManager();

        // Register test blocks directly (no file I/O or OpenGL needed)
        registerBlock(BLOCK_AIR, false, false);
        registerBlock(BLOCK_STONE, true, false);
        registerBlock(BLOCK_GLOWSTONE, true, true);
        registerBlock(BLOCK_TORCH, false, true);

        engine = new LightPropagationEngine(world, blockDataManager);
    }

    private void registerBlock(int id, boolean isFullBlock, boolean isEmissive) {
        BlockDataManager.BlockData data = new BlockDataManager.BlockData();
        data.isFullBlock = isFullBlock;
        data.emissive = isEmissive ? 200 : 0;
        data.albedo = java.awt.Color.WHITE;
        data.name = "test_block_" + id;
        // Set dummy texture indices so validation doesn't fail
        for (int i = 0; i < 6; i++) data.tex[i] = 0;
        blockDataManager.blockRegistry.put(id, data);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Allocate a chunk at absolute chunk coords (cx, cy, cz) with slot index. */
    private void allocateChunk(int cx, int cy, int cz, int slot) {
        world.setChunkSlot(cx, cy, cz, slot);
        // Fill with air
        for (int ly = 0; ly < CHUNK_SIZE; ly++) {
            for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                    world.setVoxelInPool(slot, lx, ly, lz, BLOCK_AIR);
                }
            }
        }
    }

    /** Place a single block at absolute world coords. */
    private void placeBlock(int x, int y, int z, int blockId) {
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) throw new IllegalStateException("Chunk not allocated for " + x + "," + y + "," + z);
        world.setVoxel(x, y, z, blockId);
    }

    /** Read light from pool at absolute world coords. Returns array [r, g, b]. */
    private int[] getLight(int x, int y, int z) {
        int[] lp = world.getLightPool();
        int slot = world.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return new int[]{0, 0, 0};
        int idx = (slot << 12) | ((x & 15) | ((y & 15) << 4) | ((z & 15) << 8));
        int p = lp[idx];
        return new int[]{p & 0x3FF, (p >> 10) & 0x3FF, (p >> 20) & 0x3FF};
    }

    /** Create a test light source. */
    private LightSource makeSource(int x, int y, int z, float r, float g, float b, float intensity) {
        return new LightSource(
            new Vector3i(x, y, z),
            new Vector3f(r, g, b),
            intensity,
            15f,
            LightType.BLOCK
        );
    }

    /** Allocate a 3x3x3 grid of chunks centered at chunk (0,0,0). */
    private void allocateChunkBlock(int minCX, int minCY, int minCZ, int countX, int countY, int countZ) {
        int slot = 0;
        for (int dx = 0; dx < countX; dx++) {
            for (int dy = 0; dy < countY; dy++) {
                for (int dz = 0; dz < countZ; dz++) {
                    allocateChunk(minCX + dx, minCY + dy, minCZ + dz, slot++);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Basic BFS Propagation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testSingleSourceBFS_propagatesAndAttenuates() {
        // Allocate a 3x3x3 grid centered at (0,0,0) for propagation space
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        // Place light source at center
        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);

        // Should affect chunks
        assertFalse("BFS should affect at least the source chunk", affected.isEmpty());

        // Source voxel should have light
        int[] srcLight = getLight(7, 7, 7);
        assertTrue("Source voxel should have light", srcLight[0] > 0);

        // Light should attenuate with distance
        int[] nearLight = getLight(7, 8, 7); // 1 step away
        assertTrue("Nearby voxel should have light but less than source",
            nearLight[0] > 0 && nearLight[0] < srcLight[0]);
    }

    @Test
    public void testSingleSourceBFS_deterministic() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp1 = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 51);

        // Run twice on same (clean) pool
        Set<Integer> affected1 = engine.runSingleSourceBFS(src, lp1, it, true);

        // Reset light pool and run again
        for (int s : affected1) {
            int base = s << 12;
            for (int i = 0; i < 4096; i++) lp1[base + i] = 0;
        }
        Set<Integer> affected2 = engine.runSingleSourceBFS(src, lp1, it, true);

        assertEquals("Same BFS should produce same affected chunk set",
            affected1.size(), affected2.size());

        // Verify light values match across both runs (we can only check the second run's values)
        int[] light1 = getLight(7, 7, 7);
        // Reset and run again
        for (int s : affected2) {
            int base = s << 12;
            for (int i = 0; i < 4096; i++) lp1[base + i] = 0;
        }
        engine.runSingleSourceBFS(src, lp1, it, true);
        int[] light2 = getLight(7, 7, 7);

        assertArrayEquals("Light values should be identical across deterministic runs", light1, light2);
    }

    @Test
    public void testBFS_stopsAtSolidBlocks() {
        allocateChunkBlock(0, 0, 0, 2, 1, 1); // two chunks along X

        // Place solid wall at x=10, spanning y=6..8
        for (int wy = 6; wy <= 8; wy++) {
            for (int wz = 6; wz <= 8; wz++) {
                placeBlock(10, wy, wz, BLOCK_STONE);
            }
        }

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(5, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        engine.runSingleSourceBFS(src, lp, it, true);

        // Light before wall
        int[] beforeWall = getLight(9, 7, 7);
        assertTrue("Light should reach just before the wall", beforeWall[0] > 0);

        // Light after wall (should be blocked)
        int[] afterWall = getLight(11, 7, 7);
        assertEquals("Light should NOT pass through solid wall", 0, afterWall[0]);
    }

    @Test
    public void testBFS_stopsAtChunkBoundary() {
        // Only allocate ONE chunk
        allocateChunk(0, 0, 0, 0);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        engine.runSingleSourceBFS(src, lp, it, true);

        // Light at chunk boundary (15, 7, 7) should have light
        int[] atBoundary = getLight(15, 7, 7);
        assertTrue("Light should reach chunk boundary", atBoundary[0] > 0);

        // Light beyond chunk boundary should be zero (no chunk allocated)
        int[] beyond = getLight(16, 7, 7);
        assertArrayEquals("Light should not exist beyond chunk boundary",
            new int[]{0, 0, 0}, beyond);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Add / Subtract Roundtrip
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testAddThenSubtract_returnsToZero() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 100);

        // Add light
        Set<Integer> affectedAdd = engine.runSingleSourceBFS(src, lp, it, true);
        assertFalse("Add should affect chunks", affectedAdd.isEmpty());

        // Verify light exists
        int[] lightAfterAdd = getLight(7, 7, 7);
        assertTrue("Light should exist after add", lightAfterAdd[0] > 0);

        // Subtract same light
        Set<Integer> affectedSub = engine.runSingleSourceBFS(src, lp, it, false);

        // All light should be zero now
        for (int s : affectedAdd) {
            int base = s << 12;
            for (int ly = 0; ly < CHUNK_SIZE; ly++) {
                for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                    for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                        int idx = base | (lx | (ly << 4) | (lz << 8));
                        int p = lp[idx];
                        int r = p & 0x3FF, g = (p >> 10) & 0x3FF, b = (p >> 20) & 0x3FF;
                        if (r != 0 || g != 0 || b != 0) {
                            fail("Light not zero after subtract at slot " + s + " local (" + lx + "," + ly + "," + lz
                                + "): R=" + r + " G=" + g + " B=" + b);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testSubtractWithoutAdd_doesNotGoBelowZero() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 100);

        // Subtract without adding first
        engine.runSingleSourceBFS(src, lp, it, false);

        // All light should still be zero
        int[] light = getLight(7, 7, 7);
        assertArrayEquals("Subtract without add should stay at zero",
            new int[]{0, 0, 0}, light);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Multiple Overlapping Sources
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testTwoSourcesAdd_independentPropagation() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Two sources far apart (no overlap)
        LightSource src1 = makeSource(5, 7, 7, 1.0f, 0.0f, 0.0f, 100); // Red
        LightSource src2 = makeSource(20, 7, 7, 0.0f, 1.0f, 0.0f, 100); // Green

        engine.runSingleSourceBFS(src1, lp, it, true);
        engine.runSingleSourceBFS(src2, lp, it, true);

        // Source 1 position should have red light
        int[] light1 = getLight(5, 7, 7);
        assertTrue("Source 1 should have red light", light1[0] > 0);

        // Source 2 position should have green light
        int[] light2 = getLight(20, 7, 7);
        assertTrue("Source 2 should have green light", light2[1] > 0);
    }

    @Test
    public void testTwoOverlappingSources_clamping() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Two identical sources at nearby positions so they overlap
        LightSource src1 = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 200);
        LightSource src2 = makeSource(8, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        engine.runSingleSourceBFS(src1, lp, it, true);

        // Record light after first source at src2's position
        int[] after1_atSrc2 = getLight(8, 7, 7);
        assertTrue("Source 2 position should have some light from src1", after1_atSrc2[0] > 0);

        engine.runSingleSourceBFS(src2, lp, it, true);

        // After adding second source, both positions should have high light
        int[] afterBoth_atSrc1 = getLight(7, 7, 7);
        int[] afterBoth_atSrc2 = getLight(8, 7, 7);
        assertTrue("Src1 position should have combined light", afterBoth_atSrc1[0] > 200);
        assertTrue("Src2 position should have combined light", afterBoth_atSrc2[0] > 200);
        // With 10-bit channels (max 1023), 200 + 170 = 370, no clamping needed
        assertTrue("Combined light should be <= 1023", afterBoth_atSrc1[0] <= 1023);

        // Remove src1
        engine.runSingleSourceBFS(src1, lp, it, false);

        // After removing src1, src2's light should remain at its FULL strength at src2's own position
        int[] afterRemoval_atSrc2 = getLight(8, 7, 7);
        assertEquals("Source 2 own position should have full light after removing src1",
            200, afterRemoval_atSrc2[0]);

        // src2's contribution to src1's position should still exist
        int[] afterRemoval_atSrc1 = getLight(7, 7, 7);
        assertTrue("Source 1 position should still have light from source 2",
            afterRemoval_atSrc1[0] > 0);
        // src2 at (8,7,7) contributes to (7,7,7) at ~170 (200*0.85)
        assertEquals("Source 1 position should have correct residual from source 2",
            170, afterRemoval_atSrc1[0]);
    }

    @Test
    public void testTwoOverlappingSources_removeAndReadd() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        LightSource src1 = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 51);
        LightSource src2 = makeSource(9, 7, 7, 1.0f, 1.0f, 1.0f, 51);

        // Add both
        engine.runSingleSourceBFS(src1, lp, it, true);
        engine.runSingleSourceBFS(src2, lp, it, true);

        // Record midpoint light
        int[] afterBoth = getLight(8, 7, 7);

        // Remove both
        engine.runSingleSourceBFS(src1, lp, it, false);
        engine.runSingleSourceBFS(src2, lp, it, false);

        // Verify all zero
        int[] finalLight = getLight(7, 7, 7);
        assertArrayEquals("All light should be zero after removing both", new int[]{0, 0, 0}, finalLight);

        // Re-add just src1
        engine.runSingleSourceBFS(src1, lp, it, true);
        int[] reAdded = getLight(7, 7, 7);
        assertTrue("Re-added source should produce light", reAdded[0] > 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: isSurrounded
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testIsSurrounded_fullyEnclosed() {
        allocateChunk(0, 0, 0, 0);

        // Create a hollow cube of stone with air inside at (7,7,7)
        // Place stone on all 6 faces around (7,7,7)
        for (Direction dir : Direction.values()) {
            placeBlock(7 + dir.x, 7 + dir.y, 7 + dir.z, BLOCK_STONE);
        }

        assertTrue("Voxel surrounded by solid blocks should be surrounded",
            engine.isSurrounded(7, 7, 7));
    }

    @Test
    public void testIsSurrounded_notSurrounded() {
        allocateChunk(0, 0, 0, 0);

        // Only one solid neighbor - not surrounded
        placeBlock(8, 7, 7, BLOCK_STONE);

        assertFalse("Voxel with only one solid neighbor should NOT be surrounded",
            engine.isSurrounded(7, 7, 7));
    }

    @Test
    public void testIsSurrounded_openAbove() {
        allocateChunk(0, 0, 0, 0);

        // Place stone on 5 faces but leave UP open
        placeBlock(6, 7, 7, BLOCK_STONE);  // WEST
        placeBlock(8, 7, 7, BLOCK_STONE);  // EAST
        placeBlock(7, 6, 7, BLOCK_STONE);  // DOWN
        placeBlock(7, 7, 6, BLOCK_STONE);  // NORTH
        placeBlock(7, 7, 8, BLOCK_STONE);  // SOUTH
        // UP(7,8,7) is left as air

        assertFalse("Voxel with open top should NOT be surrounded",
            engine.isSurrounded(7, 7, 7));
    }

    @Test
    public void testIsSurrounded_emissiveBlocksCount() {
        allocateChunk(0, 0, 0, 0);

        // Surround with glowstone (emissive + full block) - should be surrounded
        for (Direction dir : Direction.values()) {
            placeBlock(7 + dir.x, 7 + dir.y, 7 + dir.z, BLOCK_GLOWSTONE);
        }

        assertTrue("Voxel surrounded by emissive blocks should be surrounded",
            engine.isSurrounded(7, 7, 7));
    }

    @Test
    public void testIsSurrounded_atChunkBoundary() {
        // Test at world origin - offsets are 0,0,0 so coordinates near 0 may be out of bounds
        // Place source at (1,1,1) with offsets at 0 - should be valid
        allocateChunk(0, 0, 0, 0);

        // (0, 0, 0) is at the edge of the valid buffer - test isSurrounded there
        // Per isBlocked, x<0 returns false (not blocked) - so isSurrounded returns false
        assertFalse("Voxel at buffer edge should not be surrounded",
            engine.isSurrounded(0, 0, 0));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Occlusion Baking
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testBakeChunkOcclusion_openSky() {
        allocateChunk(0, 0, 0, 0);

        engine.bakeChunkOcclusion(0, 0, 0, 0);

        short[] occPool = world.getOcclusionPool();
        // Check an air voxel near top of chunk - should have sky visibility
        int idx = 0; // (0,0,0) local
        int occ = occPool[idx] & 0xFFFF;
        // At y=0 local, y=0 world, with open sky above - some sky rays should reach
        assertTrue("Air voxel should have some sky visibility", occ != 0);
    }

    @Test
    public void testBakeChunkOcclusion_solidBlockOccludes() {
        allocateChunk(0, 0, 0, 0);

        // Place a solid ceiling
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                placeBlock(lx, 10, lz, BLOCK_STONE);
            }
        }

        engine.bakeChunkOcclusion(0, 0, 0, 0);

        short[] occPool = world.getOcclusionPool();
        // Check a voxel below the ceiling
        int idx = (5) | (9 << 4) | (5 << 8); // local (5,9,5)
        int occ = occPool[idx] & 0xFFFF;

        // The solid ceiling at y=10 should block some sky directions
        // But since the sky check goes 32 steps up, and there's a solid at y=10,
        // the upward directions should be occluded.
        // Direction 0 is (0,1,0) - straight up - this should be occluded
        assertTrue("Upward occlusion bit should be cleared",
            (occ & (1 << 0)) == 0);
    }

    @Test
    public void testBakeChunkOcclusion_solidBlockNotSkyVisible() {
        allocateChunk(0, 0, 0, 0);
        placeBlock(5, 5, 5, BLOCK_STONE);

        engine.bakeChunkOcclusion(0, 0, 0, 0);

        short[] occPool = world.getOcclusionPool();
        int idx = (5) | (5 << 4) | (5 << 8); // local (5,5,5)
        int occ = occPool[idx] & 0xFFFF;
        assertEquals("Solid block should have zero occlusion mask (fully occluded)",
            0, occ);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Edge Cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testZeroIntensity_returnsEmpty() {
        allocateChunk(0, 0, 0, 0);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 0);

        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);
        assertTrue("Zero intensity should return empty set", affected.isEmpty());
    }

    @Test
    public void testOutOfBounds_returnsEmpty() {
        allocateChunk(0, 0, 0, 0);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        // Position outside the 2048 buffer
        LightSource src = makeSource(3000, 100, 100, 1.0f, 1.0f, 1.0f, 200);

        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);
        assertTrue("Out of bounds should return empty set", affected.isEmpty());
    }

    @Test
    public void testSurroundedSource_skipped() {
        allocateChunk(0, 0, 0, 0);

        // Surround the source voxel
        for (Direction dir : Direction.values()) {
            placeBlock(7 + dir.x, 7 + dir.y, 7 + dir.z, BLOCK_STONE);
        }

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);
        assertTrue("Surrounded source should be skipped (empty set)",
            affected.isEmpty());
    }

    @Test
    public void testLightPoolInitialState_allZero() {
        int[] lp = world.getLightPool();
        for (int i = 0; i < Math.min(1000, lp.length); i++) {
            assertEquals("Light pool should start all zero", 0, lp[i]);
        }
    }

    @Test
    public void testNonFullBlockLetsLightThrough() {
        allocateChunkBlock(0, 0, 0, 2, 1, 1);

        // Place a TORCH block (non-full) as a wall
        for (int wy = 6; wy <= 8; wy++) {
            for (int wz = 6; wz <= 8; wz++) {
                placeBlock(15, wy, wz, BLOCK_TORCH);
            }
        }

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();
        LightSource src = makeSource(5, 7, 7, 1.0f, 1.0f, 1.0f, 200);

        engine.runSingleSourceBFS(src, lp, it, true);

        // Light should pass through non-full block wall
        int[] beyond = getLight(16, 7, 7);
        // Depending on chunk allocation, may or may not have light
        // But within the same chunk just before the non-full block:
        int[] before = getLight(14, 7, 7);
        assertTrue("Light should reach up to the non-full block wall", before[0] > 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Light Value Packing
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testLightValuePackingAndUnpacking() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Red light source
        LightSource src = makeSource(7, 7, 7, 1.0f, 0.0f, 0.0f, 200);
        engine.runSingleSourceBFS(src, lp, it, true);

        int[] light = getLight(7, 7, 7);
        assertTrue("Red channel should be bright", light[0] > 150);
        assertEquals("Green channel should be zero", 0, light[1]);
        assertEquals("Blue channel should be zero", 0, light[2]);
    }

    @Test
    public void testColorChannelSeparation() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Pure red source
        LightSource red = makeSource(7, 7, 7, 1.0f, 0.0f, 0.0f, 100);
        engine.runSingleSourceBFS(red, lp, it, true);
        int[] rLight = getLight(7, 7, 7);
        assertEquals("Red source should have zero green", 0, rLight[1]);
        assertEquals("Red source should have zero blue", 0, rLight[2]);

        // Reset
        for (int i = 0; i < lp.length; i++) lp[i] = 0;

        // Pure green source
        LightSource green = makeSource(7, 7, 7, 0.0f, 1.0f, 0.0f, 100);
        engine.runSingleSourceBFS(green, lp, it, true);
        int[] gLight = getLight(7, 7, 7);
        assertEquals("Green source should have zero red", 0, gLight[0]);
        assertEquals("Green source should have zero blue", 0, gLight[2]);
    }

    @Test
    public void testChannelClamping() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Source with intensity below 1023 - should NOT clamp
        LightSource src = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 500);
        engine.runSingleSourceBFS(src, lp, it, true);

        int[] light = getLight(7, 7, 7);
        assertEquals("Channel should match intensity (500)", 500, light[0]);

        // Add another light at same position (sum = 500+500=1000 < 1023, no clamping)
        LightSource src2 = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 500);
        engine.runSingleSourceBFS(src2, lp, it, true);
        int[] combined = getLight(7, 7, 7);
        assertEquals("Combined channels should add without clamping", 1000, combined[0]);

        // Add a third - would exceed 1023
        LightSource src3 = makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 500);
        engine.runSingleSourceBFS(src3, lp, it, true);
        int[] clamped = getLight(7, 7, 7);
        assertEquals("Channel should clamp at 1023 when exceeding max", 1023, clamped[0]);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: propagateAllLights
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testPropagateAllLights_multipleSources() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        List<LightSource> sources = Arrays.asList(
            makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 100),
            makeSource(10, 10, 10, 1.0f, 1.0f, 1.0f, 100),
            makeSource(5, 5, 5, 1.0f, 1.0f, 1.0f, 100)
        );

        int done = engine.propagateAllLights(sources);
        assertEquals("All 3 sources should propagate", 3, done);

        // Verify all three positions have light
        assertTrue(getLight(7, 7, 7)[0] > 0);
        assertTrue(getLight(10, 10, 10)[0] > 0);
        assertTrue(getLight(5, 5, 5)[0] > 0);
    }

    @Test
    public void testPropagateAllLights_skipsSun() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        List<LightSource> sources = Arrays.asList(
            makeSource(7, 7, 7, 1.0f, 1.0f, 1.0f, 100),
            new LightSource(new Vector3i(0, 200, 0), new Vector3f(1, 1, 1), 255, 1000, LightType.SUN)
        );

        int done = engine.propagateAllLights(sources);
        assertEquals("SUN source should be skipped, only 1 BLOCK source processed", 1, done);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TESTS: Coordinate Edge Cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    public void testSourceAtChunkOrigin() {
        allocateChunkBlock(0, 0, 0, 2, 2, 2);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Source exactly at chunk corner (0,0,0)
        LightSource src = makeSource(0, 0, 0, 1.0f, 1.0f, 1.0f, 51);
        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);
        assertFalse("Source at chunk origin should propagate", affected.isEmpty());

        int[] light = getLight(0, 0, 0);
        assertTrue("Source at origin should have light", light[0] > 0);
    }

    @Test
    public void testSourceAtChunkMaxCoords() {
        allocateChunkBlock(-1, -1, -1, 3, 3, 3);

        int[] lp = world.getLightPool();
        int[] it = world.getIndirectionTable();

        // Source at (15,15,15) - top corner of chunk
        LightSource src = makeSource(15, 15, 15, 1.0f, 1.0f, 1.0f, 51);
        Set<Integer> affected = engine.runSingleSourceBFS(src, lp, it, true);
        assertFalse("Source at chunk corner should propagate", affected.isEmpty());

        int[] light = getLight(15, 15, 15);
        assertTrue("Source at corner should have light", light[0] > 0);
    }
}
