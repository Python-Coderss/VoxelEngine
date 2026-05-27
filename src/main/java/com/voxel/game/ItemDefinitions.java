package com.voxel.game;

import com.voxel.utils.BlockDataManager;
import com.voxel.utils.TextureManager;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ItemDefinitions {
    public enum ItemKind { BLOCK, TOOL }

    public enum ToolType { HAND, PICKAXE, SHOVEL, AXE }

    public static final class ItemDefinition {
        public final String id;
        public final String displayName;
        public final ItemKind kind;
        public final int blockId;
        public final int iconLayer;
        public final ToolType toolType;
        public final float miningSpeed;
        public final int maxStack;
        public final Vector4f color;

        public ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.blockId = blockId;
            this.iconLayer = iconLayer;
            this.toolType = toolType;
            this.miningSpeed = miningSpeed;
            this.maxStack = maxStack;
            this.color = new Vector4f(color);
        }
    }

    public static final class ItemStack {
        public String itemId;
        public int count;
        public int durability = 0; // For parachutes and other durable items

        public ItemStack(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        public ItemStack copy() {
            ItemStack copy = new ItemStack(itemId, count);
            copy.durability = this.durability;
            return copy;
        }
    }

    private final Map<String, ItemDefinition> itemRegistry = new HashMap<>();
    private final Map<String, String> itemAliases = new HashMap<>();
    private final Map<Integer, String> blockItemByBlockId = new HashMap<>();

    private BlockDataManager blockDataManager;
    private TextureManager textureManager;

    public void setup(BlockDataManager blockDataManager, TextureManager textureManager) {
        this.blockDataManager = blockDataManager;
        this.textureManager = textureManager;
        itemRegistry.clear();
        itemAliases.clear();
        blockItemByBlockId.clear();
        registerAllItems();
    }

    private void registerAllItems() {
        registerBlock("grass", "Grass Block", 1, "grass_side");
        registerBlock("stone", "Stone", 2, "stone");
        registerBlock("glass", "Glass", 3, "glass");
        registerBlock("leaves", "Oak Leaves", 4, "leaves_oak");
        registerBlock("oak_log", "Oak Log", 5, "log_oak");
        registerBlock("dirt", "Dirt", 13, "dirt");
        registerBlock("sand", "Sand", 14, "sand");
        registerBlock("obsidian", "Obsidian", 16, "obsidian");
        registerBlock("glowstone", "Glowstone", 17, "glowstone");
        registerBlock("end_stone", "End Stone", 18, "end_stone");
        // --- Nether Blocks ---
        registerBlock("netherrack", "Netherrack", 20, "netherrack");
        registerBlock("lava", "Lava", 21, "lava_still");
        registerBlock("soul_sand", "Soul Sand", 22, "soul_sand");
        registerBlock("quartz_ore", "Nether Quartz Ore", 23, "quartz_ore");
        registerBlock("nether_brick", "Nether Brick", 24, "nether_brick");
        // --- Redstone Blocks ---
        registerBlock("redstone_block", "Redstone Block", 25, "redstone_block");
        registerBlock("redstone_ore", "Redstone Ore", 26, "redstone_ore");
        registerBlock("redstone_torch", "Redstone Torch", 27, "redstone_torch_on");
        registerBlock("redstone_lamp", "Redstone Lamp", 28, "redstone_lamp_off");
        registerBlock("redstone_wire", "Redstone Wire", 29, "redstone_dust_dot");
        registerBlock("redstone_lamp_on", "Redstone Lamp (lit)", 30, "redstone_lamp_on");
        // --- Pistons ---
        registerBlock("piston", "Piston", 31, "piston_top_normal");
        registerBlock("sticky_piston", "Sticky Piston", 32, "piston_top_sticky");
        // --- Tools ---
        registerTool("flint_and_steel", "Flint and Steel", "flint_and_steel", ToolType.HAND, 1.0f, new Vector4f(1, 1, 1, 1));
        registerTool("water_bucket", "Water Bucket", "bucket_water", ToolType.HAND, 1.0f, new Vector4f(1, 1, 1, 1));
        registerTool("eye_of_ender", "Eye of Ender", "ender_eye", ToolType.HAND, 1.0f, new Vector4f(1, 1, 1, 1));
        // --- Aether Items ---
        registerBlock("aether_grass", "Aether Grass", 100, "aether_grass_block_top");
        registerBlock("holystone", "Holystone", 101, "holystone");
        registerBlock("aether_dirt", "Aether Dirt", 102, "aether_dirt");
        registerBlock("skyroot_log", "Skyroot Log", 103, "skyroot_log");
        registerBlock("skyroot_leaves", "Skyroot Leaves", 104, "skyroot_leaves");
        registerBlock("aerogel", "Aerogel", 105, "aerogel");
        registerBlock("aether_portal", "Aether Portal", 106, "aether_portal");
        registerBlock("ambrosium_ore", "Ambrosium Ore", 107, "ambrosium_ore");
        registerBlock("gravitite_ore", "Gravitite Ore", 108, "gravitite_ore");
        registerBlock("quicksoil", "Quicksoil", 109, "quicksoil");
        registerBlock("icestone", "Icestone", 110, "icestone");
        registerBlock("zanite_ore", "Zanite Ore", 111, "zanite_ore");
        registerBlock("skyroot_planks", "Skyroot Planks", 112, "skyroot_planks");
        registerBlock("mossy_holystone", "Mossy Holystone", 113, "mossy_holystone");
        registerBlock("holystone_bricks", "Holystone Bricks", 114, "holystone_bricks");
        registerBlock("blue_aercloud", "Blue Aercloud", 124, "blue_aercloud");
        registerBlock("crafting_table", "Crafting Table", 115, "crafting_table_top");
        registerBlock("furnace_off", "Furnace", 116, "furnace_front");
        registerBlock("furnace_on", "Furnace", 117, "furnace_front_on");
        registerBlock("chest", "Chest", 118, "chest_front");
        registerTool("stick", "Stick", "stick", ToolType.HAND, 1.0f, new Vector4f(0.85f, 0.7f, 0.5f, 1));
        // --- Parachutes ---
        registerTool("cold_parachute", "Cold Parachute", "cold_parachute", ToolType.HAND, 1.0f, new Vector4f(0.7f, 0.85f, 1, 1));
        registerTool("golden_parachute", "Golden Parachute", "golden_parachute", ToolType.HAND, 1.0f, new Vector4f(1, 0.9f, 0.5f, 1));
        // --- Aether tools ---
        registerTool("wood_pickaxe", "Wood Pickaxe", "wood_pickaxe", ToolType.PICKAXE, 4.5f, new Vector4f(1, 1, 1, 1));
        registerTool("wood_shovel", "Wood Shovel", "wood_shovel", ToolType.SHOVEL, 4.0f, new Vector4f(1, 1, 1, 1));
        registerTool("wood_axe", "Wood Axe", "wood_axe", ToolType.AXE, 4.2f, new Vector4f(1, 1, 1, 1));
        registerTool("stone_pickaxe", "Stone Pickaxe", "stone_pickaxe", ToolType.PICKAXE, 6.0f, new Vector4f(1, 1, 1, 1));
        registerTool("iron_pickaxe", "Iron Pickaxe", "iron_pickaxe", ToolType.PICKAXE, 8.5f, new Vector4f(1, 1, 1, 1));
        // --- Aliases ---
        registerAlias("pickaxe", "wood_pickaxe");
        registerAlias("shovel", "wood_shovel");
        registerAlias("axe", "wood_axe");
        registerAlias("redstone_dust", "redstone_wire");
        registerAlias("sticky", "sticky_piston");
        registerAlias("redstone", "redstone_wire");
        registerAlias("dust", "redstone_wire");
        registerAlias("piston_block", "piston");
    }

    private void registerBlock(String itemId, String displayName, int blockId, String textureName) {
        Color albedo = blockDataManager.getAlbedo(blockId);
        Vector4f color = new Vector4f(albedo.getRed() / 255.0f, albedo.getGreen() / 255.0f, albedo.getBlue() / 255.0f, 1.0f);
        int iconLayer = textureManager.getTextureIndex(textureName);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.BLOCK, blockId, iconLayer, ToolType.HAND, 1.0f, 64, color);
        itemRegistry.put(itemId, definition);
        blockItemByBlockId.put(blockId, itemId);
        registerAlias(itemId, itemId);
        registerAlias(itemId + "_block", itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    private void registerTool(String itemId, String displayName, String textureName, ToolType toolType, float miningSpeed, Vector4f color) {
        int iconLayer = textureManager.getTextureIndex(textureName);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.TOOL, 0, iconLayer, toolType, miningSpeed, 1, color);
        itemRegistry.put(itemId, definition);
        registerAlias(itemId, itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    public void registerAlias(String alias, String itemId) {
        itemAliases.put(alias.toLowerCase(Locale.ROOT), itemId);
    }

    public String resolveItemId(String token) {
        if (token == null) return null;
        String normalized = token.toLowerCase(Locale.ROOT);
        if (itemRegistry.containsKey(normalized)) return normalized;
        if (itemAliases.containsKey(normalized)) return itemAliases.get(normalized);
        Integer blockId = blockDataManager.findBlockId(normalized);
        if (blockId != null) return blockItemByBlockId.get(blockId);
        return null;
    }

    public ItemDefinition getDefinition(String itemId) {
        return itemRegistry.get(itemId);
    }

    public Map<String, ItemDefinition> getRegistry() { return itemRegistry; }
    public Map<Integer, String> getBlockItemByBlockId() { return blockItemByBlockId; }
}
