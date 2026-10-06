package com.voxel.world;

import com.voxel.World;
import com.voxel.utils.PerlinNoise;

/**
 * Terrain generator for the Sift — Minecraft's soul-lit fourth dimension.
 *
 * <p>Two biomes cover the realm:</p>
 * <ul>
 *   <li><b>Meadow</b> — bright, rolling turf of {@code meadow_grass} over warm
 *       {@code sift_soil}, dotted with {@code sift_bed} patches (the sifting
 *       block) and {@code soul_lamp} posts.</li>
 *   <li><b>Carapace</b> — a harsher shell landscape: chitinous {@code carapace}
 *       ridges crowned with glowing {@code carapace_lamp}, rising higher than
 *       the meadow.</li>
 * </ul>
 *
 * <p>Where the two meet, a rift gate of {@code rift_frame} and
 * {@code sift_portal} stands near the origin so the dimension is reachable.</p>
 *
 * <p>Self-contained per-column (density + decorate), the same shape as
 * {@link AetherGenerator}, so it slots into {@link DimensionManager} without
 * touching the shared structure pipeline.</p>
 */
public class SiftGenerator extends WorldGenerator {

    public static final int MEADOW = 0;
    public static final int CARAPACE = 1;

    /** Average terrain height; also the dimension's spawn band. */
    public static final int BASE_HEIGHT = 72;

    private final PerlinNoise baseNoise;
    private final PerlinNoise detailNoise;
    private final PerlinNoise biomeNoise;
    private final PerlinNoise ridgeNoise;

    private final int siftStone, meadowGrass, siftSoil, carapace, carapaceLamp,
            siftBed, soulLamp, soulSlate, riftFrame, siftPortal;

    public SiftGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.baseNoise = new PerlinNoise(seed + 11);
        this.detailNoise = new PerlinNoise(seed + 23);
        this.biomeNoise = new PerlinNoise(seed + 37);
        this.ridgeNoise = new PerlinNoise(seed + 53);
        this.siftStone = id("sift_stone", 927);
        this.meadowGrass = id("meadow_grass", 928);
        this.siftSoil = id("sift_soil", 929);
        this.carapace = id("carapace", 930);
        this.carapaceLamp = id("carapace_lamp", 931);
        this.siftBed = id("sift_bed", 932);
        this.riftFrame = id("rift_frame", 933);
        this.siftPortal = id("sift_portal", 934);
        this.soulLamp = id("soul_lamp", 935);
        this.soulSlate = id("soul_slate", 936);
    }

    private int id(String name, int fallback) {
        Integer v = blockDataManager.findBlockId(name);
        return v != null && v > 0 ? v : fallback;
    }

    /** 0 = Meadow, 1 = Carapace. Deterministic from the world seed. */
    public int biomeAt(int x, int z) {
        return biomeNoise.noise(x, z, 0.006f) > 0.15f ? CARAPACE : MEADOW;
    }

    /** Topmost solid block of a column. */
    public int columnHeight(int x, int z) {
        float base = baseNoise.noise(x, z, 0.010f) * 12f;
        float detail = detailNoise.noise(x, z, 0.05f) * 3f;
        int h = BASE_HEIGHT + (int) (base + detail);
        if (biomeAt(x, z) == CARAPACE) {
            h += (int) (Math.abs(ridgeNoise.noise(x, z, 0.045f)) * 10f);
        }
        return h;
    }

    @Override
    public int getHeight(int x, int y, int z) {
        return columnHeight(x, z);
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        if (y < 0) return 0;
        int h = columnHeight(x, z);
        if (y > h) return 0;
        int biome = biomeAt(x, z);
        if (y == h) {
            if (biome == CARAPACE) {
                return ((x * 31 + z * 17) % 9 == 0) ? carapaceLamp : carapace;
            }
            return meadowGrass;
        }
        if (y >= h - 3) {
            return biome == CARAPACE ? carapace : siftSoil;
        }
        int hash = (x * 31 + z * 73 + y * 137) & 0xFF;
        if (y < 30 && hash < 4) return soulSlate;
        return siftStone;
    }

    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        int worldX = cx << 4;
        int worldZ = cz << 4;
        int yMin = cy << 4;
        int yMax = yMin + 15;

        // A rift gate near the origin: a 4-wide portal frame on a small plinth.
        if (cx == 0 && cz == 0) {
            int gx = 6, gz = 0;
            int gh = columnHeight(gx, gz);
            if (gh >= yMin && gh <= yMax) {
                placeRiftGate(world, gx, gh, gz);
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;
                int h = columnHeight(wx, wz);
                if (h < yMin || h > yMax) continue;
                int hash = (wx * 31 + wz * 73) & 0xFFFF;
                if (biomeAt(wx, wz) == MEADOW) {
                    // Sift beds: patches of the sifting block on the turf.
                    if (hash % 23 == 0) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                world.setVoxel(wx + dx, h, wz + dz, siftBed);
                            }
                        }
                    } else if (hash % 41 == 0) {
                        // Soul lamp post.
                        world.setVoxel(wx, h + 1, wz, soulLamp);
                    }
                } else {
                    // Carapace spires with a glowing crown.
                    if (hash % 17 == 0) {
                        int spireH = 3 + (hash % 4);
                        for (int i = 1; i <= spireH; i++) {
                            world.setVoxel(wx, h + i, wz, carapace);
                        }
                        world.setVoxel(wx, h + spireH + 1, wz, carapaceLamp);
                    } else if (hash % 29 == 0) {
                        world.setVoxel(wx, h + 1, wz, carapaceLamp);
                    }
                }
            }
        }
    }

    /** A small rift-gate frame: plinth, four rift_frame posts, portal interior. */
    private void placeRiftGate(World world, int x, int groundY, int z) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setVoxel(x + dx, groundY, z + dz, soulSlate);
            }
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 1; dy <= 4; dy++) {
                world.setVoxel(x + dx, groundY + dy, z, siftPortal);
            }
        }
        world.setVoxel(x - 1, groundY + 1, z, riftFrame);
        world.setVoxel(x - 1, groundY + 2, z, riftFrame);
        world.setVoxel(x - 1, groundY + 3, z, riftFrame);
        world.setVoxel(x - 1, groundY + 4, z, riftFrame);
        world.setVoxel(x + 2, groundY + 1, z, riftFrame);
        world.setVoxel(x + 2, groundY + 2, z, riftFrame);
        world.setVoxel(x + 2, groundY + 3, z, riftFrame);
        world.setVoxel(x + 2, groundY + 4, z, riftFrame);
        for (int dx = -1; dx <= 2; dx++) {
            world.setVoxel(x + dx, groundY + 5, z, riftFrame);
        }
    }
}
