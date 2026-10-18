package com.voxel.game;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Asset contract for the book items. Items render as miniature block AABB
 * models, so a book is geometry (cover boards + page stack + a spine element),
 * not a flat sprite. A missing texture makes {@code BlockDataManager} throw at
 * boot, and more than 8 elements silently overflows the shader's AABB loop —
 * both are caught here without needing a GL context.
 */
public class BookModelTest {

    private static final String MODELS =
            "src/main/resources/assets/minecraft/models/block";
    private static final String TEX_BLOCKS =
            "src/main/resources/assets/minecraft/textures/blocks";
    private static final String TEX_ITEMS =
            "src/main/resources/assets/minecraft/textures/items";

    private static final String[] BOOK_MODELS = {
            "item_book", "item_drop_book",
            "item_writable_book", "item_drop_writable_book",
            "item_written_book", "item_drop_written_book",
            "item_enchanted_book", "item_drop_enchanted_book",
    };

    private static JSONObject load(String name) throws Exception {
        return new JSONObject(new String(
                Files.readAllBytes(Paths.get(MODELS, name + ".json"))));
    }

    private static boolean textureExists(String name) {
        return Files.exists(Paths.get(TEX_BLOCKS, name + ".png"))
                || Files.exists(Paths.get(TEX_ITEMS, name + ".png"));
    }

    @Test
    public void everyBookModelParsesWithinTheAabbBudgetAndResolvesItsTextures()
            throws Exception {
        for (String name : BOOK_MODELS) {
            JSONObject model = load(name);
            JSONArray elements = model.getJSONArray("elements");
            assertTrue(name + " needs book geometry", elements.length() >= 3);
            assertTrue(name + " exceeds the shader's 8-AABB budget",
                    elements.length() <= 8);

            JSONObject textures = model.getJSONObject("textures");
            for (String key : textures.keySet()) {
                String tex = textures.getString(key);
                assertTrue(name + " references missing texture " + tex,
                        textureExists(tex));
            }
        }
    }

    @Test
    public void booksHaveASpineOnTheWestFaceAndPagesOnTheForeEdge()
            throws Exception {
        JSONObject flat = load("item_book");
        JSONObject textures = flat.getJSONObject("textures");
        assertEquals("book_spine", textures.getString("west"));
        assertEquals("book_pages", textures.getString("east"));

        // The spine element must wrap the bound (west) edge of the book.
        boolean spineElement = false;
        JSONArray elements = flat.getJSONArray("elements");
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            double fromX = el.getJSONArray("from").getDouble(0);
            JSONArray to = el.getJSONArray("to");
            double thickness = to.getDouble(0) - fromX;
            // The spine is the narrow strip hugging the west edge (the covers
            // also start at x=2 but span the whole book).
            if (fromX <= 2.0 && thickness > 0 && thickness <= 2.0) {
                assertTrue("the spine must span the book's thickness",
                        to.getDouble(1) - el.getJSONArray("from").getDouble(1) >= 3.0);
                spineElement = true;
            }
        }
        assertTrue("a spine element must wrap the west edge", spineElement);
    }

    @Test
    public void uprightDropModelStandsOnItsBottomEdge() throws Exception {
        JSONObject drop = load("item_drop_book");
        JSONArray elements = drop.getJSONArray("elements");
        boolean spansFullHeight = false;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            JSONArray from = el.getJSONArray("from");
            JSONArray to = el.getJSONArray("to");
            if (from.getDouble(1) == 0.0 && to.getDouble(1) == 16.0) {
                spansFullHeight = true;
            }
        }
        assertTrue("the dropped book must stand a full block tall",
                spansFullHeight);
    }
}
