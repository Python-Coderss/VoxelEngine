package com.voxel.game;

import com.voxel.game.ItemDefinitions.ItemStack;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-block furnace state data and handles smelting logic.
 * Each furnace has: input slot, fuel slot, output slot, smelt progress, fuel burn time.
 * Supports save/load to a binary file for world persistence.
 */
public class FurnaceManager {

    /** A single furnace's full state. */
    public static class FurnaceState {
        public ItemStack input;
        public ItemStack fuel;
        public ItemStack output;
        public float smeltProgress;   // 0..1, resets when output is ready
        public float fuelBurnTime;    // seconds remaining for current fuel
        public float maxFuelBurnTime; // total seconds the current fuel provides

        public FurnaceState() {}

        public FurnaceState copy() {
            FurnaceState s = new FurnaceState();
            s.input = (input != null) ? input.copy() : null;
            s.fuel = (fuel != null) ? fuel.copy() : null;
            s.output = (output != null) ? output.copy() : null;
            s.smeltProgress = smeltProgress;
            s.fuelBurnTime = fuelBurnTime;
            s.maxFuelBurnTime = maxFuelBurnTime;
            return s;
        }

        /** Returns true if this furnace is currently active (has fuel burning). */
        public boolean isLit() {
            return fuelBurnTime > 0;
        }
    }

    /** Smelting recipe: input item -> output item + output count + smelt time */
    public static class SmeltingRecipe {
        public final String inputItemId;
        public final String outputItemId;
        public final int outputCount;
        public final float smeltTime; // seconds to smelt one item

        public SmeltingRecipe(String input, String output, int count, float time) {
            this.inputItemId = input;
            this.outputItemId = output;
            this.outputCount = count;
            this.smeltTime = time;
        }
    }

    /** Fuel types: item -> burn time in seconds */
    public static class FuelType {
        public final String itemId;
        public final float burnTime; // seconds

        public FuelType(String itemId, float burnTime) {
            this.itemId = itemId;
            this.burnTime = burnTime;
        }
    }

    private final java.util.List<SmeltingRecipe> recipes = new java.util.ArrayList<>();
    private final java.util.List<FuelType> fuels = new java.util.ArrayList<>();
    /** Maps packed block position -> FurnaceState */
    private final Map<Long, FurnaceState> furnaceData = new HashMap<>();

    public FurnaceManager() {
        registerDefaultRecipes();
        registerDefaultFuels();
    }

    private void registerDefaultRecipes() {
        addSmelting("iron_ore", "iron_ingot", 1, 8.0f);
        addSmelting("quartz_ore", "nether_quartz", 2, 6.0f);
        // stone -> smelted stone (just returns stone for now)
        addSmelting("stone", "stone", 1, 10.0f);
        // sand -> glass
        addSmelting("sand", "glass", 1, 6.0f);
        // oak_log -> charcoal
        addSmelting("oak_log", "charcoal", 1, 8.0f);
        // iron_ore smelting
        addSmelting("redstone_ore", "redstone_wire", 4, 6.0f);
    }

    private void addSmelting(String inputId, String outputId, int count, float time) {
        recipes.add(new SmeltingRecipe(inputId, outputId, count, time));
    }

    private void registerDefaultFuels() {
        addFuel("oak_log", 15.0f);
        addFuel("skyroot_log", 15.0f);
        addFuel("skyroot_planks", 10.0f);
        addFuel("coal", 80.0f);
        addFuel("charcoal", 80.0f);
        addFuel("lava_bucket", 1000.0f);
    }

    private void addFuel(String itemId, float burnTime) {
        fuels.add(new FuelType(itemId, burnTime));
    }

    // --- State access ---

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    public FurnaceState getState(int x, int y, int z) {
        FurnaceState s = furnaceData.get(packPos(x, y, z));
        if (s == null) {
            s = new FurnaceState();
            furnaceData.put(packPos(x, y, z), s);
        }
        return s;
    }

    /** Removes and returns the state for a furnace. */
    public FurnaceState removeFurnace(int x, int y, int z) {
        return furnaceData.remove(packPos(x, y, z));
    }

    public boolean hasFurnace(int x, int y, int z) {
        return furnaceData.containsKey(packPos(x, y, z));
    }

    // --- Smelting logic ---

    /** Looks up the recipe for a given input item. */
    public SmeltingRecipe findRecipe(String inputItemId) {
        if (inputItemId == null) return null;
        for (SmeltingRecipe r : recipes) {
            if (r.inputItemId.equals(inputItemId)) return r;
        }
        return null;
    }

    /** Looks up the fuel burn time for a given item. Returns 0 if not a fuel. */
    public float getFuelBurnTime(String itemId) {
        if (itemId == null) return 0;
        for (FuelType f : fuels) {
            if (f.itemId.equals(itemId)) return f.burnTime;
        }
        return 0;
    }

