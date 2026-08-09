package com.voxel.world;

import com.voxel.World;
import com.voxel.biome.BiomeProvider;
import com.voxel.biome.BiomeRegistry;
import com.voxel.world.beta.BetaChunkProvider;
import com.voxel.world.beta.BetaBiomeGenBase;
import com.voxel.world.beta.BetaNumericProfile;
import com.voxel.world.structure.MapGenStructure;
import java.util.HashSet;
import java.util.Set;

/**
 * World generator that uses the Beta 1.7.3 terrain generation algorithm.
 * 
 * This generator wraps BetaChunkProvider and plugs into the VoxelEngine's
 * WorldGenerator interface. It preserves ALL Beta 1.7.3 bugs including
 * the Far Lands floating-point precision issues.
 * 
 * Supports cubic chunks: terrain exists at y=0..127 (Beta surface),
 * deep stone below y=0, air above y=127.
 */
public class BetaWorldGenerator extends WorldGenerator {
    
    private final BetaChunkProvider betaProvider;
    private final BetaNumericProfile numericProfile;
    
    // Track which columns have been decorated (seed-once-per-column)
    private final Set<Long> decoratedColumns = new HashSet<>();
    
    // Structure generator for villages, mineshafts, etc.
    private final MapGenStructure structureGen;

