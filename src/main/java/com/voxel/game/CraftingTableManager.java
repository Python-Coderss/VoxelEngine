package com.voxel.game;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores the per-block crafting table 3x3 grid data.
 * Maps block positions (packed as long) to 3x3 String grids.
 * Supports save/load to a binary file for world persistence.
 */
public class CraftingTableManager {
    /** Maps packed block position (x,y,z) -> 3x3 grid of item IDs (null = empty). */
    private final Map<Long, String[][]> tableData = new HashMap<>();

    /** Packs block coordinates into a single long key. */
    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    /** Returns the 3x3 grid for a given crafting table position, or null if none. */
    public String[][] getGrid(int x, int y, int z) {
        return tableData.get(packPos(x, y, z));
    }

    /** Sets the 3x3 grid for a given crafting table position. */
    public void setGrid(int x, int y, int z, String[][] grid) {
        // Deep copy the grid
        String[][] copy = new String[3][3];
        if (grid != null) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    copy[r][c] = (grid[r] != null && c < grid[r].length) ? grid[r][c] : null;
                }
            }
        }
        tableData.put(packPos(x, y, z), copy);
    }

    /** Removes the grid data for a given position. Returns the grid if it existed. */
    public String[][] removeGrid(int x, int y, int z) {
        return tableData.remove(packPos(x, y, z));
    }

    /** Returns true if there is any grid data at this position. */
    public boolean hasGrid(int x, int y, int z) {
        return tableData.containsKey(packPos(x, y, z));
    }

    /** Returns true if all cells in the grid are null/empty. */
    public static boolean isGridEmpty(String[][] grid) {
        if (grid == null) return true;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (grid[r] != null && grid[r][c] != null) return false;
            }
        }
        return true;
    }

    /** Saves all crafting table data to a file. */
    public void saveToFile(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(tableData.size());
            for (Map.Entry<Long, String[][]> entry : tableData.entrySet()) {
                out.writeLong(entry.getKey());
                String[][] grid = entry.getValue();
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        String item = (grid[r] != null) ? grid[r][c] : null;
                        if (item != null) {
                            out.writeBoolean(true);
                            out.writeUTF(item);
                        } else {
                            out.writeBoolean(false);
                        }
                    }
                }
            }
        }
    }

    /** Loads all crafting table data from a file. */
    public void loadFromFile(File file) throws IOException {
        tableData.clear();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                String[][] grid = new String[3][3];
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        if (in.readBoolean()) {
                            grid[r][c] = in.readUTF();
                        }
                    }
                }
                tableData.put(key, grid);
            }
        }
    }
}
