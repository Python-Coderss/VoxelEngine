package com.voxel.world.beta;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Beta 1.7.3 terrain generator adapted for cubic chunks.
 * 
 * Ports the exact terrain generation algorithm from Beta 1.7.3's ChunkProviderGenerate,
 * preserving ALL bugs including the Far Lands floating-point precision issues.
 * 
 * Key differences from the original:
 * - Supports cubic chunks (generates per 16³ section, Y can go below 0 and above 127)
 * - Uses VoxelEngine block IDs (mapped from Beta 1.7.3 IDs)
 * - Generates terrain for y=0..127 (Beta surface), air above, deep stone below
 * 
 * Preserved bugs:
 * - Far Lands at extreme X/Z coordinates (floating-point precision in octave noise)
 * - Beta 1.7.3 exact biome distribution and surface replacement
 * - Exact cave carving thresholds and shapes
 * - Exact ore placement probabilities and heights
 * - Seed-based RNG determinism matches Beta 1.7.3
 */
public class BetaChunkProvider {
    private Random rand;
    private NoiseGeneratorOctaves field_912_k;   // octaves=16
    private NoiseGeneratorOctaves field_911_l;   // octaves=16
    private NoiseGeneratorOctaves field_910_m;   // octaves=8
    private NoiseGeneratorOctaves field_909_n;   // octaves=4  
    private NoiseGeneratorOctaves field_908_o;   // octaves=4
    public NoiseGeneratorOctaves field_922_a;    // octaves=10
    public NoiseGeneratorOctaves field_921_b;    // octaves=16
    public NoiseGeneratorOctaves mobSpawnerNoise; // octaves=8
    private long worldSeed;

    // Cached noise arrays (matching Beta 1.7.3 field names exactly)
    private double[] field_4180_q;  // main density field
    private double[] sandNoise = new double[256];
    private double[] gravelNoise = new double[256];
    private double[] stoneNoise = new double[256];
    double[] field_4185_d;
    double[] field_4184_e;
    double[] field_4183_f;
    double[] field_4182_g;
    double[] field_4181_h;
    int[][] field_914_i = new int[32][32];

    // Beta 1.7.3 block IDs (mapped to VoxelEngine IDs at construction)
    private byte betaStone;
    private byte betaGrass;
    private byte betaDirt;
    private byte betaBedrock;
    private byte betaWaterStill;
    private byte betaWaterMoving;
    private byte betaLavaStill;
    private byte betaLavaMoving;
    private byte betaSand;
    private byte betaGravel;
    private byte betaSandStone;
    private byte betaIce;
    private byte betaSnow;
    private byte betaObsidian;
    private byte betaLeaves;
    private byte betaWood;

    // VoxelEngine block IDs
    private final int veStone;
    private final int veGrass;
    private final int veDirt;
    private final int veBedrock;
    private final int veWaterStill;
    private final int veLavaStill;
    private final int veSand;
    private final int veGravel;
    private final int veSandStone;
    private final int veIce;
    private final int veSnow;
    private final int veObsidian;
    private final int veLeaves;
    private final int veWood;
    // Decoration block IDs
    private final int veDandelion;
    private final int veRose;
    private final int veTallGrass;
    private final int veDeadBush;
    private final int veCactus;
    private final int vePumpkin;
    private final int veCoalOre;
    private final int veIronOre;
    private final int veGoldOre;
    private final int veDiamondOre;
    private final int veRedstoneOre;
    private final int veLapisOre;

    // Beta 1.7.3 constants (preserved exactly)
    private static final byte BETA_AIR = 0;
    private static final byte BETA_STONE = 1;
    private static final byte BETA_GRASS = 2;
    private static final byte BETA_DIRT = 3;
    private static final byte BETA_BEDROCK = 7;
    private static final byte BETA_WATER_STILL = 9;
    private static final byte BETA_WATER_MOVING = 8;
    private static final byte BETA_LAVA_STILL = 11;
    private static final byte BETA_LAVA_MOVING = 10;
    private static final byte BETA_SAND = 12;
    private static final byte BETA_GRAVEL = 13;
    private static final byte BETA_SANDSTONE = 24;
    private static final byte BETA_ICE = 79;
    private static final byte BETA_SNOW = 78;
    private static final byte BETA_COAL_ORE = 16;
    private static final byte BETA_IRON_ORE = 15;
    private static final byte BETA_GOLD_ORE = 14;
    private static final byte BETA_DIAMOND_ORE = 56;
    private static final byte BETA_REDSTONE_ORE = 73;
    private static final byte BETA_LAPIS_ORE = 21;
    private static final byte BETA_WOOD = 17;
    private static final byte BETA_LEAVES = 18;
    private static final byte BETA_PLANT_YELLOW = 37;
    private static final byte BETA_PLANT_RED = 38;
    private static final byte BETA_TALL_GRASS = 31;
    private static final byte BETA_DEAD_BUSH = 32;
    private static final byte BETA_CACTUS = 81;
    private static final byte BETA_PUMPKIN = 86;

    // Biome data
    private BetaWorldChunkManager worldChunkManager;
    private int[] biomesForGeneration;    // biome IDs for current column
    private double[] temperatures;        // temperature values for current column
    private double[] humidities;          // humidity values for current column
    private int cachedCX = Integer.MIN_VALUE;
    private int cachedCZ = Integer.MIN_VALUE;

    // Cave generator
    private BetaMapGenCaves caveGen = new BetaMapGenCaves();

    // Column-level byte array cache (for cave carving and surface replacement)
    // This is a byte[32768] array matching Beta 1.7.3 format: index = x<<11|z<<7|y
    private byte[] columnBlocks;
    private int columnCX = Integer.MIN_VALUE;
    private int columnCZ = Integer.MIN_VALUE;
    private boolean columnGenerated = false;

