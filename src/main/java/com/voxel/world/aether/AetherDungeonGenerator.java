package com.voxel.world.aether;

import com.voxel.World;
import com.voxel.utils.BlockDataManager;
import java.util.Random;

/**
 * Generates the three Aether dungeons (ported from the mod's
 * BronzeDungeonStructure, SilverDungeonStructure and GoldDungeonStructure):
 *
 * - BRONZE: tunnel-and-room complex carved inside an island's holystone,
 *   guarded by Sentries and Mimics. Boss: the Slider.
 * - SILVER: angelic-stone temple on an island surface with Valkyries.
 *   Boss: the Valkyrie Queen.
 * - GOLD: a hidden hollow hellfire-stone island. Boss: the Sun Spirit.
 */
public final class AetherDungeonGenerator {

    /** One dungeon attempt per ~24x24 chunk column region, per dungeon tier. */
    private static final int REGION = 24;

    private final int holystoneId, carvedId, lockedCarvedId, doorBronzeId;
    private final int angelicId, lightAngelicId, lockedAngelicId, doorSilverId;
    private final int hellfireId, lightHellfireId, lockedHellfireId, doorGoldId;
    private final int chestId, grassId;

    /** Null-safe block lookup: unknown names map to -1 instead of NPE-on-unbox. */
    private static int id(BlockDataManager bdm, String name) {
        Integer v = bdm != null ? bdm.findBlockId(name) : null;
        return v != null ? v : -1;
    }

    public AetherDungeonGenerator(long seed, BlockDataManager bdm) {
        this.holystoneId     = id(bdm, "holystone");
        this.carvedId        = id(bdm, "carved_stone");
        this.lockedCarvedId  = id(bdm, "locked_carved_stone");
        this.doorBronzeId    = id(bdm, "boss_doorway_sentry_stone");
        this.angelicId       = id(bdm, "angelic_stone");
        this.lightAngelicId  = id(bdm, "light_angelic_stone");
        this.lockedAngelicId = id(bdm, "locked_angelic_stone");
        this.doorSilverId    = id(bdm, "boss_doorway_angelic_stone");
        this.hellfireId      = id(bdm, "hellfire_stone");
        this.lightHellfireId = id(bdm, "light_hellfire_stone");
        this.lockedHellfireId= id(bdm, "locked_hellfire_stone");
        this.doorGoldId      = id(bdm, "boss_doorway_hellfire_stone");
        this.chestId         = id(bdm, "chest");
        this.grassId         = id(bdm, "aether_grass_block");

        // Fallbacks so generation still works if a themed block is missing
        if (carvedId <= 0)          { /* leave; checks below handle it */ }
        if (doorBronzeId <= 0)      {}
        if (lockedCarvedId <= 0)    {}
    }

    /** Called from AetherGenerator.decorate for every chunk in the Aether dimension. */
    public void decorate(int cx, int cy, int cz, World world) {
        long regionX = Math.floorDiv(cx, REGION);
        long regionZ = Math.floorDiv(cz, REGION);
        int lx = cx - (int)(regionX * REGION);
        int lz = cz - (int)(regionZ * REGION);

        // Each tier picks its own chunk inside the region (deterministic)
        Random rng = new Random(((regionX * 341873128712L) ^ (regionZ * 132897987541L)) ^ 0xA37E12L);

        int bronzeChunk = rng.nextInt(REGION * REGION);
        int silverChunk = rng.nextInt(REGION * REGION);
        int goldChunk   = rng.nextInt(REGION * REGION);

        int cell = lz * REGION + lx;
        if (cell == bronzeChunk && cy >= 2 && cy <= 5) generateBronze(world, cx, cy, cz, rng);
        else if (cell == silverChunk && cy >= 4 && cy <= 7) tryGenerateSilver(world, cx, cy, cz, rng);
        else if (cell == goldChunk && cy >= 4 && cy <= 6) tryGenerateGold(world, cx, cy, cz, rng);
    }

    // ════════════════════════════ BRONZE ════════════════════════════

