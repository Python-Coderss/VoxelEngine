package com.voxel.world.beta;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Beta 1.7.3 terrain generator adapted for cubic chunks with infinite Y.
 * 
 * Uses cubic 16³ section caching — one byte[4096] per cy (Y section index)
 * stored in a HashMap. Sections are batch-generated via one func_4061_a
 * call per generation event, matching the speed of the old column cache.
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

    private double[] field_4180_q;
    private double[] sandNoise = new double[256];
    private double[] gravelNoise = new double[256];
    private double[] stoneNoise = new double[256];
    double[] field_4185_d;
    double[] field_4184_e;
    double[] field_4183_f;
    double[] field_4182_g;
    double[] field_4181_h;
    int[][] field_914_i = new int[32][32];

    private byte betaStone, betaGrass, betaDirt, betaBedrock;
    private byte betaWaterStill, betaWaterMoving, betaLavaStill, betaLavaMoving;
    private byte betaSand, betaGravel, betaSandStone, betaIce, betaSnow;
    private byte betaObsidian, betaLeaves, betaWood;

    private final int veStone, veGrass, veDirt, veBedrock;
    private final int veWaterStill, veLavaStill, veSand, veGravel;
    private final int veSandStone, veIce, veSnow, veObsidian;
    private final int veLeaves, veWood;
    private final int veDandelion, veRose, veTallGrass, veDeadBush;
    private final int veCactus, vePumpkin;
    private final int veCoalOre, veIronOre, veGoldOre;
    private final int veDiamondOre, veRedstoneOre, veLapisOre, veGlowstone;
    private final int veSugarCane, veClay, veCobblestone, veMossyCobble;
    private final int veChest, veSpawner;
    private final int[] veSnowLevels;

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
    private static final byte BETA_GLOWSTONE = 89;

    private BetaWorldChunkManager worldChunkManager;
    private int[] biomesForGeneration;
    private double[] temperatures;
    private double[] humidities;
    private int cachedCX = Integer.MIN_VALUE;
    private int cachedCZ = Integer.MIN_VALUE;

    private BetaMapGenCaves caveGen = new BetaMapGenCaves();

    // ── Cubic section cache: HashMap<cy, byte[4096]> ──
    private HashMap<Integer, byte[]> columnSections;
    private int columnCX = Integer.MIN_VALUE;
    private int columnCZ = Integer.MIN_VALUE;
    private boolean columnGenerated = false;
    private int maxSectionCY = -1;

    // During decoration, neighbor columns use the same section-based storage
    private final Map<Long, HashMap<Integer, byte[]>> neighborBlocks = new HashMap<>();
    private final Map<Long, HashMap<Integer, byte[]>> decorationOverlay = new HashMap<>();

    private byte[] caveTempArray = new byte[32768];

    private static final int SEA_LEVEL = 64;
    private static final double BETA_Y_SAMPLES = 17.0;

    /** Index into a 16³ section: lx|(ly<<4)|(lz<<8) */
    private static int sectionIdx(int lx, int ly, int lz) {
        return lx | (ly << 4) | (lz << 8);
    }

    private byte[] getOrCreateSection(int cy) {
        byte[] sec = columnSections.get(cy);
        if (sec == null) {
            sec = new byte[4096];
            columnSections.put(cy, sec);
            if (cy > maxSectionCY) maxSectionCY = cy;
        }
        return sec;
    }

    static byte getSectionBlock(HashMap<Integer, byte[]> sections, int lx, int ly, int lz) {
        int cy = ly >> 4;
        byte[] sec = sections.get(cy);
        return sec != null ? sec[sectionIdx(lx, ly & 15, lz)] : 0;
    }

    static void setSectionBlock(HashMap<Integer, byte[]> sections, int lx, int ly, int lz, byte val) {
        if (val == 0) {
            int cy = ly >> 4;
            byte[] sec = sections.get(cy);
            if (sec != null) sec[sectionIdx(lx, ly & 15, lz)] = 0;
            return;
        }
        int cy = ly >> 4;
        byte[] sec = sections.get(cy);
        if (sec == null) {
            sec = new byte[4096];
            sections.put(cy, sec);
        }
        sec[sectionIdx(lx, ly & 15, lz)] = val;
    }

    private byte getMainBlock(int lx, int lz, int y) {
        int cy = y >> 4;
        byte[] sec = columnSections.get(cy);
        return sec != null ? sec[sectionIdx(lx, y & 15, lz)] : 0;
    }

    private void setMainBlock(int lx, int lz, int y, byte val) {
        if (val == 0) {
            int cy = y >> 4;
            byte[] sec = columnSections.get(cy);
            if (sec != null) sec[sectionIdx(lx, y & 15, lz)] = 0;
            return;
        }
        byte[] sec = getOrCreateSection(y >> 4);
        sec[sectionIdx(lx, y & 15, lz)] = val;
    }


    public BetaChunkProvider(long seed,
                              int veStone, int veGrass, int veDirt, int veBedrock,
                              int veWaterStill, int veLavaStill, int veSand, int veGravel,
                              int veSandStone, int veIce, int veSnow, int veObsidian,
                              int veLeaves, int veWood,
                              int veDandelion, int veRose, int veTallGrass, int veDeadBush,
                              int veCactus, int vePumpkin,
                              int veCoalOre, int veIronOre, int veGoldOre,
                              int veDiamondOre, int veRedstoneOre, int veLapisOre, int veGlowstone,
                              int veSugarCane, int veClay, int veCobblestone, int veMossyCobble,
                              int veChest, int veSpawner, int[] veSnowLevels) {
        this.worldSeed = seed;
        this.rand = new Random(seed);
        this.field_912_k = new NoiseGeneratorOctaves(this.rand, 16);
        this.field_911_l = new NoiseGeneratorOctaves(this.rand, 16);
        this.field_910_m = new NoiseGeneratorOctaves(this.rand, 8);
        this.field_909_n = new NoiseGeneratorOctaves(this.rand, 4);
        this.field_908_o = new NoiseGeneratorOctaves(this.rand, 4);
        this.field_922_a = new NoiseGeneratorOctaves(this.rand, 10);
        this.field_921_b = new NoiseGeneratorOctaves(this.rand, 16);
        this.mobSpawnerNoise = new NoiseGeneratorOctaves(this.rand, 8);

        this.veStone = veStone; this.veGrass = veGrass; this.veDirt = veDirt;
        this.veBedrock = veBedrock; this.veWaterStill = veWaterStill;
        this.veLavaStill = veLavaStill; this.veSand = veSand; this.veGravel = veGravel;
        this.veSandStone = veSandStone; this.veIce = veIce; this.veSnow = veSnow;
        this.veObsidian = veObsidian; this.veLeaves = veLeaves; this.veWood = veWood;
        this.veDandelion = veDandelion; this.veRose = veRose;
        this.veTallGrass = veTallGrass; this.veDeadBush = veDeadBush;
        this.veCactus = veCactus; this.vePumpkin = vePumpkin;
        this.veCoalOre = veCoalOre; this.veIronOre = veIronOre;
        this.veGoldOre = veGoldOre; this.veDiamondOre = veDiamondOre;
        this.veRedstoneOre = veRedstoneOre; this.veLapisOre = veLapisOre;
        this.veGlowstone = veGlowstone;
        this.veSugarCane = veSugarCane; this.veClay = veClay;
        this.veCobblestone = veCobblestone; this.veMossyCobble = veMossyCobble;
        this.veChest = veChest; this.veSpawner = veSpawner;
        this.veSnowLevels = veSnowLevels;

        this.betaStone = BETA_STONE; this.betaGrass = BETA_GRASS;
        this.betaDirt = BETA_DIRT; this.betaBedrock = BETA_BEDROCK;
        this.betaWaterStill = BETA_WATER_STILL; this.betaWaterMoving = BETA_WATER_MOVING;
        this.betaLavaStill = BETA_LAVA_STILL; this.betaLavaMoving = BETA_LAVA_MOVING;
        this.betaSand = BETA_SAND; this.betaGravel = BETA_GRAVEL;
        this.betaSandStone = BETA_SANDSTONE; this.betaIce = BETA_ICE;
        this.betaSnow = BETA_SNOW;

        this.worldChunkManager = new BetaWorldChunkManager(seed);
    }

    // ══════════════════════════════════════════════════════════════════
    //  COLUMN GENERATION
    // ══════════════════════════════════════════════════════════════════

    public void generateColumn(int cx, int cz) {
        if (columnGenerated && columnCX == cx && columnCZ == cz) return;
        
        columnCX = cx;
        columnCZ = cz;
        if (columnSections == null) {
            columnSections = new HashMap<>();
        } else {
            columnSections.clear();
        }
        maxSectionCY = -1;

        this.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        this.biomesForGeneration = this.worldChunkManager.loadBlockGeneratorData(
                this.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        this.temperatures = this.worldChunkManager.temperature;
        this.humidities = this.worldChunkManager.humidity;

        this.generateTerrain(cx, cz, columnSections, this.biomesForGeneration, this.temperatures);
        this.replaceBlocksForBiome(cx, cz, columnSections, this.biomesForGeneration);

        carveCavesFromSections(worldSeed, cx, cz, columnSections);

        for (int cy : columnSections.keySet()) {
            if (cy > maxSectionCY) maxSectionCY = cy;
        }

        Long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        HashMap<Integer, byte[]> overlay = decorationOverlay.get(key);
        if (overlay != null) {
            for (Map.Entry<Integer, byte[]> entry : overlay.entrySet()) {
                int ocy = entry.getKey();
                byte[] os = entry.getValue();
                byte[] cs = getOrCreateSection(ocy);
                for (int i = 0; i < 4096; i++) {
                    if (os[i] != 0) cs[i] = os[i];
                }
            }
        }

        columnGenerated = true;
    }

    private void carveCavesFromSections(long worldSeed, int cx, int cz, HashMap<Integer, byte[]> sections) {
        java.util.Arrays.fill(caveTempArray, (byte) 0);
        for (int cy = 0; cy < 8; cy++) {
            byte[] sec = sections.get(cy);
            if (sec == null) continue;
            for (int i = 0; i < 4096; i++) {
                if (sec[i] == 0) continue;
                int lx = i & 15, ly = (i >> 4) & 15, lz = (i >> 8) & 15;
                int y = (cy << 4) | ly;
                caveTempArray[(lx * 16 + lz) * 128 + y] = sec[i];
            }
        }
        caveGen.func_867_a(worldSeed, cx, cz, caveTempArray);
        for (int cy = 0; cy < 8; cy++) {
            boolean any = false;
            byte[] sec = sections.get(cy);
            for (int i = 0; i < 4096; i++) {
                int lx = i & 15, ly = (i >> 4) & 15, lz = (i >> 8) & 15;
                int y = (cy << 4) | ly;
                byte b = caveTempArray[(lx * 16 + lz) * 128 + y];
                if (sec == null) {
                    if (b != 0) { sec = new byte[4096]; sections.put(cy, sec); }
                    else continue;
                }
                sec[i] = b;
                if (b != 0) any = true;
            }
            if (!any && sec != null) sections.remove(cy);
        }
    }

    private HashMap<Integer, byte[]> generateColumnCopy(int cx, int cz) {
        HashMap<Integer, byte[]> blocks = new HashMap<>();
        int savedCX = this.columnCX, savedCZ = this.columnCZ;
        HashMap<Integer, byte[]> savedSections = this.columnSections;
        boolean savedGenerated = this.columnGenerated;
        int savedMaxCY = this.maxSectionCY;
        int[] savedBiomes = this.biomesForGeneration;
        double[] savedTemps = this.temperatures, savedHums = this.humidities;

        this.columnSections = blocks;
        this.columnCX = cx; this.columnCZ = cz;
        this.maxSectionCY = -1;
        this.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        this.biomesForGeneration = this.worldChunkManager.loadBlockGeneratorData(
                this.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        this.temperatures = this.worldChunkManager.temperature;
        this.humidities = this.worldChunkManager.humidity;
        this.generateTerrain(cx, cz, blocks, this.biomesForGeneration, this.temperatures);
        this.replaceBlocksForBiome(cx, cz, blocks, this.biomesForGeneration);
        carveCavesFromSections(worldSeed, cx, cz, blocks);

        this.columnCX = savedCX; this.columnCZ = savedCZ;
        this.columnSections = savedSections;
        this.columnGenerated = savedGenerated;
        this.maxSectionCY = savedMaxCY;
        this.biomesForGeneration = savedBiomes;
        this.temperatures = savedTemps; this.humidities = savedHums;
        return blocks;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BLOCK QUERIES
    // ══════════════════════════════════════════════════════════════════

    public int getBetaBlock(int x, int z, int y) {
        int cx = x >> 4, cz = z >> 4;
        if (!columnGenerated || columnCX != cx || columnCZ != cz) {
            generateColumn(cx, cz);
        }
        if (y < 0) {
            if (y <= -64) return BETA_BEDROCK;
            return evaluateDensity(x, y, z) ? BETA_STONE : BETA_AIR;
        }
        int lx = x & 15, lz = z & 15, ly = y & 15, cy = y >> 4;
        byte[] sec = columnSections.get(cy);
        if (sec == null) {
            // Section not yet generated — batch generate it via the full pipeline.
            // generateSectionRange handles this single-section case efficiently
            // (one func_4061_a call for just 3 Y samples).
            generateSectionRange(cy, cy + 1);
            sec = columnSections.get(cy);
            if (sec == null) return 0;
        }
        return sec[sectionIdx(lx, ly, lz)] & 0xFF;
    }

    // ══════════════════════════════════════════════════════════════════
    //  getHeight — with batch extension matching column-cache speed
    // ══════════════════════════════════════════════════════════════════

    public int getHeight(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4, cy = y >> 4;
        if (!columnGenerated || columnCX != cx || columnCZ != cz) generateColumn(cx, cz);

        // Far Lands: terrain is infinite at extreme coordinates.
        // Skip the scan entirely — there is no "top" to find.
        if (maxSectionCY == 127 || Math.abs(cx) >= 100000 || Math.abs(cy) >= 100000 || Math.abs(cz) >= 100000) {
            return Integer.MAX_VALUE;
        }

        int lx = x & 15, lz = z & 15;

        // Normal terrain: scan downward from highest known section.
        for (int scanCY = maxSectionCY; scanCY >= 0; scanCY--) {
            byte[] sec = columnSections.get(scanCY);
            if (sec == null) continue;
            for (int ly = 15; ly >= 0; ly--) {
                if (sec[sectionIdx(lx, ly, lz)] != 0) {
                    return (scanCY << 4) | ly;
                }
            }
        }
        for (int yy = -1; yy >= -64; yy--) {
            if (evaluateDensity(x, yy, z)) return yy;
        }
        return 0;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BATCH SECTION GENERATION — one func_4061_a call = column-cache speed
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generate all sections in [fromCY, toCY) in one func_4061_a call.
     * Uses the full Beta pipeline: noise + temperature modulation +
     * trilinear interpolation + water/ice fill. At Far Lands where
     * all density is positive, the short-circuit skip fills stone
     * instantly without per-block checks.
     */
    private void generateSectionRange(int fromCY, int toCY) {
        if (fromCY >= toCY) return;
        int minY = fromCY << 4;
        int maxY = toCY << 4;
        int yStart = minY / 8;
        int yEnd = (maxY - 1) / 8 + 1;
        int ySamples = yEnd - yStart + 1;

        byte var6 = 4;
        int var8 = var6 + 1;      // 5
        int var9 = ySamples;       // Y sample count
        int var10 = var6 + 1;      // 5

        double[] densityField = new double[var8 * var9 * var10];
        densityField = func_4061_a(densityField, columnCX * var6, yStart, columnCZ * var6, var8, var9, var10);
        interpolateDensityToSections(densityField, var8, var9, var10, yStart);
    }

    /** Trilinar interpolate a density field into the section cache. */
    private void interpolateDensityToSections(double[] densityField,
                                               int var8, int var9, int var10, int yStart) {
        byte var6 = 4;
        byte var7 = 64;  // sea level for water fill
        int sampleCount = var9 - 1;

        for (int var11 = 0; var11 < var6; ++var11) {
            for (int var12 = 0; var12 < var6; ++var12) {
                for (int var13 = 0; var13 < sampleCount; ++var13) {
                    double v16 = densityField[((var11) * var10 + var12) * var9 + var13];
                    double v18 = densityField[((var11) * var10 + var12 + 1) * var9 + var13];
                    double v20 = densityField[((var11 + 1) * var10 + var12) * var9 + var13];
                    double v22 = densityField[((var11 + 1) * var10 + var12 + 1) * var9 + var13];

                    // ── Short-circuit: if all 8 corners are > 0, the entire
                    //     4×8×4 sub-volume is solid stone. Skip the inner loops.
                    double v16p = densityField[((var11) * var10 + var12) * var9 + var13 + 1];
                    double v18p = densityField[((var11) * var10 + var12 + 1) * var9 + var13 + 1];
                    double v20p = densityField[((var11 + 1) * var10 + var12) * var9 + var13 + 1];
                    double v22p = densityField[((var11 + 1) * var10 + var12 + 1) * var9 + var13 + 1];

                    boolean allSolid = (v16 > 0) && (v18 > 0) && (v20 > 0) && (v22 > 0)
                                    && (v16p > 0) && (v18p > 0) && (v20p > 0) && (v22p > 0);

                    if (allSolid) {
                        // Everything in this 4×8×4 sub-volume is stone.
                        // Fill all 16 Y levels for all 4×4 XZ cells instantly.
                        int yBase = yStart * 8 + var13 * 8;
                        for (int var43 = 0; var43 < 4; ++var43) {
                            int lx = var43 + var11 * 4;
                            for (int var52 = 0; var52 < 4; ++var52) {
                                int lz = var52 + var12 * 4;
                                for (int var32 = 0; var32 < 8; ++var32) {
                                    setMainBlock(lx, lz, yBase + var32, BETA_STONE);
                                }
                            }
                        }
                        continue;
                    }

                    // ── Slow path: full per-block interpolation ──
                    double var14 = 0.125D;
                    double var24 = (v16p - v16) * var14;
                    double var26 = (v18p - v18) * var14;
                    double var28 = (v20p - v20) * var14;
                    double var30 = (v22p - v22) * var14;

                    for (int var32 = 0; var32 < 8; ++var32) {
                        double var33 = 0.25D;
                        double var35 = v16, var37 = v18;
                        double var39 = (v20 - v16) * var33;
                        double var41 = (v22 - v18) * var33;

                        for (int var43 = 0; var43 < 4; ++var43) {
                            int lx = var43 + var11 * 4;
                            int y = yStart * 8 + var13 * 8 + var32;
                            double var46 = 0.25D;
                            double var48 = var35;
                            double var50 = (var37 - var35) * var46;

                            for (int var52 = 0; var52 < 4; ++var52) {
                                double var53 = temperatures[(var11 * 4 + var43) * 16 + var12 * 4 + var52];
                                int var55 = 0;
                                if (y < var7) {
                                    var55 = (var53 < 0.5D && y >= var7 - 1) ? BETA_ICE : BETA_WATER_STILL;
                                }
                                if (var48 > 0.0D) var55 = BETA_STONE;
                                setMainBlock(lx, var52 + var12 * 4, y, (byte) var55);
                                var48 += var50;
                            }
                            var35 += var39; var37 += var41;
                        }
                        v16 += var24; v18 += var26; v20 += var28; v22 += var30;
                    }
                }
            }
        }
    }

    private boolean evaluateDensity(int x, int y, int z) {
        double nx = x / 4.0, ny = y / 8.0, nz = z / 4.0;
        double[] t = new double[1];
        double n1 = field_912_k.generateNoiseOctaves(t, nx, ny, nz, 1, 1, 1, 684.412, 684.412, 684.412)[0];
        double n2 = field_911_l.generateNoiseOctaves(t, nx, ny, nz, 1, 1, 1, 684.412, 684.412, 684.412)[0];
        double n3 = field_910_m.generateNoiseOctaves(t, nx, ny, nz, 1, 1, 1, 684.412/80.0, 684.412/160.0, 684.412/80.0)[0];
        double sel = (n3 / 10.0 + 1.0) / 2.0;
        if (sel < 0.0) sel = 0.0; if (sel > 1.0) sel = 1.0;
        return (n1 / 512.0 * (1.0 - sel) + n2 / 512.0 * sel) > 0.0;
    }

    public int mapToVeBlock(int betaId) {
        switch (betaId) {
            case BETA_STONE:       return veStone;
            case BETA_GRASS:       return veGrass;
            case BETA_DIRT:        return veDirt;
            case BETA_BEDROCK:     return veBedrock;
            case BETA_WATER_STILL: case BETA_WATER_MOVING: return veWaterStill;
            case BETA_LAVA_STILL:  case BETA_LAVA_MOVING:  return veLavaStill;
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
            case BETA_GLOWSTONE:   return veGlowstone;
            default:               return 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  TERRAIN GENERATION (exact Beta 1.7.3, writes to sections)
    // ══════════════════════════════════════════════════════════════════

    public void generateTerrain(int var1, int var2, HashMap<Integer, byte[]> var3, int[] var4, double[] var5) {
        byte var6 = 4;
        byte var7 = 64;
        int var8 = var6 + 1, var9 = 257, var10 = var6 + 1;
        this.field_4180_q = this.func_4061_a(this.field_4180_q, var1 * var6, 0, var2 * var6, var8, var9, var10);

        for (int var11 = 0; var11 < var6; ++var11) {
            for (int var12 = 0; var12 < var6; ++var12) {
                for (int var13 = 0; var13 < 256; ++var13) {
                    double v16 = this.field_4180_q[((var11) * var10 + var12) * var9 + var13];
                    double v18 = this.field_4180_q[((var11) * var10 + var12 + 1) * var9 + var13];
                    double v20 = this.field_4180_q[((var11 + 1) * var10 + var12) * var9 + var13];
                    double v22 = this.field_4180_q[((var11 + 1) * var10 + var12 + 1) * var9 + var13];

                    // Short-circuit: if all 8 corners > 0, everything here is stone
                    double v16p = this.field_4180_q[((var11) * var10 + var12) * var9 + var13 + 1];
                    double v18p = this.field_4180_q[((var11) * var10 + var12 + 1) * var9 + var13 + 1];
                    double v20p = this.field_4180_q[((var11 + 1) * var10 + var12) * var9 + var13 + 1];
                    double v22p = this.field_4180_q[((var11 + 1) * var10 + var12 + 1) * var9 + var13 + 1];

                    boolean allSolid = (v16 > 0) && (v18 > 0) && (v20 > 0) && (v22 > 0)
                                    && (v16p > 0) && (v18p > 0) && (v20p > 0) && (v22p > 0);

                    if (allSolid) {
                        int yBase = var13 * 8;
                        for (int var43 = 0; var43 < 4; ++var43) {
                            int lx = var43 + var11 * 4;
                            for (int var52 = 0; var52 < 4; ++var52) {
                                int lz = var52 + var12 * 4;
                                for (int var32 = 0; var32 < 8; ++var32) {
                                    setSectionBlock(var3, lx, yBase + var32, lz, BETA_STONE);
                                }
                            }
                        }
                        continue;
                    }

                    // Slow path: full interpolation
                    double var14 = 0.125D;
                    double var24 = (v16p - v16) * var14;
                    double var26 = (v18p - v18) * var14;
                    double var28 = (v20p - v20) * var14;
                    double var30 = (v22p - v22) * var14;

                    for (int var32 = 0; var32 < 8; ++var32) {
                        double var33 = 0.25D, var35 = v16, var37 = v18;
                        double var39 = (v20 - v16) * var33, var41 = (v22 - v18) * var33;

                        for (int var43 = 0; var43 < 4; ++var43) {
                            int lx = var43 + var11 * 4, lz = 0 + var12 * 4;
                            int y = var13 * 8 + var32;
                            double var46 = 0.25D, var48 = var35;
                            double var50 = (var37 - var35) * var46;

                            for (int var52 = 0; var52 < 4; ++var52) {
                                double var53 = var5[(var11 * 4 + var43) * 16 + var12 * 4 + var52];
                                int var55 = 0;
                                if (y < var7) {
                                    var55 = (var53 < 0.5D && y >= var7 - 1) ? BETA_ICE : BETA_WATER_STILL;
                                }
                                if (var48 > 0.0D) var55 = BETA_STONE;
                                setSectionBlock(var3, lx, y, lz + var52, var55 == 0 ? (byte)0 : (byte)var55);
                                var48 += var50;
                            }
                            var35 += var39; var37 += var41;
                        }
                        v16 += var24; v18 += var26; v20 += var28; v22 += var30;
                    }
                }
            }
        }
    }

    public void replaceBlocksForBiome(int var1, int var2, HashMap<Integer, byte[]> var3, int[] var4) {
        byte var5 = 64;
        double var6 = 1.0D / 32.0D;
        this.sandNoise = this.field_909_n.generateNoiseOctaves(this.sandNoise,
                (double)(var1*16), (double)(var2*16), 0.0D, 16, 16, 1, var6, var6, 1.0D);
        this.gravelNoise = this.field_909_n.generateNoiseOctaves(this.gravelNoise,
                (double)(var1*16), 109.0134D, (double)(var2*16), 16, 1, 16, var6, 1.0D, var6);
        this.stoneNoise = this.field_908_o.generateNoiseOctaves(this.stoneNoise,
                (double)(var1*16), (double)(var2*16), 0.0D, 16, 16, 1, var6*2.0D, var6*2.0D, var6*2.0D);

        int topY = -1;
        for (int cy : var3.keySet()) {
            int secTop = (cy << 4) + 15;
            if (secTop > topY) topY = secTop;
        }
        if (topY < 0) return;

        for (int var8 = 0; var8 < 16; ++var8) {
            for (int var9 = 0; var9 < 16; ++var9) {
                int biomeId = var4[var8 + var9 * 16];
                boolean var11 = this.sandNoise[var8 + var9 * 16] + this.rand.nextDouble() * 0.2D > 0.0D;
                boolean var12 = this.gravelNoise[var8 + var9 * 16] + this.rand.nextDouble() * 0.2D > 3.0D;
                int var13 = (int)(this.stoneNoise[var8 + var9 * 16] / 3.0D + 3.0D + this.rand.nextDouble() * 0.25D);
                int var14 = -1;
                byte var15 = (byte)BetaBiomeGenBase.TOP_BLOCKS[biomeId];
                byte var16 = (byte)BetaBiomeGenBase.FILLER_BLOCKS[biomeId];

                for (int var17 = topY; var17 >= 0; --var17) {
                    if (var17 <= 0 + this.rand.nextInt(5)) {
                        setSectionBlock(var3, var8, var17, var9, BETA_BEDROCK);
                    } else {
                        byte var19 = getSectionBlock(var3, var8, var17, var9);
                        if (var19 == 0) { var14 = -1; }
                        else if (var19 == BETA_STONE) {
                            if (var14 == -1) {
                                if (var13 <= 0) { var15 = 0; var16 = BETA_STONE; }
                                else if (var17 >= var5 - 4 && var17 <= var5 + 1) {
                                    var15 = (byte)BetaBiomeGenBase.TOP_BLOCKS[biomeId];
                                    var16 = (byte)BetaBiomeGenBase.FILLER_BLOCKS[biomeId];
                                    if (var12) { var15 = 0; var16 = BETA_GRAVEL; }
                                    if (var11) { var15 = BETA_SAND; var16 = BETA_SAND; }
                                }
                                if (var17 < var5 && var15 == 0) var15 = BETA_WATER_STILL;
                                var14 = var13;
                                // Require 8 blocks of air above for a proper surface.
                                // If terrain is too close above, leave it as bare stone.
                                byte topBlock = var15;
                                if (topBlock != 0) {
                                    boolean airAbove = true;
                                    for (int above = 1; above <= 8; above++) {
                                        if (getSectionBlock(var3, var8, var17 + above, var9) != 0) {
                                            airAbove = false;
                                            break;
                                        }
                                    }
                                    if (!airAbove) topBlock = BETA_STONE;
                                }
                                setSectionBlock(var3, var8, var17, var9,
                                    var17 >= var5 - 1 ? topBlock : var16);
                            } else if (var14 > 0) {
                                --var14;
                                setSectionBlock(var3, var8, var17, var9, var16);
                                if (var14 == 0 && var16 == BETA_SAND) {
                                    var14 = this.rand.nextInt(4); var16 = BETA_SANDSTONE;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  func_4061_a — density field (preserved exactly, fixed for absolute Y)
    // ══════════════════════════════════════════════════════════════════

    private double[] func_4061_a(double[] var1, int var2, int var3, int var4,
                                  int var5, int var6, int var7) {
        if (var1 == null) var1 = new double[var5 * var6 * var7];
        double var8 = 684.412D, var10 = 684.412D;
        double[] var12 = this.temperatures, var13 = this.humidities;

        // func_4061_a is called with varying Y sample counts: 257 for the
        // initial generateTerrain (5x257x5=6425) but potentially more for
        // batch generateSectionRange (e.g. 5x400+x5). Null out any reused
        // instance arrays that are too small so generateNoiseOctaves allocates
        // fresh ones of the correct size.
        int noiseSize = var5 * var6 * var7;
        if (this.field_4185_d == null || this.field_4185_d.length < noiseSize) this.field_4185_d = null;
        if (this.field_4184_e == null || this.field_4184_e.length < noiseSize) this.field_4184_e = null;
        if (this.field_4183_f == null || this.field_4183_f.length < noiseSize) this.field_4183_f = null;

        this.field_4182_g = this.field_922_a.func_4109_a(this.field_4182_g, var2, var4, var5, var7, 1.121D, 1.121D, 0.5D);
        this.field_4181_h = this.field_921_b.func_4109_a(this.field_4181_h, var2, var4, var5, var7, 200.0D, 200.0D, 0.5D);
        this.field_4185_d = this.field_910_m.generateNoiseOctaves(this.field_4185_d,
                (double)var2, (double)var3, (double)var4, var5, var6, var7,
                var8/80.0D, var10/160.0D, var8/80.0D);
        this.field_4184_e = this.field_912_k.generateNoiseOctaves(this.field_4184_e,
                (double)var2, (double)var3, (double)var4, var5, var6, var7, var8, var10, var8);
        this.field_4183_f = this.field_911_l.generateNoiseOctaves(this.field_4183_f,
                (double)var2, (double)var3, (double)var4, var5, var6, var7, var8, var10, var8);

        int var14 = 0, var15 = 0, var16 = 16 / var5;
        for (int var17 = 0; var17 < var5; ++var17) {
            int var18 = var17 * var16 + var16 / 2;
            for (int var19 = 0; var19 < var7; ++var19) {
                int var20 = var19 * var16 + var16 / 2;
                double var21 = var12[var18 * 16 + var20];
                double var23 = var13[var18 * 16 + var20] * var21;
                double var25 = 1.0D - var23; var25 *= var25; var25 *= var25; var25 = 1.0D - var25;
                double var27 = (this.field_4182_g[var15] + 256.0D) / 512.0D;
                var27 *= var25; if (var27 > 1.0D) var27 = 1.0D;

                double var29 = this.field_4181_h[var15] / 8000.0D;
                if (var29 < 0.0D) var29 = -var29 * 0.3D;
                var29 = var29 * 3.0D - 2.0D;
                if (var29 < 0.0D) {
                    var29 /= 2.0D; if (var29 < -1.0D) var29 = -1.0D;
                    var29 /= 1.4D; var29 /= 2.0D; var27 = 0.0D;
                } else { if (var29 > 1.0D) var29 = 1.0D; var29 /= 8.0D; }
                if (var27 < 0.0D) var27 = 0.0D;
                var27 += 0.5D;
                var29 = var29 * BETA_Y_SAMPLES / 16.0D;
                double var31 = BETA_Y_SAMPLES / 2.0D + var29 * 4.0D;
                ++var15;

                for (int var33 = 0; var33 < var6; ++var33) {
                    double var34 = 0.0D;
                    double var36 = ((double)(var33 + var3) - var31) * 12.0D / var27;
                    if (var36 < 0.0D) var36 *= 4.0D;
                    double var38 = this.field_4184_e[var14] / 512.0D;
                    double var40 = this.field_4183_f[var14] / 512.0D;
                    double var42 = (this.field_4185_d[var14] / 10.0D + 1.0D) / 2.0D;
                    if (var42 < 0.0D) var34 = var38;
                    else if (var42 > 1.0D) var34 = var40;
                    else var34 = var38 + (var40 - var38) * var42;
                    var34 -= var36;
                    var1[var14] = var34; ++var14;
                }
            }
        }
        return var1;
    }

    // ══════════════════════════════════════════════════════════════════
    //  CACHE / DECORATION
    // ══════════════════════════════════════════════════════════════════

    public boolean isColumnCached(int cx, int cz) {
        return columnGenerated && columnCX == cx && columnCZ == cz;
    }

    public void clearColumnCache() {
        if (columnSections != null) columnSections.clear();
        columnGenerated = false; columnCX = Integer.MIN_VALUE; columnCZ = Integer.MIN_VALUE;
        maxSectionCY = -1;
        neighborBlocks.clear();
        decorationOverlay.clear();
    }

    public void invalidateCache() {
        columnGenerated = false; columnCX = Integer.MIN_VALUE; columnCZ = Integer.MIN_VALUE;
    }

    public int[] getCurrentBiomes() { return biomesForGeneration; }
    public double[] getCurrentTemperatures() { return temperatures; }
    public int getBetaBiomeId(int x, int z) { return worldChunkManager.getBiomeGenAt(x, z); }

    // ══════════════════════════════════════════════════════════════════
    //  POPULATION (decoration)
    // ══════════════════════════════════════════════════════════════════

    public void populateColumn(com.voxel.World world, int cx, int cz) {
        if (!columnGenerated || columnCX != cx || columnCZ != cz) generateColumn(cx, cz);

        neighborBlocks.clear();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dz != 0) getColumnBlocks(cx + dx, cz + dz);

        int var4 = cx * 16, var5 = cz * 16;
        this.rand.setSeed(worldSeed);
        long var7 = this.rand.nextLong() / 2L * 2L + 1L;
        long var9 = this.rand.nextLong() / 2L * 2L + 1L;
        this.rand.setSeed((long)cx * var7 + (long)cz * var9 ^ worldSeed);
        int biomeId = this.biomesForGeneration[8 + 8 * 16];

        for (int i = 0; i < 20; ++i) { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(128), var5+rand.nextInt(16), veDirt, 32); }
        for (int i = 0; i < 10; ++i) { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(128), var5+rand.nextInt(16), veGravel, 32); }
        for (int i = 0; i < 20; ++i) { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(128), var5+rand.nextInt(16), veCoalOre, 16); }
        for (int i = 0; i < 20; ++i) { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(64), var5+rand.nextInt(16), veIronOre, 8); }
        for (int i = 0; i < 2; ++i)  { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(32), var5+rand.nextInt(16), veGoldOre, 8); }
        for (int i = 0; i < 8; ++i)  { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(16), var5+rand.nextInt(16), veRedstoneOre, 7); }
        for (int i = 0; i < 1; ++i)  { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(16), var5+rand.nextInt(16), veDiamondOre, 7); }
        for (int i = 0; i < 1; ++i)  { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(16)+rand.nextInt(16), var5+rand.nextInt(16), veLapisOre, 6); }
        // Glowstone ore: copious amounts across the full column for Far Lands lighting
        int glowMaxY = Math.max(128, (maxSectionCY + 1) << 4);
        for (int i = 0; i < 60; ++i) { genOreVein(world, var4+rand.nextInt(16), rand.nextInt(glowMaxY), var5+rand.nextInt(16), veGlowstone, 12); }

        double var11 = 0.5D;
        int treeBase = (int)((this.mobSpawnerNoise.func_806_a((double)var4*var11, (double)var5*var11)/8.0D+rand.nextDouble()*4.0D+4.0D)/3.0D);
        int treeCount = rand.nextInt(10)==0 ? 1 : 0;
        switch (biomeId) {
            case BetaBiomeGenBase.FOREST: case BetaBiomeGenBase.RAINFOREST: case BetaBiomeGenBase.TAIGA: treeCount+=treeBase+5; break;
            case BetaBiomeGenBase.SEASONAL_FOREST: treeCount+=treeBase+2; break;
            case BetaBiomeGenBase.DESERT: case BetaBiomeGenBase.TUNDRA: case BetaBiomeGenBase.PLAINS: treeCount-=20; break;
            default: treeCount+=treeBase; break;
        }
        for (int i=0; i<treeCount; ++i) {
            int tx=var4+rand.nextInt(16)+8, tz=var5+rand.nextInt(16)+8, ty=worldGetTopY(tx,tz);
            if (ty>0) placeTree(world,tx,ty+1,tz,biomeId);
        }
        int flowerCount=0;
        switch (biomeId) { case BetaBiomeGenBase.FOREST: case BetaBiomeGenBase.TAIGA: flowerCount=2; break; case BetaBiomeGenBase.SEASONAL_FOREST: flowerCount=4; break; case BetaBiomeGenBase.PLAINS: flowerCount=3; break; }
        for (int i=0; i<flowerCount; ++i) { int fx=var4+rand.nextInt(16)+8, fy=rand.nextInt(128), fz=var5+rand.nextInt(16)+8; if (world.getVoxel(fx,fy,fz)==veGrass||world.getVoxel(fx,fy,fz)==veDirt) if (world.getVoxel(fx,fy+1,fz)==0) setVoxelColumnAware(world,fx,fy+1,fz,veDandelion,BETA_PLANT_YELLOW); }
        int grassCount=0;
        switch (biomeId) { case BetaBiomeGenBase.FOREST: grassCount=2; break; case BetaBiomeGenBase.RAINFOREST: grassCount=10; break; case BetaBiomeGenBase.SEASONAL_FOREST: grassCount=2; break; case BetaBiomeGenBase.TAIGA: grassCount=1; break; case BetaBiomeGenBase.PLAINS: grassCount=10; break; }
        for (int i=0; i<grassCount; ++i) { int gx=var4+rand.nextInt(16)+8, gy=rand.nextInt(128), gz=var5+rand.nextInt(16)+8; if (world.getVoxel(gx,gy,gz)==veGrass||world.getVoxel(gx,gy,gz)==veDirt) if (world.getVoxel(gx,gy+1,gz)==0) setVoxelColumnAware(world,gx,gy+1,gz,veTallGrass,BETA_TALL_GRASS); }
        if (rand.nextInt(2)==0) { int rx=var4+rand.nextInt(16)+8, ry=rand.nextInt(128), rz=var5+rand.nextInt(16)+8; if (world.getVoxel(rx,ry,rz)==veGrass||world.getVoxel(rx,ry,rz)==veDirt) if (world.getVoxel(rx,ry+1,rz)==0) setVoxelColumnAware(world,rx,ry+1,rz,veRose,BETA_PLANT_RED); }
        for (int i=0;i<50;++i){generateLake(world,var4+rand.nextInt(16),rand.nextInt(120)+4,var5+rand.nextInt(16),veWaterStill);}
        for (int i=0;i<50;++i){generateLake(world,var4+rand.nextInt(16),rand.nextInt(rand.nextInt(10)+8),var5+rand.nextInt(16),veLavaStill);}
        generateBeaches(world,cx,cz); generateClay(world,cx,cz);
        for (int i=0;i<1;++i){generateDungeon(world,var4+rand.nextInt(16),rand.nextInt(30)+6,var5+rand.nextInt(16));}
        if (biomeId==BetaBiomeGenBase.DESERT) for (int i=0;i<2;++i){int dx=var4+rand.nextInt(16)+8,dy=rand.nextInt(128),dz=var5+rand.nextInt(16)+8;if(world.getVoxel(dx,dy,dz)==veSand)if(world.getVoxel(dx,dy+1,dz)==0)setVoxelColumnAware(world,dx,dy+1,dz,veDeadBush,BETA_DEAD_BUSH);}
        for (int i=0;i<64;++i){generatePumpkinPatch(world,var4+rand.nextInt(16)+8,rand.nextInt(128),var5+rand.nextInt(16)+8);}
        if (biomeId==BetaBiomeGenBase.DESERT) for (int i=0;i<10;++i){int cx2=var4+rand.nextInt(16)+8,cz2=var5+rand.nextInt(16)+8;int topY=worldGetTopY(cx2,cz2);if(topY>0)generateCactusPatch(world,cx2,topY+1,cz2);}
        int sugarAttempts=(biomeId==BetaBiomeGenBase.DESERT)?20:1;
        for (int i=0;i<sugarAttempts;++i){int sx=var4+rand.nextInt(16)+8,sz=var5+rand.nextInt(16)+8;int topY=worldGetTopY(sx,sz);if(topY>0)generateSugarCanePatch(world,sx,topY+1,sz);}
        generateSnow(world,cx,cz);

        for (Map.Entry<Long, HashMap<Integer, byte[]>> entry : neighborBlocks.entrySet()) {
            long key = entry.getKey();
            int ncx = (int)(key>>32), ncz = (int)(key & 0xFFFFFFFFL);
            HashMap<Integer, byte[]> nb = entry.getValue();
            int bx = ncx*16, bz = ncz*16;
            HashMap<Integer, byte[]> overlay = new HashMap<>();
            boolean hasDeco = false;
            for (Map.Entry<Integer, byte[]> se : nb.entrySet()) {
                int ocy = se.getKey();
                byte[] slice = se.getValue();
                byte[] os = null;
                for (int i = 0; i < 4096; i++) {
                    byte betaId = slice[i];
                    if (betaId == 0) continue;
                    int lx = i & 15, ly = (i>>4)&15, lz = (i>>8)&15;
                    int y = (ocy<<4)|ly;
                    int veId = mapToVeBlock(betaId & 0xFF);
                    if (veId != 0) world.setVoxel(bx+lx, y, bz+lz, veId);
                    if (betaId == BETA_LEAVES || betaId == BETA_WOOD) {
                        if (os == null) { os = new byte[4096]; overlay.put(ocy, os); }
                        os[i] = betaId; hasDeco = true;
                    }
                }
            }
            if (hasDeco) decorationOverlay.put(key, overlay);
        }
        neighborBlocks.clear();
    }

    private void genOreVein(com.voxel.World world, int cx, int cy, int cz, int blockId, int count) {
        float f = this.rand.nextFloat() * (float)Math.PI;
        double dx=(double)((float)(cx+8)+(float)Math.sin(f)*(float)count/8.0F);
        double dy=(double)((float)(cx+8)-(float)Math.sin(f)*(float)count/8.0F);
        double dz=(double)((float)(cz+8)+(float)Math.cos(f)*(float)count/8.0F);
        double dw=(double)((float)(cz+8)-(float)Math.cos(f)*(float)count/8.0F);
        double ex=(double)(cy+this.rand.nextInt(3)-2), ey=(double)(cy+this.rand.nextInt(3)-2);
        for (int i=0;i<count;++i){float progress=(float)i/(float)count;
            double cx2=dx+(dy-dx)*(double)progress, cy2=ex+(ey-ex)*(double)progress, cz2=dz+(dw-dz)*(double)progress;
            double radius=this.rand.nextDouble()*(double)count/16.0D;
            double rXZ=(double)((float)Math.sin((float)i*(float)Math.PI/(float)count)+1.0F)*radius+1.0D;
            double rY=(double)((float)Math.sin((float)i*(float)Math.PI/(float)count)+1.0F)*radius+1.0D;
            int minX=(int)Math.floor(cx2-rXZ/2.0D),minY=(int)Math.floor(cy2-rY/2.0D),minZ=(int)Math.floor(cz2-rXZ/2.0D);
            int maxX=(int)Math.floor(cx2+rXZ/2.0D),maxY=(int)Math.floor(cy2+rY/2.0D),maxZ=(int)Math.floor(cz2+rXZ/2.0D);
            for(int px=minX;px<=maxX;++px){double dxD=((double)px+0.5D-cx2)/(rXZ/2.0D);if(dxD*dxD>=1.0D)continue;
                for(int py=minY;py<=maxY;++py){double dyD=((double)py+0.5D-cy2)/(rY/2.0D);if(dxD*dxD+dyD*dyD>=1.0D)continue;
                    for(int pz=minZ;pz<=maxZ;++pz){double dzD=((double)pz+0.5D-cz2)/(rXZ/2.0D);if(dxD*dxD+dyD*dyD+dzD*dzD<1.0D){
                        HashMap<Integer,byte[]>b=getColumnBlocks(px>>4,pz>>4);
                        if((getSectionBlock(b,px&15,py,pz&15)&0xFF)==BETA_STONE){setSectionBlock(b,px&15,py,pz&15,BETA_STONE);world.setVoxel(px,py,pz,blockId);}
                    }}}
            }
        }
    }

    private int worldGetTopY(int x, int z) {
        HashMap<Integer,byte[]> blocks=getColumnBlocks(x>>4,z>>4);
        int lx=x&15,lz=z&15,topCY=-1;
        for(int cy:blocks.keySet())if(cy>topCY)topCY=cy;
        for(int cy=topCY;cy>=0;cy--){byte[]sec=blocks.get(cy);if(sec==null)continue;
            for(int ly=15;ly>=0;ly--)if(sec[sectionIdx(lx,ly,lz)]!=0)return(cy<<4)|ly;}
        return 0;
    }

    private HashMap<Integer,byte[]> getColumnBlocks(int cx,int cz){
        if(columnGenerated&&columnCX==cx&&columnCZ==cz)return columnSections;
        long key=((long)cx<<32)|(cz&0xFFFFFFFFL);
        HashMap<Integer,byte[]>blocks=neighborBlocks.get(key);
        if(blocks==null){blocks=generateColumnCopy(cx,cz);neighborBlocks.put(key,blocks);}
        return blocks;
    }

    private void setVoxelColumnAware(com.voxel.World world,int x,int y,int z,int veId,int betaId){
        world.setVoxel(x,y,z,veId);
        HashMap<Integer,byte[]>b=getColumnBlocks(x>>4,z>>4);
        setSectionBlock(b,x&15,y,z&15,(byte)betaId);
    }

    private void placeTree(com.voxel.World world,int x,int y,int z,int biomeId){
        int height;boolean isBig=false;
        if((biomeId==BetaBiomeGenBase.FOREST||biomeId==BetaBiomeGenBase.RAINFOREST)&&rand.nextInt(10)==0){isBig=true;height=5+rand.nextInt(11);}
        else height=4+rand.nextInt(3);
        HashMap<Integer,byte[]>tb=getColumnBlocks(x>>4,z>>4);int lx=x&15,lz=z&15;
        for(int dy=0;dy<height+2;dy++){if(getSectionBlock(tb,lx,y+dy,lz)!=0&&dy<height)return;}
        for(int dy=0;dy<height;dy++){setSectionBlock(tb,lx,y+dy,lz,BETA_WOOD);world.setVoxel(x,y+dy,z,veWood);}
        int leafStart=height-3;if(isBig)leafStart=height-4;
        for(int dy=leafStart;dy<=height;dy++){int radius=(dy==leafStart||dy==height)?1:2;if(isBig&&dy>=leafStart+1&&dy<height)radius=2+(dy-leafStart-1);
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
                if(Math.abs(dx)==radius&&Math.abs(dz)==radius&&rand.nextInt(2)==0)continue;if(dy==height&&(Math.abs(dx)>1||Math.abs(dz)>1))continue;
                int wx=x+dx,wz=z+dz,ly=y+dy;
                HashMap<Integer,byte[]>lb=getColumnBlocks(wx>>4,wz>>4);
                if(getSectionBlock(lb,wx&15,ly,wz&15)==0){setSectionBlock(lb,wx&15,ly,wz&15,BETA_LEAVES);world.setVoxel(wx,ly,wz,veLeaves);}
            }
        }
        if(isBig){int topY=y+height;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(Math.abs(dx)+Math.abs(dz)<=1){
                int wx=x+dx,wz=z+dz;HashMap<Integer,byte[]>lb=getColumnBlocks(wx>>4,wz>>4);
                if(getSectionBlock(lb,wx&15,topY,wz&15)==0){setSectionBlock(lb,wx&15,topY,wz&15,BETA_LEAVES);world.setVoxel(wx,topY,wz,veLeaves);}
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  DECORATION HELPERS
    // ══════════════════════════════════════════════════════════════════

    private void generateLake(com.voxel.World world,int cx,int cy,int cz,int blockId){
        if(blockId==veLavaStill&&cy<5)return;int radius=4+rand.nextInt(4);int sc=0,ac=0;
        for(int x=cx-radius;x<=cx+radius;x++)for(int y=cy-3;y<=cy+3;y++)for(int z=cz-radius;z<=cz+radius;z++){int v=world.getVoxel(x,y,z);if(v==0)ac++;else sc++;}
        if(ac>sc/4)return;
        for(int s=0;s<4;s++){int ox=cx+rand.nextInt(radius)-radius/2,oy=cy+rand.nextInt(3),oz=cz+rand.nextInt(radius)-radius/2,r=2+rand.nextInt(3);
            for(int x=ox-r;x<=ox+r;x++)for(int y=oy-r;y<=oy+r;y++)for(int z=oz-r;z<=oz+r;z++){
                int dx=x-ox,dy=y-oy,dz=z-oz;if(dx*dx+dy*dy+dz*dz<=r*r){int ex=world.getVoxel(x,y,z);if(ex==0||ex==blockId)continue;world.setVoxel(x,y,z,y>oy?0:blockId);}
            }
        }
    }

    private void generateBeaches(com.voxel.World world,int cx,int cz){int bx=cx*16,bz=cz*16;
        for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){int wx=bx+lx,wz=bz+lz,topY=0;byte topBeta=0;
            int scanFrom=maxSectionCY>=0?(maxSectionCY<<4)+15:127;
            for(int y=scanFrom;y>=60;y--){int v=world.getVoxel(wx,y,wz);if(v!=0&&v!=veWaterStill&&v!=veIce){topY=y;topBeta=betaForVe(v);break;}}
            if(topY==0)continue;boolean nearWater=false;
            for(int dx=-8;dx<=8;dx+=2){for(int dz=-8;dz<=8;dz+=2){if(dx==0&&dz==0)continue;for(int dy=-2;dy<=2;dy++){int ny=topY+dy;if(world.getVoxel(wx+dx,ny,wz+dz)==veWaterStill){nearWater=true;break;}}if(nearWater)break;}if(nearWater)break;}
            if(nearWater&&(topBeta==BETA_GRASS||topBeta==BETA_DIRT)){world.setVoxel(wx,topY,wz,veSand);for(int dy=1;dy<=3&&(topY-dy)>=60;dy++){int below=world.getVoxel(wx,topY-dy,wz);if(below==veDirt||below==veGrass)world.setVoxel(wx,topY-dy,wz,veSand);else break;}}
        }
    }

    private boolean isSnowLevel(int veId){for(int l=1;l<=8;l++)if(veSnowLevels[l]==veId)return true;return veId==veSnow;}
    private byte betaForVe(int veId){if(veId==veStone)return BETA_STONE;if(veId==veGrass)return BETA_GRASS;if(veId==veDirt)return BETA_DIRT;if(veId==veSand)return BETA_SAND;if(veId==veGravel)return BETA_GRAVEL;if(veId==veWaterStill)return BETA_WATER_STILL;if(veId==veIce)return BETA_ICE;return 0;}

    private void generateClay(com.voxel.World world,int cx,int cz){int bx=cx*16,bz=cz*16;
        for(int i=0;i<4;i++){int wx=bx+rand.nextInt(16),wz=bz+rand.nextInt(16);
            for(int y=55;y<=64;y++){int v=world.getVoxel(wx,y,wz);if((v==veSand||v==veGravel)&&world.getVoxel(wx,y+1,wz)==veWaterStill){for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){if(rand.nextInt(3)==0)continue;int tv=world.getVoxel(wx+dx,y,wz+dz);if(tv==veSand||tv==veGravel)world.setVoxel(wx+dx,y,wz+dz,veClay);}break;}}
        }
    }

    private void generateDungeon(com.voxel.World world,int cx,int cy,int cz){int width=5+rand.nextInt(4),height=4,half=width/2,airIn=0,solidAround=0;
        for(int x=cx-half-1;x<=cx+half+1;x++)for(int y=cy-1;y<=cy+height;y++)for(int z=cz-half-1;z<=cz+half+1;z++){int v=world.getVoxel(x,y,z);boolean w=(x==cx-half-1||x==cx+half+1||z==cz-half-1||z==cz+half+1||y==cy-1||y==cy+height);boolean in=(x>=cx-half&&x<=cx+half&&z>=cz-half&&z<=cz+half&&y>=cy&&y<=cy+height-1);if(in&&v==0)airIn++;else if(w&&v!=0)solidAround++;}
        if(airIn<(width*width*height)/4||solidAround<20)return;
        for(int x=cx-half-1;x<=cx+half+1;x++)for(int y=cy-1;y<=cy+height;y++)for(int z=cz-half-1;z<=cz+half+1;z++){boolean w=(x==cx-half-1||x==cx+half+1||z==cz-half-1||z==cz+half+1||y==cy-1||y==cy+height);boolean in=(x>=cx-half&&x<=cx+half&&z>=cz-half&&z<=cz+half&&y>=cy&&y<=cy+height-1);if(in)world.setVoxel(x,y,z,0);else if(w)world.setVoxel(x,y,z,rand.nextInt(4)==0?veMossyCobble:veCobblestone);}
        world.setVoxel(cx,cy,cz,veSpawner);int cc=1+rand.nextInt(2);for(int i=0;i<cc;i++){int chX=cx+(rand.nextInt(width)-half),chZ=cz+(rand.nextInt(width)-half);if(Math.abs(chX-cx)<Math.abs(chZ-cz))chX=cx+(chX>cx?half:-half);else chZ=cz+(chZ>cz?half:-half);if(world.getVoxel(chX,cy+1,chZ)==0)world.setVoxel(chX,cy,chZ,veChest);}
    }

    private void generatePumpkinPatch(com.voxel.World world,int x,int y,int z){for(int dy=y;dy>0;dy--)if(world.getVoxel(x,dy,z)!=0){y=dy+1;break;}if(y<=0||world.getVoxel(x,y-1,z)!=veGrass||world.getVoxel(x,y,z)!=0)return;setVoxelColumnAware(world,x,y,z,vePumpkin,BETA_PUMPKIN);int cluster=1+rand.nextInt(3);for(int i=0;i<cluster;i++){int px=x+rand.nextInt(5)-2,pz=z+rand.nextInt(5)-2;if(px==x&&pz==z)continue;int py=y;for(int dy=py;dy>0;dy--)if(world.getVoxel(px,dy,pz)!=0){py=dy+1;break;}if(py<=0||world.getVoxel(px,py-1,pz)!=veGrass||world.getVoxel(px,py,pz)!=0)continue;setVoxelColumnAware(world,px,py,pz,vePumpkin,BETA_PUMPKIN);}}

    private void generateCactusPatch(com.voxel.World world,int x,int y,int z){if(y<=0||world.getVoxel(x,y-1,z)!=veSand)return;int h=1+rand.nextInt(3);for(int dy=0;dy<h;dy++){if(world.getVoxel(x,y+dy,z)!=0)return;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){if((dx==0&&dz==0)||Math.abs(dx)+Math.abs(dz)!=1)continue;if(world.getVoxel(x+dx,y+dy,z+dz)!=0)return;}}for(int dy=0;dy<h;dy++)setVoxelColumnAware(world,x,y+dy,z,veCactus,BETA_CACTUS);}

    private void generateSugarCanePatch(com.voxel.World world,int x,int y,int z){if(y<=0)return;int g=world.getVoxel(x,y-1,z);if(g!=veGrass&&g!=veDirt&&g!=veSand)return;boolean nw=false;for(int dx=-1;dx<=1;dx++){for(int dz=-1;dz<=1;dz++){if(Math.abs(dx)+Math.abs(dz)!=1)continue;if(world.getVoxel(x+dx,y-1,z+dz)==veWaterStill){nw=true;break;}}if(nw)break;}if(!nw)return;int h=2+rand.nextInt(3);for(int dy=0;dy<h;dy++){if(world.getVoxel(x,y+dy,z)!=0)break;setVoxelColumnAware(world,x,y+dy,z,veSugarCane,(byte)0);}}

    private void generateSnow(com.voxel.World world,int cx,int cz){if(biomesForGeneration==null)return;int bx=cx*16,bz=cz*16;int scanFrom=maxSectionCY>=0?(maxSectionCY<<4)+15:127;
        for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){int biomeId=biomesForGeneration[lx+lz*16];if(biomeId!=BetaBiomeGenBase.TAIGA&&biomeId!=BetaBiomeGenBase.TUNDRA&&biomeId!=BetaBiomeGenBase.ICE_DESERT)continue;int wx=bx+lx,wz=bz+lz;
            for(int y=scanFrom;y>=50;y--){int v=world.getVoxel(wx,y,wz);if(v==0||v==veWaterStill||v==veIce)continue;if(v==veLeaves||v==veTallGrass||v==veDandelion||v==veRose||v==veDeadBush||v==veSugarCane||v==veCactus||v==vePumpkin)continue;if(isSnowLevel(v))continue;int aboveY=y+1,above=world.getVoxel(wx,aboveY,wz);if(above!=0&&!isSnowLevel(above))break;int level;if(y>90)level=1;else if(y>75)level=1+rand.nextInt(2);else level=1;level=Math.min(level,8);int snowId=veSnowLevels[level];if(snowId>0)setVoxelColumnAware(world,wx,aboveY,wz,snowId,(byte)0);break;}
        }
    }

    public long getWorldSeed(){return worldSeed;}
    public NoiseGeneratorOctaves getMobSpawnerNoise(){return mobSpawnerNoise;}
    public Random getRand(){return rand;}
}
