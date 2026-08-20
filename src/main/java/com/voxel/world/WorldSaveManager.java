package com.voxel.world;

import com.voxel.World;
import com.voxel.game.CraftingTableManager;
import com.voxel.game.SurfaceCraftingManager;
import com.voxel.game.CommandBlockManager;
import com.voxel.game.GameContext;
import com.voxel.game.ItemDefinitions;
import com.voxel.game.PlayerInventory;
import com.voxel.Player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles saving and loading world data to/from disk.
 * Save format: saves/<save_name>/<dimension_name>/chunks/<cx>/<cz>.dat
 * Each chunk file is a GZIP-compressed binary containing all 16 chunk layers
 * (16x16x16 ints each). Per-dimension data (crafting/furnace/chest) lives next
 * to the dimension folder, and a top-level level.dat (JSON) stores world
 * metadata: seed, world size, game mode, world time, player position / health
 * / inventory, and the last-played timestamp.
 */
public class WorldSaveManager {
    public static final String SAVES_DIR = "saves";

    /**
     * Version marker written as the first int of every chunk file. Older saves
     * (no marker) begin with the chunk X, so {@link #loadChunk} detects this
     * magic and falls back to the legacy short-only format.
     */
    public static final int CHUNK_MAGIC = 0x564F5856; // "VOXV"
    /** Semi-generated structure/feature writes awaiting a target chunk section. */
    private static final int PENDING_MAGIC = 0x564F5850; // "VOXP"

    private final String basePath;

    public WorldSaveManager(String basePath) {
        this.basePath = basePath;
    }

    /** Creates a save manager rooted at saves/<name>/. */
    public static WorldSaveManager forSave(String name) {
        return new WorldSaveManager(SAVES_DIR + "/" + name);
    }

