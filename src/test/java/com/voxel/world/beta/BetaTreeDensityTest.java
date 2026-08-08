package com.voxel.world.beta;

import com.voxel.World;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostic tests for the Beta dimensions:
 *  - biome map caching returns identical results (no drift from memoization)
 *  - tree density per chunk is reasonable in tree-capable biomes
 */
public class BetaTreeDensityTest {

    private static final int WOOD = 17;
    private static final int LEAVES = 18;

    private static BetaChunkProvider makeProvider(long seed, BetaNumericProfile profile) {
        int[] snowLevels = {0, 78, 78, 78, 78, 78, 78, 78, 78};
        return new BetaChunkProvider(seed, profile,
                1, 2, 3, 7,             // stone, grass, dirt, bedrock
                9, 11, 12, 13,          // water, lava, sand, gravel
                24, 79, 78, 49,         // sandstone, ice, snow, obsidian
                LEAVES, WOOD,           // leaves, wood
                37, 38, 31, 32,         // dandelion, rose, tallgrass, deadbush
                81, 86,                 // cactus, pumpkin
                14, 15, 16, 56, 73, 21, 89, // ores + glowstone
                83, 82, 4, 48,          // sugarcane, clay, cobble, mossy
                54, 52, snowLevels);    // chest, spawner
    }

    /** Register a 5×5×9 chunk block of slots so world.setVoxel has somewhere to write. */
    private static World makeWorld(int centerCX, int centerCZ) {
        World world = new World(512);
        int slot = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int cy = 0; cy < 9; cy++) {
                    world.setChunkSlot(centerCX + dx, cy, centerCZ + dz, slot++);
                }
            }
        }
        return world;
    }

    private static int countVoxels(World world, int x0, int z0, int size, int wantId) {
        int count = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int y = 0; y < 144; y++) {
                    if (world.getVoxel(x0 + dx, y, z0 + dz) == wantId) count++;
                }
            }
        }
        return count;
    }

    private static boolean isTreeBiome(int b) {
        return b == BetaBiomeGenBase.FOREST || b == BetaBiomeGenBase.RAINFOREST
                || b == BetaBiomeGenBase.SEASONAL_FOREST || b == BetaBiomeGenBase.TAIGA
                || b == BetaBiomeGenBase.SWAMPLAND || b == BetaBiomeGenBase.SAVANNA
                || b == BetaBiomeGenBase.SHRUBLAND || b == BetaBiomeGenBase.PLAINS;
    }

    @Test
    public void biomeMapCacheReturnsIdenticalResults() {
        BetaWorldChunkManager mgr = new BetaWorldChunkManager(999L, BetaNumericProfile.STANDARD_BETA);
        int[] first = mgr.loadBlockGeneratorData(null, 0, 0, 16, 16);
        double[] t1 = mgr.temperature.clone();
        double[] h1 = mgr.humidity.clone();
        // Second request for the same column must be served from the cache
        // with byte-identical output and restored temperature/humidity fields.
        int[] second = mgr.loadBlockGeneratorData(null, 0, 0, 16, 16);
        assertArrayEquals(first, second);
        assertArrayEquals(t1, mgr.temperature, 0.0);
        assertArrayEquals(h1, mgr.humidity, 0.0);
        // Single-point lookups (the per-pixel tint-map / HUD path) must match
        // the corresponding 16×16 map cell exactly.
        assertEquals(first[5 | (7 << 4)], mgr.getBiomeGenAt(5, 7));
        assertEquals(first[15 | (0 << 4)], mgr.getBiomeGenAt(15, 0));
        // And a different column is independent (cache keying is correct).
        mgr.loadBlockGeneratorData(null, 16, 0, 16, 16);
        assertEquals(first[0], mgr.getBiomeGenAt(0, 0));
    }

    @Test
    public void treesSpawnInForestBiomes() {
        for (BetaNumericProfile profile : new BetaNumericProfile[]{BetaNumericProfile.STANDARD_BETA, BetaNumericProfile.DEFAULT}) {
            long seed = 12345L;
            BetaChunkProvider provider = makeProvider(seed, profile);

            // Find tree-capable columns cheaply via the cached biome lookup.
            // Sample columns spaced 2 apart in X so each column's tree range
            // (cx*16+8 .. cx*16+23, which straddles the +x chunk border) is
            // counted without overlapping a sibling sample.
            int[] biomeCounts = new int[13];
            int found = 0;
            int denseWood = 0, denseLeaves = 0, denseColumns = 0;
            int otherWood = 0, otherLeaves = 0, otherColumns = 0;
            StringBuilder report = new StringBuilder();
            outer:
            for (int cz = 0; cz < 64; cz++) {
                for (int cx = 0; cx < 64; cx += 2) {
                    int biome = provider.getBetaBiomeId(cx * 16 + 8, cz * 16 + 8);
                    biomeCounts[biome]++;
                    if (!isTreeBiome(biome)) continue;
                    if (found >= 8) break outer;
                    found++;
                    World world = makeWorld(cx, cz);
                    provider.generateColumn(cx, cz);
                    provider.populateColumn(world, cx, cz);
                    int w = countVoxels(world, cx * 16, cz * 16, 32, WOOD);
                    int l = countVoxels(world, cx * 16, cz * 16, 32, LEAVES);
                    boolean dense = biome == BetaBiomeGenBase.FOREST || biome == BetaBiomeGenBase.RAINFOREST
                            || biome == BetaBiomeGenBase.TAIGA || biome == BetaBiomeGenBase.SEASONAL_FOREST;
                    if (dense) { denseWood += w; denseLeaves += l; denseColumns++; }
                    else { otherWood += w; otherLeaves += l; otherColumns++; }
                    report.append("col(").append(cx).append(',').append(cz)
                            .append(") biome=").append(BetaBiomeGenBase.NAMES[biome])
                            .append(" wood=").append(w).append(" leaves=").append(l).append('\n');
                }
            }
            System.out.println("=== Profile: " + (profile == BetaNumericProfile.STANDARD_BETA ? "STANDARD_BETA (overworld)" : "DEFAULT (error502)") + " ===");
            System.out.println("Biome distribution over 64×64 chunk scan (stride 2):");
            for (int b = 0; b < 13; b++) {
                if (biomeCounts[b] > 0) System.out.println("  " + BetaBiomeGenBase.NAMES[b] + "=" + biomeCounts[b]);
            }
            System.out.println("Dense-forest columns sampled: " + denseColumns + " wood=" + denseWood + " leaves=" + denseLeaves);
            System.out.println("Other tree-capable columns: " + otherColumns + " wood=" + otherWood + " leaves=" + otherLeaves);
            System.out.println(report);

            assertTrue("found tree-capable columns to sample (got " + found + ")", found >= 6);
            // Forest/taiga/rainforest must be clearly forested: on average at
            // least ~2 trunks per column (~10 wood voxels).
            assertTrue("dense forest columns should have wood (got " + denseWood + " over " + denseColumns + ")",
                    denseColumns >= 2 && denseWood >= denseColumns * 8);
        }
    }
}
