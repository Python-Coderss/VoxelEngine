package com.voxel.game;

import com.voxel.entity.VillagerEntity;
import com.voxel.World;
import org.joml.Vector3i;

import java.util.*;

/**
 * VillagerVillageManager - Manages village state and coordinates villager activities.
 * Tracks villages, assigns building tasks, and manages villager populations.
 */
public class VillagerVillageManager {
    
    /** A single village with its center, buildings, and villager list. */
    public static class Village {
        public Vector3i center;
        public int radius;
        public List<VillagerEntity> villagers = new ArrayList<>();
        public List<Vector3i> buildingOrigins = new ArrayList<>();
        public boolean needsRepairs = false;
        public boolean hasWalls = false;
        public long lastRaidTime = 0;
        
        public Village(Vector3i center, int radius) {
            this.center = new Vector3i(center);
            this.radius = radius;
        }
    }
    
    private final Map<String, Village> villages = new HashMap<>();
    
    /** Register a village at the given position. */
    public Village registerVillage(Vector3i center, int radius) {
        String key = villageKey(center.x, center.z);
        Village v = new Village(center, radius);
        villages.put(key, v);
        return v;
    }
    
    /** Check if a village exists near these coordinates. */
    public Village findNearestVillage(float x, float z) {
        Village nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (Village v : villages.values()) {
            float dx = x - v.center.x;
            float dz = z - v.center.z;
            float dist = dx * dx + dz * dz;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = v;
            }
        }
        return nearest;
    }
    
    /** Find a village close enough to contain this position. */
    public Village findVillageAt(float x, float z) {
        for (Village v : villages.values()) {
            float dx = x - v.center.x;
            float dz = z - v.center.z;
            if (dx * dx + dz * dz < v.radius * v.radius) {
                return v;
            }
        }
        return null;
    }
    
    /** Spawn villagers for a village if it doesn't have enough. */
    public void ensureVillagerPopulation(Village village, World world,
                                          com.voxel.utils.TextureManager textureManager,
                                          com.voxel.entity.EntityManager entityManager) {
        int targetPop = 2 + village.buildingOrigins.size(); // 2 base + 1 per building
        while (village.villagers.size() < targetPop) {
            float sx = village.center.x + (new Random().nextFloat() - 0.5f) * village.radius * 0.8f;
            float sz = village.center.z + (new Random().nextFloat() - 0.5f) * village.radius * 0.8f;
            float sy = findSurface(world, (int)sx, (int)sz);
            if (sy < 0) sy = village.center.y;
            
            VillagerEntity villager = new VillagerEntity(
                50000 + village.villagers.size() + villages.size() * 100,
                new org.joml.Vector3f(sx, sy + 1, sz),
                textureManager
            );
            villager.setWorld(world);
            villager.setVillage(village.center, village.radius);
            village.villagers.add(villager);
            entityManager.addEntity(villager);
        }
    }
    
    /** Assign villagers to watch a TV. Returns channel being watched. */
    public int gatherVillagersAtTV(Village village, int tvX, int tvY, int tvZ,
                                    VillagerTVSystem tvSystem, int channel) {
        tvSystem.setChannel(tvX, tvY, tvZ, channel);
        for (VillagerEntity v : village.villagers) {
            // Only gather available villagers (not fleeing, mating, or already watching)
            if (v.isAvailable()) {
                v.startWatchingTV(new Vector3i(tvX, tvY, tvZ), channel);
                tvSystem.addViewer(tvX, tvY, tvZ, v);
            }
        }
        return channel;
    }
    
    /** Dismiss villagers from watching TV. */
    public void dismissVillagersFromTV(Village village, int tvX, int tvY, int tvZ,
                                        VillagerTVSystem tvSystem) {
        for (VillagerEntity v : village.villagers) {
            if (v.isWatchingTV()) {
                v.stopWatchingTV();
                tvSystem.removeViewer(tvX, tvY, tvZ, v);
            }
        }
    }
    
    /** Assign a building project to all villagers in the village. */
    public void assignBuildingProject(Village village, Vector3i origin, 
                                       int width, int depth, int height) {
        // Distribute building tasks among villagers
        int villagerCount = village.villagers.size();
        if (villagerCount == 0) return;
        
        // Split the house into sections for each villager
        int sectionsPerVillager = Math.max(1, (width * depth * height) / (villagerCount * 10));
        
        // For simplicity, first villager gets the whole house
        if (!village.villagers.isEmpty()) {
            VillagerEntity builder = village.villagers.get(0);
            // Pick a builder or the first non-nitwit
            for (VillagerEntity v : village.villagers) {
                if (v.getProfession() == VillagerEntity.Profession.BUILDER) {
                    builder = v;
                    break;
                }
            }
            builder.queueBuildHouse(origin, width, depth, height);
        }
        
        village.buildingOrigins.add(new Vector3i(origin));
    }
    
    /** Assign wall building to villagers. */
    public void assignWallProject(Village village, Vector3i start, int length, 
                                   int height, int direction) {
        if (!village.villagers.isEmpty()) {
            VillagerEntity builder = village.villagers.get(0);
            for (VillagerEntity v : village.villagers) {
                if (v.getProfession() == VillagerEntity.Profession.BUILDER) {
                    builder = v;
                    break;
                }
            }
            builder.queueBuildWall(start, length, height, direction);
            village.hasWalls = true;
        }
    }
    
    private int findSurface(World world, int x, int z) {
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }
    
    public Collection<Village> getAllVillages() { return villages.values(); }
    
    private static String villageKey(int x, int z) {
        return (x >> 8) + "," + (z >> 8);
    }
}