    /** Returns the save name (last path component). */
    public String getSaveName() {
        String p = basePath.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    public String getBasePath() { return basePath; }

    public File getSaveDir() { return new File(basePath); }

    /** Returns the level.dat metadata file. */
    public File getLevelFile() { return new File(basePath, "level.dat"); }

    /** True when this save already has a level.dat (i.e. it is a real save). */
    public boolean exists() { return getLevelFile().exists(); }

    /** Lists all save names under saves/ that contain a level.dat. */
    public static List<String> listSaves() {
        List<String> result = new ArrayList<>();
        File dir = new File(SAVES_DIR);
        if (!dir.isDirectory()) return result;
        File[] children = dir.listFiles();
        if (children == null) return result;
        for (File f : children) {
            if (f.isDirectory() && new File(f, "level.dat").exists()) {
                result.add(f.getName());
            }
        }
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    /** Deletes a save folder recursively. */
    public static boolean deleteSave(String name) {
        File dir = new File(SAVES_DIR, name);
        if (!dir.isDirectory()) return false;
        return deleteRecursively(dir);
    }

    private static boolean deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        return f.delete();
    }

    /** Returns the directory for a given dimension. */
    private File getDimensionDir(DimensionType dim) {
        return new File(basePath, dim.name);
    }

    /** Returns the chunk file path for absolute chunk coordinates. */
    private File getChunkFile(DimensionType dim, int cx, int cz) {
        File dimDir = getDimensionDir(dim);
        File regionDir = new File(dimDir, "chunks" + File.separator + (cx >> 5) + "_" + (cz >> 5));
        return new File(regionDir, cx + "_" + cz + ".dat");
    }

    /** Sidecar containing structure writes made while a target section was absent. */
    private File getPendingChunkFile(DimensionType dim, int cx, int cz) {
        File chunk = getChunkFile(dim, cx, cz);
        return new File(chunk.getParentFile(), cx + "_" + cz + ".pending");
    }

    /** Returns the crafting data file for a dimension. */
    private File getCraftingFile(DimensionType dim) {
        return new File(getDimensionDir(dim), "crafting.dat");
    }

    /**
     * Saves a chunk column (all 16 y-layers) to disk.
     */
    public void saveChunk(DimensionType dim, int cx, int cz, World world) {
        try {
            File file = getChunkFile(dim, cx, cz);
            file.getParentFile().mkdirs();

            int[] data = new int[16 * 16 * 16 * 16];
            int count = 0;

            for (int cy = 0; cy < 16; cy++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int wx = (cx << 4) + lx;
                            int wy = (cy << 4) + ly;
                            int wz = (cz << 4) + lz;
                            int raw = world.getRawVoxel(wx, wy, wz);
                            int idx = (cy * 16 + ly) * 256 + lx * 16 + lz;
                            data[idx] = raw;
                            if ((raw & 0xFFFF) > 0) count++;
                        }
                    }
                }
            }

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
                out.writeInt(CHUNK_MAGIC);
                out.writeInt(cx);
                out.writeInt(cz);
                out.writeInt(count);
                for (int i = 0; i < data.length; i++) {
                    int raw = data[i];
                    if ((raw & 0xFFFF) > 0) {
                        out.writeShort(i);
                        out.writeShort(raw & 0xFFFF);       // block type
                        out.writeByte((raw >>> 16) & 0xFF); // extra (facing)
                    }
                }
            }

            WorldGenLogger.log("DISK_SAVE dim=" + dim.name + " chunk(" + cx + "," + cz + ") blocks=" + count + " -> " + file.getPath());
        } catch (IOException e) {
            WorldGenLogger.log("DISK_SAVE_ERR dim=" + dim.name + " chunk(" + cx + "," + cz + ") " + e.getMessage());
            System.err.println("Failed to save chunk (" + cx + "," + cz + "): " + e.getMessage());
        }
    }

    /**
     * Loads a chunk column from disk into the world.
     */
    public boolean loadChunk(DimensionType dim, int cx, int cz, World world) {
        File file = getChunkFile(dim, cx, cz);
        if (!file.exists()) {
            loadPendingVoxels(dim, cx, cz, world);
            return false;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int first = in.readInt();
            int fileCX, fileCZ;
            if (first == CHUNK_MAGIC) {
                // v2: magic, cx, cz, count, then (short idx, short type, byte extra)
                fileCX = in.readInt();
                fileCZ = in.readInt();
            } else {
                // v1 (legacy): cx, cz, count, then (short idx, short type)
                fileCX = first;
                fileCZ = in.readInt();
            }
            if (fileCX != cx || fileCZ != cz) {
                WorldGenLogger.log("DISK_LOAD_MISMATCH dim=" + dim.name + " expected(" + cx + "," + cz + ") got(" + fileCX + "," + fileCZ + ")");
                System.err.println("Chunk file mismatch: expected (" + cx + "," + cz + "), got (" + fileCX + "," + fileCZ + ")");
                return false;
            }

            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int idx = in.readShort() & 0xFFFF;
                int blockType = in.readShort() & 0xFFFF;
                int extra = (first == CHUNK_MAGIC) ? in.readUnsignedByte() : 0;

                int cy = idx / 4096;
                int rem = idx % 4096;
                int ly = rem / 256;
                int inner = rem % 256;
                int lx = inner / 16;
                int lz = inner % 16;

                int wx = (cx << 4) + lx;
                int wy = (cy << 4) + ly;
                int wz = (cz << 4) + lz;

                world.setVoxelWithData(wx, wy, wz, blockType, extra);
            }

            // Restore structure writes after the base chunk data is registered.
            // World keeps writes for sections that are still outside the current
            // Y window; those are applied when the section eventually loads.
            loadPendingVoxels(dim, cx, cz, world);
            WorldGenLogger.log("DISK_LOAD dim=" + dim.name + " chunk(" + cx + "," + cz + ") blocks=" + count + " <- " + file.getPath());
            return true;
        } catch (IOException e) {
            WorldGenLogger.log("DISK_LOAD_ERR dim=" + dim.name + " chunk(" + cx + "," + cz + ") " + e.getMessage());
            System.err.println("Failed to load chunk (" + cx + "," + cz + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Persists writes whose structure/feature source crossed into an unallocated
     * target section. These sidecars are intentionally separate from the full
     * chunk file: the target may still need procedural terrain generation.
     */
    public void savePendingVoxels(DimensionType dim, List<World.DeferredVoxelWrite> writes) {
        if (writes == null || writes.isEmpty()) return;
        Map<Long, List<World.DeferredVoxelWrite>> grouped = new HashMap<>();
        for (World.DeferredVoxelWrite write : writes) {
            int cx = Math.floorDiv(write.x, 16);
            int cz = Math.floorDiv(write.z, 16);
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(write);
        }
        for (Map.Entry<Long, List<World.DeferredVoxelWrite>> entry : grouped.entrySet()) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = (int) (long) entry.getKey();
            File file = getPendingChunkFile(dim, cx, cz);
            file.getParentFile().mkdirs();
            Map<PendingKey, Integer> merged = readPendingFile(file);
            for (World.DeferredVoxelWrite write : entry.getValue()) {
                merged.put(new PendingKey(write.x, write.y, write.z), write.raw);
            }
            writePendingFile(file, merged);
            WorldGenLogger.log("DISK_PENDING_SAVE dim=" + dim.name + " chunk(" + cx + "," + cz + ") writes=" + entry.getValue().size());
        }
    }

    /** Queues persisted semi-generated writes into World for application after generation. */
    private void loadPendingVoxels(DimensionType dim, int cx, int cz, World world) {
        File file = getPendingChunkFile(dim, cx, cz);
        if (!file.exists()) return;
        for (Map.Entry<PendingKey, Integer> entry : readPendingFile(file).entrySet()) {
            PendingKey key = entry.getKey();
            world.queueDeferredVoxelWrite(key.x, key.y, key.z, entry.getValue());
        }
    }

    /** Removes semi-generated records once their target writes are in a live section. */
    public void acknowledgePendingVoxels(DimensionType dim, List<World.DeferredVoxelWrite> applied) {
        if (applied == null || applied.isEmpty()) return;
        Map<Long, List<World.DeferredVoxelWrite>> grouped = new HashMap<>();
        for (World.DeferredVoxelWrite write : applied) {
            int cx = Math.floorDiv(write.x, 16);
            int cz = Math.floorDiv(write.z, 16);
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(write);
        }
        for (Map.Entry<Long, List<World.DeferredVoxelWrite>> entry : grouped.entrySet()) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = (int) (long) entry.getKey();
            File file = getPendingChunkFile(dim, cx, cz);
            Map<PendingKey, Integer> remaining = readPendingFile(file);
            for (World.DeferredVoxelWrite write : entry.getValue()) {
                PendingKey key = new PendingKey(write.x, write.y, write.z);
                Integer current = remaining.get(key);
                if (current != null && current == write.raw) remaining.remove(key);
            }
            if (remaining.isEmpty()) file.delete();
            else writePendingFile(file, remaining);
        }
    }

    private Map<PendingKey, Integer> readPendingFile(File file) {
        Map<PendingKey, Integer> result = new HashMap<>();
        if (!file.exists()) return result;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != PENDING_MAGIC) return result;
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                result.put(new PendingKey(in.readInt(), in.readInt(), in.readInt()), in.readInt());
            }
        } catch (IOException e) {
            WorldGenLogger.log("DISK_PENDING_LOAD_ERR " + file.getPath() + " " + e.getMessage());
        }
        return result;
    }

    private void writePendingFile(File file, Map<PendingKey, Integer> writes) {
        if (writes.isEmpty()) {
            file.delete();
            return;
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(PENDING_MAGIC);
            out.writeInt(writes.size());
            for (Map.Entry<PendingKey, Integer> entry : writes.entrySet()) {
                out.writeInt(entry.getKey().x);
                out.writeInt(entry.getKey().y);
                out.writeInt(entry.getKey().z);
                out.writeInt(entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("Failed to save pending structure data: " + e.getMessage());
        }
    }

    private static final class PendingKey {
        final int x, y, z;
        PendingKey(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof PendingKey)) return false;
            PendingKey key = (PendingKey) other;
            return x == key.x && y == key.y && z == key.z;
        }
        @Override public int hashCode() {
            int result = 31 * x + y;
            return 31 * result + z;
        }
    }

    /** Checks if a chunk exists on disk. */
    public boolean chunkExists(DimensionType dim, int cx, int cz) {
        return getChunkFile(dim, cx, cz).exists();
    }

    // ════════════════════════════════════════════════════════════════════
    //  level.dat — world metadata (seed, size, mode, player state)
    // ════════════════════════════════════════════════════════════════════

    /** Serializable snapshot of the player's persistent state. */
    public static class PlayerState {
        public double x, y, z;
        public float yaw, pitch;
        public float health;
        public ItemDefinitions.ItemStack[] inventory = new ItemDefinitions.ItemStack[PlayerInventory.INVENTORY_SIZE];
    }

    /** Writes level.dat with world metadata + the current player state. */
    public void saveLevelData(GameContext ctx, Player player, PlayerInventory inventory) {
        try {
            File file = getLevelFile();
            file.getParentFile().mkdirs();

            JSONObject root = new JSONObject();
            root.put("saveName", getSaveName());
            root.put("seed", ctx.worldSeed);
            root.put("worldSize", ctx.worldSize != null ? ctx.worldSize.name() : WorldSize.MEDIUM.name());
            root.put("gameMode", ctx.gameMode != null ? ctx.gameMode.name() : GameContext.GameMode.SURVIVAL.name());
            root.put("worldTime", ctx.worldTime);
            root.put("tutorial", ctx.tutorialWorld);
            root.put("lastPlayed", System.currentTimeMillis());

            JSONObject playerJson = new JSONObject();
            if (player != null) {
                playerJson.put("x", player.getPosition().x);
                playerJson.put("y", player.getPosition().y);
                playerJson.put("z", player.getPosition().z);
                playerJson.put("yaw", ctx.yaw);
                playerJson.put("pitch", ctx.pitch);
                playerJson.put("health", player.getHealth());
                playerJson.put("dimension", ctx.activeDimension.name());
            }
            JSONArray invJson = new JSONArray();
            if (inventory != null) {
                for (int i = 0; i < PlayerInventory.INVENTORY_SIZE; i++) {
                    ItemDefinitions.ItemStack stack = inventory.getSlot(i);
                    if (stack == null) continue;
                    JSONObject s = new JSONObject();
                    s.put("slot", i);
                    s.put("item", stack.itemId);
                    s.put("count", stack.count);
                    s.put("durability", stack.durability);
                    invJson.put(s);
                }
            }
            playerJson.put("inventory", invJson);
            root.put("player", playerJson);

            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
                w.write(root.toString(2));
            }
            WorldGenLogger.log("LEVEL_SAVE " + file.getPath());
        } catch (IOException e) {
            System.err.println("Failed to save level data: " + e.getMessage());
        }
    }

    /**
     * Reads level.dat and applies world metadata (seed / size / mode / time) to
     * ctx. Returns the saved player state (or null when missing/corrupt).
     */
    public PlayerState loadLevelData(GameContext ctx) {
        File file = getLevelFile();
        if (!file.exists()) return null;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            JSONObject root = new JSONObject(content);
            ctx.worldSeed = root.optLong("seed", 0L);
            ctx.worldSize = WorldSize.fromString(root.optString("worldSize", "MEDIUM"));
            ctx.gameMode = GameContext.GameMode.valueOf(root.optString("gameMode", "SURVIVAL"));
            ctx.worldTime = (float) root.optDouble("worldTime", 720.0);
            ctx.tutorialWorld = root.optBoolean("tutorial", false);

            JSONObject playerJson = root.optJSONObject("player");
            if (playerJson == null) return null;
            PlayerState ps = new PlayerState();
            ps.x = playerJson.optDouble("x", 0);
            ps.y = playerJson.optDouble("y", 64);
            ps.z = playerJson.optDouble("z", 0);                ps.yaw = (float) playerJson.optDouble("yaw", -90);
                ps.pitch = (float) playerJson.optDouble("pitch", 0);
                ps.health = (float) playerJson.optDouble("health", 20);
                // Keep the saved dimension separate from the active dimension
                // until Main has built the correct world/chunk manager.
                String savedDimension = playerJson.optString("dimension", DimensionType.OVERWORLD.name);
                try {
                    ctx.loadDimension = DimensionType.valueOf(savedDimension.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    ctx.loadDimension = DimensionType.OVERWORLD;
                }
            JSONArray invJson = playerJson.optJSONArray("inventory");
            if (invJson != null) {
                for (int i = 0; i < invJson.length(); i++) {
                    JSONObject s = invJson.getJSONObject(i);
                    int slot = s.optInt("slot", -1);
                    if (slot < 0 || slot >= ps.inventory.length) continue;
                    ItemDefinitions.ItemStack stack = new ItemDefinitions.ItemStack(
                        s.optString("item", ""), s.optInt("count", 1));
                    stack.durability = s.optInt("durability", 0);
                    ps.inventory[slot] = stack;
                }
            }
            return ps;
        } catch (Exception e) {
            System.err.println("Failed to load level data (" + file.getPath() + "): " + e.getMessage());
            return null;
        }
    }

    /** Returns the furnace data file for a dimension. */
    private File getSurfaceCraftingFile(DimensionType dim) {
        return new File(getDimensionDir(dim), "surface_crafting.dat");
    }

    /** Saves persistent command-block programs for a dimension. */
    public void saveCommandBlockData(DimensionType dim, CommandBlockManager manager) {
        try {
            File file = new File(getDimensionDir(dim), "command_blocks.dat");
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save command-block data for " + dim.name + ": " + e.getMessage());
        }
    }

    /** Loads persistent command-block programs for a dimension. */
    public void loadCommandBlockData(DimensionType dim, CommandBlockManager manager) {
        try {
            manager.loadFromFile(new File(getDimensionDir(dim), "command_blocks.dat"));
        } catch (IOException e) {
            System.err.println("Failed to load command-block data for " + dim.name + ": " + e.getMessage());
        }
    }

    /** Saves arbitrary-block 2x2 surface crafting data for a dimension. */
    public void saveSurfaceCraftingData(DimensionType dim, SurfaceCraftingManager manager) {
        try {
            File file = getSurfaceCraftingFile(dim);
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save surface crafting data for " + dim.name + ": " + e.getMessage());
        }
    }

    /** Loads arbitrary-block 2x2 surface crafting data for a dimension. */
    public void loadSurfaceCraftingData(DimensionType dim, SurfaceCraftingManager manager) {
        try {
            manager.loadFromFile(getSurfaceCraftingFile(dim));
        } catch (IOException e) {
            System.err.println("Failed to load surface crafting data for " + dim.name + ": " + e.getMessage());
        }
    }

    private File getFurnaceFile(DimensionType dim) {
        return new File(getDimensionDir(dim), "furnace.dat");
    }

    /** Returns the chest data file for a dimension. */
    private File getChestFile(DimensionType dim) {
        return new File(getDimensionDir(dim), "chest.dat");
    }

    /** Saves the CraftingTableManager data for a dimension. */
    public void saveCraftingData(DimensionType dim, CraftingTableManager manager) {
        try {
            File file = getCraftingFile(dim);
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save crafting data for " + dim.name + ": " + e.getMessage());
        }
    }

    /** Loads the CraftingTableManager data for a dimension. */
    public void loadCraftingData(DimensionType dim, CraftingTableManager manager) {
        try {
            File file = getCraftingFile(dim);
            manager.loadFromFile(file);
        } catch (IOException e) {
            System.err.println("Failed to load crafting data for " + dim.name + ": " + e.getMessage());
        }
    }

    // --- Furnace data ---

    public void saveFurnaceData(DimensionType dim, com.voxel.game.FurnaceManager manager) {
        try {
            File file = getFurnaceFile(dim);
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save furnace data for " + dim.name + ": " + e.getMessage());
        }
    }

    public void loadFurnaceData(DimensionType dim, com.voxel.game.FurnaceManager manager) {
        try {
            File file = getFurnaceFile(dim);
            manager.loadFromFile(file);
        } catch (IOException e) {
            System.err.println("Failed to load furnace data for " + dim.name + ": " + e.getMessage());
        }
    }

    // --- Chest data ---

    public void saveChestData(DimensionType dim, com.voxel.game.ChestManager manager) {
        try {
            File file = getChestFile(dim);
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save chest data for " + dim.name + ": " + e.getMessage());
        }
    }

    public void loadChestData(DimensionType dim, com.voxel.game.ChestManager manager) {
        try {
            File file = getChestFile(dim);
            manager.loadFromFile(file);
        } catch (IOException e) {
            System.err.println("Failed to load chest data for " + dim.name + ": " + e.getMessage());
        }
    }

    // --- Create machine data (deployer contents) ---

    public void saveMachineData(DimensionType dim, com.voxel.game.CreateMachineManager manager) {
        try {
            File file = new File(getDimensionDir(dim), "machines.dat");
            file.getParentFile().mkdirs();
            manager.saveToFile(file);
        } catch (IOException e) {
            System.err.println("Failed to save machine data for " + dim.name + ": " + e.getMessage());
        }
    }

    public void loadMachineData(DimensionType dim, com.voxel.game.CreateMachineManager manager) {
        try {
            manager.loadFromFile(new File(getDimensionDir(dim), "machines.dat"));
        } catch (IOException e) {
            System.err.println("Failed to load machine data for " + dim.name + ": " + e.getMessage());
        }
    }
}