    // Map Beta 1.7.3 biome IDs → dedicated VoxelEngine BiomeRegistry IDs (honest mapping)
    private static final int[] BETA_TO_VE_BIOME = new int[13];
    static {
        BETA_TO_VE_BIOME[BetaBiomeGenBase.RAINFOREST]       = BiomeRegistry.RAINFOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.SWAMPLAND]        = BiomeRegistry.SWAMPLAND;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.SEASONAL_FOREST]  = BiomeRegistry.SEASONAL_FOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.FOREST]           = BiomeRegistry.FOREST;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.SAVANNA]          = BiomeRegistry.SAVANNA;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.SHRUBLAND]        = BiomeRegistry.SHRUBLAND;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.TAIGA]            = BiomeRegistry.TAIGA;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.DESERT]           = BiomeRegistry.DESERT;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.PLAINS]           = BiomeRegistry.PLAINS;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.ICE_DESERT]       = BiomeRegistry.ICE_DESERT;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.TUNDRA]           = BiomeRegistry.TUNDRA;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.HELL]             = BiomeRegistry.HELL;
        BETA_TO_VE_BIOME[BetaBiomeGenBase.SKY]              = BiomeRegistry.SKY;
    }
    
    public BetaWorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager) {
        this(seed, blockDataManager, BetaNumericProfile.DEFAULT);
    }

    public BetaWorldGenerator(long seed, com.voxel.utils.BlockDataManager blockDataManager,
                              BetaNumericProfile numericProfile) {
        super(seed, blockDataManager);
        this.numericProfile = numericProfile == null ? BetaNumericProfile.DEFAULT : numericProfile;
        
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
        int veObsidian = findOr(blockDataManager, "obsidian", veStone);
        int veLeaves = findOr(blockDataManager, "oak_leaves", 0);
        int veWood = findOr(blockDataManager, "oak_log", 0);
        
        // Decoration block IDs
        int veDandelion = findOr(blockDataManager, "dandelion", 0);
        int veRose = findOr(blockDataManager, "rose", 0);
        int veTallGrass = findOr(blockDataManager, "tallgrass", 0);
        int veDeadBush = findOr(blockDataManager, "deadbush", 0);
        int veCactus = findOr(blockDataManager, "cactus", 0);
        int vePumpkin = findOr(blockDataManager, "pumpkin", 0);
        int veSugarCane = findOr(blockDataManager, "reeds", 0);
        int veClay = findOr(blockDataManager, "clay", veDirt);
        int veCoalOre = findOr(blockDataManager, "coal_ore", veStone);
        int veIronOre = findOr(blockDataManager, "iron_ore", veStone);
        int veGoldOre = findOr(blockDataManager, "gold_ore", veStone);
        int veDiamondOre = findOr(blockDataManager, "diamond_ore", veStone);
        int veRedstoneOre = findOr(blockDataManager, "redstone_ore", veStone);
        int veLapisOre = findOr(blockDataManager, "lapis_ore", veStone);
        int veGlowstone = findOr(blockDataManager, "glowstone", veStone);
        int veCobblestone = findOr(blockDataManager, "cobblestone", veStone);
        int veMossyCobble = findOr(blockDataManager, "mossy_cobblestone", veCobblestone);
        int veChest = findOr(blockDataManager, "chest", 0);
        int veSpawner = findOr(blockDataManager, "spawner", veStone);
        // Snow layer levels (snow_1..snow_8) for height-based snow placement
        int[] veSnowLevels = new int[9];
        veSnowLevels[0] = 0; // no snow at level 0
        for (int level = 1; level <= 8; level++) {
            veSnowLevels[level] = findOr(blockDataManager, "snow_" + level, veSnow);
        }
        // Initialize structure generator with the world seed
        this.structureGen = new MapGenStructure(seed);
        
        this.betaProvider = new BetaChunkProvider(
            seed, this.numericProfile,
            veStone, veGrass, veDirt, veBedrock,
            veWater, veLava, veSand, veGravel,
            veSandStone, veIce, veSnow, veObsidian,
            veLeaves, veWood,
            veDandelion, veRose, veTallGrass, veDeadBush,
            veCactus, vePumpkin,
            veCoalOre, veIronOre, veGoldOre,
            veDiamondOre, veRedstoneOre, veLapisOre, veGlowstone,
            veSugarCane, veClay, veCobblestone, veMossyCobble,
            veChest, veSpawner, veSnowLevels
        );

        // Expose Beta 1.7.3 biomes as a VoxelEngine BiomeProvider
        this.biomeProvider = new BiomeProvider(seed) {
            @Override
            public com.voxel.biome.Biome getBiome(int x, int z) {
                int betaId = betaProvider.getBetaBiomeId(x, z);
                int veId = BETA_TO_VE_BIOME[betaId];
                return BiomeRegistry.getBiome(veId);
            }
        };
    }
    
    public BetaNumericProfile getNumericProfile() {
        return numericProfile;
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
        // The provider's per-section path generates only the requested cubic
        // section — no full-column batching needed here.
        return betaProvider.getHeight(x, y, z);
    }
    
    /**
     * Copy the provider's cached Beta section directly into the engine pool.
     * This keeps startup section generation batched and avoids a second 4,096
     * block-query pass through the generator.
     */
    @Override
    public int populateSection(int cx, int cy, int cz, World world, int slot) {
        return betaProvider.populateSection(cx, cy, cz, world, slot);
    }

    /**
     * Prepare one Beta section and report whether it contains any blocks. The
     * provider performs the section-level noise/interpolation work once; this
     * prevents ChunkManager from issuing 4,096 queries for an empty section.
     */
    @Override
    public boolean prepareSection(int cx, int cy, int cz) {
        return betaProvider.prepareSection(cx, cy, cz);
    }

    /**
     * Direct Beta lookup used by cubic section generation. Beta terrain is a
     * 3D function of (x,y,z), so no column height is needed or consulted.
     */
    @Override
    public int getBlockType(int x, int y, int z) {
        int betaId = betaProvider.getBetaBlock(x, z, y);
        return betaProvider.mapToVeBlock(betaId);
    }

    @Override
    public int getBlockType(int x, int y, int z, int height) {
        // The provider's per-section path generates only the requested cubic
        // section — no full-column batching needed here.
        int betaId = betaProvider.getBetaBlock(x, z, y);
        return betaProvider.mapToVeBlock(betaId);
    }
    
    /**
     * Decorate a chunk section with Beta 1.7.3 features (trees, flowers, ores, etc.).
     * Only runs once per column (cy == 4, roughly sea level).
     */
    @Override
    public void decorate(int cx, int cy, int cz, int slot, World world) {
        // Each Y-precision band gets its own testing facility. Generate upper
        // facilities while their containing section is being populated so every
        // block remains inside the currently loaded cubic section. The surface
        // facility is generated after Beta population below, preventing the
        // population pass from overwriting its room.
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
        
        // Do not publish completion until every population step succeeds. If a
        // transient generation failure occurs, ChunkManager's pending stage can
        // then retry this column instead of being hidden by a premature marker.
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
