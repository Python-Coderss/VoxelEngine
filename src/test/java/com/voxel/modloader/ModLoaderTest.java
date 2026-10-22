package com.voxel.modloader;

import com.voxel.api.events.TickEvent;
import com.voxel.game.ItemDefinitions;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockRegistry;
import com.voxel.utils.ShaderBlockRegistry;
import com.voxel.utils.TextureManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless checks for the Java modding API: jar discovery, reserved block ids,
 * queued block/item registration and listener isolation.
 */
public class ModLoaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File modsDir;

    @Before
    public void setUp() throws IOException {
        ModLoader.reset();
        modsDir = temp.newFolder("mods");
    }

    @After
    public void tearDown() {
        ModLoader.reset();
    }

    @Test
    public void discoversLoadsAndRegistersAModJar() throws Exception {
        File jar = new File(modsDir, "testmod.jar");
        writeModJar(jar, TestMod.class.getName(), true);

        ModLoader.Discovery discovery = ModLoader.discover(modsDir);
        assertEquals("one mod discovered", 1, discovery.mods.size());
        assertEquals("mod instance", TestMod.class, discovery.mods.get(0).mod.getClass());
        assertFalse("texture dir extracted", discovery.textureDirs().length == 0);

        BlockDataManager blocks = new BlockDataManager();
        BlockRegistry registry = new BlockRegistry();
        ShaderBlockRegistry shaders = new ShaderBlockRegistry();
        TextureManager textures = headlessTextures();

        ModLoader.load(discovery, registry, shaders, blocks, textures);

        Integer id = blocks.findBlockId("test_block");
        assertNotNull("mod block registered", id);
        assertTrue("id comes from the mod range", id >= ModLoader.FIRST_MOD_BLOCK_ID);
        assertEquals("mod sees the same id", id.intValue(), TestMod.id);

        ItemDefinitions items = new ItemDefinitions();
        items.setup(blocks, textures);
        ModLoader.flushItems(items);

        assertNotNull("block item registered", items.getDefinition("test_block"));
        assertNotNull("standalone item registered", items.getDefinition("test_gem"));
    }

    @Test
    public void aBrokenModDoesNotStopTheOtherMods() throws Exception {
        writeModJar(new File(modsDir, "a_testmod.jar"), TestMod.class.getName(), true);
        writeModJar(new File(modsDir, "b_broken.jar"), BrokenTestMod.class.getName(), false);

        ModLoader.Discovery discovery = ModLoader.discover(modsDir);
        assertEquals("both jars discovered", 2, discovery.mods.size());

        BlockDataManager blocks = new BlockDataManager();
        ModLoader.load(discovery, new BlockRegistry(), new ShaderBlockRegistry(),
                blocks, headlessTextures());

        assertEquals("healthy mod still loaded", 1, ModLoader.loadedMods().size());
        assertNotNull("healthy mod's block exists", blocks.findBlockId("test_block"));
        boolean sawError = false;
        for (ModLoader.LoadedMod mod : discovery.mods) {
            if ("brokenmod".equals(mod.id()) && mod.error != null) sawError = true;
        }
        assertTrue("failure recorded on the broken mod", sawError);
    }

    @Test
    public void listenerExceptionsAreIsolated() {
        EventBus bus = new EventBus("test");
        final int[] calls = {0};
        bus.onTick(event -> {
            throw new RuntimeException("boom");
        });
        bus.onTick(event -> calls[0]++);

        EventBus.fireTick(new TickEvent(null, 0.05f, 1f));

        assertEquals("second listener still ran", 1, calls[0]);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * TextureManager without an OpenGL context: every texture name maps to
     * layer 0, which is all block registration needs (the real atlas is
     * uploaded by the game before mods load).
     */
    private static TextureManager headlessTextures() {
        return new TextureManager() {
            @Override
            public int getTextureIndex(String name) {
                return 0;
            }
        };
    }

    private static void writeModJar(File jar, String serviceClass, boolean withAssets)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            entry(out, "META-INF/services/com.voxel.api.VoxelMod",
                    (serviceClass + "\n").getBytes(StandardCharsets.UTF_8));
            if (withAssets) {
                entry(out, "assets/testmod/models/block/test_block.json",
                        ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"test_block\"}}")
                                .getBytes(StandardCharsets.UTF_8));
                entry(out, "assets/testmod/textures/blocks/test_block.png", png(0xC8, 0x7A, 0x30));
                entry(out, "assets/testmod/textures/items/test_gem.png", png(0x54, 0xD0, 0xE0));
            }
        }
    }

    private static void entry(JarOutputStream out, String name, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] png(int r, int g, int b) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int shade = 180 + ((x + y) % 4) * 12;
                image.setRGB(x, y, 0xFF000000 | (r * shade / 255) << 16
                        | (g * shade / 255) << 8 | (b * shade / 255));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        return bytes.toByteArray();
    }
}
