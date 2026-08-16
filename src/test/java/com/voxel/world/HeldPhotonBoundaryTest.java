package com.voxel.world;

import com.voxel.World;
import com.voxel.lighting.LightEngine;
import com.voxel.utils.BlockDataManager;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;

import static org.junit.Assert.assertTrue;

/**
 * Regression test for held light photons: light from a loaded chunk must wait
 * at an unloaded chunk boundary and continue propagating once that chunk loads
 * (no chunk-boundary light seams). Covers both block light (torch) and sky
 * light (horizontal fan BFS).
 */
public class HeldPhotonBoundaryTest {

    private static final class Harness implements AutoCloseable {
        final World world;
        final ChunkManager chunkManager;
        final LightEngine lightEngine;

        Harness() throws Exception {
            world = new World(128);
            BlockDataManager bdm = stubBdm();
            WorldSaveManager saveManager = new WorldSaveManager(
                    System.getProperty("java.io.tmpdir") + "/voxel-held-photon-" + System.nanoTime());
            WorldGenerator gen = new WorldGenerator(2L, bdm) {
                @Override
                public int populateSection(int cx, int cy, int cz, World w, int slot) {
                    return 0; // all-air
                }
            };
            lightEngine = new LightEngine(world, bdm);
            chunkManager = new ChunkManager(world, gen, lightEngine, 4, saveManager,
                    DimensionType.OVERWORLD, null, bdm);
        }

        void ready(float px, float py, float pz) throws InterruptedException {
            chunkManager.update(new Vector3f(px, py, pz), 0f);
            long deadline = System.currentTimeMillis() + 30_000;
            int secX = ((int) px) >> 4, secY = ((int) py) >> 4, secZ = ((int) pz) >> 4;
            while (System.currentTimeMillis() < deadline
                    && !chunkManager.isPlayerSectionGenerated(secX, secY, secZ)) {
                Thread.sleep(10);
            }
            long settle = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < settle) {
                if (world.getOffsetX() != 0 || world.getOffsetY() != 0 || world.getOffsetZ() != 0) break;
                Thread.sleep(10);
            }
        }

        void place(int x, int y, int z, int block) {
            chunkManager.setVoxel(x, y, z, block);
        }

        /** Runs the light engine synchronously on the loaded columns (as the light thread would). */
        void relight() throws Exception {
            Map<Long, NavigableMap<Integer, Integer>> columns = new HashMap<>();
            for (Map.Entry<Long, NavigableMap<Integer, Integer>> e : chunkManager.getLoadedChunks().entrySet()) {
                columns.put(e.getKey(), e.getValue());
            }
            lightEngine.propagateBlockLightRegion(columns);
            for (Map.Entry<Long, NavigableMap<Integer, Integer>> e : columns.entrySet()) {
                int cx = (int) (e.getKey() >> 32);
                int cz = e.getKey().intValue();
                lightEngine.generateSkyLight(cx, cz, e.getValue());
            }
        }

