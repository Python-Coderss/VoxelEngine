package com.voxel.world.beta;

import com.voxel.World;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Faithful port of Beta 1.8.1's ChunkProviderGenerate (continental terrain),
 * adapted for cubic chunks. Generates the classic 0..127 column once per chunk
 * (sections 0..7); sections above 127 are air and sections below 0 are
 * density-evaluated deep stone.
 */
public class BetaChunkProvider implements BetaGenContext {
    // ── Classic beta block ids ──
    private static final int BETA_AIR = 0, BETA_STONE = 1, BETA_GRASS = 2, BETA_DIRT = 3;
    private static final int BETA_COBBLESTONE = 4, BETA_BEDROCK = 7;
    private static final int BETA_WATER_MOVING = 8, BETA_WATER_STILL = 9;
    private static final int BETA_LAVA_MOVING = 10, BETA_LAVA_STILL = 11;
    private static final int BETA_SAND = 12, BETA_GRAVEL = 13;
    private static final int BETA_GOLD_ORE = 14, BETA_IRON_ORE = 15, BETA_COAL_ORE = 16;
    private static final int BETA_WOOD = 17, BETA_LEAVES = 18, BETA_LAPIS_ORE = 21;
    private static final int BETA_SANDSTONE = 24, BETA_TALL_GRASS = 31, BETA_DEAD_BUSH = 32;
    private static final int BETA_PLANT_YELLOW = 37, BETA_PLANT_RED = 38;
    private static final int BETA_MUSHROOM_BROWN = 39, BETA_MUSHROOM_RED = 40;
    private static final int BETA_MOSSY_COBBLE = 48, BETA_MOB_SPAWNER = 52, BETA_CHEST = 54;
    private static final int BETA_DIAMOND_ORE = 56, BETA_REDSTONE_ORE = 73, BETA_SNOW = 78, BETA_ICE = 79;
    private static final int BETA_CACTUS = 81, BETA_CLAY = 82, BETA_REEDS = 83, BETA_PUMPKIN = 86;

    private static final int SEA_LEVEL = 63;
    private static final int COLUMN_BYTES = 16 * 128 * 16;

    // ── Noise generators (1.8.1 octave counts) ──
    private final NoiseGeneratorOctaves field_912_k; // 16
    private final NoiseGeneratorOctaves field_911_l; // 16
    private final NoiseGeneratorOctaves field_910_m; // 8
    private final NoiseGeneratorOctaves field_908_o; // 4 (stoneNoise)
    private final NoiseGeneratorOctaves field_922_a; // 10 (field_4182_g)
    private final NoiseGeneratorOctaves field_921_b; // 16 (field_4181_h continental)
    public final NoiseGeneratorOctaves mobSpawnerNoise; // 8

    // Scratch noise arrays.
    private double[] field_4185_d, field_4184_e, field_4183_f, field_4182_g, field_4181_h;
    private double[] stoneNoise = new double[256];
    private float[] field_35388_l; // 25-entry biome weight table

    private final long worldSeed;
    private final BetaBlocks blocks;
    private final BetaWorldChunkManager worldChunkManager;
    private final BetaMapGenCaves caveGen = new BetaMapGenCaves();
    private final BetaMapGenRavine ravineGen = new BetaMapGenRavine();
    private final BetaBiomeDecorator decorator;

    // ── Cubic section cache for the current column ──
    private HashMap<Integer, byte[]> columnSections;
    private int columnCX = Integer.MIN_VALUE;
    private int columnCZ = Integer.MIN_VALUE;

