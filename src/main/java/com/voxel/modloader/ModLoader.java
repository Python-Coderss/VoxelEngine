package com.voxel.modloader;

import com.voxel.api.ModContext;
import com.voxel.api.ModItem;
import com.voxel.api.VoxelMod;
import com.voxel.game.ItemDefinitions;
import com.voxel.utils.BlockDataManager;
import com.voxel.utils.BlockRegistry;
import com.voxel.utils.ShaderBlockRegistry;
import com.voxel.utils.TextureManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers and loads Voxel Engine mods from {@code mods/*.jar}.
 *
 * <p>Loading is split in two phases so mod textures can join the single texture
 * array upload:</p>
 * <ol>
 *   <li>{@link #discover(File)} — file work only (extract jars to
 *       {@code mods/.cache}, instantiate plugin classes). Called before
 *       {@code TextureManager.loadTextures}, whose argument list includes
 *       {@link Discovery#textureDirs()}.</li>
 *   <li>{@link #load(Discovery, BlockRegistry, ShaderBlockRegistry,
 *       BlockDataManager, TextureManager)} — calls {@link VoxelMod#onLoad} and
 *       registers blocks. Called after the built-in block table is complete,
 *       before {@link ItemDefinitions#setup}.</li>
 * </ol>
 *
 * <p>{@link #flushItems(ItemDefinitions)} then turns queued mod items into real
 * item definitions (it must run after {@code ItemDefinitions.setup}).</p>
 *
 * <p>A mod jar declares its plugin in
 * {@code META-INF/services/com.voxel.api.VoxelMod}. Assets are extracted from
 * the jar's standard {@code assets/<modid>/...} tree.</p>
 */
public final class ModLoader {

    /** Folder scanned for mod jars (relative to the working directory). */
    public static final File MODS_DIR = new File("mods");
    /** First block id handed to mods. Built-in blocks stop below 1000. */
    public static final int FIRST_MOD_BLOCK_ID = 3000;
    /** Exclusive end of the mod block-id range. */
    public static final int MOD_BLOCK_ID_LIMIT = 4096;

    private static final List<LoadedMod> LOADED = new ArrayList<>();
    private static final List<QueuedItem> QUEUED_ITEMS = new ArrayList<>();
    private static final EventBus EVENTS = new EventBus("engine");

    private ModLoader() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /** Event bus handed to the engine for firing; never null. */
    public static EventBus events() {
        return EVENTS;
    }

    /** Mods loaded in the current process (in discovery order). */
    public static List<LoadedMod> loadedMods() {
        return Collections.unmodifiableList(LOADED);
    }

    /** Clears loader state. Tests only. */
    public static void reset() {
        LOADED.clear();
        QUEUED_ITEMS.clear();
        EventBus.clearAll();
    }

    /**
     * Extracts every {@code *.jar} in {@code modsDir} and instantiates its
     * {@link VoxelMod} plugins. Never throws for a broken mod: the failure is
     * recorded on the {@link LoadedMod} and logged.
     */
    public static Discovery discover(File modsDir) {
        File root = modsDir == null ? MODS_DIR : modsDir;
        List<LoadedMod> mods = new ArrayList<>();
        if (!root.isDirectory()) {
            return new Discovery(root, mods);
        }
        File[] jars = root.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return new Discovery(root, mods);
        }
        java.util.Arrays.sort(jars, Comparator.comparing(File::getName));

        Set<String> seenIds = new HashSet<>();
        for (File jar : jars) {
            LoadedMod loaded = loadJar(root, jar);
            if (loaded == null) continue;
            String id = loaded.mod == null ? null : loaded.mod.id();
            if (id != null && !seenIds.add(id)) {
                System.err.println("[Mods] duplicate mod id '" + id + "' in " + jar.getName()
                        + " — skipped");
                continue;
            }
            mods.add(loaded);
        }
        return new Discovery(root, mods);
    }

    /**
     * Runs {@code onLoad} for every discovered mod and applies its block
     * registrations. Requires the texture array to already contain the mods'
     * textures (pass {@link Discovery#textureDirs()} to
     * {@code TextureManager.loadTextures}).
     */
    public static void load(Discovery discovery, BlockRegistry blockRegistry,
                            ShaderBlockRegistry shaderRegistry,
                            BlockDataManager blockData,
                            TextureManager textures) {
        if (discovery == null) return;
        LOADED.clear();
        QUEUED_ITEMS.clear();
        mods:
        for (LoadedMod loaded : discovery.mods) {
            if (loaded.mod == null) continue;
            String id = loaded.mod.id();
            if (!isValidId(id)) {
                System.err.println("[Mods] invalid mod id '" + id + "' in "
                        + loaded.file.getName() + " (use [a-z0-9_-]) — skipped");
                continue;
            }
            File assets = new File(loaded.extractDir, "assets/" + id);
            File models = new File(assets, "models/block");
            ModContextImpl context = new ModContextImpl(loaded.mod, assets, models,
                    blockRegistry, shaderRegistry, blockData, textures);
            loaded.assetsDir = assets;
            try {
                loaded.mod.onLoad(context);
                LOADED.add(loaded);
                System.out.println("[Mods] loaded " + loaded.mod.name() + " " + loaded.mod.version()
                        + " (" + id + "), " + context.blockCount() + " block(s), "
                        + context.itemCount() + " item(s)");
            } catch (Throwable t) {
                // Roll the failed mod's queued items back but keep its blocks:
                // removing a registered block from the shared tables is unsafe
                // once world generation has seen it.
                QUEUED_ITEMS.removeAll(context.takeQueuedItems());
                loaded.error = t.toString();
                System.err.println("[Mods] " + id + " failed to load: " + t);
                t.printStackTrace();
            }
        }
    }

    /**
     * Registers items queued by mods. Call after {@code ItemDefinitions.setup}
     * so the built-in item table exists first.
     */
    public static void flushItems(ItemDefinitions items) {
        if (items == null) return;
        for (QueuedItem queued : QUEUED_ITEMS) {
            items.registerGeneratedItem(queued.itemId, queued.displayName,
                    queued.blockId, queued.iconLayer);
        }
        QUEUED_ITEMS.clear();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static LoadedMod loadJar(File root, File jar) {
        File cache = new File(root, ".cache/" + stripExtension(jar.getName()));
        try {
            extract(jar, cache);
        } catch (IOException e) {
            System.err.println("[Mods] could not extract " + jar.getName() + ": " + e.getMessage());
            return null;
        }
        LoadedMod loaded = new LoadedMod(jar, cache);
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[] { jar.toURI().toURL() },
                    ModLoader.class.getClassLoader());
            loaded.classLoader = loader;
            ServiceLoader<VoxelMod> services = ServiceLoader.load(VoxelMod.class, loader);
            for (VoxelMod mod : services) {
                if (loaded.mod == null) {
                    loaded.mod = mod;
                } else {
                    System.err.println("[Mods] " + jar.getName()
                            + " declares more than one VoxelMod; only '" + loaded.mod.id()
                            + "' is used");
                }
            }
            if (loaded.mod == null) {
                System.err.println("[Mods] " + jar.getName()
                        + " has no META-INF/services/com.voxel.api.VoxelMod entry");
            }
        } catch (Throwable t) {
            loaded.error = t.toString();
            System.err.println("[Mods] " + jar.getName() + " could not be loaded: " + t);
        }
        return loaded;
    }

    private static void extract(File jar, File target) throws IOException {
        deleteRecursively(target);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("cannot create " + target);
        }
        String targetPath = target.getCanonicalPath() + File.separator;
        try (JarFile archive = new JarFile(jar)) {
            java.util.Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("META-INF/") && !name.startsWith("META-INF/services/")) {
                    continue; // signatures/manifests are irrelevant after extraction
                }
                File out = new File(target, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(targetPath)) {
                    throw new IOException("jar entry escapes cache dir: " + name);
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (InputStream in = archive.getInputStream(entry);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
                }
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }

    // ── Value types ──────────────────────────────────────────────────────────

    /** Result of {@link ModLoader#discover}. */
    public static final class Discovery {
        public final File modsDir;
        public final List<LoadedMod> mods;

        Discovery(File modsDir, List<LoadedMod> mods) {
            this.modsDir = modsDir;
            this.mods = Collections.unmodifiableList(mods);
        }

        /** Texture directories to pass to {@code TextureManager.loadTextures}. */
        public String[] textureDirs() {
            List<String> dirs = new ArrayList<>();
            for (LoadedMod mod : mods) {
                if (mod.mod == null) continue;
                File textures = new File(mod.extractDir, "assets/" + mod.mod.id() + "/textures");
                if (textures.isDirectory()) dirs.add(textures.getPath());
            }
            return dirs.toArray(new String[0]);
        }
    }

    /** One mod jar, its extracted cache and plugin instance. */
    public static final class LoadedMod {
        public final File file;
        /** {@code mods/.cache/<jar>}: extracted jar contents. */
        public final File extractDir;
        public VoxelMod mod;
        public ClassLoader classLoader;
        public File assetsDir;
        /** Non-null when discovery or onLoad failed. */
        public String error;

        LoadedMod(File file, File extractDir) {
            this.file = file;
            this.extractDir = extractDir;
        }

        public String id() {
            return mod == null ? file.getName() : mod.id();
        }

        public String name() {
            return mod == null ? file.getName() : mod.name();
        }
    }

    /** An item queued by a mod, resolved after the item table exists. */
    static final class QueuedItem {
        final String itemId;
        final String displayName;
        final int blockId;
        int iconLayer;

        QueuedItem(String itemId, String displayName, int blockId, int iconLayer) {
            this.itemId = itemId;
            this.displayName = displayName;
            this.blockId = blockId;
            this.iconLayer = iconLayer;
        }
    }

    static List<QueuedItem> queuedItems() {
        return QUEUED_ITEMS;
    }
}
