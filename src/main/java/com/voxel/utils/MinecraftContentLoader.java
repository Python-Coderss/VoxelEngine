package com.voxel.utils;

import com.voxel.game.ItemDefinitions;
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

/**
 * Loads the parts of the Minecraft resource pack that are not hand-wired in
 * Main. This keeps the content registry data-driven as new 1.12.2 resources
 * are added, while preserving the project's special IDs and behaviors.
 */
public final class MinecraftContentLoader {
    private MinecraftContentLoader() { }

    /**
     * Registers one canonical renderable block for every blockstate file that
     * is not already represented by the engine's hand-written registry. The
     * first model in the blockstate is used as the default visual; directional,
     * age, and powered state names remain available as aliases for now.
     */
    public static int registerMissingBlocks(BlockDataManager blockDataManager,
                                            BlockRegistry blockRegistry,
                                            ShaderBlockRegistry shaderBlockRegistry,
                                            TextureManager textureManager,
                                            String blockstatesDir,
                                            String modelsDir,
                                            int firstId) {
        Path root = Paths.get(blockstatesDir);
        if (!Files.isDirectory(root)) return 0;

        int nextId = firstId;
        int registered = 0;
        List<String> failed = new ArrayList<>();
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(files::add);
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));

            for (Path statePath : files) {
                String name = stripExtension(statePath.getFileName().toString());
                if (blockRegistry.hasName(name)) continue;

                String model = firstModel(readJson(statePath));
                if (model == null) {
                    failed.add(name + " (no model)");
                    continue;
                }
                model = modelName(model);
                Path modelPath = Paths.get(modelsDir, model + ".json");
                if (!Files.exists(modelPath)) {
                    failed.add(name + " (missing model " + model + ")");
                    continue;
                }

                // Several blockstates are state aliases (for example lit_furnace)
                // whose first model is already hand-registered. Reuse that ID
                // instead of overwriting the canonical model's registry entry.
                int existingModelId = blockRegistry.getId(model);
                if (existingModelId > 0) {
                    blockRegistry.register(name, existingModelId);
                    shaderBlockRegistry.register(existingModelId, existingModelId);
                    registered++;
                    continue;
                }

                while (blockRegistry.hasId(nextId) || blockDataManager.blockRegistry.containsKey(nextId)) nextId++;
                try {
                    // Register the parsed data first. BlockDataManager only adds
                    // its name/ID after successful texture resolution.
                    blockDataManager.registerBlock(nextId, model, textureManager, modelsDir);
                    blockRegistry.register(name, nextId);
                    blockRegistry.register(model, nextId);
                    shaderBlockRegistry.register(nextId, nextId);
                    nextId++;
                    registered++;
                } catch (RuntimeException ex) {
                    failed.add(name + " (" + ex.getMessage() + ")");
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to scan Minecraft blockstates: " + root, ex);
        }

        System.out.println("[MC content] Registered " + registered + " additional blockstates"
                + (failed.isEmpty() ? "" : "; skipped " + failed.size()));
        if (!failed.isEmpty()) {
            System.err.println("[MC content] Blockstate skips: " + String.join(", ", failed));
        }
        return registered;
    }

    /**
     * Registers every item model as an inventory item when it is not already
     * covered by ItemDefinitions. Item-only resources get icon support; block
     * items reuse the canonical block ID when one exists.
     */
    public static int registerMissingItems(ItemDefinitions itemDefinitions,
                                           BlockDataManager blockDataManager,
                                           TextureManager textureManager,
                                           String itemModelsDir) {
        Path root = Paths.get(itemModelsDir);
        if (!Files.isDirectory(root)) return 0;

        int registered = 0;
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(files::add);
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));

            for (Path itemPath : files) {
                String itemId = stripExtension(itemPath.getFileName().toString());
                if (itemDefinitions.hasItem(itemId)) continue;

                JSONObject model = readJson(itemPath);
                String texture = firstTexture(model, itemModelsDir, new HashSet<String>());
                int iconLayer = texture == null ? -1 : textureManager.getTextureIndex(texture);
                Integer blockId = blockDataManager.findBlockId(itemId);
                itemDefinitions.registerGeneratedItem(itemId, displayName(itemId),
                        blockId == null ? 0 : blockId, iconLayer);
                registered++;
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to scan Minecraft item models: " + root, ex);
        }

        System.out.println("[MC content] Registered " + registered + " additional item models");
        return registered;
    }

    private static JSONObject readJson(Path path) {
        try {
            return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read Minecraft model resource: " + path, ex);
        }
    }

    private static String firstModel(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("model") && object.get("model") instanceof String) {
                return object.getString("model");
            }
            String[] preferred = { "variants", "multipart", "apply" };
            for (String key : preferred) {
                if (object.has(key)) {
                    String model = firstModel(object.get(key));
                    if (model != null) return model;
                }
            }
            for (String key : object.keySet()) {
                String model = firstModel(object.get(key));
                if (model != null) return model;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String model = firstModel(array.get(i));
                if (model != null) return model;
            }
        }
        return null;
    }

    private static String firstTexture(JSONObject model, String itemModelsDir, Set<String> visited) {
        if (model.has("textures")) {
            JSONObject textures = model.getJSONObject("textures");
            for (String key : textures.keySet()) {
                String value = textures.getString(key);
                if (!value.startsWith("#")) return textureName(value);
            }
        }
        if (!model.has("parent")) return null;
        String parent = model.getString("parent");
        if (parent.contains(":")) parent = parent.substring(parent.lastIndexOf(':') + 1);
        boolean blockParent = parent.startsWith("block/");
        String normalized = parent;
        if (normalized.startsWith("item/")) normalized = normalized.substring(5);
        else if (blockParent) normalized = normalized.substring(6);
        if (!visited.add((blockParent ? "block/" : "item/") + normalized)) return null;

        Path modelsRoot = Paths.get(itemModelsDir).getParent();
        Path parentPath = blockParent
                ? modelsRoot.resolve("block").resolve(normalized + ".json")
                : Paths.get(itemModelsDir, normalized + ".json");
        if (Files.exists(parentPath)) return firstTexture(readJson(parentPath), itemModelsDir, visited);
        return null;
    }

    private static String textureName(String value) {
        if (value.contains(":")) value = value.substring(value.lastIndexOf(':') + 1);
        if (value.contains("/")) value = value.substring(value.lastIndexOf('/') + 1);
        return value;
    }

    private static String modelName(String value) {
        if (value.contains(":")) value = value.substring(value.lastIndexOf(':') + 1);
        if (value.startsWith("block/")) value = value.substring(6);
        return value;
    }

    private static String stripExtension(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static String displayName(String id) {
        String[] parts = id.split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
