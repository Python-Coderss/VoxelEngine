package com.voxel.world;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Headless validation of the decoration block models.
 *
 * <p>Registering a block needs the model's texture references to resolve on
 * disk; there is no OpenGL context here, so this reads the JSON directly. It
 * catches the failure mode a missing texture causes at boot: a model that
 * parses but cannot bind every face, or a face pointing at a texture the
 * resource tree does not ship.</p>
 */
public class DecorationModelsHeadlessTest {

    private static final File BLOCK_MODELS =
            new File("src/main/resources/assets/minecraft/models/block");
    private static final File ITEM_MODELS =
            new File("src/main/resources/assets/minecraft/models/item");
    private static final File BLOCK_TEXTURES =
            new File("src/main/resources/assets/minecraft/textures/blocks");

    @Test
    public void everyDecorationModelResolvesToRealTextures() throws Exception {
        assumeTrue("model directory present", BLOCK_MODELS.isDirectory());
        assumeTrue("texture directory present", BLOCK_TEXTURES.isDirectory());
        for (String name : DecorBlocks.NAMES) {
            JSONObject model = readBlockModel(name, new HashSet<String>());
            Set<String> textures = new HashSet<String>();
            collectTextures(model, textures);
            assertTrue(name + " references at least one texture", !textures.isEmpty());
            for (String texture : textures) {
                assertTrue(name + " texture '" + texture + "' exists",
                        new File(BLOCK_TEXTURES, texture + ".png").isFile());
            }
        }
    }

    @Test
    public void bedAndBannerGeometryBindsAllSixFaces() throws Exception {
        assumeTrue("model directory present", BLOCK_MODELS.isDirectory());
        for (String name : DecorBlocks.NAMES) {
            if (!name.equals("bed") && !name.startsWith("bed_")
                    && !name.equals("banner") && !name.startsWith("banner_")) {
                continue;
            }
            JSONObject model = readBlockModel(name, new HashSet<String>());
            JSONArray elements = model.getJSONArray("elements");
            assertTrue(name + " has geometry", elements.length() > 0);
            for (int i = 0; i < elements.length(); i++) {
                JSONObject faces = elements.getJSONObject(i).getJSONObject("faces");
                assertEquals(name + " element " + i + " binds six faces", 6, faces.length());
                for (String face : faces.keySet()) {
                    assertTrue(name + " " + face + " has a texture",
                            faces.getJSONObject(face).has("texture"));
                }
            }
        }
    }

    @Test
    public void bedAndBannerItemsUseARealIconTexture() throws Exception {
        assumeTrue("item model directory present", ITEM_MODELS.isDirectory());
        for (String item : new String[]{"bed", "banner"}) {
            JSONObject model = new JSONObject(read(new File(ITEM_MODELS, item + ".json")));
            String layer = model.getJSONObject("textures").getString("layer0");
            assertTrue(item + " icon is a painted item model",
                    "item/generated".equals(model.getString("parent")));
            String texture = layer.contains("/")
                    ? layer.substring(layer.lastIndexOf('/') + 1) : layer;
            assertTrue(item + " icon texture '" + texture + "' exists",
                    new File(BLOCK_TEXTURES, texture + ".png").isFile());
        }
    }

    /** Load a model and follow its whole parent chain (block/... aliases included). */
    private static JSONObject readBlockModel(String name, Set<String> visited) throws Exception {
        File file = new File(BLOCK_MODELS, name + ".json");
        assertTrue("model " + name + ".json exists", file.isFile());
        if (!visited.add(name)) return new JSONObject();
        JSONObject model = new JSONObject(read(file));
        if (model.has("parent")) {
            String parent = model.getString("parent");
            if (parent.contains(":")) parent = parent.substring(parent.lastIndexOf(':') + 1);
            if (parent.startsWith("block/")) parent = parent.substring(6);
            if (new File(BLOCK_MODELS, parent + ".json").isFile()) {
                JSONObject resolved = readBlockModel(parent, visited);
                // Merge the child over the parent: elements and textures are
                // what registration needs, and children win on collisions.
                for (String key : resolved.keySet()) {
                    if (!model.has(key)) model.put(key, resolved.get(key));
                }
            }
        }
        return model;
    }

    private static void collectTextures(Object value, Set<String> out) {
        collect(value, out, false);
    }

    /**
     * Every string inside a "textures" object is a texture reference, plus any
     * explicit face "texture" key. Other strings (parent, cullface, ...) are
     * not texture references.
     */
    private static void collect(Object value, Set<String> out, boolean inTextures) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) {
                Object child = object.get(key);
                if (child instanceof String) {
                    if (inTextures || key.equals("texture")) addTexture((String) child, out);
                } else {
                    collect(child, out, key.equals("textures"));
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collect(array.get(i), out, inTextures);
            }
        }
    }

    private static void addTexture(String reference, Set<String> out) {
        if (reference == null || reference.startsWith("#") || reference.isEmpty()) return;
        String name = reference.contains(":") ? reference.substring(reference.lastIndexOf(':') + 1)
                : reference;
        name = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        out.add(name);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
