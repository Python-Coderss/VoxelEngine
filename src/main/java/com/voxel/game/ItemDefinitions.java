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

    // Tool tier for mining progression: 0=hand, 1=wood, 2=stone, 3=iron, 4=diamond
    public static final int TIER_HAND = 0;
    public static final int TIER_WOOD = 1;
    public static final int TIER_STONE = 2;
    public static final int TIER_IRON = 3;
    public static final int TIER_DIAMOND = 4;

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
        public final int tier; // 0=hand, 1=wood, 2=stone, 3=iron, 4=diamond

        public ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color, int tier) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.blockId = blockId;
            this.iconLayer = iconLayer;
            this.toolType = toolType;
            this.miningSpeed = miningSpeed;
            this.maxStack = maxStack;
            this.color = new Vector4f(color);
            this.tier = tier;
        }

        public ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color) {
            this(id, displayName, kind, blockId, iconLayer, toolType, miningSpeed, maxStack, color, 0);
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
        registerBlock("piston", "Piston", 33, "piston_top_normal");
        
        registerBlock("sticky_piston", "Sticky Piston", 32, "piston_top_sticky");
        // --- Biome/Decoration Blocks ---
        registerBlock("dandelion", "Dandelion", 121, "flower_dandelion");
        registerBlock("poppy", "Poppy", 34, "flower_rose");
        registerBlock("tallgrass", "Tall Grass", 35, "tallgrass");
        registerBlock("dead_bush", "Dead Bush", 36, "deadbush");
        registerBlock("brown_mushroom", "Brown Mushroom", 37, "mushroom_brown");
        registerBlock("red_mushroom", "Red Mushroom", 38, "mushroom_red");
        registerBlock("cactus", "Cactus", 39, "cactus_side");
        registerBlock("reeds", "Sugar Cane", 40, "reeds");
        registerBlock("waterlily", "Water Lily", 41, "waterlily");
        registerBlock("pumpkin", "Pumpkin", 42, "pumpkin_face_off");
        registerBlock("melon", "Melon", 43, "melon_side");
        registerBlock("vine", "Vines", 44, "vine");
        registerBlock("birch_log", "Birch Log", 46, "log_birch");
        registerBlock("spruce_log", "Spruce Log", 47, "log_spruce");
        registerBlock("spruce_leaves", "Spruce Leaves", 48, "leaves_spruce");
        registerBlock("jungle_log", "Jungle Log", 49, "log_jungle");
        registerBlock("jungle_leaves", "Jungle Leaves", 50, "leaves_jungle");
        registerBlock("acacia_log", "Acacia Log", 51, "log_acacia");
        registerBlock("dark_oak_log", "Dark Oak Log", 52, "log_big_oak");
        registerBlock("dark_oak_leaves", "Dark Oak Leaves", 53, "leaves_big_oak");
        registerBlock("gravel", "Gravel", 54, "gravel");
        registerBlock("clay", "Clay", 55, "clay");
        registerBlock("brown_mushroom_block", "Brown Mushroom Block", 56, "mushroom_block_skin_brown");
        registerBlock("red_mushroom_block", "Red Mushroom Block", 57, "mushroom_block_skin_red");
        registerBlock("mushroom_stem", "Mushroom Stem", 58, "mushroom_block_skin_stem");
        registerBlock("sandstone", "Sandstone", 59, "sandstone_top");
        registerBlock("bone_block", "Bone Block", 60, "bone_block_side");
        registerBlock("coal_ore", "Coal Ore", 61, "coal_ore");
        registerBlock("tulip", "Tulip", 62, "flower_tulip_red");
        registerBlock("azure_bluet", "Azure Bluet", 63, "flower_houstonia");
        registerBlock("fern", "Fern", 64, "tallgrass_fern");
        registerBlock("hardened_clay", "Hardened Clay", 65, "hardened_clay");
        registerBlock("mycelium", "Mycelium", 66, "mycelium_top");
        registerBlock("snow_layer", "Snow", 67, "snow");
        registerBlock("ice", "Ice", 68, "ice");
        registerBlock("packed_ice", "Packed Ice", 69, "ice_packed");
        registerBlock("cobblestone", "Cobblestone", 71, "cobblestone");
        registerBlock("red_sand", "Red Sand", 78, "red_sand");
        registerBlock("iron_ore", "Iron Ore", 81, "iron_ore");
        registerBlock("gold_ore", "Gold Ore", 82, "gold_ore");
        registerBlock("diamond_ore", "Diamond Ore", 83, "diamond_ore");
        registerBlock("emerald_ore", "Emerald Ore", 84, "emerald_ore");
        registerBlock("lapis_ore", "Lapis Ore", 85, "lapis_ore");
        registerBlock("wool", "White Wool", 91, "wool_colored_white");
        // --- Cold/Blue Aercloud remnants ---
        registerBlock("cold_aercloud", "Cold Aercloud", 125, "cold_aercloud");
        registerBlock("golden_aercloud", "Golden Aercloud", 126, "golden_aercloud");
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
        // --- Wood planks ---
        registerBlock("oak_planks", "Oak Planks", 72, "planks_oak");
        registerBlock("spruce_planks", "Spruce Planks", 73, "planks_spruce");
        registerBlock("birch_planks", "Birch Planks", 74, "planks_birch");
        registerBlock("jungle_planks", "Jungle Planks", 75, "planks_jungle");
        registerBlock("acacia_planks", "Acacia Planks", 76, "planks_acacia");
        registerBlock("dark_oak_planks", "Dark Oak Planks", 77, "planks_big_oak");
        registerBlock("smooth_sandstone", "Smooth Sandstone", 79, "sandstone_smooth");
        registerBlock("crafting_table", "Crafting Table", 115, "crafting_table_top");
        registerBlock("furnace_off", "Furnace", 116, "furnace_front");
        registerBlock("furnace_on", "Furnace", 117, "furnace_front_on");
        registerBlock("chest", "Chest", 118, "chest_front");
        registerTool("stick", "Stick", "stick", ToolType.HAND, 1.0f, new Vector4f(0.85f, 0.7f, 0.5f, 1));
        // --- Parachutes ---
        registerTool("cold_parachute", "Cold Parachute", "cold_parachute", ToolType.HAND, 1.0f, new Vector4f(0.7f, 0.85f, 1, 1));
        registerTool("golden_parachute", "Golden Parachute", "golden_parachute", ToolType.HAND, 1.0f, new Vector4f(1, 0.9f, 0.5f, 1));
        // --- New staple blocks ---
        registerBlock("brick", "Bricks", 130, "brick");
        registerBlock("stone_brick", "Stone Bricks", 131, "stonebrick");
        registerBlock("mossy_cobblestone", "Mossy Cobblestone", 132, "cobblestone_mossy");
        registerBlock("andesite", "Andesite", 133, "stone_andesite");
        registerBlock("diorite", "Diorite", 134, "stone_diorite");
        registerBlock("granite", "Granite", 135, "stone_granite");
        registerBlock("bookshelf", "Bookshelf", 136, "bookshelf");
        registerBlock("iron_block", "Iron Block", 137, "iron_block");
        registerBlock("gold_block", "Gold Block", 138, "gold_block");
        registerBlock("diamond_block", "Diamond Block", 139, "diamond_block");
        registerBlock("emerald_block", "Emerald Block", 140, "emerald_block");
        registerBlock("lapis_block", "Lapis Lazuli Block", 141, "lapis_block");
        // --- Stair blocks ---
        registerBlock("oak_stairs", "Oak Stairs", 200, "planks_oak");
        registerBlock("cobblestone_stairs", "Cobblestone Stairs", 201, "cobblestone");
        registerBlock("stone_brick_stairs", "Stone Brick Stairs", 202, "stonebrick");
        registerBlock("brick_stairs", "Brick Stairs", 203, "brick");
        registerBlock("sandstone_stairs", "Sandstone Stairs", 204, "sandstone_top");
        registerBlock("nether_brick_stairs", "Nether Brick Stairs", 205, "nether_brick");
        // --- Slab blocks ---
        registerBlock("oak_slab", "Oak Slab", 206, "planks_oak");
        registerBlock("cobblestone_slab", "Cobblestone Slab", 207, "cobblestone");
        registerBlock("stone_brick_slab", "Stone Brick Slab", 208, "stonebrick");
        registerBlock("brick_slab", "Brick Slab", 209, "brick");
        registerBlock("sandstone_slab", "Sandstone Slab", 210, "sandstone_top");
        // --- Torch ---
        registerBlock("torch", "Torch", 211, "torch_on");
        // --- Aether tools ---
        registerTool("wood_pickaxe", "Wood Pickaxe", "wood_pickaxe", ToolType.PICKAXE, 4.5f, new Vector4f(1, 1, 1, 1), TIER_WOOD);
        registerTool("wood_shovel", "Wood Shovel", "wood_shovel", ToolType.SHOVEL, 4.0f, new Vector4f(1, 1, 1, 1), TIER_WOOD);
        registerTool("wood_axe", "Wood Axe", "wood_axe", ToolType.AXE, 4.2f, new Vector4f(1, 1, 1, 1), TIER_WOOD);
        registerTool("stone_pickaxe", "Stone Pickaxe", "stone_pickaxe", ToolType.PICKAXE, 6.0f, new Vector4f(1, 1, 1, 1), TIER_STONE);
        registerTool("stone_shovel", "Stone Shovel", "stone_shovel", ToolType.SHOVEL, 5.5f, new Vector4f(1, 1, 1, 1), TIER_STONE);
        registerTool("stone_axe", "Stone Axe", "stone_axe", ToolType.AXE, 5.8f, new Vector4f(1, 1, 1, 1), TIER_STONE);
        registerTool("iron_pickaxe", "Iron Pickaxe", "iron_pickaxe", ToolType.PICKAXE, 8.5f, new Vector4f(1, 1, 1, 1), TIER_IRON);
        registerTool("iron_shovel", "Iron Shovel", "iron_shovel", ToolType.SHOVEL, 7.5f, new Vector4f(1, 1, 1, 1), TIER_IRON);
        registerTool("iron_axe", "Iron Axe", "iron_axe", ToolType.AXE, 7.8f, new Vector4f(1, 1, 1, 1), TIER_IRON);
        registerTool("diamond_pickaxe", "Diamond Pickaxe", "diamond_pickaxe", ToolType.PICKAXE, 12.0f, new Vector4f(0.4f, 0.85f, 0.95f, 1), TIER_DIAMOND);
        registerTool("diamond_shovel", "Diamond Shovel", "diamond_shovel", ToolType.SHOVEL, 10.0f, new Vector4f(0.4f, 0.85f, 0.95f, 1), TIER_DIAMOND);
        registerTool("diamond_axe", "Diamond Axe", "diamond_axe", ToolType.AXE, 10.5f, new Vector4f(0.4f, 0.85f, 0.95f, 1), TIER_DIAMOND);
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
        if (blockItemByBlockId.containsKey(blockId)) {
            throw new RuntimeException("Item block ID collision! ID " + blockId + " is already registered to '" + blockItemByBlockId.get(blockId) + "'. Attempted to register '" + itemId + "'.");
        }
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

    private void registerTool(String itemId, String displayName, String textureName, ToolType toolType, float miningSpeed, Vector4f color, int tier) {
        int iconLayer = textureManager.getTextureIndex(textureName);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.TOOL, 0, iconLayer, toolType, miningSpeed, 1, color, tier);
        itemRegistry.put(itemId, definition);
        registerAlias(itemId, itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    private void registerTool(String itemId, String displayName, String textureName, ToolType toolType, float miningSpeed, Vector4f color) {
        registerTool(itemId, displayName, textureName, toolType, miningSpeed, color, 0);
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
