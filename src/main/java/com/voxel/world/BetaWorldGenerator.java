package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;
import com.voxel.world.beta.BetaBiomeGenBase;
import com.voxel.world.beta.BetaBlocks;
import com.voxel.world.beta.BetaChunkProvider;
import com.voxel.world.structure.MapGenStructure;
import java.util.HashSet;
import java.util.Set;

/**
 * World generator that uses the Beta 1.8.1 (Adventure Update) continental
 * terrain generation algorithm — the first biome-driven generator with
 * large-scale oceans and landmasses.
 *
 * This generator wraps BetaChunkProvider and plugs into the VoxelEngine's
 * WorldGenerator interface. The precision layer (Far Lands tuning) has been
 * removed in favor of the vanilla 1.8.1 double-precision math.
 *
 * Supports cubic chunks: terrain exists at y=0..127 (Beta 1.8.1 surface),
 * density-evaluated deep stone below y=0, air above y=127.
 */
public class BetaWorldGenerator extends WorldGenerator {

    private final BetaChunkProvider betaProvider;

    // Track which columns have been decorated (seed-once-per-column)
    private final Set<Long> decoratedColumns = new HashSet<>();

    // Structure generator for villages, mineshafts, etc.
    private final MapGenStructure structureGen;

    // Map Beta 1.8.1 biome IDs (0–9 vanilla, 10–15 legacy 1.7.3) →
    // dedicated VoxelEngine BiomeRegistry IDs (honest mapping).
    private static final int[] BETA_TO_VE_BIOME = new int[16];
    static {
        BETA_TO_VE_BIOME[BetaBiomeGenBase.field_35484_b.field_35494_y] = BiomeRegistry.OCEAN;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.field_35485_c.field_35494_y] = BiomeRegistry.PLAINS;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.desert.field_35494_y]         = BiomeRegistry.DESERT;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.field_35483_e.field_35494_y]  = BiomeRegistry.EXTREME_HILLS;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.forest.field_35494_y]         = BiomeRegistry.FOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.taiga.field_35494_y]           = BiomeRegistry.TAIGA;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.swampland.field_35494_y]       = BiomeRegistry.SWAMPLAND;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.field_35487_i.field_35494_y]  = BiomeRegistry.RIVER;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.hell.field_35494_y]           = BiomeRegistry.HELL;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.sky.field_35494_y]            = BiomeRegistry.SKY;
        // Legacy 1.7.3 biomes
        BETA_TO_VE_BIOME[BetaBiomeGenBase.rainforest.field_35494_y]      = BiomeRegistry.RAINFOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.seasonalForest.field_35494_y]  = BiomeRegistry.SEASONAL_FOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.savanna.field_35494_y]         = BiomeRegistry.SAVANNA;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.shrubland.field_35494_y]       = BiomeRegistry.SHRUBLAND;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.iceDesert.field_35494_y]       = BiomeRegistry.ICE_DESERT;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.tundra.field_35494_y]          = BiomeRegistry.TUNDRA;
    }

    public BetaWorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        super(seed, blockDataManager);

        // Look up VoxelEngine block IDs from BlockDataManager (all null-safe)
        int veStone = findOr(blockDataManager, "stone", 2);
        int veGrass = findOr(blockDataManager, "grass_block", 1);
        int veDirt = findOr(blockDataManager, "dirt", veStone);
        int veBedrock = findOr(blockDataManager, "bedrock", veStone);
        int veWater = findOr(blockDataManager, "water", 0);
        int veLava = findOr(blockDataManager, "lava", 0);
        int veSand = findOr(blockDataManager, "sand", veDirt);
        int veGravel = findOr(blockDataManager, "gravel", veDirt);
        int veSandStone = findOr(blockDataManager, "sandstone", veSand);
        int veIce = findOr(blockDataManager, "ice", 0);
        int veSnow = findOr(blockDataManager, "snow_layer", 0);
        int veLeaves = findOr(blockDataManager, "oak_leaves", 0);
        int veWood = findOr(blockDataManager, "oak_log", 0);

        // Decoration block IDs
        int veDandelion = findOr(blockDataManager, "dandelion", 0);
        int veRose = findOr(blockDataManager, "rose", 0);
        int veTallGrass = findOr(blockDataManager, "tallgrass", 0);
        int veDeadBush = findOr(blockDataManager, "deadbush", 0);
        int veCactus = findOr(blockDataManager, "cactus", 0);
        int vePumpkin = findOr(blockDataManager, "pumpkin", 0);
        int veMushroomBrown = findOr(blockDataManager, "brown_mushroom", 0);
        int veMushroomRed = findOr(blockDataManager, "red_mushroom", 0);
        int veSugarCane = findOr(blockDataManager, "reeds", 0);
        int veClay = findOr(blockDataManager, "clay", veDirt);
        int veCoalOre = findOr(blockDataManager, "coal_ore", veStone);
        int veIronOre = findOr(blockDataManager, "iron_ore", veStone);
        int veGoldOre = findOr(blockDataManager, "gold_ore", veStone);
        int veDiamondOre = findOr(blockDataManager, "diamond_ore", veStone);
        int veRedstoneOre = findOr(blockDataManager, "redstone_ore", veStone);
        int veLapisOre = findOr(blockDataManager, "lapis_ore", veStone);
        int veCobblestone = findOr(blockDataManager, "cobblestone", veStone);
        int veMossyCobble = findOr(blockDataManager, "mossy_cobblestone", veCobblestone);
        int veChest = findOr(blockDataManager, "chest", 0);
        int veSpawner = findOr(blockDataManager, "spawner", veStone);

        BetaBlocks blocks = new BetaBlocks(
            veStone, veGrass, veDirt, veBedrock, veWater, veLava,
            veSand, veGravel, veSandStone, veIce, veSnow, veLeaves, veWood,
            veDandelion, veRose, veTallGrass, veDeadBush, veCactus, vePumpkin,
            veMushroomBrown, veMushroomRed, veSugarCane, veClay,
            veCoalOre, veIronOre, veGoldOre, veDiamondOre, veRedstoneOre,
            veLapisOre, veCobblestone, veMossyCobble, veChest, veSpawner);

        // Initialize structure generator with the world seed
        this.structureGen = new MapGenStructure(seed);

        this.betaProvider = new BetaChunkProvider(seed, blocks);

        // Expose Beta 1.8.1 biomes as a VoxelEngine BiomeProvider
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public com.voxel.biome.Biome getBiome(int x, int z) {
                int betaId = betaProvider.getBetaBiomeId(x, z);
                if (betaId < 0 || betaId >= BETA_TO_VE_BIOME.length) {
                    return BiomeRegistry.getBiome(BiomeRegistry.PLAINS);
                }
                return BiomeRegistry.getBiome(BETA_TO_VE_BIOME[betaId]);
            }
        };
    }

    /**
     * Kept as a no-op for callers that used to tune the precision layer —
     * the faithful 1.8.1 generator always uses vanilla double-precision math,
     * so the world size no longer affects the terrain.
     */
    public void setWorldSize(WorldSize ws) {
    }

    /** Returns the underlying BetaChunkProvider for direct access. */
    public BetaChunkProvider getBetaProvider() {
        return betaProvider;
    }

    @Override
    public BiomeProvider getBiomeProvider() {
        return biomeProvider;
    }

    @Override
    public int getHeight(int x, int y, int z) {
        return betaProvider.getHeight(x, y, z);
    }

    /**
     * Copy the provider's cached Beta section directly into the engine pool.
     */
    @Override
    public int populateSection(int cx, int cy, int cz, World world, int slot) {
        return betaProvider.populateSection(cx, cy, cz, world, slot);
    }

    /**
     * Prepare one Beta section and report whether it contains any blocks.
     */
    @Override
    public boolean prepareSection(int cx, int cy, int cz) {
        return betaProvider.prepareSection(cx, cy, cz);
    }

    /**
     * Direct Beta lookup used by cubic section generation.
     */
    @Override
    public int getBlockType(int x, int y, int z) {
        int betaId = betaProvider.getBetaBlock(x, z, y);
        return betaProvider.mapToVeBlock(betaId);
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        int betaId = betaProvider.getBetaBlock(x, z, y);
        return betaProvider.mapToVeBlock(betaId);
    }

    /**
     * Decorate a chunk section with Beta 1.8.1 features (trees, flowers, ores,
     * lakes, dungeons, etc.). Only runs once per column (cy == 4).
     */
    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        int facilityChunkX = Math.floorDiv(com.voxel.world.AncientBuilderFacility.FACILITY_X, 16);
        int facilityChunkZ = Math.floorDiv(com.voxel.world.AncientBuilderFacility.FACILITY_Z, 16);
        boolean facilityColumn = cx == facilityChunkX && cz == facilityChunkZ;
        if (facilityColumn && cy != 4) {
            for (int facilityY : com.voxel.world.AncientBuilderFacility.FACILITY_YS) {
                if (com.voxel.world.AncientBuilderFacility.intersectsSection(cy, facilityY)) {
                    com.voxel.world.AncientBuilderFacility.generate(world, facilityY, cy);
                }
            }
        }

        // Only decorate Beta terrain once per column, and only in the surface section range.
        if (cy != 4) return;

        long colKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (decoratedColumns.contains(colKey)) return;

        try {
            betaProvider.populateColumn(world, cx, cz);

            // Generate structures (villages, mineshafts, etc.) in Beta terrain
            structureGen.generateStructures(world, cx, cz, biomeProvider);
            decoratedColumns.add(colKey);
        } catch (RuntimeException failure) {
            decoratedColumns.remove(colKey);
            throw failure;
        }

        if (facilityColumn) {
            for (int facilityY : com.voxel.world.AncientBuilderFacility.FACILITY_YS) {
                if (com.voxel.world.AncientBuilderFacility.intersectsSection(cy, facilityY)) {
                    com.voxel.world.AncientBuilderFacility.generate(world, facilityY, cy);
                }
            }
        }
    }

    private static int findOr(com.voxel.utils.BlockDataManager bdm, String name, int fallback) {
        Integer id = bdm.findBlockId(name);
        return id != null ? id : fallback;
    }
}
