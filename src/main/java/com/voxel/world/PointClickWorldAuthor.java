package com.voxel.world;

import com.voxel.game.ChestManager;

/**
 * Hand-authored Point & Click demo world — a small, self-contained showcase
 * for the MCSM-style point-and-click + billboard system.
 *
 * A single plaza holds every interactable type in one screen: crafting table,
 * furnace, chest, both portals, and a villager pedestal, surrounded by a low
 * wall and lamp posts. The player spawns at the center so every billboard is
 * visible immediately on entry.
 *
 * Like {@link TutorialWorldAuthor}, this writes the whole world eagerly
 * through a {@link Sink}; {@link PointClickWorldExporter} materialises it
 * into a bundled template shipped under src/main/resources/pointclick_world.
 *
 * World size: 128x128 columns (8x8 chunks), flat, height ~72. The plaza sits
 * at the center; the rest is open grass so the scene has breathing room.
 */
public final class PointClickWorldAuthor {

    /** Surface height of the flat plaza (player spawns with feet at G+1 = 63). */
    public static final int G = 62;
    /** World extent: -64..63 in X/Z (8x8 chunks = 128x128 blocks). */
    public static final int MIN = -64, MAX = 63;
    /** Authored Y range (base terrain up to G, structures to G+4). */
    public static final int AREA_H = 72;

    // Block IDs (mirror TutorialWorldAuthor).
    private static final int GRASS = 1, STONE = 2, DIRT = 13,
            OBSIDIAN = 16, GLOWSTONE = 17, NETHER_PORTAL = 19, AETHER_PORTAL = 106,
            CRAFT_TABLE = 115, FURNACE = 116, CHEST = 118,
            STONE_BRICK = 131, BOOKSHELF = 136, GOLD_BLOCK = 138;

    /** A target that receives every voxel the author places (type + facing). */
    public interface Sink {
        void set(int x, int y, int z, int type, int extra);
    }

    /**
     * Builds the complete demo world into {@code sink} and records the plaza
     * chest inventory into {@code chests}.
     */
    public static void buildAll(Sink sink, ChestManager chests) {
        Author a = new Author(sink, chests);
        a.buildBaseTerrain();
        a.buildPlaza();
    }

    private static final class Author {
        final Sink sink;
        final ChestManager chests;

        Author(Sink sink, ChestManager chests) {
            this.sink = sink;
            this.chests = chests;
        }

        private void set(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }
        private void place(int x, int y, int z, int b) { sink.set(x, y, z, b, 0); }

        private void buildBaseTerrain() {
            // Flat grass plain under the whole 32x32 area, with a few layers
            // of dirt + stone beneath so the player can dig if they want.
            for (int x = MIN; x <= MAX; x++) {
                for (int z = MIN; z <= MAX; z++) {
                    set(x, G, z, GRASS);
                    set(x, G - 1, z, DIRT);
                    set(x, G - 2, z, DIRT);
                    set(x, G - 3, z, DIRT);
                    for (int y = G - 4; y >= 1; y--) set(x, y, z, STONE);
                    set(x, 0, z, STONE);
                }
            }
        }

        private void buildPlaza() {
            int x = 0, zz = 0; // spawn at center
            // Stone-brick plaza floor on top of the grass.
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    set(x + dx, G, zz + dz, STONE_BRICK);
                }
            }
            // Low ornamental border wall (1 block high — doesn't block billboards).
            for (int a = -10; a <= 10; a++) {
                place(x + a, G + 1, zz - 10, STONE_BRICK);
                place(x + a, G + 1, zz + 10, STONE_BRICK);
                place(x - 10, G + 1, zz + a, STONE_BRICK);
                place(x + 10, G + 1, zz + a, STONE_BRICK);
            }
            // ── Interactable stations, clustered so all billboards fit on screen ──
            // Crafting table
            place(x - 6, G + 1, zz - 4, CRAFT_TABLE);
            // Furnace
            place(x - 3, G + 1, zz - 4, FURNACE);
            // Chest (stocked with a sampler so opening it is rewarding)
            place(x, G + 1, zz - 4, CHEST);
            chestAt(x, G + 1, zz - 4,
                new String[]{"oak_log","iron_ingot","coal","apple","stick","torch","flint_and_steel"},
                new int[]{16, 8, 16, 4, 16, 12, 1});
            // Nether portal frame (obsidian + portal block) — 2 wide x 4 tall
            for (int py = 1; py <= 4; py++) place(x + 4, G + py, zz - 5, OBSIDIAN);
            for (int py = 1; py <= 3; py++) place(x + 4, G + py, zz - 5, NETHER_PORTAL);
            place(x + 4, G, zz - 5, OBSIDIAN);
            // Aether portal frame (glowstone + aether portal)
            for (int py = 1; py <= 4; py++) place(x + 7, G + py, zz - 5, GLOWSTONE);
            for (int py = 1; py <= 3; py++) place(x + 7, G + py, zz - 5, AETHER_PORTAL);
            place(x + 7, G, zz - 5, GLOWSTONE);
            // Villager pedestal (villager is spawned at runtime; this marks the spot).
            place(x - 2, G + 1, zz + 4, GOLD_BLOCK);
            place(x - 2, G + 2, zz + 4, GOLD_BLOCK);
            // Lamp posts at the four corners of the plaza.
            lampPost(x - 8, G, zz - 8); lampPost(x + 8, G, zz - 8);
            lampPost(x - 8, G, zz + 8); lampPost(x + 8, G, zz + 8);
            // Welcome sign (bookshelves) behind the villager pedestal.
            place(x, G + 1, zz + 6, BOOKSHELF);
            place(x, G + 2, zz + 6, BOOKSHELF);
            place(x + 1, G + 1, zz + 6, BOOKSHELF);
            place(x - 1, G + 1, zz + 6, BOOKSHELF);
        }

        private void lampPost(int x, int y, int z) {
            place(x, y + 1, z, STONE_BRICK);
            place(x, y + 2, z, STONE_BRICK);
            place(x, y + 3, z, GLOWSTONE);
        }

        private void chestAt(int x, int y, int z, String[] items, int[] counts) {
            // ChestManager stores by absolute block coords; items use itemId
            // strings, counts are stack sizes. Mirrors TutorialWorldAuthor.
            com.voxel.game.ItemDefinitions.ItemStack[] inv =
                new com.voxel.game.ItemDefinitions.ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                inv[i] = new com.voxel.game.ItemDefinitions.ItemStack(items[i], counts[i]);
            }
            chests.setInventory(x, y, z, inv);
        }
    }
}
