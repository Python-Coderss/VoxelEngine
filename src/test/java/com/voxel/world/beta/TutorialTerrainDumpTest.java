package com.voxel.world.beta;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Diagnostic harness: dumps the deterministic Beta terrain that the Tutorial
 * World builds on. The tutorial world is always created with seed 1234567L
 * (see Main.handleMainMenuInput), so its spawn terrain is identical every run.
 * This regenerates that exact terrain (via the provider's pure block lookups,
 * which work for negative coordinates) and prints a surface heightmap +
 * surface-block map, letting us design the hand-crafted tutorial scene against
 * the real landscape instead of guessing.
 */
public class TutorialTerrainDumpTest {

    // Real VoxelEngine block ids (mirrors Main.registerBlock / BetaWorldGenerator.findOr).
    private static final int STONE = 2, GRASS = 1, DIRT = 13, BEDROCK = 2;
    private static final int WATER = 15, LAVA = 21, SAND = 14, GRAVEL = 54;
    private static final int SANDSTONE = 59, ICE = 68, SNOW = 67, OBSIDIAN = 16;
    private static final int LEAVES = 4, WOOD = 5;
    private static final int DANDELION = 121, ROSE = 122, TALLGRASS = 35, DEADBUSH = 36;
    private static final int CACTUS = 39, PUMPKIN = 42;
    private static final int COAL = 61, IRON = 81, GOLD = 82, DIAMOND = 83, REDSTONE_ORE = 26, LAPIS = 85, GLOWSTONE = 17;
    private static final int REEDS = 40, CLAY = 55, COBBLE = 71, MOSSY = 132;
    private static final int CHEST = 118, SPAWNER = 258;

    private static BetaChunkProvider makeProvider(long seed) {
        int[] snowLevels = {0, SNOW, SNOW, SNOW, SNOW, SNOW, SNOW, SNOW, SNOW};
        return new BetaChunkProvider(seed, BetaNumericProfile.OVERWORLD,
                STONE, GRASS, DIRT, BEDROCK,
                WATER, LAVA, SAND, GRAVEL,
                SANDSTONE, ICE, SNOW, OBSIDIAN,
                LEAVES, WOOD,
                DANDELION, ROSE, TALLGRASS, DEADBUSH,
                CACTUS, PUMPKIN,
                COAL, IRON, GOLD,
                DIAMOND, REDSTONE_ORE, LAPIS, GLOWSTONE,
                REEDS, CLAY, COBBLE, MOSSY,
                CHEST, SPAWNER, snowLevels);
    }

    private static String surfaceName(int id) {
        switch (id) {
            case 0: return "air";
            case STONE: return "stone";
            case GRASS: return "grass";
            case DIRT: return "dirt";
            case WATER: return "water";
            case LAVA: return "lava";
            case SAND: return "sand";
            case GRAVEL: return "gravel";
            case SANDSTONE: return "sandstone";
            case ICE: return "ice";
            case SNOW: return "snow";
            case LEAVES: return "leaves";
            case WOOD: return "wood";
            case CLAY: return "clay";
            case COBBLE: return "cobble";
            default: return "id" + id;
        }
    }

    @Test
    public void dumpTutorialSpawnTerrain() {
        // seedFor(OVERWORLD): worldSeed ^ ((ordinal+1) * 0x9E3779B97F4A7C15L), ordinal 0.
        long typeSeed = 1234567L ^ 0x9E3779B97F4A7C15L;
        BetaChunkProvider provider = makeProvider(typeSeed);

        int half = 40; // 81x81 block window centered on spawn (0,0)
        int n = half * 2 + 1;
        int[][] height = new int[n][n];
        int[][] surf = new int[n][n];
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        long sum = 0; int cnt = 0;

        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                int h = -1, top = 0;
                for (int y = 127; y >= 0; y--) {
                    int b = provider.mapToVeBlock(provider.getBetaBlock(x, z, y));
                    if (b != 0) { h = y; top = b; break; }
                }
                height[x + half][z + half] = h;
                surf[x + half][z + half] = top;
                if (h >= 0) { minH = Math.min(minH, h); maxH = Math.max(maxH, h); sum += h; cnt++; }
            }
        }

        System.out.println("=== Tutorial World terrain dump (seed 1234567L, typeSeed=" + typeSeed + ") ===");
        System.out.println("Surface height range: " + minH + ".." + maxH + ", mean=" + (cnt == 0 ? 0 : (sum / cnt)));
        System.out.println("Biome at spawn (0,0): " + BetaBiomeGenBase.NAMES[provider.getBetaBiomeId(0, 0)]);
        System.out.println("Surface height at spawn (0,0): " + height[half][half] + " (" + surfaceName(surf[half][half]) + ")");
        System.out.println("Surface height at build origin (0,8): " + height[half][half + 8] + " (" + surfaceName(surf[half][half + 8]) + ")");

        System.out.println("--- heightmap (x horizontal, z vertical, z=-" + half + " top, digit=height, ~=water) ---");
        char[] ramp = "0123456789".toCharArray();
        for (int z = -half; z <= half; z++) {
            StringBuilder line = new StringBuilder();
            for (int x = -half; x <= half; x++) {
                int h = height[x + half][z + half];
                int s = surf[x + half][z + half];
                if (s == WATER) line.append('~');
                else if (h < 0) line.append(' ');
                else {
                    int v = maxH == minH ? 0 : Math.min(9, (h - minH) * 9 / (maxH - minH));
                    line.append(ramp[v]);
                }
            }
            System.out.println(line);
        }

        System.out.println("--- surface map (x horizontal, z vertical, z=-" + half + " top) ---");
        for (int z = -half; z <= half; z++) {
            StringBuilder line = new StringBuilder();
            for (int x = -half; x <= half; x++) {
                int s = surf[x + half][z + half];
                line.append(s == 0 ? ' ' : surfaceName(s).charAt(0));
            }
            System.out.println(line);
        }

        assertTrue("spawn surface should be a sane height (40..120)", height[half][half] >= 40 && height[half][half] <= 120);
    }
}
