package villager.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Locates and loads the recorded voice clips: TEAVSRP corpus WAVs (committed
 * under {@code voice/corpus}) and Villager News addon OGGs (read from the addon
 * zip at the repo root or an extracted resource pack — whichever is present).
 * The neural synthesis pipeline is gone; every spoken line is a recording.
 */
public final class ClipLibrary implements AutoCloseable {

    public static final String ADDON_PROPERTY = "voxel.voice.addon";
    public static final String CORPUS_PROPERTY = "voxel.voice.corpus";
    /** Addon ogg layout inside the resource pack (from clips_index.json). */
    static final String SOUND_DIR = "sounds/oreville/vn";

    private static final String[] ADDON_ZIP_CANDIDATES = {
            "Villager News 1.0 Add-On (addon).zip",
            "Villager News 1.0 Add-On.zip",
    };
    private static final String[] ADDON_DIR_CANDIDATES = {
            "dev/vn_addon/Villager News 1.0 Add-On RP",
            "Villager News 1.0 Add-On RP",
    };

    private final ClipIndex index;
    private final Path corpusDir;
    private final AddonSource addon;

    /** Open with the index and audio locations discovered from disk. */
    public static ClipLibrary openDefault() throws IOException {
        return new ClipLibrary(ClipIndex.loadDefault(), findCorpusDir(), findAddonSource());
    }

    /** Explicit wiring (tests, tools). {@code addon} may be null. */
    public ClipLibrary(ClipIndex index, Path corpusDir, AddonSource addon) {
        if (index == null) {
            throw new IllegalArgumentException("index must not be null");
        }
        this.index = index;
        this.corpusDir = corpusDir;
        this.addon = addon;
    }

    public ClipIndex index() { return index; }
    public boolean hasAddonClips() { return addon != null; }
    public boolean hasCorpusClips() { return corpusDir != null && Files.isDirectory(corpusDir); }

    /** Decode one indexed clip to mono float samples. */
    public WavAudio load(ClipIndex.Clip clip) throws IOException {
        if (clip == null) {
            throw new IllegalArgumentException("clip must not be null");
        }
        if ("teavrsp".equals(clip.kind)) {
            if (!hasCorpusClips()) {
                throw new IOException("TEAVSRP corpus directory not found");
            }
            Path wav = corpusDir.resolve(clip.file);
            return WavAudio.read(wav);
        }
        if (addon == null) {
            throw new IOException("Villager News addon clips not found"
                    + " (set -D" + ADDON_PROPERTY + "=<zip or RP directory>)");
        }
        return OggAudio.read(addon.read(clip.file));
    }

    @Override
    public void close() {
        if (addon != null) {
            addon.close();
        }
    }

    // ── Source discovery ──────────────────────────────────────────────────

    static Path findCorpusDir() {
        String configured = System.getProperty(CORPUS_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured);
        }
        String legacy = System.getProperty(ReferenceCorpusVoice.REFERENCE_PROPERTY);
        if (legacy != null && !legacy.trim().isEmpty()) {
            return Paths.get(legacy);
        }
        return ReferenceCorpusVoice.DEFAULT_REFERENCE;
    }

    static AddonSource findAddonSource() throws IOException {
        String configured = System.getProperty(ADDON_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return openSource(Paths.get(configured));
        }
        for (String candidate : ADDON_ZIP_CANDIDATES) {
            Path zip = Paths.get(candidate);
            if (Files.isRegularFile(zip)) {
                AddonSource source = ZipAddonSource.open(zip);
                if (source != null) return source;
            }
        }
        String temp = System.getProperty("java.io.tmpdir");
        for (String candidate : ADDON_DIR_CANDIDATES) {
            Path dir = Paths.get(candidate);
            if (Files.isDirectory(dir)) {
                AddonSource source = DirAddonSource.open(dir);
                if (source != null) return source;
            }
        }
        if (temp != null) {
            Path dir = Paths.get(temp, "vn_addon", "Villager News 1.0 Add-On RP");
            if (Files.isDirectory(dir)) {
                AddonSource source = DirAddonSource.open(dir);
                if (source != null) return source;
            }
        }
        return null;
    }

    private static AddonSource openSource(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            AddonSource source = DirAddonSource.open(path);
            if (source != null) return source;
            Path nested = path.resolve("Villager News 1.0 Add-On RP");
            if (Files.isDirectory(nested)) {
                source = DirAddonSource.open(nested);
                if (source != null) return source;
            }
            throw new IOException("no " + SOUND_DIR + " under " + path);
        }
        AddonSource source = ZipAddonSource.open(path);
        if (source == null) {
            throw new IOException("no " + SOUND_DIR + "/**.ogg inside " + path);
        }
        return source;
    }

    /** Byte access to one addon clip by basename (no extension). */
    interface AddonSource extends AutoCloseable {
        byte[] read(String clipFile) throws IOException;

        @Override
        void close();
    }

    /** Extracted resource pack: {@code <root>/sounds/oreville/vn/<file>.ogg}. */
    static final class DirAddonSource implements AddonSource {
        private final Path root;

        private DirAddonSource(Path root) {
            this.root = root;
        }

        static DirAddonSource open(Path rpRoot) {
            Path soundDir = rpRoot.resolve(SOUND_DIR);
            return Files.isDirectory(soundDir) ? new DirAddonSource(rpRoot) : null;
        }

        @Override
        public byte[] read(String clipFile) throws IOException {
            Path ogg = root.resolve(SOUND_DIR).resolve(clipFile + ".ogg").normalize();
            if (!Files.isRegularFile(ogg)) {
                throw new IOException("addon clip missing: " + clipFile);
            }
            return Files.readAllBytes(ogg);
        }

        @Override
        public void close() {
        }
    }

    /** Addon zip: entries may live under any top-level pack folder. */
    static final class ZipAddonSource implements AddonSource {
        private final ZipFile zip;
        private final Map<String, String> entriesByClip = new HashMap<>();

        private ZipAddonSource(ZipFile zip) {
            this.zip = zip;
        }

        static ZipAddonSource open(Path zipPath) throws IOException {
            ZipFile zip = new ZipFile(zipPath.toFile());
            ZipAddonSource source = new ZipAddonSource(zip);
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            String marker = SOUND_DIR + "/";
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                int at = name.indexOf(marker);
                if (at < 0 || !name.endsWith(".ogg")) continue;
                String base = name.substring(at + marker.length(), name.length() - 4);
                source.entriesByClip.put(base.toLowerCase(Locale.ROOT), name);
            }
            if (source.entriesByClip.isEmpty()) {
                source.close();
                return null;
            }
            return source;
        }

        @Override
        public byte[] read(String clipFile) throws IOException {
            String entry = entriesByClip.get(clipFile.toLowerCase(Locale.ROOT));
            if (entry == null) {
                throw new IOException("addon clip missing: " + clipFile);
            }
            InputStream in = zip.getInputStream(zip.getEntry(entry));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down.
            }
        }
    }
}
