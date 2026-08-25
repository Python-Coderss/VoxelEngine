package com.voxel.world.structure;

import com.voxel.World;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;
import java.util.Random;

/**
 * Improved village structure generator with proper houses, paths,
 * light posts, walls, and decorative elements.
 * Inspired by Minecraft 1.12.2 village generation.
 * Also spawns villagers when a village is generated.
 */
public class MapGenVillage {

    // Static references set by Main.init() — follows EnemyEntity pattern
    public static com.voxel.entity.EntityManager entityManager;
    public static com.voxel.utils.TextureManager textureManager;
    public static com.voxel.game.VillagerVillageManager villageManager;
    public static void setEntityManager(com.voxel.entity.EntityManager em) { entityManager = em; }
    public static void setTextureManager(com.voxel.utils.TextureManager tm) { textureManager = tm; }
    public static void setVillageManager(com.voxel.game.VillagerVillageManager vm) { villageManager = vm; }

    private static final int VILLAGE_CHANCE = 32; // 1 in 32 chunks
    private static final int VILLAGE_SIZE = 48; // blocks radius
    private static final int VILLAGE_MIN_HEIGHT = 63;

    private static final int PLANKS = 72;
    private static final int COBBLESTONE = 71;
    private static final int GLASS = 3;
    private static final int OAK_LOG = 5;
    private static final int OAK_LEAVES = 4;
    private static final int DIRT = 13;
    private static final int GRAVEL = 54;
    private static final int TORCH = 211;
    private static final int GLOWSTONE = 17;
    private static final int WATER = 15;
    private static final int FENCE = 73; // oak fence
    private static final int WOOL_WHITE = 80;
    private static final int VILLAGER_TV = 274;

    /** Last generated village center (for /locate command). */
    private static int lastVillageX = 0, lastVillageY = 0, lastVillageZ = 0;
    private static boolean lastVillageGenerated = false;

    public static int getLastVillageX() { return lastVillageX; }
    public static int getLastVillageY() { return lastVillageY; }
    public static int getLastVillageZ() { return lastVillageZ; }
    public static boolean hasLastVillage() { return lastVillageGenerated; }

    /**
     * Attempts to generate a village if the chunk qualifies.
     * Returns true if a village was generated.
     */
    public boolean generate(World world, int cx, int cz, java.util.Random rand) {
        if (rand.nextInt(VILLAGE_CHANCE) != 0) return false;

        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;

        // Find surface height at center
        int surfaceY = findSurfaceHeight(world, centerX, centerZ);
        if (surfaceY < VILLAGE_MIN_HEIGHT || surfaceY > 100) return false;

        // Flatten the village area slightly
        flattenArea(world, centerX - 20, centerZ - 20, centerX + 20, centerZ + 20, surfaceY);

        // Generate a central plaza
        generatePlaza(world, centerX, surfaceY, centerZ, rand);

        // Central well (meeting point)
        generateWell(world, centerX, surfaceY, centerZ, rand);

        // Generate buildings in a rough circle
        int buildingCount = 5 + rand.nextInt(4); // 5-8 buildings
        for (int i = 0; i < buildingCount; i++) {
            double angle = (Math.PI * 2 * i) / buildingCount + rand.nextDouble() * 0.3;
            int dist = 14 + rand.nextInt(20);
            int bx = centerX + (int)(Math.cos(angle) * dist);
            int bz = centerZ + (int)(Math.sin(angle) * dist);
            int by = findSurfaceHeight(world, bx, bz);

            if (by > VILLAGE_MIN_HEIGHT && by < 90) {
                int type = rand.nextInt(4);
                switch (type) {
                    case 0: generateSmallHouse(world, bx, by, bz, rand); break;
                    case 1: generateLargeHouse(world, bx, by, bz, rand); break;
                    case 2: generateFarm(world, bx, by, bz, rand); break;
                    case 3: generateWorkshop(world, bx, by, bz, rand); break;
                }
            }
        }

        // Villager News studio (always present — every village broadcasts)
        Vector3i[] newsStation = generateNewsStation(world, centerX, surfaceY, centerZ);

        // The Pit of Death (2-block hole — canonical punishment spot)
        generatePitOfDeath(world, centerX + 6, surfaceY, centerZ + 6);

        // Generate paths between buildings and center
        generatePaths(world, centerX, surfaceY, centerZ, rand);

        // Light posts around the village
        generateLightPosts(world, centerX, surfaceY, centerZ, rand);

        // Simple wall around village perimeter (partial)
        if (rand.nextBoolean()) {
            generatePerimeterWall(world, centerX, surfaceY, centerZ, rand);
        }

        lastVillageX = centerX;
        lastVillageY = surfaceY + 1;
        lastVillageZ = centerZ;
        lastVillageGenerated = true;

        // Register village with the village manager
        if (villageManager != null) {
            com.voxel.game.VillagerVillageManager.Village village =
                villageManager.registerVillage(new org.joml.Vector3i(centerX, surfaceY + 1, centerZ), VILLAGE_SIZE);
            if (newsStation != null) {
                village.newsAnchorPos = newsStation[0];
                village.newsTvPos = newsStation[1];
            }
            // Mark building origins for existing structures
            for (int i = 0; i < buildingCount; i++) {
                double angle = (Math.PI * 2 * i) / buildingCount + rand.nextDouble() * 0.3;
                int dist = 14 + rand.nextInt(20);
                int bx = centerX + (int)(Math.cos(angle) * dist);
                int bz = centerZ + (int)(Math.sin(angle) * dist);
                village.buildingOrigins.add(new org.joml.Vector3i(bx, findSurfaceHeight(world, bx, bz), bz));
            }
            // Spawn villagers and add them to the village's villager list
            spawnVillagersForVillage(world, village, centerX, surfaceY + 1, centerZ, rand);
        } else {
            // Fallback: spawn villagers without village registration
            spawnVillagers(world, centerX, surfaceY + 1, centerZ, rand);
        }

        return true;
    }