    /**
     * Ticks the furnace at the given position.
     * @param dt delta time in seconds
     * @return true if the furnace became lit/unlit (block state needs updating)
     */
    public boolean tick(int x, int y, int z, float dt) {
        FurnaceState s = getState(x, y, z);
        boolean wasLit = s.isLit();

        // If furnace is lit, burn fuel
        if (s.fuelBurnTime > 0) {
            s.fuelBurnTime -= dt;
            if (s.fuelBurnTime < 0) s.fuelBurnTime = 0;
        }

        // If no fuel burning but we have input, try to refuel
        if (s.fuelBurnTime <= 0 && s.input != null) {
            // Try to consume a fuel item
            if (s.fuel != null && s.fuel.count > 0) {
                float burnTime = getFuelBurnTime(s.fuel.itemId);
                if (burnTime > 0) {
                    s.fuelBurnTime = burnTime;
                    s.maxFuelBurnTime = burnTime;
                    s.fuel.count--;
                    if (s.fuel.count <= 0) s.fuel = null;
                }
            }
        }

        // Smelt if we have fuel and input
        if (s.fuelBurnTime > 0 && s.input != null) {
            SmeltingRecipe recipe = findRecipe(s.input.itemId);
            if (recipe != null) {
                // Check if output slot has space
                boolean canOutput = (s.output == null) ||
                    (s.output.itemId.equals(recipe.outputItemId) &&
                     s.output.count + recipe.outputCount <= 64);

                if (canOutput) {
                    s.smeltProgress += dt / recipe.smeltTime;
                    if (s.smeltProgress >= 1.0f) {
                        s.smeltProgress = 0;
                        // Consume input
                        s.input.count--;
                        if (s.input.count <= 0) s.input = null;
                        // Produce output
                        if (s.output == null) {
                            s.output = new ItemStack(recipe.outputItemId, recipe.outputCount);
                        } else {
                            s.output.count += recipe.outputCount;
                        }
                    }
                }
            }
        } else {
            // No fuel or no input, reset progress
            s.smeltProgress = 0;
        }

        boolean isLit = s.isLit();
        return wasLit != isLit;
    }

    /**
     * Ticks all furnaces in this manager.
     * @param chunkManager the chunk manager (used to update furnace lit/unlit block state)
     * @param dt delta time in seconds
     */
    public void tickAll(com.voxel.world.ChunkManager chunkManager, float dt) {
        // Iterate a copy of the keys to avoid concurrent modification
        java.util.ArrayList<Long> keys = new java.util.ArrayList<>(furnaceData.keySet());
        for (long key : keys) {
            // Decode position
            int x = (int)(key & 0x1FFFFFL);
            int y = (int)((key >> 21) & 0x1FFFFFL);
            int z = (int)((key >> 42) & 0x1FFFFFL);
            // Sign extend
            if ((x & 0x100000) != 0) x |= ~0x1FFFFF;
            if ((y & 0x100000) != 0) y |= ~0x1FFFFF;
            if ((z & 0x100000) != 0) z |= ~0x1FFFFF;
            boolean litChanged = tick(x, y, z, dt);
            if (litChanged && chunkManager != null) {
                FurnaceState s = getState(x, y, z);
                int newBlockId = s.isLit() ? 117 : 116; // furnace_on : furnace_off
                chunkManager.setVoxel(x, y, z, newBlockId);
            }
        }
    }

    // --- Persistence ---

    public void saveToFile(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(furnaceData.size());
            for (Map.Entry<Long, FurnaceState> entry : furnaceData.entrySet()) {
                out.writeLong(entry.getKey());
                FurnaceState s = entry.getValue();
                writeStack(out, s.input);
                writeStack(out, s.fuel);
                writeStack(out, s.output);
                out.writeFloat(s.smeltProgress);
                out.writeFloat(s.fuelBurnTime);
                out.writeFloat(s.maxFuelBurnTime);
            }
        }
    }

    private void writeStack(DataOutputStream out, ItemStack stack) throws IOException {
        if (stack != null && stack.itemId != null) {
            out.writeBoolean(true);
            out.writeUTF(stack.itemId);
            out.writeInt(stack.count);
        } else {
            out.writeBoolean(false);
        }
    }

    private ItemStack readStack(DataInputStream in) throws IOException {
        if (in.readBoolean()) {
            return new ItemStack(in.readUTF(), in.readInt());
        }
        return null;
    }

    public void loadFromFile(File file) throws IOException {
        furnaceData.clear();
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                FurnaceState s = new FurnaceState();
                s.input = readStack(in);
                s.fuel = readStack(in);
                s.output = readStack(in);
                s.smeltProgress = in.readFloat();
                s.fuelBurnTime = in.readFloat();
                s.maxFuelBurnTime = in.readFloat();
                furnaceData.put(key, s);
            }
        }
    }
}
