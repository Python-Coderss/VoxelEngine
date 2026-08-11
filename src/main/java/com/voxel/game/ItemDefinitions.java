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
        public final int dropBlockId; // separate model for dropped items (vertical plane vs horizontal crafting table)
        public final int iconLayer;
        public final ToolType toolType;
        public final float miningSpeed;
        public final int maxStack;
        public final Vector4f color;
        public final int tier; // 0=hand, 1=wood, 2=stone, 3=iron, 4=diamond

        public ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int dropBlockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color, int tier) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.blockId = blockId;
            this.dropBlockId = dropBlockId;
            this.iconLayer = iconLayer;
            this.toolType = toolType;
            this.miningSpeed = miningSpeed;
            this.maxStack = maxStack;
            this.color = new Vector4f(color);
            this.tier = tier;
        }

        public ItemDefinition(String id, String displayName, ItemKind kind, int blockId, int iconLayer, ToolType toolType, float miningSpeed, int maxStack, Vector4f color) {
            this(id, displayName, kind, blockId, blockId, iconLayer, toolType, miningSpeed, maxStack, color, 0);
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
        // Block 31 is the piston BASE (32 = sticky base). Blocks 33/259 are the
        // extended heads and must never be placed directly by the player.
        registerBlock("piston", "Piston", 31, "piston_top_normal");
        
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
        // --- Items with flat models (block IDs 212-218) ---
        registerItemBlock("flint", "Flint", "flint", 212, 231, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("iron_ingot", "Iron Ingot", "iron_ingot", 213, 232, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("stick", "Stick", "stick", 214, 233, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("flint_and_steel", "Flint and Steel", "flint_and_steel", 215, 234, ToolType.HAND, 1.0f, 1, 0);
        registerItemBlock("bucket", "Bucket", "bucket_empty", 216, 235, ToolType.HAND, 1.0f, 1, 0);
        registerItemBlock("water_bucket", "Water Bucket", "bucket_water", 217, 236, ToolType.HAND, 1.0f, 1, 0);
        registerItemBlock("lava_bucket", "Lava Bucket", "bucket_lava", 218, 237, ToolType.HAND, 1.0f, 1, 0);
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
        // --- Create-inspired ores and metal blocks (copper + zinc) ---
        registerBlock("copper_ore", "Copper Ore", 142, "copper_ore");
        registerBlock("copper_block", "Copper Block", 143, "copper_block");
        registerBlock("zinc_ore", "Zinc Ore", 144, "zinc_ore");
        registerBlock("zinc_block", "Zinc Block", 145, "zinc_block");
        // --- Create-inspired blocks ---
        registerBlock("andesite_casing", "Andesite Casing", 262, "stone_andesite");
        registerBlock("encased_fan", "Encased Fan", 263, "furnace_top");
        // --- Villager TV (block 274) ---
        registerBlock("villager_tv", "Villager TV", 274, "villager_tv_front");
        // Ancient-builder command blocks and the portable power-fragment key.
        registerBlock("command_block", "Command Block", 275, "command_block_side");
        registerBlock("chain_command_block", "Chain Command Block", 276, "chain_command_block_side");
        registerBlock("repeating_command_block", "Repeating Command Block", 277, "repeating_command_block_side");
        registerItemBlock("power_fragment", "Power Fragment", "emerald", 278, 278, ToolType.HAND, 1.0f, 16, 0);
        // --- Material items for proper progression (coal/diamond drop from ores, ingots smelt) ---
        registerItemBlock("coal", "Coal", "coal", 279, 285, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("diamond", "Diamond", "diamond", 280, 286, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("gold_ingot", "Gold Ingot", "gold_ingot", 281, 287, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("copper_ingot", "Copper Ingot", "copper_ingot", 282, 288, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("zinc_ingot", "Zinc Ingot", "zinc_ingot", 283, 289, ToolType.HAND, 1.0f, 64, 0);
        registerItemBlock("charcoal", "Charcoal", "charcoal", 284, 290, ToolType.HAND, 1.0f, 64, 0);
        // --- Create-inspired kinetic blocks (shafts, cogs, water wheel) ---
        registerBlock("shaft", "Shaft", 291, "axis_top");
        registerBlock("cogwheel", "Cogwheel", 294, "cogwheel");
        registerBlock("large_cogwheel", "Large Cogwheel", 295, "large_cogwheel");
        registerBlock("water_wheel", "Water Wheel", 296, "wheel");
        // --- Colored redstone lamps (off variants are the items; on variants drop the off item) ---
        String[] lampColors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                               "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int c = 0; c < lampColors.length; c++) {
            String col = lampColors[c];
            int offId = 297 + c * 2;
            int onId = offId + 1;
            String cap = col.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + col.substring(1);
            registerBlock("lamp_" + col, cap + " Lamp", offId, "lamp_" + col + "_off");
            blockItemByBlockId.put(onId, "lamp_" + col);
        }
        // --- Redstone repeaters (only the off, base-direction items are placeable) ---
        String[] hDirs = {"north", "south", "west", "east"};
        for (int d = 0; d < 4; d++) {
            String dir = hDirs[d];
            registerBlock("repeater_" + dir, "Redstone Repeater", 329 + d, "repeater_off_" + dir);
            blockItemByBlockId.put(333 + d, "repeater_" + dir);
        }
        // --- Redstone comparators ---
        for (int d = 0; d < 4; d++) {
            String dir = hDirs[d];
            registerBlock("comparator_" + dir, "Redstone Comparator", 337 + d, "comparator_off_" + dir);
            for (int s = 1; s < 4; s++) {
                blockItemByBlockId.put(337 + d + s * 4, "comparator_" + dir);
            }
        }
        // --- Clutches and gearshifts ---
        registerBlock("clutch", "Clutch", 353, "clutch_off");
        blockItemByBlockId.put(354, "clutch");
        registerBlock("gearshift", "Gearshift", 355, "gearshift_off");
        blockItemByBlockId.put(356, "gearshift");
        // --- Dyes + nether quartz ---
        String[] dyeColors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                              "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int c = 0; c < dyeColors.length; c++) {
            String col = dyeColors[c];
            String tex = col.equals("light_gray") ? "dye_powder_silver" : "dye_powder_" + col;
            String cap = col.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + col.substring(1);
            registerItemBlock(col + "_dye", cap + " Dye", tex, 357 + c, 373 + c, ToolType.HAND, 1.0f, 64, 0);
        }
        registerItemBlock("nether_quartz", "Nether Quartz", "quartz", 389, 390, ToolType.HAND, 1.0f, 64, 0);
        // --- Rails and minecarts ---
        registerBlock("rail", "Rail", 391, "rail_normal");
        blockItemByBlockId.put(392, "rail"); // E-W rail variant drops the same item
        registerBlock("minecart", "Minecart", 393, "minecart_normal");
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
        // --- Tool items with flat models (block IDs 219-230) ---
        registerItemBlock("wood_pickaxe", "Wood Pickaxe", "wood_pickaxe", 219, 238, ToolType.PICKAXE, 4.5f, 1, TIER_WOOD);
        registerItemBlock("wood_shovel", "Wood Shovel", "wood_shovel", 220, 239, ToolType.SHOVEL, 4.0f, 1, TIER_WOOD);
        registerItemBlock("wood_axe", "Wood Axe", "wood_axe", 221, 240, ToolType.AXE, 4.2f, 1, TIER_WOOD);
        registerItemBlock("stone_pickaxe", "Stone Pickaxe", "stone_pickaxe", 222, 241, ToolType.PICKAXE, 6.0f, 1, TIER_STONE);
        registerItemBlock("stone_shovel", "Stone Shovel", "stone_shovel", 223, 242, ToolType.SHOVEL, 5.5f, 1, TIER_STONE);
        registerItemBlock("stone_axe", "Stone Axe", "stone_axe", 224, 243, ToolType.AXE, 5.8f, 1, TIER_STONE);
        registerItemBlock("iron_pickaxe", "Iron Pickaxe", "iron_pickaxe", 225, 244, ToolType.PICKAXE, 8.5f, 1, TIER_IRON);
        registerItemBlock("iron_shovel", "Iron Shovel", "iron_shovel", 226, 245, ToolType.SHOVEL, 7.5f, 1, TIER_IRON);
        registerItemBlock("iron_axe", "Iron Axe", "iron_axe", 227, 246, ToolType.AXE, 7.8f, 1, TIER_IRON);
        registerItemBlock("diamond_pickaxe", "Diamond Pickaxe", "diamond_pickaxe", 228, 247, ToolType.PICKAXE, 12.0f, 1, TIER_DIAMOND);
        registerItemBlock("diamond_shovel", "Diamond Shovel", "diamond_shovel", 229, 248, ToolType.SHOVEL, 10.0f, 1, TIER_DIAMOND);
        registerItemBlock("diamond_axe", "Diamond Axe", "diamond_axe", 230, 249, ToolType.AXE, 10.5f, 1, TIER_DIAMOND);
        // --- Aliases ---
        registerAlias("pickaxe", "wood_pickaxe");
        registerAlias("shovel", "wood_shovel");
        registerAlias("axe", "wood_axe");
        registerAlias("redstone_dust", "redstone_wire");
        registerAlias("sticky", "sticky_piston");
        registerAlias("redstone", "redstone_wire");
        registerAlias("dust", "redstone_wire");
        registerAlias("piston_block", "piston");
        registerAlias("empty_bucket", "bucket");
        registerAlias("iron_bucket", "bucket");
        registerAlias("silver_dye", "light_gray_dye");
        // --- Drop mappings for orientation variants (no separate items) ---
        // Horizontal oak logs (260/261) drop the regular oak_log item.
        blockItemByBlockId.put(260, "oak_log");
        blockItemByBlockId.put(261, "oak_log");
        // Directional piston variants drop the regular piston items
        blockItemByBlockId.put(264, "piston");
        blockItemByBlockId.put(265, "piston");
        blockItemByBlockId.put(266, "piston");
        blockItemByBlockId.put(267, "piston");
        blockItemByBlockId.put(268, "piston");
        blockItemByBlockId.put(269, "sticky_piston");
        blockItemByBlockId.put(270, "sticky_piston");
        blockItemByBlockId.put(271, "sticky_piston");
        blockItemByBlockId.put(272, "sticky_piston");
        blockItemByBlockId.put(273, "sticky_piston");
        // Horizontal shaft variants (292/293) drop the regular shaft item.
        blockItemByBlockId.put(292, "shaft");
        blockItemByBlockId.put(293, "shaft");
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
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.TOOL, 0, 0, iconLayer, toolType, miningSpeed, 1, color, tier);
        itemRegistry.put(itemId, definition);
        registerAlias(itemId, itemId);
        registerAlias(displayName.toLowerCase(Locale.ROOT).replace(' ', '_'), itemId);
    }

    /** Register an item with both a block model (for crafting-table 3D rendering) and tool properties.
     *  @param blockId   crafting-table model (horizontal flat plane)
     *  @param dropBlockId  dropped-item model (vertical plane, 3px thick) */
    private void registerItemBlock(String itemId, String displayName, String textureName, int blockId, int dropBlockId, ToolType toolType, float miningSpeed, int maxStack, int tier) {
        if (blockItemByBlockId.containsKey(blockId)) {
            throw new RuntimeException("Item block ID collision! ID " + blockId + " is already registered to '" + blockItemByBlockId.get(blockId) + "'. Attempted to register '" + itemId + "'.");
        }
        int iconLayer = textureManager.getTextureIndex(textureName);
        Color albedo = blockDataManager.getAlbedo(blockId);
        Vector4f color = new Vector4f(albedo.getRed() / 255.0f, albedo.getGreen() / 255.0f, albedo.getBlue() / 255.0f, 1.0f);
        ItemDefinition definition = new ItemDefinition(itemId, displayName, ItemKind.BLOCK, blockId, dropBlockId, iconLayer, toolType, miningSpeed, maxStack, color, tier);
        itemRegistry.put(itemId, definition);
        blockItemByBlockId.put(blockId, itemId);
        registerAlias(itemId, itemId);
        registerAlias(itemId + "_block", itemId);
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