    /** Spawn 4-7 villagers around the village center. */
    private void spawnVillagers(World world, int cx, int cy, int cz, Random rand) {
        if (entityManager == null || textureManager == null) return;
        int count = 4 + rand.nextInt(4); // 4-7 villagers
        for (int i = 0; i < count; i++) {
            float sx = cx + (rand.nextFloat() - 0.5f) * 10f;
            float sz = cz + (rand.nextFloat() - 0.5f) * 10f;
            int id = 60000 + (lastVillageX & 0xFFFF) * 100 + i;
            VillagerEntity villager = new VillagerEntity(id, new Vector3f(sx, cy, sz), textureManager);
            villager.setWorld(world);
            villager.setVillage(new org.joml.Vector3i(cx, cy, cz), VILLAGE_SIZE);
            entityManager.addEntity(villager);
        }
    }

    /** Spawn villagers and add them to the village's registered villager list. */
    private void spawnVillagersForVillage(World world, com.voxel.game.VillagerVillageManager.Village village,
                                           int cx, int cy, int cz, Random rand) {
        if (entityManager == null || textureManager == null) return;
        int count = 4 + rand.nextInt(4); // 4-7 villagers
        for (int i = 0; i < count; i++) {
            float sx = cx + (rand.nextFloat() - 0.5f) * 10f;
            float sz = cz + (rand.nextFloat() - 0.5f) * 10f;
            int id = 60000 + (lastVillageX & 0xFFFF) * 100 + i;
            VillagerEntity villager = new VillagerEntity(id, new Vector3f(sx, cy, sz), textureManager);
            villager.setWorld(world);
            villager.setVillage(new org.joml.Vector3i(cx, cy, cz), VILLAGE_SIZE);
            entityManager.addEntity(villager);
            // Add to village's villager list for TV gathering and population management
            if (village != null) {
                village.villagers.add(villager);
            }
        }
    }