    // During decoration, we may need neighbor column data for cross-column tree leaves/ores.
    // These are generated on-demand during populateColumn.
    private final Map<Long, byte[]> neighborBlocks = new HashMap<>();

    // Persistent overlay of decoration blocks (tree leaves, wood) that survive
    // across column regenerations. Applied by generateColumn after terrain gen.
    private final Map<Long, byte[]> decorationOverlay = new HashMap<>();

    // Sea level (Beta 1.7.3 hardcoded value)
    private static final int SEA_LEVEL = 64;

    public BetaChunkProvider(long seed,
                              int veStone, int veGrass, int veDirt, int veBedrock,
                              int veWaterStill, int veLavaStill, int veSand, int veGravel,
                              int veSandStone, int veIce, int veSnow, int veObsidian,
                              int veLeaves, int veWood,
                              int veDandelion, int veRose, int veTallGrass, int veDeadBush,
                              int veCactus, int vePumpkin,
                              int veCoalOre, int veIronOre, int veGoldOre,
                              int veDiamondOre, int veRedstoneOre, int veLapisOre) {
        this.worldSeed = seed;
        this.rand = new Random(seed);

        // Create noise generators (matching Beta 1.7.3 exactly)
        this.field_912_k = new NoiseGeneratorOctaves(this.rand, 16);
        this.field_911_l = new NoiseGeneratorOctaves(this.rand, 16);
        this.field_910_m = new NoiseGeneratorOctaves(this.rand, 8);
        this.field_909_n = new NoiseGeneratorOctaves(this.rand, 4);
        this.field_908_o = new NoiseGeneratorOctaves(this.rand, 4);
        this.field_922_a = new NoiseGeneratorOctaves(this.rand, 10);
        this.field_921_b = new NoiseGeneratorOctaves(this.rand, 16);
        this.mobSpawnerNoise = new NoiseGeneratorOctaves(this.rand, 8);

        // Store VoxelEngine block IDs
        this.veStone = veStone;
        this.veGrass = veGrass;
        this.veDirt = veDirt;
        this.veBedrock = veBedrock;
        this.veWaterStill = veWaterStill;
        this.veLavaStill = veLavaStill;
        this.veSand = veSand;
        this.veGravel = veGravel;
        this.veSandStone = veSandStone;
        this.veIce = veIce;
        this.veSnow = veSnow;
        this.veObsidian = veObsidian;
        this.veLeaves = veLeaves;
        this.veWood = veWood;
        this.veDandelion = veDandelion;
        this.veRose = veRose;
        this.veTallGrass = veTallGrass;
        this.veDeadBush = veDeadBush;
        this.veCactus = veCactus;
        this.vePumpkin = vePumpkin;
        this.veCoalOre = veCoalOre;
        this.veIronOre = veIronOre;
        this.veGoldOre = veGoldOre;
        this.veDiamondOre = veDiamondOre;
        this.veRedstoneOre = veRedstoneOre;
        this.veLapisOre = veLapisOre;

        // Store Beta 1.7.3 block IDs (for internal byte array operations)
        this.betaStone = BETA_STONE;
        this.betaGrass = BETA_GRASS;
        this.betaDirt = BETA_DIRT;
        this.betaBedrock = BETA_BEDROCK;
        this.betaWaterStill = BETA_WATER_STILL;
        this.betaWaterMoving = BETA_WATER_MOVING;
        this.betaLavaStill = BETA_LAVA_STILL;
        this.betaLavaMoving = BETA_LAVA_MOVING;
        this.betaSand = BETA_SAND;
        this.betaGravel = BETA_GRAVEL;
        this.betaSandStone = BETA_SANDSTONE;
        this.betaIce = BETA_ICE;
        this.betaSnow = BETA_SNOW;

        // Create biome manager
        this.worldChunkManager = new BetaWorldChunkManager(seed);
    }

    /**
     * Generate the full Beta 1.7.3 column for chunk (cx, cz).
     * This is called once before per-voxel queries for the column.
     */
    public void generateColumn(int cx, int cz) {
        if (columnGenerated && columnCX == cx && columnCZ == cz) return;
        
        columnCX = cx;
        columnCZ = cz;
        if (columnBlocks == null) {
            columnBlocks = new byte[32768]; // 16 * 128 * 16
        } else {
            java.util.Arrays.fill(columnBlocks, (byte) 0);
        }

        // Seed RNG matching Beta 1.7.3's provideChunk
        this.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);

        // Load biome data for this column
        this.biomesForGeneration = this.worldChunkManager.loadBlockGeneratorData(
                this.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        this.temperatures = this.worldChunkManager.temperature;
        this.humidities = this.worldChunkManager.humidity;

        // Generate base terrain (density field → blocks)
        this.generateTerrain(cx, cz, columnBlocks, this.biomesForGeneration, this.temperatures);

        // Replace top blocks with biome-appropriate blocks
        this.replaceBlocksForBiome(cx, cz, columnBlocks, this.biomesForGeneration);

        // Carve caves
        this.caveGen.func_867_a(worldSeed, cx, cz, columnBlocks);

        // Apply any persistent decoration overlay (cross-column tree leaves/wood)
        Long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        byte[] overlay = decorationOverlay.get(key);
        if (overlay != null) {
            for (int i = 0; i < 32768; i++) {
                if (overlay[i] != 0) {
                    columnBlocks[i] = overlay[i];
                }
            }
        }

        columnGenerated = true;
    }

