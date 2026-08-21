package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;

/**
 * Full terrain override for the Point & Click demo world.
 *
 * The generator itself materialises the entire authored scene (plaza,
 * interactable stations, portals, lamp posts — everything
 * {@link PointClickWorldAuthor} builds) directly into every section it is
 * asked to populate. Nothing depends on disk chunks or bundled templates:
 * even if the save directory is empty, the plaza renders.
 *
 * Layout: 8x8 chunks (128x128 blocks, -64..63), flat grass plain at y=62
 * with the demo plaza centered on the origin. Player spawn: (0, 63, 0).
 */
public class PointClickWorldGenerator extends WorldGenerator {

    /** Surface height of the flat plain (player feet rest at GROUND + 1 = 63). */
    public static final int GROUND = PointClickWorldAuthor.G;

    private static final int DEMO_MIN = PointClickWorldAuthor.MIN;
    private static final int DEMO_W = PointClickWorldAuthor.MAX - PointClickWorldAuthor.MIN + 1;
    private static final int DEMO_H = PointClickWorldAuthor.AREA_H;

    private final int grassId;
    private final int dirtId;
    private final int stoneId;
    private final int bedrockId;

    /** Dense authored-voxel cache {type}, indexed (y * W + lx) * W + lz. */
    private short[] demoTypes;

    public PointClickWorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.grassId = findOr(blockDataManager, "grass_block", 1);
        this.dirtId = findOr(blockDataManager, "dirt", 13);
        this.stoneId = findOr(blockDataManager, "stone", 2);
        this.bedrockId = findOr(blockDataManager, "bedrock", stoneId);

        // A trivial, uniform biome provider so the tint map stays a single flat
        // colour under the demo plaza (no Beta-biome patchwork).
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public Biome getBiome(int x, int z) {
                return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            }
        };
    }

    /**
     * Builds the whole authored scene once into a dense cache. Chest contents
     * are discarded here — they live in the save's chest.dat and are loaded
     * by ChestManager; this cache only carries visible voxels.
     */
    private synchronized void ensureDemoBuilt() {
        if (demoTypes != null) return;
        short[] types = new short[DEMO_W * DEMO_W * DEMO_H];
        PointClickWorldAuthor.buildAll(new PointClickWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                int lx = x - DEMO_MIN, lz = z - DEMO_MIN;
                if (y < 0 || y >= DEMO_H || lx < 0 || lx >= DEMO_W || lz < 0 || lz >= DEMO_W) return;
                if (type == 0) return;
                types[(y * DEMO_W + lx) * DEMO_W + lz] = (short) type;
            }
        }, new com.voxel.game.ChestManager());
        demoTypes = types;
    }

    @Override
    public int getHeight(int x, int y, int z) {
        return GROUND;
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        if (y > GROUND) {
            ensureDemoBuilt();
            int t = demoVoxel(x, y, z);
            return t;
        }
        if (y == GROUND) {
            // Plaza floor overrides grass where the author placed something at G.
            ensureDemoBuilt();
            int t = demoVoxel(x, y, z);
            return t != 0 ? t : grassId;
        }
        if (y >= GROUND - 3) return dirtId;
        if (y == 0) return bedrockId;
        return stoneId;
    }

    private int demoVoxel(int x, int y, int z) {
        int lx = x - DEMO_MIN, lz = z - DEMO_MIN;
        if (y < 0 || y >= DEMO_H || lx < 0 || lx >= DEMO_W || lz < 0 || lz >= DEMO_W) return 0;
        return demoTypes[(y * DEMO_W + lx) * DEMO_W + lz] & 0xFFFF;
    }

    /**
     * Bulk-fills each 16³ section: flat terrain plus every authored demo
     * voxel that intersects the section. Returns the solid count so
     * ChunkManager takes the fast bulk path (no per-voxel generation).
     */
    @Override
    public int populateSection(int cx, int cy, int cz, World world, int slot) {
        ensureDemoBuilt();
        int yBase = cy << 4;
        int solid = 0;
        for (int ly = 0; ly < 16; ly++) {
            int y = yBase + ly;
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx;
                for (int lz = 0; lz < 16; lz++) {
                    int z = (cz << 4) + lz;
                    int type = terrainOrDemo(x, y, z);
                    if (type != 0) {
                        world.setVoxelInPool(slot, lx, ly, lz, type);
                        solid++;
                    }
                }
            }
        }
        return solid;
    }

    /** Flat-terrain profile with the authored demo voxels overlaid. */
    private int terrainOrDemo(int x, int y, int z) {
        boolean inDemo = y >= 0 && y < DEMO_H
                && x >= DEMO_MIN && x < DEMO_MIN + DEMO_W
                && z >= DEMO_MIN && z < DEMO_MIN + DEMO_W;
        if (inDemo) {
            int t = demoVoxel(x, y, z);
            if (t != 0) return t;
        }
        if (y > GROUND) return 0;
        if (y == GROUND) return grassId;
        if (y >= GROUND - 3) return dirtId;
        if (y == 0) return bedrockId;
        return stoneId;
    }

    @Override
    public boolean prepareSection(int cx, int cy, int cz) {
        // Every section is non-empty below GROUND and empty above it; the bulk
        // populate path above handles both, so always claim readiness.
        return true;
    }

    private static int findOr(com.voxel.utils.BlockDataManager bdm, String name, int fallback) {
        Integer id = bdm.findBlockId(name);
        return id != null ? id : fallback;
    }
}
