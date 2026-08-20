package com.voxel.crafting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Imports vanilla 1.12.2 shaped and shapeless crafting recipes. */
public final class VanillaRecipeLoader {
    private VanillaRecipeLoader() { }

    public static int load(CraftingManager manager, String recipesDir, Set<String> knownItems) {
        Path root = Paths.get(recipesDir);
        if (!Files.isDirectory(root)) return 0;
        int loaded = 0;
        int skipped = 0;
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            paths.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path path : files) {
                try {
                    JSONObject recipe = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                    if (!"crafting_shaped".equals(recipe.optString("type", "crafting_shaped"))
                            && !"crafting_shapeless".equals(recipe.optString("type", "crafting_shaped"))) {
                        skipped++;
                        continue;
                    }
                    String result = canonicalResultId(ingredientId(recipe.getJSONObject("result")), knownItems);
                    if (result == null) {
                        skipped++;
                        continue;
                    }
                    int count = recipe.getJSONObject("result").optInt("count", 1);
                    int gridSize;
                    if ("crafting_shapeless".equals(recipe.optString("type"))) {
                        List<String> ingredients = new ArrayList<>();
                        JSONArray array = recipe.getJSONArray("ingredients");
                        for (int i = 0; i < array.length(); i++) {
                            String value = ingredientValue(array.get(i));
                            if (value == null || !isKnownIngredient(value, knownItems)) { ingredients.clear(); break; }
                            ingredients.add(value);
                        }
                        if (ingredients.isEmpty()) { skipped++; continue; }
                        gridSize = ingredients.size() > 4 ? 3 : 2;
                        manager.addLoadedShapeless(ingredients, result, count, gridSize);
                    } else {
                        JSONArray rows = recipe.getJSONArray("pattern");
                        int height = rows.length();
                        int width = 0;
                        for (int r = 0; r < height; r++) width = Math.max(width, rows.getString(r).length());
                        if (height > 3 || width > 3 || height == 0 || width == 0) { skipped++; continue; }
                        String[][] pattern = new String[height][width];
                        JSONObject key = recipe.getJSONObject("key");
                        boolean valid = true;
                        for (int r = 0; r < height && valid; r++) {
                            String row = rows.getString(r);
                            for (int c = 0; c < width; c++) {
                                char symbol = c < row.length() ? row.charAt(c) : ' ';
                                if (symbol == ' ') {
                                    pattern[r][c] = null;
                                } else if (!key.has(String.valueOf(symbol))) {
                                    valid = false;
                                    break;
                                } else {
                                    pattern[r][c] = ingredientValue(key.get(String.valueOf(symbol)));
                                    if (pattern[r][c] == null || !isKnownIngredient(pattern[r][c], knownItems)) {
                                        valid = false;
                                        break;
                                    }
                                }
                            }
                        }
                        if (!valid) { skipped++; continue; }
                        gridSize = Math.max(height, width) <= 2 ? 2 : 3;
                        manager.addLoadedShaped(pattern, result, count, gridSize);
                    }
                    loaded++;
                } catch (RuntimeException ex) {
                    skipped++;
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to scan vanilla recipes: " + root, ex);
        }
        System.out.println("[MC content] Loaded " + loaded + " vanilla recipes; skipped " + skipped);
        return loaded;
    }