    /**
     * Generate a column's blocks into a NEW byte array without disturbing the main cache.
     * Used during decoration to access neighbor columns for cross-column tree leaves.
     */
    private byte[] generateColumnCopy(int cx, int cz) {
        byte[] blocks = new byte[32768];
        
        // Temporarily save the main column state
        int savedCX = this.columnCX;
        int savedCZ = this.columnCZ;
        byte[] savedBlocks = this.columnBlocks;
        boolean savedGenerated = this.columnGenerated;
        int[] savedBiomes = this.biomesForGeneration;
        double[] savedTemps = this.temperatures;
        double[] savedHums = this.humidities;

        // Redirect to the neighbor column's blocks
        this.columnBlocks = blocks;
        this.columnCX = cx;
        this.columnCZ = cz;
        
        // Seed RNG
        this.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);

        // Load biome data
        this.biomesForGeneration = this.worldChunkManager.loadBlockGeneratorData(
                this.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        this.temperatures = this.worldChunkManager.temperature;
        this.humidities = this.worldChunkManager.humidity;

        // Generate terrain into the neighbor blocks
        this.generateTerrain(cx, cz, blocks, this.biomesForGeneration, this.temperatures);
        this.replaceBlocksForBiome(cx, cz, blocks, this.biomesForGeneration);
        this.caveGen.func_867_a(worldSeed, cx, cz, blocks);

        // Restore the main column state
        this.columnCX = savedCX;
        this.columnCZ = savedCZ;
        this.columnBlocks = savedBlocks;
        this.columnGenerated = savedGenerated;
        this.biomesForGeneration = savedBiomes;
        this.temperatures = savedTemps;
        this.humidities = savedHums;

        return blocks;
    }

    /**
     * Get the Beta 1.7.3 block ID at (x, y, z) within the current column.
     * Returns the Beta block ID (1-255), not the VoxelEngine ID.
     * Caller must map to VoxelEngine IDs.
     * For cubic chunks: returns 0 (air) for y < 0, and BETA_STONE for very deep y.
     */
    public int getBetaBlock(int x, int z, int y) {
        int cx = x >> 4;
        int cz = z >> 4;
        
        // Ensure column is generated
        if (!columnGenerated || columnCX != cx || columnCZ != cz) {
            generateColumn(cx, cz);
        }

        // Handle cubic chunk Y ranges
        if (y < 0) {
            // Deep below Beta world: solid stone with bedrock at very bottom
            if (y <= -64) return BETA_BEDROCK;
            return BETA_STONE;
        }
        if (y >= 128) {
            return BETA_AIR; // Above Beta world height
        }

        int lx = x & 15;
        int lz = z & 15;
        int idx = (lx << 11) | (lz << 7) | y;
        return columnBlocks[idx] & 0xFF;
    }

    /**
     * Map Beta 1.7.3 block ID to VoxelEngine block ID.
     * Includes all block types used in terrain generation and decoration.
     */
    public int mapToVeBlock(int betaId) {
        switch (betaId) {
            case BETA_STONE:       return veStone;
            case BETA_GRASS:       return veGrass;
            case BETA_DIRT:        return veDirt;
            case BETA_BEDROCK:     return veBedrock;
            case BETA_WATER_STILL:
            case BETA_WATER_MOVING:return veWaterStill;
            case BETA_LAVA_STILL:
            case BETA_LAVA_MOVING: return veLavaStill;
            case BETA_SAND:        return veSand;
            case BETA_GRAVEL:      return veGravel;
            case BETA_SANDSTONE:   return veSandStone;
            case BETA_ICE:         return veIce;
            case BETA_SNOW:        return veSnow;
            case BETA_WOOD:        return veWood;
            case BETA_LEAVES:      return veLeaves;
            case BETA_PLANT_YELLOW:return veDandelion;
            case BETA_PLANT_RED:   return veRose;
            case BETA_TALL_GRASS:  return veTallGrass;
            case BETA_DEAD_BUSH:   return veDeadBush;
            case BETA_CACTUS:      return veCactus;
            case BETA_PUMPKIN:     return vePumpkin;
            case BETA_COAL_ORE:    return veCoalOre;
            case BETA_IRON_ORE:    return veIronOre;
            case BETA_GOLD_ORE:    return veGoldOre;
            case BETA_DIAMOND_ORE: return veDiamondOre;
            case BETA_REDSTONE_ORE:return veRedstoneOre;
            case BETA_LAPIS_ORE:   return veLapisOre;
            default:               return 0; // air or unknown
        }
    }

    /**
     * Get the surface height at (x, z) using Beta 1.7.3 algorithm.
     * Scans top-down to find the highest non-air block.
     */
    public int getHeight(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        
        if (!columnGenerated || columnCX != cx || columnCZ != cz) {
            generateColumn(cx, cz);
        }

        int lx = x & 15;
        int lz = z & 15;
        
        for (int y = 127; y >= 0; y--) {
            int idx = (lx << 11) | (lz << 7) | y;
            if (columnBlocks[idx] != 0) {
                return y;
            }
        }
        return 0;
    }

