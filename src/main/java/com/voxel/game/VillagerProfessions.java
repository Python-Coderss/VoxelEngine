package com.voxel.game;

import com.voxel.World;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3i;

import java.util.Random;

/**
 * Profession data: every profession's job-site block, its career ladder
 * (XP thresholds and titles), and the job-site assignment a villager performs.
 *
 * <p>Job sites reuse blocks the villages already build — a farm's farmland, a
 * workshop's crafting table, a house chest, and the Villager News studio's TV
 * block — so a village's profession mix follows from what is actually standing
 * in it.</p>
 */
public final class VillagerProfessions {

    /** Highest career level a villager can reach. */
    public static final int MAX_LEVEL = 5;
    /** Sentinel for "this profession has no job site". */
    public static final int NO_JOB_SITE = 0;

    public static final int CRAFTING_TABLE = 115;
    public static final int CHEST = 118;
    public static final int VILLAGER_TV = 274;

    /** Horizontal search radius (blocks) when a villager looks for a job site. */
    public static final int SEARCH_RADIUS = 18;
    /** Vertical tolerance for the job-site scan. */
    public static final int SEARCH_HEIGHT = 6;

    /** XP awarded for making progress at a job site (per work beat). */
    public static final int XP_TEND = 2;
    /** XP for planting a crop. */
    public static final int XP_PLANT = 3;
    /** XP for each harvested crop. */
    public static final int XP_HARVEST = 5;
    /** XP for a completed build step. */
    public static final int XP_BUILD = 4;
    /** XP for a broadcast at the news desk. */
    public static final int XP_BROADCAST = 3;
    /** XP awarded to the merchant for a completed trade. */
    public static final int XP_TRADE = 6;

    /** Total XP required to reach each level (index = level, so 0 and 1 are 0). */
    private static final int[] XP_TO_LEVEL = {0, 0, 20, 50, 90, 140};

    /** Career titles per profession, indexed at level - 1. */
    private static final String[] FARMER_TITLES =
            {"Novice Farmer", "Farmer", "Skilled Farmer", "Expert Farmer", "Master Farmer"};
    private static final String[] BUILDER_TITLES =
            {"Novice Builder", "Builder", "Skilled Builder", "Expert Builder", "Master Builder"};
    private static final String[] SHOPKEEPER_TITLES =
            {"Novice Trader", "Trader", "Skilled Trader", "Expert Trader", "Master Trader"};
    private static final String[] NEWS_ANCHOR_TITLES =
            {"Junior Reporter", "Reporter", "Field Anchor", "News Anchor", "Prime Anchor"};

    private VillagerProfessions() {
    }

    /** The block that gives this profession its job site, or {@link #NO_JOB_SITE}. */
    public static int workstationBlock(VillagerEntity.Profession profession) {
        if (profession == null) return NO_JOB_SITE;
        switch (profession) {
            case FARMER: return FarmBlocks.BLOCK_FARMLAND_DRY;
            case BUILDER: return CRAFTING_TABLE;
            case SHOPKEEPER: return CHEST;
            case NEWS_ANCHOR: return VILLAGER_TV;
            case NITWIT:
            default: return NO_JOB_SITE;
        }
    }

    /** The profession a job-site block hands out, or null when it is not one. */
    public static VillagerEntity.Profession professionForBlock(int blockId) {
        if (FarmBlocks.isFarmland(blockId) || FarmBlocks.isWheat(blockId)) {
            return VillagerEntity.Profession.FARMER;
        }
        if (blockId == CRAFTING_TABLE) return VillagerEntity.Profession.BUILDER;
        if (blockId == CHEST) return VillagerEntity.Profession.SHOPKEEPER;
        if (blockId == VILLAGER_TV) return VillagerEntity.Profession.NEWS_ANCHOR;
        return null;
    }

    public static boolean isWorkstation(int blockId) {
        return professionForBlock(blockId) != null;
    }

