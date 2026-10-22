package com.voxel.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The enchanting table (block 458): three level-scaled offers for the held
 * tool, powered by nearby bookshelves.
 *
 * <p>Minecraft's table reads shelf power from the ring around it; this version
 * keeps the same feel with a simpler curve — 0-3 shelves unlock level I, 4-7
 * unlock II, 8+ unlock III. Prices are paid in XP levels (1/2/3), so the
 * progression loop is: mine, level up, come back, enchant.</p>
 *
 * <p>The open table is modal: {@link #open} publishes a snapshot of the offers
 * to {@link GameContext}, {@link #apply} spends levels and stamps the stack,
 * and {@link #close} clears it. The HUD only reads that snapshot.</p>
 */
public final class EnchantingTable {

    public static final int BLOCK_ID = 458;

    /** One row in the table UI. */
    public static final class Offer {
        public final String enchantment;
        public final int level;
        public final int cost;

        public Offer(String enchantment, int level, int cost) {
            this.enchantment = enchantment;
            this.level = level;
            this.cost = cost;
        }

        public String label() {
            return Enchantments.label(enchantment, level);
        }

        /** "Sharpness II — 2 levels" */
        public String row() {
            return label() + " — " + cost + (cost == 1 ? " level" : " levels");
        }
    }

    private EnchantingTable() {
    }

    public static boolean isTable(int blockId) {
        return blockId == BLOCK_ID;
    }

    /** Tools only: blocks and plain items have nothing to enchant. */
    public static boolean canEnchant(ItemDefinitions.ItemDefinition def) {
        return def != null && def.kind == ItemDefinitions.ItemKind.TOOL
                && def.toolType != ItemDefinitions.ToolType.HAND;
    }

    /** The enchantment this tool takes, or null when it cannot be enchanted. */
    public static String enchantmentFor(ItemDefinitions.ItemDefinition def) {
        if (!canEnchant(def)) return null;
        // Axes double as the engine's swords (Gabriel's Sword, the gauntlet),
        // so they get the weapon enchant; digging tools get Efficiency.
        return def.toolType == ItemDefinitions.ToolType.AXE
                ? Enchantments.SHARPNESS
                : Enchantments.EFFICIENCY;
    }

    /** Bookshelf blocks within the vanilla-ish ring around the table. */
    public static int bookshelfPower(GameContext ctx, int x, int y, int z) {
        if (ctx == null || ctx.world == null || ctx.blockRegistry == null) return 0;
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int id = ctx.world.getVoxel(x + dx, y + dy, z + dz);
                    if (id <= 0) continue;
                    if ("bookshelf".equals(ctx.blockRegistry.getName(id))) count++;
                }
            }
        }
        return count;
    }

    public static int maxLevelFor(int bookshelves) {
        if (bookshelves >= 8) return 3;
        if (bookshelves >= 4) return 2;
        return 1;
    }

    /** Offers for the currently held stack at this table. */
    public static List<Offer> offers(GameContext ctx, int x, int y, int z) {
        if (ctx == null || ctx.playerInventory == null || ctx.itemDefinitions == null) {
            return Collections.emptyList();
        }
        ItemDefinitions.ItemStack stack = ctx.playerInventory.getSelected();
        ItemDefinitions.ItemDefinition def = stack == null ? null
                : ctx.itemDefinitions.getDefinition(stack.itemId);
        if (!canEnchant(def)) return Collections.emptyList();

        String enchantment = enchantmentFor(def);
        int max = maxLevelFor(bookshelfPower(ctx, x, y, z));
        List<Offer> out = new ArrayList<Offer>();
        for (int level = 1; level <= max; level++) {
            out.add(new Offer(enchantment, level, level));
        }
        return out;
    }

    /**
     * Open the table for the held stack. Returns false (with a status line)
     * when nothing enchantable is held, so the caller can fall through.
     */
    public static boolean open(GameContext ctx, int x, int y, int z) {
        List<Offer> offers = offers(ctx, x, y, z);
        if (offers.isEmpty()) {
            ctx.setStatus("Hold a tool or weapon to use the enchanting table");
            return false;
        }
        ItemDefinitions.ItemStack stack = ctx.playerInventory.getSelected();
        ItemDefinitions.ItemDefinition def = ctx.itemDefinitions.getDefinition(stack.itemId);
        ctx.enchantingTableX = x;
        ctx.enchantingTableY = y;
        ctx.enchantingTableZ = z;
        ctx.enchantingOffers = offers;
        ctx.enchantingItemName = def == null ? stack.itemId : def.displayName;
        ctx.enchantingOpen = true;
        return true;
    }

    /** Close the table without applying anything. */
    public static void close(GameContext ctx) {
        ctx.enchantingOpen = false;
        ctx.enchantingOffers = Collections.emptyList();
        ctx.enchantingItemName = "";
    }

    /**
     * Apply an offer. Spends XP levels and stamps the held stack; keeps the
     * table open so the player can see the updated state.
     *
     * @return false only when the table is closed or the index is invalid
     */
    public static boolean apply(GameContext ctx, int index) {
        if (ctx == null || !ctx.enchantingOpen) return false;
        List<Offer> offers = ctx.enchantingOffers;
        if (index < 0 || index >= offers.size()) return false;
        Offer offer = offers.get(index);

        ItemDefinitions.ItemStack stack = ctx.playerInventory == null
                ? null : ctx.playerInventory.getSelected();
        ItemDefinitions.ItemDefinition def = stack == null ? null
                : ctx.itemDefinitions.getDefinition(stack.itemId);
        if (!canEnchant(def)) {
            ctx.setStatus("Hold a tool or weapon to use the enchanting table");
            return false;
        }
        if (ctx.player != null && ctx.player.getExperienceLevel() < offer.cost) {
            ctx.setStatus("Not enough levels: need " + offer.cost
                    + ", have " + ctx.player.getExperienceLevel());
            return true;
        }
        if (offer.enchantment.equals(stack.enchantment) && stack.enchantLevel >= offer.level) {
            ctx.setStatus("Already " + offer.label());
            return true;
        }
        if (ctx.player != null) ctx.player.spendLevels(offer.cost);
        stack.enchantment = offer.enchantment;
        stack.enchantLevel = offer.level;
        ctx.setStatus("Enchanted " + ctx.enchantingItemName + " with " + offer.label());
        ctx.enchantingOffers = offers(ctx,
                ctx.enchantingTableX, ctx.enchantingTableY, ctx.enchantingTableZ);
        if (ctx.cinematic != null) {
            ctx.cinematic.playActionBeat(com.voxel.cinematic.CinematicSystem.Beat.ENCHANT,
                    new org.joml.Vector3f(ctx.enchantingTableX + 0.5f,
                            ctx.enchantingTableY + 1.0f,
                            ctx.enchantingTableZ + 0.5f), true);
        }
        return true;
    }
}