    /**
     * Exact port of Beta 1.7.3's generateTerrain.
     * Creates the base terrain by thresholding a 3D density field.
     */
    public void generateTerrain(int var1, int var2, byte[] var3, int[] var4, double[] var5) {
        byte var6 = 4;
        byte var7 = 64; // sea level
        int var8 = var6 + 1;
        byte var9 = 17;
        int var10 = var6 + 1;
        this.field_4180_q = this.func_4061_a(this.field_4180_q, var1 * var6, 0, var2 * var6, var8, var9, var10);

        for (int var11 = 0; var11 < var6; ++var11) {
            for (int var12 = 0; var12 < var6; ++var12) {
                for (int var13 = 0; var13 < 16; ++var13) {
                    double var14 = 0.125D;
                    double var16 = this.field_4180_q[((var11 + 0) * var10 + var12 + 0) * var9 + var13 + 0];
                    double var18 = this.field_4180_q[((var11 + 0) * var10 + var12 + 1) * var9 + var13 + 0];
                    double var20 = this.field_4180_q[((var11 + 1) * var10 + var12 + 0) * var9 + var13 + 0];
                    double var22 = this.field_4180_q[((var11 + 1) * var10 + var12 + 1) * var9 + var13 + 0];
                    double var24 = (this.field_4180_q[((var11 + 0) * var10 + var12 + 0) * var9 + var13 + 1] - var16) * var14;
                    double var26 = (this.field_4180_q[((var11 + 0) * var10 + var12 + 1) * var9 + var13 + 1] - var18) * var14;
                    double var28 = (this.field_4180_q[((var11 + 1) * var10 + var12 + 0) * var9 + var13 + 1] - var20) * var14;
                    double var30 = (this.field_4180_q[((var11 + 1) * var10 + var12 + 1) * var9 + var13 + 1] - var22) * var14;

                    for (int var32 = 0; var32 < 8; ++var32) {
                        double var33 = 0.25D;
                        double var35 = var16;
                        double var37 = var18;
                        double var39 = (var20 - var16) * var33;
                        double var41 = (var22 - var18) * var33;

                        for (int var43 = 0; var43 < 4; ++var43) {
                            int var44 = var43 + var11 * 4 << 11 | 0 + var12 * 4 << 7 | var13 * 8 + var32;
                            short var45 = 128;
                            double var46 = 0.25D;
                            double var48 = var35;
                            double var50 = (var37 - var35) * var46;

                            for (int var52 = 0; var52 < 4; ++var52) {
                                double var53 = var5[(var11 * 4 + var43) * 16 + var12 * 4 + var52];
                                int var55 = 0;
                                if (var13 * 8 + var32 < var7) {
                                    if (var53 < 0.5D && var13 * 8 + var32 >= var7 - 1) {
                                        var55 = BETA_ICE;
                                    } else {
                                        var55 = BETA_WATER_STILL;
                                    }
                                }

                                if (var48 > 0.0D) {
                                    var55 = BETA_STONE;
                                }

                                var3[var44] = (byte) var55;
                                var44 += var45;
                                var48 += var50;
                            }

                            var35 += var39;
                            var37 += var41;
                        }

                        var16 += var24;
                        var18 += var26;
                        var20 += var28;
                        var22 += var30;
                    }
                }
            }
        }
    }

