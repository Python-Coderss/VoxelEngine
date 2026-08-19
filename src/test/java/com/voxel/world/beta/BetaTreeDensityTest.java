package com.voxel.world.beta;

import com.voxel.World;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostic tests for the Beta 1.8.1 dimension:
 *  - biome map caching returns identical results (no drift from memoization)
 *  - tree density per chunk is reasonable in tree-capable biomes
 */
public class BetaTreeDensityTest {

    private static final int WOOD = 17;
    private static final int LEAVES = 18;

    private static BetaChunkProvider makeProvider(long seed) {
        // Classic beta ids (mapToVeBlock identity for the terrain block set).
        return new BetaChunkProvider(seed, new BetaBlocks(
                1, 2, 3, 7,             // stone, grass, dirt, bedrock
                9, 11, 12, 13,          // water, lava, sand, gravel
                24, 79, 78,             // sandstone, ice, snow
                LEAVES, WOOD,           // leaves, wood
                37, 38, 31, 32,         // dandelion, rose, tallgrass, deadbush
                81, 86,                 // cactus, pumpkin
                39, 40, 83, 82,         // mushrooms, reeds, clay
                16, 15, 14, 56, 73, 21, // coal, iron, gold, diamond, redstone, lapis
                4, 48, 54, 52));        // cobble, mossy, chest, spawner
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

    private static int biomeId(BetaBiomeGenBase b) {
        return b.field_35494_y;
    }

    private static String biomeName(int id) {
        BetaBiomeGenBase b = BetaBiomeGenBase.field_35486_a[id];
        return b == null ? "?" : b.biomeName;
    }

    private static boolean isTreeBiome(int b) {
        return b == biomeId(BetaBiomeGenBase.forest) || b == biomeId(BetaBiomeGenBase.rainforest)
                || b == biomeId(BetaBiomeGenBase.seasonalForest) || b == biomeId(BetaBiomeGenBase.taiga)
                || b == biomeId(BetaBiomeGenBase.swampland) || b == biomeId(BetaBiomeGenBase.savanna)
                || b == biomeId(BetaBiomeGenBase.shrubland);
    }

    @Test
    public void biomeMapCacheReturnsIdenticalResults() {
        BetaWorldChunkManager mgr = new BetaWorldChunkManager(999L);
        BetaBiomeGenBase[] first = mgr.loadBlockGeneratorData(null, 0, 0, 16, 16);
        // Second request for the same column must be served from the cache
        // with identical output.
        BetaBiomeGenBase[] second = mgr.loadBlockGeneratorData(null, 0, 0, 16, 16);
        assertArrayEquals(first, second);
        // Single-point lookups (the per-pixel tint-map / HUD path) must match
        // the corresponding cached 16×16 map cell exactly.
        assertEquals(first[5 | (7 << 4)].field_35494_y, mgr.getBiomeGenAt(5, 7).field_35494_y);
        assertEquals(first[15 | (0 << 4)].field_35494_y, mgr.getBiomeGenAt(15, 0).field_35494_y);
        // And a different column is independent (cache keying is correct).
        mgr.loadBlockGeneratorData(null, 16, 0, 16, 16);
        assertEquals(first[0].field_35494_y, mgr.getBiomeGenAt(0, 0).field_35494_y);
    }

    @Test
    public void treesSpawnInForestBiomes() {
        long seed = 12345L;
        BetaChunkProvider provider = makeProvider(seed);

        // Find tree-capable columns cheaply via the cached biome lookup.
        // Sample columns spaced 2 apart in X so each column's tree range
        // (cx*16+8 .. cx*16+23, which straddles the +x chunk border) is
        // counted without overlapping a sibling sample.
        int[] biomeCounts = new int[16];
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
                provider.populateColumn(world, cx, cz);
                int w = countVoxels(world, cx * 16, cz * 16, 32, WOOD);
                int l = countVoxels(world, cx * 16, cz * 16, 32, LEAVES);
                boolean dense = biome == biomeId(BetaBiomeGenBase.forest) || biome == biomeId(BetaBiomeGenBase.rainforest)
                        || biome == biomeId(BetaBiomeGenBase.taiga) || biome == biomeId(BetaBiomeGenBase.seasonalForest);
                if (dense) { denseWood += w; denseLeaves += l; denseColumns++; }
                else { otherWood += w; otherLeaves += l; otherColumns++; }
                report.append("col(").append(cx).append(',').append(cz)
                        .append(") biome=").append(biomeName(biome))
                        .append(" wood=").append(w).append(" leaves=").append(l).append('\n');
            }
        }
        System.out.println("=== Seed 12345, Beta 1.8.1 landscape ===");
        System.out.println("Biome distribution over 64×64 chunk scan (stride 2):");
        for (int b = 0; b < 16; b++) {
            if (biomeCounts[b] > 0) System.out.println("  " + biomeName(b) + "=" + biomeCounts[b]);
        }
        System.out.println("Dense-forest columns sampled: " + denseColumns + " wood=" + denseWood + " leaves=" + denseLeaves);
        System.out.println("Other tree-capable columns: " + otherColumns + " wood=" + otherWood + " leaves=" + otherLeaves);
        System.out.println(report);

        assertTrue("found tree-capable columns to sample (got " + found + ")", found >= 6);
        // Forest/taiga/rainforest/seasonal must be clearly forested: on average
        // at least ~2 trunks per column (~10 wood voxels).
        assertTrue("dense forest columns should have wood (got " + denseWood + " over " + denseColumns + ")",
                denseColumns >= 2 && denseWood >= denseColumns * 8);
    }
}