    /**
     * Nearest job-site block around a position, or null when none is loaded.
     * Farmland and the wheat above it are the same job site, so a wheat block
     * resolves to the soil under it.
     */
    public static Vector3i nearestWorkstation(World world, float x, float y, float z) {
        return nearestWorkstationFor(world, x, y, z, null);
    }

    /**
     * Nearest job-site block around a position, optionally limited to the one
     * profession's own kind of site (pass null for any site). Farmland and the
     * wheat above it are the same job site, so a wheat block resolves to the
     * soil under it.
     */
    public static Vector3i nearestWorkstationFor(World world, float x, float y, float z,
                                                 VillagerEntity.Profession profession) {
        if (world == null) return null;
        int ox = (int) Math.floor(x);
        int oy = (int) Math.floor(y);
        int oz = (int) Math.floor(z);
        Vector3i best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
                    int bx = ox + dx;
                    int by = oy + dy;
                    int bz = oz + dz;
                    int block = world.getVoxel(bx, by, bz);
                    if (!isWorkstation(block)) continue;
                    if (profession != null && professionForBlock(block) != profession) continue;
                    if (FarmBlocks.isWheat(block)) {
                        // Stand at the soil, not on the crop.
                        by = by - 1;
                    }
                    int dist = dx * dx + dy * dy + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new Vector3i(bx, by, bz);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Adopt the profession of the nearest job site, if there is one. Does not
     * replace an existing profession: villagers only get a job when they have
     * none (a nitwit picking up work) or when they were told to rescan.
     *
     * @return true when a job site was found and adopted
     */
    public static boolean adoptFromNearbyWorkstation(VillagerEntity villager, World world,
                                                     boolean replaceExisting) {
        if (villager == null || world == null) return false;
        if (!replaceExisting && villager.getProfession() != VillagerEntity.Profession.NITWIT) {
            return false;
        }
        Vector3i site = nearestWorkstation(world, villager.getPosX(), villager.getPosY(),
                villager.getPosZ());
        if (site == null) return false;
        int block = world.getVoxel(site.x, site.y, site.z);
        VillagerEntity.Profession profession = professionForBlock(block);
        if (profession == null) return false;
        villager.setProfession(profession);
        villager.setWorkstation(site, workstationBlock(profession));
        return true;
    }

    /** Weighted random profession for a fresh villager. Never a nitwit. */
    public static VillagerEntity.Profession randomProfession(Random rng) {
        Random random = rng == null ? new Random() : rng;
        int roll = random.nextInt(9);
        if (roll < 3) return VillagerEntity.Profession.FARMER;
        if (roll < 6) return VillagerEntity.Profession.BUILDER;
        if (roll < 8) return VillagerEntity.Profession.SHOPKEEPER;
        return VillagerEntity.Profession.NEWS_ANCHOR;
    }

    /** Total XP required to reach a level (1-based; level 1 needs none). */
    public static int xpForLevel(int level) {
        if (level <= 1) return 0;
        if (level >= XP_TO_LEVEL.length) return XP_TO_LEVEL[XP_TO_LEVEL.length - 1];
        return XP_TO_LEVEL[level];
    }

    /** Highest level earned by this much XP. */
    public static int levelForXp(int xp) {
        int level = 1;
        for (int candidate = 2; candidate <= MAX_LEVEL; candidate++) {
            if (xp >= xpForLevel(candidate)) {
                level = candidate;
            }
        }
        return level;
    }

    /** Career title for a profession at a level, e.g. "Expert Farmer". */
    public static String title(VillagerEntity.Profession profession, int level) {
        int index = Math.max(1, Math.min(MAX_LEVEL, level)) - 1;
        if (profession == null) return "Villager";
        switch (profession) {
            case FARMER: return FARMER_TITLES[index];
            case BUILDER: return BUILDER_TITLES[index];
            case SHOPKEEPER: return SHOPKEEPER_TITLES[index];
            case NEWS_ANCHOR: return NEWS_ANCHOR_TITLES[index];
            case NITWIT:
            default: return "Nitwit";
        }
    }
}