    /**
     * Exact port of Beta 1.7.3's replaceBlocksForBiome.
     * Replaces top stone blocks with biome-appropriate surface blocks.
     */
    public void replaceBlocksForBiome(int var1, int var2, byte[] var3, int[] var4) {
        byte var5 = 64; // sea level
        double var6 = 1.0D / 32.0D;
        this.sandNoise = this.field_909_n.generateNoiseOctaves(this.sandNoise,
                (double) (var1 * 16), (double) (var2 * 16), 0.0D,
                16, 16, 1, var6, var6, 1.0D);
        this.gravelNoise = this.field_909_n.generateNoiseOctaves(this.gravelNoise,
                (double) (var1 * 16), 109.0134D, (double) (var2 * 16),
                16, 1, 16, var6, 1.0D, var6);
        this.stoneNoise = this.field_908_o.generateNoiseOctaves(this.stoneNoise,
                (double) (var1 * 16), (double) (var2 * 16), 0.0D,
                16, 16, 1, var6 * 2.0D, var6 * 2.0D, var6 * 2.0D);

        for (int var8 = 0; var8 < 16; ++var8) {
            for (int var9 = 0; var9 < 16; ++var9) {
                int biomeId = var4[var8 + var9 * 16];
                boolean var11 = this.sandNoise[var8 + var9 * 16] + this.rand.nextDouble() * 0.2D > 0.0D;
                boolean var12 = this.gravelNoise[var8 + var9 * 16] + this.rand.nextDouble() * 0.2D > 3.0D;
                int var13 = (int) (this.stoneNoise[var8 + var9 * 16] / 3.0D + 3.0D + this.rand.nextDouble() * 0.25D);
                int var14 = -1;
                byte var15 = (byte) BetaBiomeGenBase.TOP_BLOCKS[biomeId];
                byte var16 = (byte) BetaBiomeGenBase.FILLER_BLOCKS[biomeId];

                for (int var17 = 127; var17 >= 0; --var17) {
                    int var18 = (var9 * 16 + var8) * 128 + var17;
                    if (var17 <= 0 + this.rand.nextInt(5)) {
                        var3[var18] = BETA_BEDROCK;
                    } else {
                        byte var19 = var3[var18];
                        if (var19 == 0) {
                            var14 = -1;
                        } else if (var19 == BETA_STONE) {
                            if (var14 == -1) {
                                if (var13 <= 0) {
                                    var15 = 0;
                                    var16 = BETA_STONE;
                                } else if (var17 >= var5 - 4 && var17 <= var5 + 1) {
                                    var15 = (byte) BetaBiomeGenBase.TOP_BLOCKS[biomeId];
                                    var16 = (byte) BetaBiomeGenBase.FILLER_BLOCKS[biomeId];
                                    if (var12) var15 = 0;
                                    if (var12) var16 = BETA_GRAVEL;
                                    if (var11) var15 = BETA_SAND;
                                    if (var11) var16 = BETA_SAND;
                                }

                                if (var17 < var5 && var15 == 0) {
                                    var15 = BETA_WATER_STILL;
                                }

                                var14 = var13;
                                if (var17 >= var5 - 1) {
                                    var3[var18] = var15;
                                } else {
                                    var3[var18] = var16;
                                }
                            } else if (var14 > 0) {
                                --var14;
                                var3[var18] = var16;
                                if (var14 == 0 && var16 == BETA_SAND) {
                                    var14 = this.rand.nextInt(4);
                                    var16 = BETA_SANDSTONE;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Exact port of Beta 1.7.3's func_4061_a - the core density field generator.
     * This is where the Far Lands bug lives: floating-point precision loss
     * in the octave noise combination at extreme coordinates.
     */
    private double[] func_4061_a(double[] var1, int var2, int var3, int var4,
                                  int var5, int var6, int var7) {
        if (var1 == null) {
            var1 = new double[var5 * var6 * var7];
        }

        double var8 = 684.412D;
        double var10 = 684.412D;
        double[] var12 = this.temperatures;
        double[] var13 = this.humidities;

        // BUG: These noise calls are where the Far Lands emerge at extreme coordinates.
        // The frequency scaling (var20 *= 2.0) in NoiseGeneratorOctaves causes 
        // floating-point precision loss beyond ~12,550,821 blocks.
        this.field_4182_g = this.field_922_a.func_4109_a(this.field_4182_g, var2, var4, var5, var7,
                1.121D, 1.121D, 0.5D);
        this.field_4181_h = this.field_921_b.func_4109_a(this.field_4181_h, var2, var4, var5, var7,
                200.0D, 200.0D, 0.5D);
        this.field_4185_d = this.field_910_m.generateNoiseOctaves(this.field_4185_d,
                (double) var2, (double) var3, (double) var4,
                var5, var6, var7,
                var8 / 80.0D, var10 / 160.0D, var8 / 80.0D);
        this.field_4184_e = this.field_912_k.generateNoiseOctaves(this.field_4184_e,
                (double) var2, (double) var3, (double) var4,
                var5, var6, var7, var8, var10, var8);
        this.field_4183_f = this.field_911_l.generateNoiseOctaves(this.field_4183_f,
                (double) var2, (double) var3, (double) var4,
                var5, var6, var7, var8, var10, var8);

        int var14 = 0;
        int var15 = 0;
        int var16 = 16 / var5;

        for (int var17 = 0; var17 < var5; ++var17) {
            int var18 = var17 * var16 + var16 / 2;

            for (int var19 = 0; var19 < var7; ++var19) {
                int var20 = var19 * var16 + var16 / 2;
                double var21 = var12[var18 * 16 + var20];
                double var23 = var13[var18 * 16 + var20] * var21;
                double var25 = 1.0D - var23;
                var25 *= var25;
                var25 *= var25;
                var25 = 1.0D - var25;
                double var27 = (this.field_4182_g[var15] + 256.0D) / 512.0D;
                var27 *= var25;
                if (var27 > 1.0D) var27 = 1.0D;

                double var29 = this.field_4181_h[var15] / 8000.0D;
                if (var29 < 0.0D) {
                    var29 = -var29 * 0.3D;
                }
                var29 = var29 * 3.0D - 2.0D;
                if (var29 < 0.0D) {
                    var29 /= 2.0D;
                    if (var29 < -1.0D) var29 = -1.0D;
                    var29 /= 1.4D;
                    var29 /= 2.0D;
                    var27 = 0.0D;
                } else {
                    if (var29 > 1.0D) var29 = 1.0D;
                    var29 /= 8.0D;
                }

                if (var27 < 0.0D) var27 = 0.0D;

                var27 += 0.5D;
                var29 = var29 * (double) var6 / 16.0D;
                double var31 = (double) var6 / 2.0D + var29 * 4.0D;
                ++var15;

                for (int var33 = 0; var33 < var6; ++var33) {
                    double var34 = 0.0D;
                    double var36 = ((double) var33 - var31) * 12.0D / var27;
                    if (var36 < 0.0D) var36 *= 4.0D;

                    double var38 = this.field_4184_e[var14] / 512.0D;
                    double var40 = this.field_4183_f[var14] / 512.0D;
                    double var42 = (this.field_4185_d[var14] / 10.0D + 1.0D) / 2.0D;
                    if (var42 < 0.0D) {
                        var34 = var38;
                    } else if (var42 > 1.0D) {
                        var34 = var40;
                    } else {
                        var34 = var38 + (var40 - var38) * var42;
                    }

                    var34 -= var36;
                    if (var33 > var6 - 4) {
                        double var44 = (double) ((float) (var33 - (var6 - 4)) / 3.0F);
                        var34 = var34 * (1.0D - var44) + -10.0D * var44;
                    }

                    var1[var14] = var34;
                    ++var14;
                }
            }
        }

        return var1;
    }

    /**
     * Check if column is already cached.
     */
    public boolean isColumnCached(int cx, int cz) {
        return columnGenerated && columnCX == cx && columnCZ == cz;
    }

    /**
     * Beta 1.7.3 population (decoration).
     * Port of ChunkProviderGenerate.populate().
     * Adds ores, trees, flowers, grass, and other features.
     * Writes to both the World (for rendering) and columnBlocks (for cross-column integrity).
     */
    public void populateColumn(com.voxel.World world, int cx, int cz) {
        // Ensure column is generated first
        if (!columnGenerated || columnCX != cx || columnCZ != cz) {
            generateColumn(cx, cz);
        }

        // Pre-generate the 8 surrounding columns so tree leaves can extend into them
        neighborBlocks.clear();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue; // current column already generated
                getColumnBlocks(cx + dx, cz + dz);
            }
        }

        int var4 = cx * 16;
        int var5 = cz * 16;

        // Seed matching Beta 1.7.3's populate()
        this.rand.setSeed(worldSeed);
        long var7 = this.rand.nextLong() / 2L * 2L + 1L;
        long var9 = this.rand.nextLong() / 2L * 2L + 1L;
        this.rand.setSeed((long) cx * var7 + (long) cz * var9 ^ worldSeed);

        int biomeId = this.biomesForGeneration[8 + 8 * 16]; // center biome

        // --- Ore veins (Beta 1.7.3 exact)
        // Dirt patches
        for (int i = 0; i < 20; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(128);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veDirt, 32);
        }
        // Gravel patches
        for (int i = 0; i < 10; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(128);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veGravel, 32);
        }
        // Coal ore
        for (int i = 0; i < 20; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(128);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veCoalOre, 16);
        }
        // Iron ore
        for (int i = 0; i < 20; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(64);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veIronOre, 8);
        }
        // Gold ore
        for (int i = 0; i < 2; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(32);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veGoldOre, 8);
        }
        // Redstone ore
        for (int i = 0; i < 8; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(16);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veRedstoneOre, 7);
        }
        // Diamond ore
        for (int i = 0; i < 1; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(16);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veDiamondOre, 7);
        }
        // Lapis ore
        for (int i = 0; i < 1; ++i) {
            int x = var4 + this.rand.nextInt(16);
            int y = this.rand.nextInt(16) + this.rand.nextInt(16);
            int z = var5 + this.rand.nextInt(16);
            genOreVein(world, x, y, z, veLapisOre, 6);
        }