    private static String ingredientValue(Object value) {
        if (value instanceof JSONArray) {
            JSONArray alternatives = (JSONArray) value;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < alternatives.length(); i++) {
                String item = ingredientId(alternatives.getJSONObject(i));
                if (item != null) values.add(item);
            }
            return values.isEmpty() ? null : alternativeToken(values);
        }
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;
        if (object.has("tag")) return "@" + shortName(object.getString("tag"));
        return ingredientId(object);
    }

    private static String ingredientId(JSONObject object) {
        if (object == null || !object.has("item")) return null;
        String item = shortName(object.getString("item"));
        int data = object.has("data") ? object.optInt("data", 0) : 0;
        return legacyVariant(item, data);
    }

    private static String legacyVariant(String item, int data) {
        if (data == 32767 || data == -1) {
            if ("wool".equals(item)) return "@wool";
            if ("dye".equals(item)) return "@dyes";
            if ("planks".equals(item)) return "@planks";
            if ("log".equals(item) || "log2".equals(item)) return "@logs";
        }
        if ("planks".equals(item)) return indexed(data,
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks");
        if ("log".equals(item)) return indexed(data, "oak_log", "spruce_log", "birch_log", "jungle_log");
        if ("log2".equals(item)) return indexed(data, "acacia_log", "dark_oak_log");
        if ("wool".equals(item)) return indexed(data,
                "white_wool", "orange_wool", "magenta_wool", "light_blue_wool", "yellow_wool", "lime_wool",
                "pink_wool", "gray_wool", "light_gray_wool", "cyan_wool", "purple_wool", "blue_wool",
                "brown_wool", "green_wool", "red_wool", "black_wool");
        if ("dye".equals(item)) return indexed(data,
                "black_dye", "red_dye", "green_dye", "brown_dye", "blue_dye", "purple_dye", "cyan_dye",
                "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye",
                "magenta_dye", "orange_dye", "white_dye");
        if ("stone".equals(item)) return indexed(data, "stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite");
        if ("stonebrick".equals(item)) return indexed(data, "stone_brick", "mossy_stonebrick", "stone_brick", "chiseled_stonebrick", "cracked_stonebrick");
        if ("sandstone".equals(item)) return indexed(data, "sandstone", "chiseled_sandstone", "smooth_sandstone");
        if ("quartz_block".equals(item)) return indexed(data, "quartz_block", "chiseled_quartz_block", "quartz_column");
        if ("red_sandstone".equals(item)) return indexed(data, "red_sandstone", "chiseled_red_sandstone", "smooth_red_sandstone");
        if ("concrete".equals(item)) return colorVariant(data, "_concrete");
        if ("concrete_powder".equals(item)) return colorVariant(data, "_concrete_powder");
        if ("stained_glass".equals(item)) return colorVariant(data, "_stained_glass");
        if ("stained_glass_pane".equals(item)) return colorVariant(data, "_stained_glass_pane");
        if ("stained_hardened_clay".equals(item)) return colorVariant(data, "_stained_hardened_clay");
        if ("monster_egg".equals(item)) return indexed(data, "stone_monster_egg", "cobblestone_monster_egg", "stone_brick_monster_egg", "mossy_brick_monster_egg", "cracked_brick_monster_egg", "chiseled_brick_monster_egg");
        if ("stone_slab".equals(item)) return indexed(data, "stone_slab", "sandstone_slab", "wood_old_slab", "cobblestone_slab", "brick_slab", "stone_brick_slab");
        if ("wooden_slab".equals(item) || "wood_old_slab".equals(item)) return indexed(data, "oak_slab", "spruce_slab", "birch_slab", "jungle_slab");
        if ("red_sandstone_slab".equals(item)) return "red_sandstone_slab";
        if ("double_plant".equals(item)) return indexed(data, "sunflower", "syringa", "double_grass", "fern", "rose", "paeonia");
        if ("stone_slab2".equals(item)) return indexed(data, "red_sandstone_slab", "purpur_slab");
        if ("leaves".equals(item)) return indexed(data, "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves");
        if ("leaves2".equals(item)) return indexed(data, "acacia_leaves", "dark_oak_leaves");
        if ("bed".equals(item)) return colorVariant(data, "_bed");
        return item;
    }

    private static String canonicalResultId(String value, Set<String> knownItems) {
        if (value == null) return null;
        if (knownItems.contains(value)) return value;
        String alias = value;
        if ("furnace".equals(alias)) alias = "furnace_off";
        else if ("lit_pumpkin".equals(alias)) alias = "pumpkin";
        else if ("redstone".equals(alias)) alias = "redstone_wire";
        else if ("brick_block".equals(alias)) alias = "brick";
        else if ("stonebrick".equals(alias)) alias = "stone_brick";
        else if ("coal_block".equals(alias) && knownItems.contains("coal_block")) alias = "coal_block";
        if (knownItems.contains(alias)) return alias;
        return null;
    }

    private static boolean isKnownIngredient(String value, Set<String> knownItems) {
        if (value == null) return false;
        if (value.startsWith("@")) return true;
        return knownItems.contains(value);
    }

    private static String indexed(int index, String... values) {
        return index >= 0 && index < values.length ? values[index] : "@variant";
    }

    private static String colorVariant(int index, String suffix) {
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        return index >= 0 && index < colors.length ? colors[index] + suffix : "@colors";
    }

    private static String alternativeToken(List<String> values) {
        Set<String> unique = new HashSet<>(values);
        if (unique.size() == 1) return unique.iterator().next();
        return "@{" + String.join("|", unique) + "}";
    }

    private static String shortName(String value) {
        int colon = value.lastIndexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }
}