    private void generateBronze(World world, int cx, int cy, int cz, Random rng) {
        if (carvedId <= 0 || holystoneId <= 0) return;
        // Room complex centered inside this chunk, buried in holystone
        int baseY = (cy << 4) + 6 + rng.nextInt(4);
        int cxw = (cx << 4) + 8;
        int czw = (cz << 4) + 8;

        // Entry room
        carveRoom(world, cxw, baseY, czw, 5, 4, 5, holystoneId);
        // Two corridors with side rooms
        carveCorridor(world, cxw, baseY, czw, 12, 1, 0, holystoneId);
        carveRoom(world, cxw + 14, baseY, czw, 4, 4, 4, holystoneId);
        carveCorridor(world, cxw, baseY, czw, 10, 0, 1, holystoneId);
        carveRoom(world, cxw, baseY, czw + 12, 4, 4, 4, holystoneId);

        // Boss hall behind a doorway
        int bx = cxw + 14, bz = czw;
        carveBossRoom(world, bx, baseY, bz, 6, 5, 6, holystoneId, lockedCarvedId, doorBronzeId);

        // Loot + guards
        placeChest(world, cxw + 14, baseY, czw - 3);
        placeChest(world, cxw, baseY, czw + 9);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.BRONZE, "sentry",
                cxw + 2.5f, baseY + 1, czw + 2.5f);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.BRONZE, "sentry",
                cxw - 2.5f, baseY + 1, czw - 2.5f);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.BRONZE, "mimic",
                cxw + 14.5f, baseY + 1, czw + 3.5f);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.BRONZE, "mimic",
                cxw + 3.5f, baseY + 1, czw + 11.5f);

        AetherDungeonRegistry.Dungeon d = AetherDungeonRegistry.addDungeon(
                AetherDungeonRegistry.DungeonType.BRONZE, bx + 0.5f, baseY + 1, bz + 0.5f);
        registerDoorway(world, d, bx, baseY, bz, 6, doorBronzeId);
    }

    // ════════════════════════════ SILVER ════════════════════════════

    private void tryGenerateSilver(World world, int cx, int cy, int cz, Random rng) {
        if (angelicId <= 0 || grassId <= 0 || holystoneId <= 0) return;
        // Find island surface near the middle of this chunk section
        int wx = (cx << 4) + 8, wz = (cz << 4) + 8;
        int surf = findSurface(world, wx, wz, cy << 4);
        if (surf < 0) return;

        int baseY = surf + 1;
        // Temple platform 13x13 with pillars and a walled hall
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                setIfAirOrGrass(world, wx + dx, baseY - 1, wz + dz, angelicId);
            }
        }
        // Walls (leave a south entrance)
        for (int h = 0; h < 5; h++) {
            for (int d = -6; d <= 6; d++) {
                wall(world, wx + d, baseY + h, wz - 6, angelicId);
                wall(world, wx + d, baseY + h, wz + 6, angelicId);
                if (!(h < 3 && Math.abs(d) < 2)) wall(world, wx - 6, baseY + h, wz + d, angelicId);
                if (!(h < 3 && Math.abs(d) < 2)) wall(world, wx + 6, baseY + h, wz + d, angelicId);
            }
        }
        // Corner pillars with lights
        for (int[] c : new int[][]{{-6,-6},{6,-6},{-6,6},{6,6}}) {
            for (int h = 0; h < 7; h++)
                world.setVoxel(wx + c[0], baseY + h, wz + c[1], angelicId);
            world.setVoxel(wx + c[0], baseY + 7, wz + c[1], lightAngelicId > 0 ? lightAngelicId : angelicId);
        }
        // Roof
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                world.setVoxel(wx + dx, baseY + 5, wz + dz, angelicId);

        // Boss chamber at the north end
        carveBossRoom(world, wx, baseY, wz - 2, 4, 4, 3, angelicId, lockedAngelicId, doorSilverId);

        placeChest(world, wx + 4, baseY, wz + 4);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.SILVER, "valkyrie",
                wx - 3.5f, baseY, wz + 2.5f);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.SILVER, "valkyrie",
                wx + 3.5f, baseY, wz - 0.5f);
        AetherDungeonRegistry.addSpawnPoint(AetherDungeonRegistry.DungeonType.SILVER, "valkyrie",
                wx - 0.5f, baseY, wz + 4.5f);

        AetherDungeonRegistry.Dungeon d = AetherDungeonRegistry.addDungeon(
                AetherDungeonRegistry.DungeonType.SILVER, wx + 0.5f, baseY + 1, wz - 2.5f);
        registerDoorway(world, d, wx, baseY, wz - 2, 4, doorSilverId);
    }

    // ═════════════════════════════ GOLD ═════════════════════════════

    private void tryGenerateGold(World world, int cx, int cy, int cz, Random rng) {
        if (hellfireId <= 0) return;
        // Gold dungeons sit on small floating islands; build our own shell so
        // placement is guaranteed even in open sky.
        int wx = (cx << 4) + 8, wz = (cz << 4) + 8;
        int baseY = (cy << 4) + 8;

        // Hollow ellipsoid shell of hellfire stone
        double rx = 9, ry = 6, rz = 9;
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    double v = (dx*dx)/(rx*rx) + (dy*dy)/(ry*ry) + (dz*dz)/(rz*rz);
                    boolean shell = v <= 1.0 && v >= 0.62;
                    if (shell) world.setVoxel(wx + dx, baseY + dy, wz + dz, hellfireId);
                    else if (v < 0.62) world.setVoxel(wx + dx, baseY + dy, wz + dz, 0); // hollow interior
                }
            }
        }

        // Boss chamber floor + doorway on the west side
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                world.setVoxel(wx + dx, baseY - 1, wz + dz, hellfireId);
        // Entrance tunnel through the shell
        for (int t = 5; t <= 9; t++) {
            for (int h = 0; h < 3; h++)
                world.setVoxel(wx - t, baseY + h, wz, 0);
            world.setVoxel(wx - t, baseY - 1, wz, lightHellfireId > 0 ? lightHellfireId : hellfireId);
        }

        placeChest(world, wx + 3, baseY, wz + 3);
        AetherDungeonRegistry.Dungeon d = AetherDungeonRegistry.addDungeon(
                AetherDungeonRegistry.DungeonType.GOLD, wx + 0.5f, baseY + 1.0f, wz + 0.5f);
        registerDoorway(world, d, wx, baseY, wz, 4, doorGoldId);
    }

    // ═══════════════════════════ helpers ═══════════════════════════

    private void carveRoom(World world, int cx, int cy, int cz, int rx, int ry, int rz, int fillId) {
        for (int dy = -1; dy <= ry; dy++) {
            for (int dx = -rx - 1; dx <= rx + 1; dx++) {
                for (int dz = -rz - 1; dz <= rz + 1; dz++) {
                    boolean wall = Math.abs(dx) > rx - 1 || Math.abs(dz) > rz - 1
                            || dy == -1 || dy == ry;
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (wall) {
                        if (world.getVoxel(x, y, z) != 0) world.setVoxel(x, y, z, fillId);
                    } else {
                        world.setVoxel(x, y, z, 0);
                    }
                }
            }
        }
    }

    private void carveCorridor(World world, int x, int y, int z, int len, int dx, int dz, int fillId) {
        for (int i = 0; i <= len; i++) {
            for (int h = 0; h < 3; h++) {
                world.setVoxel(x + dx * i, y + h, z + dz * i, 0);
                world.setVoxel(x + dx * i, y + h, z + dz * i + (dx != 0 ? 1 : 0), 0);
                world.setVoxel(x + dx * i + (dz != 0 ? 1 : 0), y + h, z + dz * i, 0);
            }
            // Reinforce corridor walls with carved stone where they border air pockets
            if (carvedId > 0) {
                world.setVoxel(x + dx * i, y - 1, z + dz * i, carvedId);
            }
        }
    }

    private void carveBossRoom(World world, int cx, int cy, int cz, int rx, int ry, int rz,
                               int fillId, int lockedId, int doorId) {
        carveRoom(world, cx, cy, cz, rx, ry, rz, fillId);
        // Locked blocks around the room interior so it can't be entered early
        if (lockedId > 0) {
            for (int dx = -rx + 1; dx <= rx - 1; dx++)
                for (int dz = -rz + 1; dz <= rz - 1; dz++)
                    if (Math.abs(dx) >= rx - 2 || Math.abs(dz) >= rz - 2)
                        if (world.getVoxel(cx + dx, cy + ry, cz + dz) != 0)
                            world.setVoxel(cx + dx, cy + ry, cz + dz, lockedId);
        }
    }

    private void registerDoorway(World world, AetherDungeonRegistry.Dungeon d, int cx, int cy, int cz, int r, int doorId) {
        if (doorId <= 0) return;
        // Ring of doorway blocks around the boss room mouth (south face)
        for (int dx = -2; dx <= 2; dx++) {
            for (int h = 0; h <= 2; h++) {
                int x = cx + dx, y = cy + h, z = cz + r;
                world.setVoxel(x, y, z, doorId);
                d.doorBlocks.add(Long.valueOf(AetherDungeonRegistry.pack(x, y, z)));
            }
        }
    }

    private void placeChest(World world, int x, int y, int z) {
        if (chestId <= 0) return;
        if (world.getVoxel(x, y, z) == 0 && world.getVoxel(x, y - 1, z) != 0) {
            world.setVoxel(x, y, z, chestId);
        } else {
            world.setVoxel(x, y, z, chestId);
            world.setVoxel(x, y - 1, z, holystoneId > 0 ? holystoneId : 1);
        }
    }

    private int findSurface(World world, int x, int z, int startY) {
        for (int y = startY + 15; y >= startY; y--) {
            if (y < 1) break;
            if (world.getVoxel(x, y, z) != 0 && world.getVoxel(x, y + 1, z) == 0
                    && world.getVoxel(x, y + 2, z) == 0) {
                return y + 1;
            }
        }
        return -1;
    }

    private void setIfAirOrGrass(World world, int x, int y, int z, int id) {
        int cur = world.getVoxel(x, y, z);
        if (cur == 0 || cur == grassId) world.setVoxel(x, y, z, id);
    }

    private void wall(World world, int x, int y, int z, int id) {
        if (world.getVoxel(x, y, z) == 0) world.setVoxel(x, y, z, id);
    }
}
