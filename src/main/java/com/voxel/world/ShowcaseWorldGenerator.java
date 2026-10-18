package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;
import com.voxel.utils.BlockDataManager;

/**
 * Full terrain override for the creative Showcase World.
 *
 * <p>Materialises everything {@link ShowcaseWorldAuthor} builds — the plaza,
 * the dimension gateway with one lit portal per dimension, the crafting
 * workshop, the redstone/machine works, the farm and the item gallery —
 * directly into every section it is asked to populate. Nothing depends on
 * disk chunks or bundled templates: even an empty save directory renders the
 * whole hub.</p>
 *
 * <p>Layout: 128x128 blocks (-64..63), flat hub surface at y=62, player spawn
 * at (0, 63, 8.5). Block ids the engine assigns dynamically at registry load
 * (the {@code end_portal} family) are resolved through the block data manager
 * while the authored cache is built.</p>
 */
public class ShowcaseWorldGenerator extends WorldGenerator {

    /** Surface height of the flat hub (player feet rest at GROUND + 1 = 63). */
    public static final int GROUND = ShowcaseWorldAuthor.G;

    private static final int DEMO_MIN = ShowcaseWorldAuthor.MIN;
    private static final int DEMO_W = ShowcaseWorldAuthor.MAX - ShowcaseWorldAuthor.MIN + 1;
    private static final int DEMO_H = ShowcaseWorldAuthor.AREA_H;

    private final BlockDataManager blockDataManager;
    private final int grassId;
    private final int dirtId;
    private final int stoneId;
    private final int bedrockId;

    /** Dense authored-voxel cache {type}, indexed (y * W + lx) * W + lz. */
    private short[] demoTypes;

    public ShowcaseWorldGenerator(long seed, BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.blockDataManager = blockDataManager;
        this.grassId = findOr(blockDataManager, "grass_block", 1);
        this.dirtId = findOr(blockDataManager, "dirt", 13);
        this.stoneId = findOr(blockDataManager, "stone", 2);
        this.bedrockId = findOr(blockDataManager, "bedrock", stoneId);

        // Uniform plains biome so the tint map is a single flat colour (no
        // Beta biome patchwork showing through the hub).
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public Biome getBiome(int x, int z) {
                return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            }
        };
    }

    /**
     * Builds the whole authored hub once into a dense cache. Chest contents are
     * discarded here — they live in the save's chest.dat and are loaded by
     * ChestManager; this cache only carries visible voxels.
     */
    private synchronized void ensureDemoBuilt() {
        if (demoTypes != null) return;
        short[] types = new short[DEMO_W * DEMO_W * DEMO_H];
        ShowcaseWorldAuthor.Ids ids = name -> {
            Integer id = blockDataManager != null ? blockDataManager.findBlockId(name) : null;
            return id != null ? id : -1;
        };
        ShowcaseWorldAuthor.buildAll(new ShowcaseWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                int lx = x - DEMO_MIN, lz = z - DEMO_MIN;
                if (y < 0 || y >= DEMO_H || lx < 0 || lx >= DEMO_W || lz < 0 || lz >= DEMO_W) return;
                // Air writes are meaningful: doorways and carved passages are
                // authored as air after their walls, and dropping them left the
                // hub solid where the bundled template was open. -1 marks
                // "authored air" so the flat fallback cannot refill a room.
                types[(y * DEMO_W + lx) * DEMO_W + lz] = (short) (type == 0 ? -1 : type);
            }
        }, new com.voxel.game.ChestManager(), ids);
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
            return demoVoxel(x, y, z);
        }
        if (y == GROUND) {
            // Hub floor overrides grass where the author placed something at G.
            ensureDemoBuilt();
            int t = demoVoxel(x, y, z);
            return t != 0 ? t : grassId;
        }
        if (y >= GROUND - 3) return dirtId;
        if (y == 0) return bedrockId;
        return stoneId;
    }

    private int demoVoxel(int x, int y, int z) {
        int raw = demoRaw(x, y, z);
        return raw == -1 ? 0 : (raw & 0xFFFF);
    }

    /** -1 = authored air, 0 = untouched, otherwise a block id. */
    private int demoRaw(int x, int y, int z) {
        int lx = x - DEMO_MIN, lz = z - DEMO_MIN;
        if (y < 0 || y >= DEMO_H || lx < 0 || lx >= DEMO_W || lz < 0 || lz >= DEMO_W) return 0;
        return demoTypes[(y * DEMO_W + lx) * DEMO_W + lz];
    }

    /**
     * Bulk-fills each 16³ section: flat terrain plus every authored voxel that
     * intersects the section. Returns the solid count so ChunkManager takes the
     * fast bulk path (no per-voxel generation).
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

    /** Flat-terrain profile with the authored hub voxels overlaid. */
    private int terrainOrDemo(int x, int y, int z) {
        boolean inDemo = y >= 0 && y < DEMO_H
                && x >= DEMO_MIN && x < DEMO_MIN + DEMO_W
                && z >= DEMO_MIN && z < DEMO_MIN + DEMO_W;
        if (inDemo) {
            int raw = demoRaw(x, y, z);
            if (raw != 0) return raw == -1 ? 0 : (raw & 0xFFFF);
        }
        if (y > GROUND) return 0;
        if (y == GROUND) return grassId;
        if (y >= GROUND - 3) return dirtId;
        if (y == 0) return bedrockId;
        return stoneId;
    }

    @Override
    public boolean prepareSection(int cx, int cy, int cz) {
        // Every section is handled by the bulk populate path above.
        return true;
    }

    private static int findOr(BlockDataManager bdm, String name, int fallback) {
        Integer id = bdm.findBlockId(name);
        return id != null ? id : fallback;
    }
}
