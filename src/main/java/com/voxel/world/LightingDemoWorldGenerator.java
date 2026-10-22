package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;
import com.voxel.utils.BlockDataManager;

/**
 * Full terrain override for the Lighting Demo world.
 *
 * <p>Materialises everything {@link LightingDemoWorldAuthor} builds — the open
 * light hall, the roofed dark hall and the corridor between them — directly
 * into every section it is asked to populate, so the demo renders with no chunk
 * files on disk. Same shape as {@link ShowcaseWorldGenerator}.</p>
 *
 * <p>Layout: 112x112 blocks (-56..55), flat plain at y=62, player spawn at
 * (0.5, 63, 0.5).</p>
 */
public class LightingDemoWorldGenerator extends WorldGenerator {

    /** Surface height of the flat plain (player feet rest at GROUND + 1 = 63). */
    public static final int GROUND = LightingDemoWorldAuthor.G;

    private static final int DEMO_MIN = LightingDemoWorldAuthor.MIN;
    private static final int DEMO_W = LightingDemoWorldAuthor.MAX - LightingDemoWorldAuthor.MIN + 1;
    private static final int DEMO_H = LightingDemoWorldAuthor.AREA_H;

    private final int grassId;
    private final int dirtId;
    private final int stoneId;
    private final int bedrockId;

    /** Dense authored-voxel cache {type}, indexed (y * W + lx) * W + lz. */
    private short[] demoTypes;

    public LightingDemoWorldGenerator(long seed, BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.grassId = findOr(blockDataManager, "grass_block", 1);
        this.dirtId = findOr(blockDataManager, "dirt", 13);
        this.stoneId = findOr(blockDataManager, "stone", 2);
        this.bedrockId = findOr(blockDataManager, "bedrock", stoneId);

        // Uniform plains biome so the tint map is one flat colour and the two
        // halls differ only by the light they receive.
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public Biome getBiome(int x, int z) {
                return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            }
        };
    }

    /** Builds the authored scene once into a dense cache. */
    private synchronized void ensureDemoBuilt() {
        if (demoTypes != null) return;
        short[] types = new short[DEMO_W * DEMO_W * DEMO_H];
        LightingDemoWorldAuthor.buildAll(new LightingDemoWorldAuthor.Sink() {
            @Override
            public void set(int x, int y, int z, int type, int extra) {
                int lx = x - DEMO_MIN, lz = z - DEMO_MIN;
                if (y < 0 || y >= DEMO_H || lx < 0 || lx >= DEMO_W || lz < 0 || lz >= DEMO_W) return;
                // Air writes carve the doorways and the corridor skylight, and
                // -1 marks them so the flat fallback cannot refill them.
                types[(y * DEMO_W + lx) * DEMO_W + lz] = (short) (type == 0 ? -1 : type);
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
            return demoVoxel(x, y, z);
        }
        if (y == GROUND) {
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
        return true;
    }

    private static int findOr(BlockDataManager bdm, String name, int fallback) {
        Integer id = bdm.findBlockId(name);
        return id != null ? id : fallback;
    }
}
