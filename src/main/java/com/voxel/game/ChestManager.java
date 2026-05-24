package com.voxel.game;

import com.voxel.game.ItemDefinitions.ItemStack;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-block chest inventory data.
 * Each chest has 20 slots (4 columns × 5 rows) of ItemStack storage.
 * Maps block positions (packed as long) to arrays of ItemStack[20].
 * Supports save/load to a binary file for world persistence.
 */
public class ChestManager {
    public static final int CHEST_SLOTS = 20;

    /** Maps packed block position (x,y,z) -> ItemStack[CHEST_SLOTS]. */
    private final Map<Long, ItemStack[]> chestData = new HashMap<>();

    /** Packs block coordinates into a single long key. */
    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    /** Returns the inventory array for a chest at the given position, or null if none. */
    public ItemStack[] getInventory(int x, int y, int z) {
        return chestData.get(packPos(x, y, z));
    }

    /** Sets the inventory for a chest at the given position. Deep-copies the array. */
    public void setInventory(int x, int y, int z, ItemStack[] inv) {
        ItemStack[] copy = new ItemStack[CHEST_SLOTS];
        if (inv != null) {
            for (int i = 0; i < Math.min(inv.length, CHEST_SLOTS); i++) {
                copy[i] = (inv[i] != null) ? inv[i].copy() : null;
            }
        }
        chestData.put(packPos(x, y, z), copy);
    }

    /** Removes the chest data for a given position. Returns the inventory if it existed. */
    public ItemStack[] removeChest(int x, int y, int z) {
        return chestData.remove(packPos(x, y, z));
    }

    /** Returns true if there is chest data at this position. */
    public boolean hasChest(int x, int y, int z) {
        return chestData.containsKey(packPos(x, y, z));
    }

    /** Returns true if all slots in the chest are empty. */
    public static boolean isChestEmpty(ItemStack[] inv) {
        if (inv == null) return true;
        for (ItemStack stack : inv) {
            if (stack != null) return false;
        }
        return true;
    }

    /** Saves all chest data to a file. */
    public void saveToFile(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(chestData.size());
            for (Map.Entry<Long, ItemStack[]> entry : chestData.entrySet()) {
                out.writeLong(entry.getKey());
                ItemStack[] inv = entry.getValue();
                for (int i = 0; i < CHEST_SLOTS; i++) {
                    ItemStack stack = (inv != null && i < inv.length) ? inv[i] : null;
                    if (stack != null && stack.itemId != null) {
                        out.writeBoolean(true);
                        out.writeUTF(stack.itemId);
                        out.writeInt(stack.count);
                    } else {
                        out.writeBoolean(false);
                    }
                }
            }
        }
    }

    /** Loads all chest data from a file. */
    public void loadFromFile(File file) throws IOException {
        chestData.clear();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                ItemStack[] inv = new ItemStack[CHEST_SLOTS];
                for (int j = 0; j < CHEST_SLOTS; j++) {
                    if (in.readBoolean()) {
                        String itemId = in.readUTF();
                        int c = in.readInt();
                        inv[j] = new ItemStack(itemId, c);
                    }
                }
                chestData.put(key, inv);
            }
        }
    }
}