    private void flattenArea(World world, int minX, int minZ, int maxX, int maxZ, int targetY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surface = findSurfaceHeight(world, x, z);
                if (surface > targetY + 3 || surface < targetY - 1) continue;
                // Smooth small bumps
                if (surface > targetY) {
                    for (int y = targetY + 1; y <= surface && y <= targetY + 2; y++) {
                        world.setVoxel(x, y, z, 0);
                    }
                }
            }
        }
    }

    private void generatePlaza(World world, int x, int y, int z, Random rand) {
        // 7x7 cobblestone plaza around the well
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                    world.setVoxel(x + dx, y, z + dz, COBBLESTONE);
                }
            }
        }
    }

    private void generateWell(World world, int x, int y, int z, Random rand) {
        // 3x3 well: cobblestone frame with water in center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int bx = x + dx;
                int bz = z + dz;
                world.setVoxel(bx, y, bz, COBBLESTONE);
                // Walls (2 blocks high)
                for (int h = 1; h <= 2; h++) {
                    if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                        world.setVoxel(bx, y + h, bz, COBBLESTONE);
                    }
                }
                // Roof over well
                world.setVoxel(bx, y + 3, bz, PLANKS);
            }
        }
        world.setVoxel(x, y + 1, z, WATER);
        world.setVoxel(x, y + 2, z, 0);
    }

    private void generateSmallHouse(World world, int x, int y, int z, Random rand) {
        int width = 5 + rand.nextInt(2);
        int depth = 5 + rand.nextInt(2);
        int height = 3;

        // Foundation (cobblestone)
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y, z + dz, COBBLESTONE);

        // Floor
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y + 1, z + dz, PLANKS);

        // Walls (oak logs for corners, planks for walls)
        for (int h = 2; h <= height + 1; h++) {
            // Corners
            world.setVoxel(x, y + h, z, OAK_LOG);
            world.setVoxel(x + width - 1, y + h, z, OAK_LOG);
            world.setVoxel(x, y + h, z + depth - 1, OAK_LOG);
            world.setVoxel(x + width - 1, y + h, z + depth - 1, OAK_LOG);

            // Plan walls
            for (int dx = 1; dx < width - 1; dx++) {
                world.setVoxel(x + dx, y + h, z, PLANKS);
                world.setVoxel(x + dx, y + h, z + depth - 1, PLANKS);
            }
            for (int dz = 1; dz < depth - 1; dz++) {
                world.setVoxel(x, y + h, z + dz, PLANKS);
                world.setVoxel(x + width - 1, y + h, z + dz, PLANKS);
            }
        }

        // Door
        int doorX = x + width / 2;
        world.setVoxel(doorX, y + 2, z, 0);
        world.setVoxel(doorX, y + 3, z, 0);

        // Windows with glass
        if (width > 5) {
            world.setVoxel(x + 2, y + 3, z, GLASS);
            world.setVoxel(x + width - 3, y + 3, z + depth - 1, GLASS);
        }

        // Roof
        int roofY = y + height + 2;
        for (int dx = -1; dx < width + 1; dx++)
            for (int dz = -1; dz < depth + 1; dz++)
                world.setVoxel(x + dx, roofY, z + dz, PLANKS);

        // Roof overhang (stairs effect with planks)
        for (int dx = 0; dx < width; dx++) {
            world.setVoxel(x + dx, roofY + 1, z - 1, PLANKS);
            world.setVoxel(x + dx, roofY + 1, z + depth, PLANKS);
        }
        for (int dz = 0; dz < depth; dz++) {
            world.setVoxel(x - 1, roofY + 1, z + dz, PLANKS);
            world.setVoxel(x + width, roofY + 1, z + dz, PLANKS);
        }
    }

    private void generateLargeHouse(World world, int x, int y, int z, Random rand) {
        int width = 7 + rand.nextInt(3);
        int depth = 6 + rand.nextInt(3);
        int height = 4;

        // Foundation
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y, z + dz, COBBLESTONE);

        // Floor
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y + 1, z + dz, PLANKS);

        // Walls - bottom row cobblestone, upper walls planks with log corners
        for (int h = 2; h <= height + 1; h++) {
            int wallBlock = (h == 2) ? COBBLESTONE : PLANKS;
            for (int dx = 0; dx < width; dx++) {
                world.setVoxel(x + dx, y + h, z, wallBlock);
                world.setVoxel(x + dx, y + h, z + depth - 1, wallBlock);
            }
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x, y + h, z + dz, wallBlock);
                world.setVoxel(x + width - 1, y + h, z + dz, wallBlock);
            }
            // Corner logs
            world.setVoxel(x, y + h, z, OAK_LOG);
            world.setVoxel(x + width - 1, y + h, z, OAK_LOG);
            world.setVoxel(x, y + h, z + depth - 1, OAK_LOG);
            world.setVoxel(x + width - 1, y + h, z + depth - 1, OAK_LOG);
        }

        // Double door
        int doorX = x + width / 2;
        world.setVoxel(doorX, y + 2, z, 0);
        world.setVoxel(doorX, y + 3, z, 0);

        // Windows
        world.setVoxel(x + 2, y + 3, z + depth - 1, GLASS);
        world.setVoxel(x + width - 3, y + 3, z + depth - 1, GLASS);
        if (depth > 6) {
            world.setVoxel(x, y + 3, z + 3, GLASS);
        }

        // A-frame roof
        int roofBase = y + height + 2;
        for (int dx = -1; dx < width + 1; dx++)
            for (int dz = -1; dz < depth + 1; dz++)
                world.setVoxel(x + dx, roofBase, z + dz, PLANKS);
    }

    private void generateFarm(World world, int x, int y, int z, Random rand) {
        int size = 6 + rand.nextInt(3);

        // Farmland
        for (int dx = 1; dx < size - 1; dx++)
            for (int dz = 1; dz < size - 1; dz++)
                world.setVoxel(x + dx, y, z + dz, DIRT);

        // Fence around farm
        for (int dx = 0; dx < size; dx++) {
            world.setVoxel(x + dx, y + 1, z, OAK_LOG);
            world.setVoxel(x + dx, y + 1, z + size - 1, OAK_LOG);
        }
        for (int dz = 0; dz < size; dz++) {
            world.setVoxel(x, y + 1, z + dz, OAK_LOG);
            world.setVoxel(x + size - 1, y + 1, z + dz, OAK_LOG);
        }

        // Water source in center
        int cx = x + size / 2, cz = z + size / 2;
        world.setVoxel(cx, y, z + cz, WATER);
    }

    private void generateWorkshop(World world, int x, int y, int z, Random rand) {
        int width = 5;
        int depth = 5;
        int height = 3;

        // Foundation
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y, z + dz, COBBLESTONE);

        // Floor
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++)
                world.setVoxel(x + dx, y + 1, z + dz, PLANKS);

        // Walls
        for (int h = 2; h <= height + 1; h++) {
            for (int dx = 0; dx < width; dx++) {
                world.setVoxel(x + dx, y + h, z, COBBLESTONE);
                world.setVoxel(x + dx, y + h, z + depth - 1, COBBLESTONE);
            }
            for (int dz = 0; dz < depth; dz++) {
                world.setVoxel(x, y + h, z + dz, COBBLESTONE);
                world.setVoxel(x + width - 1, y + h, z + dz, COBBLESTONE);
            }
        }

        // Door
        world.setVoxel(x + width / 2, y + 2, z, 0);
        world.setVoxel(x + width / 2, y + 3, z, 0);

        // Roof
        int roofY = y + height + 2;
        for (int dx = -1; dx < width + 1; dx++)
            for (int dz = -1; dz < depth + 1; dz++)
                world.setVoxel(x + dx, roofY, z + dz, PLANKS);

        // Place a crafting table inside
        world.setVoxel(x + 2, y + 2, z + 2, 115);
    }

    /**
     * Villager News studio, Episode-1 style: glass-fronted building facing the
     * plaza, anchor desk at the back, TV block as the camera.
     *
     * @return {anchorStandPos, tvPos} or null when the site is unsuitable
     *         (positions are feet-level cells for AI use).
     */
    private Vector3i[] generateNewsStation(World world, int centerX, int y, int centerZ) {
        int x0 = centerX + 13;
        int z0 = centerZ - 3;   // footprint 9(x) x 7(z), west face looks at plaza
        int by = findSurfaceHeight(world, x0 + 4, z0 + 3);
        if (by < VILLAGE_MIN_HEIGHT || by > 90) return null;

        // Foundation + floor
        for (int dx = 0; dx < 9; dx++)
            for (int dz = 0; dz < 7; dz++) {
                world.setVoxel(x0 + dx, by, z0 + dz, COBBLESTONE);
                world.setVoxel(x0 + dx, by + 1, z0 + dz, PLANKS);
            }

        // Walls (interior height 4: y+2 .. y+5)
        for (int h = 2; h <= 5; h++) {
            // West wall (studio front): glass band with a centered doorway
            for (int dz = 0; dz < 7; dz++) {
                int wx = x0, wz = z0 + dz;
                if (dz == 3) {
                    world.setVoxel(wx, by + h, wz, h <= 3 ? 0 : GLASS);
                } else {
                    world.setVoxel(wx, by + h, wz, (h == 3 || h == 4) ? GLASS : PLANKS);
                }
            }
            // East wall with one window
            for (int dz = 0; dz < 7; dz++) {
                world.setVoxel(x0 + 8, by + h, z0 + dz,
                        (dz == 3 && h == 4) ? GLASS : PLANKS);
            }
            // North / South walls
            for (int dx = 1; dx < 8; dx++) {
                world.setVoxel(x0 + dx, by + h, z0,
                        (dx == 4 && h == 4) ? GLASS : PLANKS);
                world.setVoxel(x0 + dx, by + h, z0 + 6,
                        (dx == 4 && h == 4) ? GLASS : PLANKS);
            }
        }

        // Corner logs
        for (int h = 2; h <= 5; h++) {
            world.setVoxel(x0, by + h, z0, OAK_LOG);
            world.setVoxel(x0, by + h, z0 + 6, OAK_LOG);
            world.setVoxel(x0 + 8, by + h, z0, OAK_LOG);
            world.setVoxel(x0 + 8, by + h, z0 + 6, OAK_LOG);
        }

        // Roof (with overhang) — two glowstone ceiling lights
        for (int dx = -1; dx < 10; dx++)
            for (int dz = -1; dz < 8; dz++)
                world.setVoxel(x0 + dx, by + 6, z0 + dz, PLANKS);
        world.setVoxel(x0 + 2, by + 6, z0 + 3, GLOWSTONE);
        world.setVoxel(x0 + 6, by + 6, z0 + 3, GLOWSTONE);

        // Anchor desk (planks row) near the back, camera TV on the desk
        for (int dz = 2; dz <= 4; dz++) {
            world.setVoxel(x0 + 6, by + 2, z0 + dz, PLANKS);
        }
        world.setVoxel(x0 + 6, by + 3, z0 + 3, VILLAGER_TV);

        return new Vector3i[]{
                new Vector3i(x0 + 7, by + 2, z0 + 3),
                new Vector3i(x0 + 6, by + 3, z0 + 3)
        };
    }

    /** 3x3 cobble rim around a 1x1 two-deep hole villagers can't climb out of. */
    private void generatePitOfDeath(World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                world.setVoxel(x + dx, y + 1, z + dz, COBBLESTONE);
            }
        world.setVoxel(x, y + 1, z, 0);
        world.setVoxel(x, y + 2, z, 0);
    }

    private void generatePaths(World world, int cx, int y, int cz, Random rand) {
        // Simple gravel paths radiating from center
        for (int angle = 0; angle < 360; angle += 45) {
            double rad = Math.toRadians(angle + rand.nextInt(15));
            int dx = (int)(Math.cos(rad) * 8);
            int dz = (int)(Math.sin(rad) * 8);
            int steps = 8 + rand.nextInt(10);
            for (int i = 2; i < steps; i++) {
                float t = (float)i / steps;
                int px = cx + (int)(dx * t);
                int pz = cz + (int)(dz * t);
                world.setVoxel(px, y, pz, GRAVEL);
            }
        }
    }

    private void generateLightPosts(World world, int cx, int y, int cz, Random rand) {
        // Place light posts at 4-6 locations around the village
        int postCount = 4 + rand.nextInt(3);
        for (int i = 0; i < postCount; i++) {
            double angle = (Math.PI * 2 * i) / postCount;
            int dist = 16 + rand.nextInt(10);
            int px = cx + (int)(Math.cos(angle) * dist);
            int pz = cz + (int)(Math.sin(angle) * dist);
            int py = findSurfaceHeight(world, px, pz);

            if (py > 0 && py < 100) {
                // Fence post
                world.setVoxel(px, py + 1, pz, OAK_LOG);
                world.setVoxel(px, py + 2, pz, OAK_LOG);
                world.setVoxel(px, py + 3, pz, OAK_LOG);
                // Light source on top
                world.setVoxel(px, py + 4, pz, GLOWSTONE);
            }
        }
    }

    private void generatePerimeterWall(World world, int cx, int y, int cz, Random rand) {
        int radius = 22;
        int wallHeight = 3;

        for (int angle = 0; angle < 360; angle += 15) {
            double rad = Math.toRadians(angle);
            int px = cx + (int)(Math.cos(rad) * radius);
            int pz = cz + (int)(Math.sin(rad) * radius);
            int py = findSurfaceHeight(world, px, pz);

            if (py > 0 && py < 100) {
                // Wall pillar
                for (int h = 1; h <= wallHeight; h++) {
                    world.setVoxel(px, py + h, pz, OAK_LOG);
                }
                // Horizontal connection (every other pillar)
                if (angle % 30 == 0) {
                    int nextAngle = (angle + 15) % 360;
                    double nextRad = Math.toRadians(nextAngle);
                    int nx = cx + (int)(Math.cos(nextRad) * radius);
                    int nz = cz + (int)(Math.sin(nextRad) * radius);
                    // Simple line between pillars
                    int steps = Math.max(Math.abs(nx - px), Math.abs(nz - pz));
                    for (int s = 1; s <= steps; s++) {
                        int wx = px + (int)((nx - px) * (float)s / steps);
                        int wz = pz + (int)((nz - pz) * (float)s / steps);
                        world.setVoxel(wx, py + 2, wz, FENCE);
                    }
                }
            }
        }
    }

    private int findSurfaceHeight(World world, int x, int z) {
        for (int y = 127; y >= 0; y--) {
            if (world.getVoxel(x, y, z) > 0) return y;
        }
        return -1;
    }

}
