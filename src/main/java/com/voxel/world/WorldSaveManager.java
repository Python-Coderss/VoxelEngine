package com.voxel.world;

import com.voxel.World;
import com.voxel.game.CraftingTableManager;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles saving and loading world data to/from disk.
 * Save format: dev/world/<dimension_name>/chunks/<cx>/<cz>.dat
 * Each file is a GZIP-compressed binary containing all 16 chunk layers (16x16x16 ints each).
 * Also saves CraftingTableManager data to dev/world/<dimension_name>/crafting.dat
 */
public class WorldSaveManager {
    private final String basePath;

    public WorldSaveManager(String basePath) {
        this.basePath = basePath;
    }

    public String getBasePath() { return basePath; }

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

    /** Returns the crafting data file for a dimension. */
    private File getCraftingFile(DimensionType dim) {
        return new File(getDimensionDir(dim), "crafting.dat");
    }

    /**
     * Saves a chunk column (all 16 y-layers) to disk.
     * Format: GZIP compressed, 4 bytes per voxel, 16*16*16*16 = 65536 ints = 256KB uncompressed.
     * Only saves non-air blocks for efficiency.
     */
    public void saveChunk(DimensionType dim, int cx, int cz, World world) {
        try {
            File file = getChunkFile(dim, cx, cz);
            file.getParentFile().mkdirs();

            // Count non-air blocks first
            int[] data = new int[16 * 16 * 16 * 16]; // All 16 layers, each 16x16x16
            int count = 0;

            for (int cy = 0; cy < 16; cy++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int wx = (cx << 4) + lx;
                            int wy = (cy << 4) + ly;
                            int wz = (cz << 4) + lz;
                            int block = world.getVoxel(wx, wy, wz);
                            int idx = (cy * 16 + ly) * 256 + lx * 16 + lz;
                            data[idx] = block;
                            if (block > 0) count++;
                        }
                    }
                }
            }

            // Only save if there are non-air blocks, or if the chunk has been modified
            // (save anyway to track which chunks have been explored)
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
                out.writeInt(cx);
                out.writeInt(cz);
                out.writeInt(count);
                for (int i = 0; i < data.length; i++) {
                    if (data[i] > 0) {
                        out.writeShort(i); // index into the flat array
                        out.writeShort(data[i]); // block type
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to save chunk (" + cx + "," + cz + "): " + e.getMessage());
        }
    }

    /**
     * Loads a chunk column from disk into the world.
     * Returns true if the chunk was loaded, false if it doesn't exist on disk.
     */
    public boolean loadChunk(DimensionType dim, int cx, int cz, World world) {
        File file = getChunkFile(dim, cx, cz);
        if (!file.exists()) return false;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int fileCX = in.readInt();
            int fileCZ = in.readInt();
            if (fileCX != cx || fileCZ != cz) {
                System.err.println("Chunk file mismatch: expected (" + cx + "," + cz + "), got (" + fileCX + "," + fileCZ + ")");
                return false;
            }

            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int idx = in.readShort() & 0xFFFF;
                int blockType = in.readShort() & 0xFFFF;

                // Decode flat index back to world coordinates
                int cy = idx / 4096;
                int rem = idx % 4096;
                int ly = rem / 256;
                int inner = rem % 256;
                int lx = inner / 16;
                int lz = inner % 16;

                int wx = (cx << 4) + lx;
                int wy = (cy << 4) + ly;
                int wz = (cz << 4) + lz;

                world.setVoxel(wx, wy, wz, blockType);
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to load chunk (" + cx + "," + cz + "): " + e.getMessage());
            return false;
        }
    }

    /** Checks if a chunk exists on disk. */
    public boolean chunkExists(DimensionType dim, int cx, int cz) {
        return getChunkFile(dim, cx, cz).exists();
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
}