    // Neighbor column cache (persistent LRU) for cross-column decoration reads.
    private static final int NEIGHBOR_CACHE_CAPACITY = 96;
    private final Map<Long, HashMap<Integer, byte[]>> neighborBlocks =
            new LinkedHashMap<Long, HashMap<Integer, byte[]>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, HashMap<Integer, byte[]>> eldest) {
                    return size() > NEIGHBOR_CACHE_CAPACITY;
                }
            };

    private World world;

    public BetaChunkProvider(long seed, BetaBlocks blocks) {
        this.worldSeed = seed;
        this.blocks = blocks;
        Random rand = new Random(seed);
        this.field_912_k = new NoiseGeneratorOctaves(rand, 16);
        this.field_911_l = new NoiseGeneratorOctaves(rand, 16);
        this.field_910_m = new NoiseGeneratorOctaves(rand, 8);
        this.field_908_o = new NoiseGeneratorOctaves(rand, 4);
        this.field_922_a = new NoiseGeneratorOctaves(rand, 10);
        this.field_921_b = new NoiseGeneratorOctaves(rand, 16);
        this.mobSpawnerNoise = new NoiseGeneratorOctaves(rand, 8);
        this.worldChunkManager = new BetaWorldChunkManager(seed);
        this.decorator = new BetaBiomeDecorator(blocks);
    }

    // ══════════════════════════════════════════════════════════════════
    //  COLUMN GENERATION
    // ══════════════════════════════════════════════════════════════════

    private void ensureColumn(int cx, int cz) {
        if (columnCX == cx && columnCZ == cz && columnSections != null) return;
        columnCX = cx;
        columnCZ = cz;
        columnSections = generateColumnBlocks(cx, cz);
    }

    private HashMap<Integer, byte[]> generateColumnBlocks(int cx, int cz) {
        byte[] col = new byte[COLUMN_BYTES];
        Random r = new Random((long) cx * 341873128712L + (long) cz * 132897987541L);

        BetaBiomeGenBase[] densityBiomes = worldChunkManager.func_35557_b(null, cx * 4 - 2, cz * 4 - 2, 10, 10);
        generateTerrain(cx, cz, col, densityBiomes);

        BetaBiomeGenBase[] surfaceBiomes = worldChunkManager.loadBlockGeneratorData(null, cx * 16, cz * 16, 16, 16);
        replaceBlocksForBiome(cx, cz, col, surfaceBiomes, r);

        caveGen.generate(worldSeed, cx, cz, col);
        ravineGen.generate(worldSeed, cx, cz, col);

        HashMap<Integer, byte[]> sections = new HashMap<>();
        for (int y = 0; y < 128; y++) {
            int cy = y >> 4, ly = y & 15;
            byte[] sec = sections.get(cy);
            if (sec == null) { sec = new byte[4096]; sections.put(cy, sec); }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    byte b = col[x * 2048 + z * 128 + y];
                    if (b != 0) sec[x | (ly << 4) | (z << 8)] = b;
                }
            }
        }
        return sections;
    }

    /** The classic 1.8.1 generateTerrain (stone + water fill, no ice). */
    private void generateTerrain(int cx, int cz, byte[] blocks, BetaBiomeGenBase[] densityBiomes) {
        double[] density = func_4061_a(null, cx * 4, 0, cz * 4, 5, 17, 5, densityBiomes);
        for (int dx = 0; dx < 4; ++dx) {
            for (int dz = 0; dz < 4; ++dz) {
                for (int dy = 0; dy < 16; ++dy) {
                    double v00 = density[((dx) * 5 + dz) * 17 + dy];
                    double v01 = density[((dx) * 5 + dz + 1) * 17 + dy];
                    double v10 = density[((dx + 1) * 5 + dz) * 17 + dy];
                    double v11 = density[((dx + 1) * 5 + dz + 1) * 17 + dy];
                    double d00 = (density[((dx) * 5 + dz) * 17 + dy + 1] - v00) * 0.125D;
                    double d01 = (density[((dx) * 5 + dz + 1) * 17 + dy + 1] - v01) * 0.125D;
                    double d10 = (density[((dx + 1) * 5 + dz) * 17 + dy + 1] - v10) * 0.125D;
                    double d11 = (density[((dx + 1) * 5 + dz + 1) * 17 + dy + 1] - v11) * 0.125D;

                    for (int sy = 0; sy < 8; ++sy) {
                        double v0 = v00, v1 = v01;
                        double v0s = (v10 - v00) * 0.25D;
                        double v1s = (v11 - v01) * 0.25D;
                        for (int sx = 0; sx < 4; ++sx) {
                            int lx = sx + dx * 4;
                            int y = dy * 8 + sy;
                            double cur = v0;
                            double curS = (v1 - v0) * 0.25D;
                            for (int sz = 0; sz < 4; ++sz) {
                                int lz = sz + dz * 4;
                                int block = 0;
                                if (y < SEA_LEVEL) block = BETA_WATER_STILL;
                                if (cur > 0.0D) block = BETA_STONE;
                                blocks[(lx * 16 + lz) * 128 + y] = (byte) block;
                                cur += curS;
                            }
                            v0 += v0s;
                            v1 += v1s;
                        }
                        v00 += d00;
                        v01 += d01;
                        v10 += d10;
                        v11 += d11;
                    }
                }
            }
        }
    }

    /**
     * func_4061_a — the 1.8.1 continental density field. Height/variation are
     * averaged over a 5×5 biome kernel; the 16-octave 200-scale noise provides
     * the continent/ocean signal. Density is laid out [x][z][y].
     */
    private double[] func_4061_a(double[] out, int x, int y, int z, int xSize, int ySize, int zSize,
                                 BetaBiomeGenBase[] biomesForGeneration) {
        if (out == null) out = new double[xSize * ySize * zSize];

        if (this.field_35388_l == null) {
            this.field_35388_l = new float[25];
            for (int dx = -2; dx <= 2; ++dx)
                for (int dz = -2; dz <= 2; ++dz) {
                    float w = 10.0F / BetaMathHelper.sqrt_float((float) (dx * dx + dz * dz) + 0.2F);
                    this.field_35388_l[dx + 2 + (dz + 2) * 5] = w;
                }
        }

        double n = 684.412D;
        this.field_4182_g = this.field_922_a.func_4109_a(this.field_4182_g, x, z, xSize, zSize, 1.121D, 1.121D, 0.5D);
        this.field_4181_h = this.field_921_b.func_4109_a(this.field_4181_h, x, z, xSize, zSize, 200.0D, 200.0D, 0.5D);
        this.field_4185_d = this.field_910_m.generateNoiseOctaves(this.field_4185_d, x, y, z, xSize, ySize, zSize, n / 80.0D, n / 160.0D, n / 80.0D);
        this.field_4184_e = this.field_912_k.generateNoiseOctaves(this.field_4184_e, x, y, z, xSize, ySize, zSize, n, n, n);
        this.field_4183_f = this.field_911_l.generateNoiseOctaves(this.field_4183_f, x, y, z, xSize, ySize, zSize, n, n, n);

        int densityIdx = 0;
        int noise2dIdx = 0;
        for (int bx = 0; bx < xSize; ++bx) {
            for (int bz = 0; bz < zSize; ++bz) {
                float height = 0.0F;
                float variation = 0.0F;
                float weight = 0.0F;
                BetaBiomeGenBase centerBiome = biomesForGeneration[bx + 2 + (bz + 2) * (xSize + 5)];
                for (int kx = -2; kx <= 2; ++kx) {
                    for (int kz = -2; kz <= 2; ++kz) {
                        BetaBiomeGenBase b = biomesForGeneration[bx + kx + 2 + (bz + kz + 2) * (xSize + 5)];
                        float w = this.field_35388_l[kx + 2 + (kz + 2) * 5] / (b.field_35492_q + 2.0F);
                        if (b.field_35492_q > centerBiome.field_35492_q) w /= 2.0F;
                        height += b.field_35491_r * w;
                        variation += b.field_35492_q * w;
                        weight += w;
                    }
                }
                height /= weight;
                variation /= weight;
                height = height * 0.9F + 0.1F;
                variation = (variation * 4.0F - 1.0F) / 8.0F;
                double continental = this.field_4181_h[noise2dIdx] / 8000.0D;
                if (continental < 0.0D) continental = -continental * 0.3D;
                continental = continental * 3.0D - 2.0D;
                if (continental < 0.0D) {
                    continental /= 2.0D;
                    if (continental < -1.0D) continental = -1.0D;
                    continental /= 1.4D;
                    continental /= 2.0D;
                } else {
                    if (continental > 1.0D) continental = 1.0D;
                    continental /= 8.0D;
                }
                ++noise2dIdx;

                for (int by = 0; by < ySize; ++by) {
                    double hgt = (double) variation;
                    double var = (double) height;
                    hgt += continental * 0.2D;
                    hgt = hgt * (double) ySize / 16.0D;
                    double base = (double) ySize / 2.0D + hgt * 4.0D;
                    double density = 0.0D;
                    double dist = ((double) by - base) * 12.0D / var;
                    if (dist < 0.0D) dist *= 4.0D;
                    double n1 = this.field_4184_e[densityIdx] / 512.0D;
                    double n2 = this.field_4183_f[densityIdx] / 512.0D;
                    double sel = (this.field_4185_d[densityIdx] / 10.0D + 1.0D) / 2.0D;
                    if (sel < 0.0D) density = n1;
                    else if (sel > 1.0D) density = n2;
                    else density = n1 + (n2 - n1) * sel;
                    density -= dist;
                    if (by > ySize - 4) {
                        double fade = (double) ((float) (by - (ySize - 4)) / 3.0F);
                        density = density * (1.0D - fade) + -10.0D * fade;
                    }
                    out[densityIdx] = density;
                    ++densityIdx;
                }
            }
        }
        return out;
    }

    /** The classic 1.8.1 replaceBlocksForBiome surface dressing. */
    private void replaceBlocksForBiome(int cx, int cz, byte[] blocks, BetaBiomeGenBase[] biomes, Random r) {
        double var6 = 1.0D / 32.0D;
        this.stoneNoise = this.field_908_o.generateNoiseOctaves(this.stoneNoise, cx * 16, cz * 16, 0, 16, 16, 1, var6 * 2.0D, var6 * 2.0D, var6 * 2.0D);

        for (int var8 = 0; var8 < 16; ++var8) {
            for (int var9 = 0; var9 < 16; ++var9) {
                BetaBiomeGenBase biome = biomes[var9 + var8 * 16];
                int depth = (int) (this.stoneNoise[var8 + var9 * 16] / 3.0D + 3.0D + r.nextDouble() * 0.25D);
                int fill = -1;
                int top = biome.topBlock;
                int filler = biome.fillerBlock;
                for (int y = 127; y >= 0; --y) {
                    int idx = (var9 * 16 + var8) * 128 + y;
                    if (y <= 0 + r.nextInt(5)) {
                        blocks[idx] = (byte) BETA_BEDROCK;
                    } else {
                        int block = blocks[idx] & 0xFF;
                        if (block == 0) {
                            fill = -1;
                        } else if (block == BETA_STONE) {
                            if (fill == -1) {
                                if (depth <= 0) { top = 0; filler = BETA_STONE; }
                                else if (y >= SEA_LEVEL - 4 && y <= SEA_LEVEL + 1) {
                                    top = biome.topBlock;
                                    filler = biome.fillerBlock;
                                }
                                if (y < SEA_LEVEL && top == 0) top = BETA_WATER_STILL;
                                fill = depth;
                                blocks[idx] = (byte) (y >= SEA_LEVEL - 1 ? top : filler);
                            } else if (fill > 0) {
                                --fill;
                                blocks[idx] = (byte) filler;
                                if (fill == 0 && filler == BETA_SAND) {
                                    fill = r.nextInt(4);
                                    filler = BETA_SANDSTONE;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  BLOCK / SECTION API
    // ══════════════════════════════════════════════════════════════════

    private static int sectionIdx(int lx, int ly, int lz) {
        return lx | (ly << 4) | (lz << 8);
    }

    public int populateSection(int cx, int cy, int cz, World world, int slot) {
        this.world = world;
        ensureColumn(cx, cz);
        if (cy < 0) return -1;
        if (cy >= 8) return 0;
        byte[] sec = columnSections.get(cy);
        if (sec == null) return 0;
        int solidCount = 0;
        for (int i = 0; i < 4096; i++) {
            int b = sec[i] & 0xFF;
            if (b == 0) continue;
            int lx = i & 15, ly = (i >> 4) & 15, lz = (i >> 8) & 15;
            world.setVoxelInPool(slot, lx, ly, lz, mapToVeBlock(b));
            solidCount++;
        }
        return solidCount;
    }

    public boolean prepareSection(int cx, int cy, int cz) {
        ensureColumn(cx, cz);
        if (cy < 0) return true;
        if (cy >= 8) return false;
        byte[] sec = columnSections.get(cy);
        if (sec == null) return false;
        for (byte b : sec) if (b != 0) return true;
        return false;
    }

    public int getBetaBlock(int x, int z, int y) {
        int cx = x >> 4, cz = z >> 4;
        if (y < 0) return evaluateDensity(x, y, z) ? BETA_STONE : BETA_AIR;
        if (y >= 128) return BETA_AIR;
        ensureColumn(cx, cz);
        byte[] sec = columnSections.get(y >> 4);
        if (sec == null) return BETA_AIR;
        return sec[sectionIdx(x & 15, y & 15, z & 15)] & 0xFF;
    }

    public int getHeight(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        ensureColumn(cx, cz);
        int lx = x & 15, lz = z & 15;
        for (int cy = 7; cy >= 0; cy--) {
            byte[] sec = columnSections.get(cy);
            if (sec == null) continue;
            for (int ly = 15; ly >= 0; ly--) {
                if (sec[sectionIdx(lx, ly, lz)] != 0) return (cy << 4) | ly;
            }
        }
        return 0;
    }

    public int getBetaBiomeId(int x, int z) {
        BetaBiomeGenBase b = worldChunkManager.getBiomeGenAt(x, z);
        return b == null ? 1 : b.field_35494_y;
    }

    // ── mapToVeBlock ──
    public int mapToVeBlock(int betaId) {
        switch (betaId) {
            case BETA_STONE: return blocks.stone;
            case BETA_GRASS: return blocks.grass;
            case BETA_DIRT: return blocks.dirt;
            case BETA_BEDROCK: return blocks.bedrock;
            case BETA_WATER_STILL: case BETA_WATER_MOVING: return blocks.waterStill;
            case BETA_LAVA_STILL: case BETA_LAVA_MOVING: return blocks.lavaStill;
            case BETA_SAND: return blocks.sand;
            case BETA_GRAVEL: return blocks.gravel;
            case BETA_SANDSTONE: return blocks.sandstone;
            case BETA_ICE: return blocks.ice;
            case BETA_SNOW: return blocks.snow;
            case BETA_WOOD: return blocks.wood;
            case BETA_LEAVES: return blocks.leaves;
            case BETA_PLANT_YELLOW: return blocks.dandelion;
            case BETA_PLANT_RED: return blocks.rose;
            case BETA_TALL_GRASS: return blocks.tallGrass;
            case BETA_DEAD_BUSH: return blocks.deadBush;
            case BETA_CACTUS: return blocks.cactus;
            case BETA_PUMPKIN: return blocks.pumpkin;
            case BETA_MUSHROOM_BROWN: return blocks.mushroomBrown;
            case BETA_MUSHROOM_RED: return blocks.mushroomRed;
            case BETA_REEDS: return blocks.reeds;
            case BETA_CLAY: return blocks.clay;
            case BETA_COAL_ORE: return blocks.coalOre;
            case BETA_IRON_ORE: return blocks.ironOre;
            case BETA_GOLD_ORE: return blocks.goldOre;
            case BETA_DIAMOND_ORE: return blocks.diamondOre;
            case BETA_REDSTONE_ORE: return blocks.redstoneOre;
            case BETA_LAPIS_ORE: return blocks.lapisOre;
            case BETA_COBBLESTONE: return blocks.cobblestone;
            case BETA_MOSSY_COBBLE: return blocks.mossyCobble;
            case BETA_CHEST: return blocks.chest;
            case BETA_MOB_SPAWNER: return blocks.spawner;
            default: return 0;
        }
    }

    private int betaForVe(int veId) {
        if (veId == blocks.stone) return BETA_STONE;
        if (veId == blocks.grass) return BETA_GRASS;
        if (veId == blocks.dirt) return BETA_DIRT;
        if (veId == blocks.bedrock) return BETA_BEDROCK;
        if (veId == blocks.waterStill) return BETA_WATER_STILL;
        if (veId == blocks.lavaStill) return BETA_LAVA_STILL;
        if (veId == blocks.sand) return BETA_SAND;
        if (veId == blocks.gravel) return BETA_GRAVEL;
        if (veId == blocks.sandstone) return BETA_SANDSTONE;
        if (veId == blocks.leaves) return BETA_LEAVES;
        if (veId == blocks.wood) return BETA_WOOD;
        if (veId == blocks.dandelion) return BETA_PLANT_YELLOW;
        if (veId == blocks.rose) return BETA_PLANT_RED;
        if (veId == blocks.tallGrass) return BETA_TALL_GRASS;
        if (veId == blocks.deadBush) return BETA_DEAD_BUSH;
        if (veId == blocks.cactus) return BETA_CACTUS;
        if (veId == blocks.pumpkin) return BETA_PUMPKIN;
        if (veId == blocks.mushroomBrown) return BETA_MUSHROOM_BROWN;
        if (veId == blocks.mushroomRed) return BETA_MUSHROOM_RED;
        if (veId == blocks.reeds) return BETA_REEDS;
        if (veId == blocks.clay) return BETA_CLAY;
        if (veId == blocks.coalOre) return BETA_COAL_ORE;
        if (veId == blocks.ironOre) return BETA_IRON_ORE;
        if (veId == blocks.goldOre) return BETA_GOLD_ORE;
        if (veId == blocks.diamondOre) return BETA_DIAMOND_ORE;
        if (veId == blocks.redstoneOre) return BETA_REDSTONE_ORE;
        if (veId == blocks.lapisOre) return BETA_LAPIS_ORE;
        if (veId == blocks.cobblestone) return BETA_COBBLESTONE;
        if (veId == blocks.mossyCobble) return BETA_MOSSY_COBBLE;
        if (veId == blocks.chest) return BETA_CHEST;
        if (veId == blocks.spawner) return BETA_MOB_SPAWNER;
        if (veId == blocks.ice) return BETA_ICE;
        if (veId == blocks.snow) return BETA_SNOW;
        return BETA_AIR;
    }

    // ══════════════════════════════════════════════════════════════════
    //  BetaGenContext (decoration block access)
    // ══════════════════════════════════════════════════════════════════

    @Override
    public BetaBlocks b() {
        return blocks;
    }

    @Override
    public int getBlock(int x, int y, int z) {
        return mapToVeBlock(readBetaBlock(x, y, z));
    }

    @Override
    public void setBlock(int x, int y, int z, int id) {
        if (world != null) world.setVoxel(x, y, z, id);
        writeBetaBlock(x, y, z, betaForVe(id));
    }

    @Override
    public int getTopSolidOrLiquid(int x, int z) {
        HashMap<Integer, byte[]> col = getColumnBlocks(x >> 4, z >> 4);
        int lx = x & 15, lz = z & 15;
        for (int cy = 7; cy >= 0; cy--) {
            byte[] sec = col.get(cy);
            if (sec == null) continue;
            for (int ly = 15; ly >= 0; ly--) {
                if (sec[sectionIdx(lx, ly, lz)] != 0) return (cy << 4) | ly;
            }
        }
        return 0;
    }

    private int readBetaBlock(int x, int y, int z) {
        if (y < 0 || y >= 128) return BETA_AIR;
        HashMap<Integer, byte[]> col = getColumnBlocks(x >> 4, z >> 4);
        byte[] sec = col.get(y >> 4);
        if (sec == null) return BETA_AIR;
        return sec[sectionIdx(x & 15, y & 15, z & 15)] & 0xFF;
    }

    private void writeBetaBlock(int x, int y, int z, int beta) {
        if (y < 0 || y >= 128) return;
        HashMap<Integer, byte[]> col = getColumnBlocks(x >> 4, z >> 4);
        int cy = y >> 4;
        byte[] sec = col.get(cy);
        if (beta == 0) {
            if (sec != null) sec[sectionIdx(x & 15, y & 15, z & 15)] = 0;
            return;
        }
        if (sec == null) { sec = new byte[4096]; col.put(cy, sec); }
        sec[sectionIdx(x & 15, y & 15, z & 15)] = (byte) beta;
    }

    private HashMap<Integer, byte[]> getColumnBlocks(int cx, int cz) {
        if (columnCX == cx && columnCZ == cz) return columnSections;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        HashMap<Integer, byte[]> blocks = neighborBlocks.get(key);
        if (blocks == null) {
            blocks = generateColumnBlocks(cx, cz);
            neighborBlocks.put(key, blocks);
        }
        return blocks;
    }

    // ══════════════════════════════════════════════════════════════════
    //  POPULATION (decoration)
    // ══════════════════════════════════════════════════════════════════

    public void populateColumn(World world, int cx, int cz) {
        this.world = world;
        ensureColumn(cx, cz);
        int x = cx * 16, z = cz * 16;
        BetaBiomeGenBase biome = worldChunkManager.getBiomeGenAt(x + 16, z + 16);

        Random rand = new Random(worldSeed);
        long a = rand.nextLong() / 2L * 2L + 1L;
        long b = rand.nextLong() / 2L * 2L + 1L;
        rand.setSeed((long) cx * a + (long) cz * b ^ worldSeed);

        int bx, by, bz;
        if (rand.nextInt(4) == 0) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            new BetaWorldGenLakes(blocks.waterStill).generate(this, rand, bx, by, bz);
        }
        if (rand.nextInt(8) == 0) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(rand.nextInt(128 - 8) + 8);
            bz = z + rand.nextInt(16) + 8;
            if (by < 63 || rand.nextInt(10) == 0) {
                new BetaWorldGenLakes(blocks.lavaStill).generate(this, rand, bx, by, bz);
            }
        }
        for (int i = 0; i < 8; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            new BetaWorldGenDungeons().generate(this, rand, bx, by, bz);
        }

        decorator.decorate(this, rand, x, z, biome);
    }

    // ══════════════════════════════════════════════════════════════════
    //  BELOW-ZERO DENSITY (deep stone)
    // ══════════════════════════════════════════════════════════════════

    private final double[] evalNoise = new double[1];

    private boolean evaluateDensity(int x, int y, int z) {
        double nx = x / 4.0D, ny = y / 8.0D, nz = z / 4.0D;
        double n1 = field_912_k.generateNoiseOctaves(evalNoise, (int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz), 1, 1, 1, 684.412D, 684.412D, 684.412D)[0];
        double n2 = field_911_l.generateNoiseOctaves(evalNoise, (int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz), 1, 1, 1, 684.412D, 684.412D, 684.412D)[0];
        double n3 = field_910_m.generateNoiseOctaves(evalNoise, (int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz), 1, 1, 1, 684.412D / 80.0D, 684.412D / 160.0D, 684.412D / 80.0D)[0];
        double sel = (n3 / 10.0D + 1.0D) / 2.0D;
        if (sel < 0.0D) sel = 0.0D;
        if (sel > 1.0D) sel = 1.0D;
        double density = (n1 / 512.0D) * (1.0D - sel) + (n2 / 512.0D) * sel;
        return density > 0.0D;
    }

    // ══════════════════════════════════════════════════════════════════
    //  CACHE CONTROL
    // ══════════════════════════════════════════════════════════════════

    public void clearColumnCache() {
        columnSections = null;
        columnCX = Integer.MIN_VALUE;
        columnCZ = Integer.MIN_VALUE;
        neighborBlocks.clear();
    }

    public void invalidateCache() {
        columnCX = Integer.MIN_VALUE;
        columnCZ = Integer.MIN_VALUE;
    }

    public long getWorldSeed() { return worldSeed; }
    public NoiseGeneratorOctaves getMobSpawnerNoise() { return mobSpawnerNoise; }
}
