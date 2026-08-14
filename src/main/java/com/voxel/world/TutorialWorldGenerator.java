package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.Biome;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;

/**
 * Deterministic flat base world for the Tutorial World.
 *
 * The Tutorial World is a large hand-built showcase shipped as a bundled map
 * that is streamed in from disk. This generator is the flat base that matches
 * the template's grass/dirt/stone profile, and fills any chunk outside the
 * template (the handcrafted area) so the player never falls through.
 *
 * Because the base is flat and generated per-section, it scales to any world
 * size for free and streams in exactly like normal terrain.
 */
public class TutorialWorldGenerator extends WorldGenerator {

    /** Surface height of the flat tutorial plain (matches the old hub's Y=68). */
    public static final int GROUND = 68;

    private final int grassId;
    private final int dirtId;
    private final int stoneId;
    private final int bedrockId;

    public TutorialWorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        super(seed, blockDataManager);
        this.grassId = findOr(blockDataManager, "grass_block", 1);
        this.dirtId = findOr(blockDataManager, "dirt", 13);
        this.stoneId = findOr(blockDataManager, "stone", 2);
        this.bedrockId = findOr(blockDataManager, "bedrock", stoneId);

        // A trivial, uniform biome provider so the tint map stays a single flat
        // colour (no Beta-biome patchwork under the showcase builds).
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public Biome getBiome(int x, int z) {
                return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
            }
        };
    }

    @Override
    public int getHeight(int x, int y, int z) {
        return GROUND;
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        if (y > GROUND) return 0;
        if (y == GROUND) return grassId;
        if (y >= GROUND - 3) return dirtId;
        if (y == 0) return bedrockId;
        return stoneId;
    }

    /**
     * Bulk-fills each 16³ section so the flat world generates in one pass
     * instead of 4,096 per-voxel queries per section.
     */
    @Override
    public int populateSection(int cx, int cy, int cz, World world, int slot) {
        int yBase = cy << 4;
        int solid = 0;
        for (int ly = 0; ly < 16; ly++) {
            int y = yBase + ly;
            int type;
            if (y > GROUND) type = 0;
            else if (y == GROUND) type = grassId;
            else if (y >= GROUND - 3) type = dirtId;
            else if (y == 0) type = bedrockId;
            else type = stoneId;
            if (type == 0) continue;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    world.setVoxelInPool(slot, lx, ly, lz, type);
                }
            }
            solid += 16 * 16;
        }
        return solid;
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
