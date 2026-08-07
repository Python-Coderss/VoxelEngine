package com.voxel.game;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/** Stores persistent 2x2 surface-crafting grids keyed by block position. */
public class SurfaceCraftingManager {
    private final Map<Long, String[][]> surfaceData = new HashMap<>();

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL)
            | (((long) y & 0x1FFFFFL) << 21)
            | (((long) z & 0x1FFFFFL) << 42);
    }

    public synchronized String[][] getGrid(int x, int y, int z) {
        return surfaceData.get(packPos(x, y, z));
    }

    public synchronized void setGrid(int x, int y, int z, String[][] grid) {
        String[][] copy = new String[2][2];
        if (grid != null) {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    copy[r][c] = grid[r] != null && c < grid[r].length ? grid[r][c] : null;
                }
            }
        }
        surfaceData.put(packPos(x, y, z), copy);
    }

    public synchronized String[][] removeGrid(int x, int y, int z) {
        return surfaceData.remove(packPos(x, y, z));
    }

    public synchronized boolean hasGrid(int x, int y, int z) {
        return surfaceData.containsKey(packPos(x, y, z));
    }

    public static boolean isGridEmpty(String[][] grid) {
        if (grid == null) return true;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                if (grid[r] != null && grid[r][c] != null) return false;
            }
        }
        return true;
    }

    public synchronized void saveToFile(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(surfaceData.size());
            for (Map.Entry<Long, String[][]> entry : surfaceData.entrySet()) {
                out.writeLong(entry.getKey());
                String[][] grid = entry.getValue();
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        String item = grid != null ? grid[r][c] : null;
                        out.writeBoolean(item != null);
                        if (item != null) out.writeUTF(item);
                    }
                }
            }
        }
    }

    public synchronized void loadFromFile(File file) throws IOException {
        surfaceData.clear();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                String[][] grid = new String[2][2];
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        if (in.readBoolean()) grid[r][c] = in.readUTF();
                    }
                }
                surfaceData.put(key, grid);
            }
        }
    }
}