        @Override
        public void close() {
            chunkManager.shutdown();
        }
    }

    /** Sum of the three block-light channels at a world position (0 when unloaded). */
    private static int blockLightSum(World w, int x, int y, int z) {
        int slot = w.getChunkSlot(x, y, z);
        if (slot == World.EMPTY) return 0;
        int lx = x & 15, ly = y & 15, lz = z & 15;
        int raw = w.getLightPool()[(slot << 12) | (lx | (ly << 4) | (lz << 8))];
        return ((raw >>> 8) & 0xFF) + ((raw >>> 16) & 0xFF) + ((raw >>> 24) & 0xFF);
    }

    @Test
    public void torchLightCrossesBoundaryWhenNeighborLoads() throws Exception {
        try (Harness h = new Harness()) {
            h.ready(8.5f, 68.5f, 8.5f);

            // update() queued the full render-distance stream on the gen worker.
            // The harness never calls requestLookAhead, so once that batch drains
            // the loaded region is STATIC — wait for it to settle, then the
            // boundary is stable for the whole test.
            long settleDeadline = System.currentTimeMillis() + 60_000;
            int lastSize = -1;
            long stableSince = System.currentTimeMillis();
            while (System.currentTimeMillis() < settleDeadline) {
                int size = h.chunkManager.getLoadedChunks().size();
                if (size == lastSize) {
                    if (System.currentTimeMillis() - stableSince > 2000) break;
                } else {
                    lastSize = size;
                    stableSince = System.currentTimeMillis();
                }
                Thread.sleep(25);
            }
            assertTrue("render-distance stream should settle (loaded " + lastSize + " columns)", lastSize > 0);

            // Find the boundary: the highest loaded X column with a section at y=68.
            int cxMax = -100;
            for (int cx = -64; cx <= 64; cx++) {
                if (h.world.getChunkSlot(cx << 4, 68, 15) != World.EMPTY) cxMax = cx;
            }
            assertTrue("expected at least the player column loaded", cxMax >= 0);

            int boundaryX = (cxMax << 4) + 15; // last cell of the loaded column
            int probeX = (cxMax << 4) + 17;    // 2 cells into the unloaded neighbor
            assertTrue("neighbor column must be unloaded",
                    h.world.getChunkSlot((cxMax + 1) << 4, 68, 15) == World.EMPTY);

            // Torch at the very last cell of the loaded column, aimed at the
            // unloaded neighbor.
            h.place(boundaryX, 68, 15, 211);
            assertTrue("torch placement must stick", h.world.getVoxel(boundaryX, 68, 15) == 211);
            h.relight();

            // The floods must have parked photons at the unloaded boundary.
            assertTrue("block photons should park at the unloaded boundary",
                    h.lightEngine.heldPhotonCount() > 0);
            assertTrue("sky photons should park at the unloaded boundary",
                    h.lightEngine.heldSkyPhotonCount() > 0);
            assertTrue("unloaded cell must read as dark before load",
                    blockLightSum(h.world, probeX, 68, 15) == 0);

            // Move the player into the neighbor column: ensure3x3x3Loaded loads
            // it, and the light task resumes the held photons.
            h.chunkManager.update(new Vector3f(((cxMax + 1) << 4) + 8.5f, 68.5f, 15.5f), 0f);
            long lightDeadline = System.currentTimeMillis() + 20_000;
            int got = 0;
            while (System.currentTimeMillis() < lightDeadline) {
                got = blockLightSum(h.world, probeX, 68, 15);
                if (got > 0) break;
                Thread.sleep(50);
            }
            assertTrue("torch light must appear in the later-loaded chunk at (" + probeX + ",68,15), got " + got,
                    got > 0);
        }
    }

    private static BlockDataManager stubBdm() {
        return new BlockDataManager() {
            @Override public boolean isFullBlock(int id) { return id > 0 && id != 211 && id != 41; }
            @Override public boolean isLiquid(int id) { return false; }
            @Override public int getEmissiveFast(int id) {
                if (id == 17) return 255;
                if (id == 211) return 204; // torch
                return 0;
            }
            @Override public int[] getEmissiveArray() {
                int[] a = new int[512];
                a[17] = 255; a[211] = 204;
                return a;
            }
            @Override public int getLightColorFast(int id) {
                if (id == 17) return 0xFFDC96;   // glowstone amber
                if (id == 211) return 0xFFDC8C;  // torch amber
                return 0xFFFFFF;
            }
            @Override public int[] getLightColorArray() {
                int[] a = new int[512];
                java.util.Arrays.fill(a, 0xFFFFFF);
                a[17] = 0xFFDC96; a[211] = 0xFFDC8C;
                return a;
            }
            @Override public int getOpacityFast(int id) { return id > 0 && id != 211 ? 16 : 0; }
            @Override public int getOpacity(int id) { return getOpacityFast(id); }
            @Override public String getName(int id) { return id == 17 ? "glowstone" : id == 211 ? "torch" : String.valueOf(id); }
        };
    }
}