        // --- Trees (biome-dependent, Beta 1.7.3 exact)
        double var11 = 0.5D;
        int treeBase = (int) ((this.mobSpawnerNoise.func_806_a((double) var4 * var11, (double) var5 * var11) / 8.0D
                + this.rand.nextDouble() * 4.0D + 4.0D) / 3.0D);
        int treeCount = 0;
        if (this.rand.nextInt(10) == 0) ++treeCount;

        switch (biomeId) {
            case BetaBiomeGenBase.FOREST:
            case BetaBiomeGenBase.RAINFOREST:
            case BetaBiomeGenBase.TAIGA:
                treeCount += treeBase + 5;
                break;
            case BetaBiomeGenBase.SEASONAL_FOREST:
                treeCount += treeBase + 2;
                break;
            case BetaBiomeGenBase.DESERT:
            case BetaBiomeGenBase.TUNDRA:
            case BetaBiomeGenBase.PLAINS:
                treeCount -= 20;
                break;
            default:
                treeCount += treeBase;
                break;
        }

        for (int i = 0; i < treeCount; ++i) {
            int tx = var4 + this.rand.nextInt(16) + 8;
            int tz = var5 + this.rand.nextInt(16) + 8;
            int ty = worldGetTopY(tx, tz);
            if (ty > 0 && ty < 128) {
                placeTree(world, tx, ty + 1, tz, biomeId);
            }
        }

        // --- Flowers (Beta 1.7.3 exact)
        int flowerCount = 0;
        switch (biomeId) {
            case BetaBiomeGenBase.FOREST:
            case BetaBiomeGenBase.TAIGA:
                flowerCount = 2; break;
            case BetaBiomeGenBase.SEASONAL_FOREST:
                flowerCount = 4; break;
            case BetaBiomeGenBase.PLAINS:
                flowerCount = 3; break;
        }
        for (int i = 0; i < flowerCount; ++i) {
            int fx = var4 + this.rand.nextInt(16) + 8;
            int fy = this.rand.nextInt(128);
            int fz = var5 + this.rand.nextInt(16) + 8;
            if (world.getVoxel(fx, fy, fz) == veGrass || world.getVoxel(fx, fy, fz) == veDirt) {
                if (world.getVoxel(fx, fy + 1, fz) == 0) {
                    setVoxelColumnAware(world, fx, fy + 1, fz, veDandelion, BETA_PLANT_YELLOW);
                }
            }
        }

        // --- Tall grass (Beta 1.7.3 exact)
        int grassCount = 0;
        switch (biomeId) {
            case BetaBiomeGenBase.FOREST: grassCount = 2; break;
            case BetaBiomeGenBase.RAINFOREST: grassCount = 10; break;
            case BetaBiomeGenBase.SEASONAL_FOREST: grassCount = 2; break;
            case BetaBiomeGenBase.TAIGA: grassCount = 1; break;
            case BetaBiomeGenBase.PLAINS: grassCount = 10; break;
        }
        for (int i = 0; i < grassCount; ++i) {
            int gx = var4 + this.rand.nextInt(16) + 8;
            int gy = this.rand.nextInt(128);
            int gz = var5 + this.rand.nextInt(16) + 8;
            if (world.getVoxel(gx, gy, gz) == veGrass || world.getVoxel(gx, gy, gz) == veDirt) {
                if (world.getVoxel(gx, gy + 1, gz) == 0) {
                    setVoxelColumnAware(world, gx, gy + 1, gz, veTallGrass, BETA_TALL_GRASS);
                }
            }
        }

        // --- Red flower (Beta 1.7.3 exact: 50% chance)
        if (this.rand.nextInt(2) == 0) {
            int rx = var4 + this.rand.nextInt(16) + 8;
            int ry = this.rand.nextInt(128);
            int rz = var5 + this.rand.nextInt(16) + 8;
            if (world.getVoxel(rx, ry, rz) == veGrass || world.getVoxel(rx, ry, rz) == veDirt) {
                if (world.getVoxel(rx, ry + 1, rz) == 0) {
                    setVoxelColumnAware(world, rx, ry + 1, rz, veRose, BETA_PLANT_RED);
                }
            }
        }

        // --- Dead bushes (desert only, Beta 1.7.3 exact)
        if (biomeId == BetaBiomeGenBase.DESERT) {
            for (int i = 0; i < 2; ++i) {
                int dx = var4 + this.rand.nextInt(16) + 8;
                int dy = this.rand.nextInt(128);
                int dz = var5 + this.rand.nextInt(16) + 8;
                if (world.getVoxel(dx, dy, dz) == veSand) {
                    if (world.getVoxel(dx, dy + 1, dz) == 0) {
                        setVoxelColumnAware(world, dx, dy + 1, dz, veDeadBush, BETA_DEAD_BUSH);
                    }
                }
            }
        }

        // Flush all neighbor columns to the World and save decoration overlay
        for (Map.Entry<Long, byte[]> entry : neighborBlocks.entrySet()) {
            long key = entry.getKey();
            int ncx = (int) (key >> 32);
            int ncz = (int) (key & 0xFFFFFFFFL);
            byte[] nb = entry.getValue();
            int bx = ncx * 16;
            int bz = ncz * 16;

            // Create sparse decoration overlay — only non-terrain blocks
            byte[] overlay = new byte[32768];
            boolean hasDecoration = false;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = 0; y < 128; y++) {
                        int idx = (lx << 11) | (lz << 7) | y;
                        byte betaId = nb[idx];
                        if (betaId != 0) {
                            int veId = mapToVeBlock(betaId & 0xFF);
                            if (veId != 0) {
                                world.setVoxel(bx + lx, y, bz + lz, veId);
                            }
                            // Track leaves/wood as persistent decorations
                            if (betaId == BETA_LEAVES || betaId == BETA_WOOD) {
                                overlay[idx] = betaId;
                                hasDecoration = true;
                            }
                        }
                    }
                }
            }
            if (hasDecoration) {
                decorationOverlay.put(key, overlay);
            }
        }

        neighborBlocks.clear();
    }

    /**
     * Place a vein of ore using Beta-style sphere generation.
     * Writes to both the World and the appropriate columnBlocks.
     */
    private void genOreVein(com.voxel.World world, int cx, int cy, int cz, int blockId, int count) {
        float f = this.rand.nextFloat() * (float) Math.PI;
        double dx = (double) ((float) (cx + 8) + (float) Math.sin(f) * (float) count / 8.0F);
        double dy = (double) ((float) (cx + 8) - (float) Math.sin(f) * (float) count / 8.0F);
        double dz = (double) ((float) (cz + 8) + (float) Math.cos(f) * (float) count / 8.0F);
        double dw = (double) ((float) (cz + 8) - (float) Math.cos(f) * (float) count / 8.0F);
        double ex = (double) (cy + this.rand.nextInt(3) - 2);
        double ey = (double) (cy + this.rand.nextInt(3) - 2);

        for (int i = 0; i < count; ++i) {
            float progress = (float) i / (float) count;
            double cx2 = dx + (dy - dx) * (double) progress;
            double cy2 = ex + (ey - ex) * (double) progress;
            double cz2 = dz + (dw - dz) * (double) progress;
            double radius = this.rand.nextDouble() * (double) count / 16.0D;
            double radiusXZ = (double) ((float) Math.sin((float) i * (float) Math.PI / (float) count) + 1.0F) * radius + 1.0D;
            double radiusY = (double) ((float) Math.sin((float) i * (float) Math.PI / (float) count) + 1.0F) * radius + 1.0D;
            int minX = (int) Math.floor(cx2 - radiusXZ / 2.0D);
            int minY = (int) Math.floor(cy2 - radiusY / 2.0D);
            int minZ = (int) Math.floor(cz2 - radiusXZ / 2.0D);
            int maxX = (int) Math.floor(cx2 + radiusXZ / 2.0D);
            int maxY = (int) Math.floor(cy2 + radiusY / 2.0D);
            int maxZ = (int) Math.floor(cz2 + radiusXZ / 2.0D);

            for (int px = minX; px <= maxX; ++px) {
                double dxDist = ((double) px + 0.5D - cx2) / (radiusXZ / 2.0D);
                if (dxDist * dxDist >= 1.0D) continue;
                // Clamp Y to Beta range
                if (px < 0 || px >= 256) continue; // reasonable world bounds
                for (int py = minY; py <= maxY; ++py) {
                    if (py < 0 || py >= 128) continue;
                    double dyDist = ((double) py + 0.5D - cy2) / (radiusY / 2.0D);
                    if (dxDist * dxDist + dyDist * dyDist >= 1.0D) continue;
                    for (int pz = minZ; pz <= maxZ; ++pz) {
                        if (pz < 0 || pz >= 256) continue;
                        double dzDist = ((double) pz + 0.5D - cz2) / (radiusXZ / 2.0D);
                        if (dxDist * dxDist + dyDist * dyDist + dzDist * dzDist < 1.0D) {
                            // Check if the block at this position is stone in columnBlocks
                            int colCx = px >> 4;
                            int colCz = pz >> 4;
                            byte[] blocks = getColumnBlocks(colCx, colCz);
                            int lx = px & 15;
                            int lz = pz & 15;
                            int idx = (lx << 11) | (lz << 7) | py;
                            if ((blocks[idx] & 0xFF) == BETA_STONE) {
                                blocks[idx] = BETA_STONE; // keep as stone in columnBlocks for now
                                world.setVoxel(px, py, pz, blockId);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Get the highest non-air Y at (x, z) using columnBlocks (ground truth). */
    private int worldGetTopY(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        byte[] blocks = getColumnBlocks(cx, cz);
        int lx = x & 15;
        int lz = z & 15;
        for (int y = 127; y > 0; y--) {
            int idx = (lx << 11) | (lz << 7) | y;
            if (blocks[idx] != 0) return y;
        }
        return 0;
    }

    /**
     * Get (or generate) the columnBlocks for the given column.
     * Returns the main cached columnBlocks if (cx,cz) matches, otherwise
     * looks up or creates neighbor column data.
     */
    private byte[] getColumnBlocks(int cx, int cz) {
        if (columnGenerated && columnCX == cx && columnCZ == cz) {
            return columnBlocks;
        }
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        byte[] blocks = neighborBlocks.get(key);
        if (blocks == null) {
            // Generate this neighbor column
            blocks = generateColumnCopy(cx, cz);
            neighborBlocks.put(key, blocks);
        }
        return blocks;
    }

    /**
     * Set a voxel both in the World AND in the appropriate columnBlocks.
     * This ensures that if a neighbor column is later generated, it won't
     * overwrite decoration blocks (like tree leaves extending across column boundaries).
     */
    private void setVoxelColumnAware(com.voxel.World world, int x, int y, int z, int veId, int betaId) {
        if (y < 0 || y >= 128) return;
        world.setVoxel(x, y, z, veId);
        int colCx = x >> 4;
        int colCz = z >> 4;
        byte[] blocks = getColumnBlocks(colCx, colCz);
        int lx = x & 15;
        int lz = z & 15;
        int idx = (lx << 11) | (lz << 7) | y;
        blocks[idx] = (byte) betaId;
    }

    /**
     * Place a simple tree at (x, y, z).
     * y is the trunk base (one block above the ground).
     * Writes to columnBlocks for cross-column integrity.
     */
    private void placeTree(com.voxel.World world, int x, int y, int z, int biomeId) {
        // Tree height: 4-6 for normal, 5-15 for big trees (forest/rainforest)
        int height;
        boolean isBig = false;
        if ((biomeId == BetaBiomeGenBase.FOREST || biomeId == BetaBiomeGenBase.RAINFOREST)
                && this.rand.nextInt(10) == 0) {
            isBig = true;
            height = 5 + this.rand.nextInt(11); // big tree: 5-15
        } else {
            height = 4 + this.rand.nextInt(3); // normal: 4-6
        }

        // Check clearance using columnBlocks
        int colCx = x >> 4;
        int colCz = z >> 4;
        byte[] trunkBlocks = getColumnBlocks(colCx, colCz);
        int lx = x & 15;
        int lz = z & 15;
        for (int dy = 0; dy < height + 2; dy++) {
            if (y + dy >= 128) return;
            int idx = (lx << 11) | (lz << 7) | (y + dy);
            if (trunkBlocks[idx] != 0 && dy < height) {
                return; // blocked
            }
        }

        // Trunk
        for (int dy = 0; dy < height; dy++) {
            int idx = (lx << 11) | (lz << 7) | (y + dy);
            trunkBlocks[idx] = BETA_WOOD;
            world.setVoxel(x, y + dy, z, veWood);
        }

        // Canopy
        int leafStart = height - 3;
        if (isBig) {
            leafStart = height - 4;
        }
        for (int dy = leafStart; dy <= height; dy++) {
            int radius = (dy == leafStart || dy == height) ? 1 : 2;
            if (isBig && dy >= leafStart + 1 && dy < height) radius = 2 + (dy - leafStart - 1);
            if (y + dy >= 128) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && this.rand.nextInt(2) == 0)
                        continue;
                    if (dy == height && (Math.abs(dx) > 1 || Math.abs(dz) > 1)) continue;
                    int wx = x + dx;
                    int wz = z + dz;
                    int ly = y + dy;
                    int leafColCx = wx >> 4;
                    int leafColCz = wz >> 4;
                    byte[] leafBlocks = getColumnBlocks(leafColCx, leafColCz);
                    int llx = wx & 15;
                    int llz = wz & 15;
                    int leafIdx = (llx << 11) | (llz << 7) | ly;
                    if (leafBlocks[leafIdx] == 0) {
                        leafBlocks[leafIdx] = BETA_LEAVES;
                        world.setVoxel(wx, ly, wz, veLeaves);
                    }
                }
            }
        }

        // Extra leaves on top for big trees
        if (isBig) {
            int topY = y + height;
            if (topY < 128) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) <= 1) {
                            int wx = x + dx;
                            int wz = z + dz;
                            int leafColCx = wx >> 4;
                            int leafColCz = wz >> 4;
                            byte[] leafBlocks = getColumnBlocks(leafColCx, leafColCz);
                            int llx = wx & 15;
                            int llz = wz & 15;
                            int leafIdx = (llx << 11) | (llz << 7) | topY;
                            if (leafBlocks[leafIdx] == 0) {
                                leafBlocks[leafIdx] = BETA_LEAVES;
                                world.setVoxel(wx, topY, wz, veLeaves);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Invalidate the column cache (useful when switching to a different area).
     */
    public void invalidateCache() {
        columnGenerated = false;
        columnCX = Integer.MIN_VALUE;
        columnCZ = Integer.MIN_VALUE;
    }

    /** Returns the current column's biome IDs. */
    public int[] getCurrentBiomes() { return biomesForGeneration; }

    /** Returns the current column's temperatures. */
    public double[] getCurrentTemperatures() { return temperatures; }

    /** Returns the Beta 1.7.3 biome ID at world coordinates (x, z). */
    public int getBetaBiomeId(int x, int z) {
        return worldChunkManager.getBiomeGenAt(x, z);
    }

    public long getWorldSeed() { return worldSeed; }
    public NoiseGeneratorOctaves getMobSpawnerNoise() { return mobSpawnerNoise; }
    public Random getRand() { return rand; }
}
