package com.voxel.audio;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A discovered, classified music library.
 *
 * <p>The project ships far more music than the old single-folder player could
 * use: eight MCSM soundtrack albums under {@code src/resources/music}, the
 * Aether soundtrack under {@code assets/aether/sounds/music}, and any
 * {@code minecraft/} or {@code fanmade/} tracks a user drops in. This class
 * walks those roots once at boot, filters out placeholders, unused takes,
 * sub-second stings and loop-layer scraps, and groups the survivors into the
 * playback pools {@link MusicDirector} asks for.</p>
 *
 * <p>Classification is a pure function of the relative path and source, so it
 * is unit tested without touching disk. Pools never dead-end: an empty
 * dimension or mood pool falls back to the story/calm pool, and finally to the
 * whole library, so a sparse install still plays something.</p>
 */
public class MusicLibrary {

    /** One playable track. */
    public static final class Track {
        public final File file;
        public final String context;
        /** Origin family: {@code mcsm}, {@code aether}, {@code minecraft} or {@code fanmade}. */
        public final String source;
        public final String title;

        Track(File file, String context, String source, String title) {
            this.file = file;
            this.context = context;
            this.source = source;
            this.title = title;
        }

        @Override
        public String toString() {
            return source + "/" + context + ": " + title;
        }
    }

    /** Tracks shorter than this are stings/risers, not background music. */
    static final long MIN_TRACK_BYTES = 700_000L;

    protected final List<Track> tracks;
    protected final Map<String, List<Track>> pools;

    protected MusicLibrary(List<Track> tracks) {
        this.tracks = Collections.unmodifiableList(tracks);
        Map<String, List<Track>> byContext = new HashMap<>();
        for (Track t : tracks) {
            byContext.computeIfAbsent(t.context, k -> new ArrayList<>()).add(t);
        }
        this.pools = byContext;
    }

    /** An empty library (headless tests, or a machine with no audio files). */
    public static MusicLibrary empty() {
        return new MusicLibrary(Collections.<Track>emptyList());
    }

    /**
     * Scans every root recursively. Missing roots are skipped silently so the
     * game runs from a jar or a different working directory.
     */
    public static MusicLibrary scan(File... roots) {
        return scan(Arrays.asList(roots));
    }

    public static MusicLibrary scan(Collection<File> roots) {
        List<Track> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            collect(root, root, found, seen);
        }
        return new MusicLibrary(found);
    }

    private static void collect(File root, File dir, List<Track> out, Set<String> seen) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries);
        for (File entry : entries) {
            if (entry.isDirectory()) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                // "unused" takes, build output and dev scratch are never played.
                if (name.equals("unused") || name.equals("target") || name.equals("dev")
                        || name.equals("voice-cache")) {
                    continue;
                }
                collect(root, entry, out, seen);
                continue;
            }
            String lower = entry.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mp3") && !lower.endsWith(".ogg")) continue;
            if (entry.length() < MIN_TRACK_BYTES) continue;
            String source = sourceOf(root, entry);
            String context = classify(source, relative(root, entry), entry.getName());
            if (context == null) continue;
            String key;
            try {
                key = entry.getCanonicalPath();
            } catch (Exception ex) {
                key = entry.getAbsolutePath();
            }
            if (!seen.add(key)) continue;
            out.add(new Track(entry, context, source, titleOf(entry.getName())));
        }
    }

    /** Origin family from the root and path (a folder named fanmade wins). */
    static String sourceOf(File root, File file) {
        String path = relative(root, file).toLowerCase(Locale.ROOT).replace('\\', '/');
        String absolute = file.getAbsolutePath().toLowerCase(Locale.ROOT).replace('\\', '/');
        String rootName = root.getName().toLowerCase(Locale.ROOT);
        // The Aether soundtrack lives under a root that is itself named
        // "music", so the absolute path is the reliable family marker.
        for (String s : new String[]{"fanmade", "minecraft", "aether", "mcsm"}) {
            if (path.startsWith(s + "/") || path.contains("/" + s + "/")
                    || absolute.contains("/" + s + "/")
                    || rootName.equals(s)) {
                return s;
            }
        }
        return "mcsm";
    }

    /**
     * Maps a track into a playback pool, or {@code null} when it should never
     * be a background candidate (menu-only pieces are handled explicitly).
     *
     * <p>Rules are keyword based on the filename because the MCSM library is
     * organised by episode, not by mood. The Aether soundtrack has real
     * {@code boss/} and {@code menu} folders, so those are honoured exactly.</p>
     */
    public static String classify(String source, String relativePath, String fileName) {
        String path = relativePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = fileName.toLowerCase(Locale.ROOT);
        String stem = name.replaceAll("\\.[a-z0-9]+$", "");

        if (source.equals("aether")) {
            if (path.contains("/boss/") || stem.contains("fight")) return MusicDirector.CONTEXT_BOSS;
            if (stem.equals("menu")) return MusicDirector.CONTEXT_MENU;
            return MusicDirector.CONTEXT_AETHER;
        }

        // Explicit mood folders win, so users can sort their own collection.
        for (String folder : MusicDirector.ALL_CONTEXTS) {
            if (path.contains("/" + folder + "/") || path.startsWith(folder + "/")) {
                return folder;
            }
        }

        // Take fragments, layers, risers and stings make poor background music.
        if (stem.contains("sting") || stem.contains("riser") || stem.contains("hit0")
                || stem.contains("gloop") || stem.contains("layers")
                || stem.contains("loop]") && stem.contains("sketch")) {
            return null;
        }

        boolean boss = stem.contains("wither") || stem.contains("finalfight")
                || stem.contains("eversource") || stem.contains("witherstorm");
        if (boss) return MusicDirector.CONTEXT_BOSS;

        // Dread/suspense are the "something is out there" pool: tense but not
        // yet a fight, so they belong to danger rather than combat.
        boolean danger = stem.contains("actionsus") || stem.contains("action suspense")
                || stem.contains("suspense") || stem.contains("dread");
        if (danger) return MusicDirector.CONTEXT_DANGER;

        boolean fight = stem.contains("fighting") || stem.contains("ivorfight")
                || stem.contains("blazerods") || stem.contains("spider");
        if (fight) return MusicDirector.CONTEXT_COMBAT;

        if (stem.contains("cave") || stem.contains("abandonedmine")
                || stem.contains("libraryatmos") || stem.contains("library atmos")
                || stem.contains("darksky") || stem.contains("darkness")
                || stem.contains("detective")) {
            return MusicDirector.CONTEXT_CAVE;
        }
        if (stem.contains("nether")) return MusicDirector.CONTEXT_NETHER;
        if (stem.contains("theend") || stem.contains("blackhole")
                || stem.contains("skylanddestroyed") || stem.startsWith("finale")) {
            return MusicDirector.CONTEXT_END;
        }
        if (stem.startsWith("108survival") || stem.contains("survival ")) {
            return MusicDirector.CONTEXT_NIGHT;
        }

        if (stem.contains("boomtown") || stem.contains("redstonia")
                || stem.contains("oak ") || stem.contains("birch")
                || stem.contains("jungle") || stem.contains("chippy")
                || stem.contains("daytimezombies") || stem.contains("cowdisco")
                || stem.contains("sportscenter") || stem.contains("stall")) {
            return MusicDirector.CONTEXT_VILLAGE;
        }
        if (stem.contains("montage") || stem.contains("buildingcompetition")
                || stem.contains("zoompianohouse") || stem.contains("convention")
                || stem.contains("minecon") || stem.contains("swordbuild")
                || stem.contains("autochicken") || stem.contains("commandblockbuild")
                || stem.contains("repeaterguitar") || stem.contains("minecart")) {
            return MusicDirector.CONTEXT_TUTORIAL;
        }
        if (stem.contains("credits") || stem.contains("jesse") || stem.contains("reuben")
                || stem.contains("ivor") || stem.contains("maproom")
                || stem.contains("palebluedot") || stem.contains("soren")
                || stem.contains("harper") || stem.contains("heroes")
                || stem.contains("oldbuilders") || stem.contains("theme")
                || stem.contains("founder") || stem.contains("montage")) {
            return MusicDirector.CONTEXT_STORY;
        }
        if (stem.contains("dark") || stem.contains("sad") || stem.contains("night")) {
            return MusicDirector.CONTEXT_NIGHT;
        }
        return MusicDirector.CONTEXT_CALM;
    }

    private static String relative(File root, File file) {
        String base;
        String full;
        try {
            base = root.getCanonicalPath();
            full = file.getCanonicalPath();
        } catch (Exception ex) {
            base = root.getAbsolutePath();
            full = file.getAbsolutePath();
        }
        if (full.startsWith(base)) {
            full = full.substring(base.length());
            while (full.startsWith("/") || full.startsWith("\\")) {
                full = full.substring(1);
            }
        }
        return full;
    }

    private static String titleOf(String fileName) {
        String t = fileName.replaceAll("\\.[A-Za-z0-9]+$", "");
        t = t.replace('_', ' ').trim();
        return t.isEmpty() ? fileName : t;
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    public int size() {
        return tracks.size();
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** Number of candidates in a pool (before fallbacks). */
    public int count(String context) {
        List<Track> pool = pools.get(context);
        return pool == null ? 0 : pool.size();
    }

    /** All pool names that have at least one track. */
    public List<String> populatedContexts() {
        List<String> out = new ArrayList<>();
        for (String c : MusicDirector.ALL_CONTEXTS) {
            if (count(c) > 0) out.add(c);
        }
        return out;
    }

    /**
     * Picks the next track for {@code context}, preferring the pool itself and
     * falling back through story → calm → the whole library. Tracks in
     * {@code avoid} are skipped when possible so a short pool still varies.
     */
    public Track pick(String context, Random rng, Collection<File> avoid) {
        if (tracks.isEmpty()) return null;
        String[] chain = fallbackChain(context);
        for (String candidate : chain) {
            Track t = pickFrom(pools.get(candidate), rng, avoid);
            if (t != null) return t;
        }
        return pickFrom(tracks, rng, avoid);
    }

    /**
     * Mood-preserving fallbacks: an empty danger pool borrows from caves and
     * night, an empty Sift pool borrows from caves, and everything ultimately
     * lands on story/calm instead of going silent.
     */
    static String[] fallbackChain(String context) {
        String c = context == null ? "" : context;
        switch (c) {
            case MusicDirector.CONTEXT_DANGER:
                return new String[]{c, MusicDirector.CONTEXT_CAVE,
                        MusicDirector.CONTEXT_NIGHT, MusicDirector.CONTEXT_STORY,
                        MusicDirector.CONTEXT_CALM};
            case MusicDirector.CONTEXT_SIFT:
                return new String[]{c, MusicDirector.CONTEXT_CAVE,
                        MusicDirector.CONTEXT_NIGHT, MusicDirector.CONTEXT_STORY,
                        MusicDirector.CONTEXT_AETHER};
            case MusicDirector.CONTEXT_NIGHT:
                return new String[]{c, MusicDirector.CONTEXT_STORY,
                        MusicDirector.CONTEXT_CALM, MusicDirector.CONTEXT_AETHER};
            case MusicDirector.CONTEXT_CAVE:
                return new String[]{c, MusicDirector.CONTEXT_NIGHT,
                        MusicDirector.CONTEXT_STORY, MusicDirector.CONTEXT_CALM};
            case MusicDirector.CONTEXT_VILLAGE:
            case MusicDirector.CONTEXT_TUTORIAL:
                return new String[]{c, MusicDirector.CONTEXT_STORY,
                        MusicDirector.CONTEXT_CALM, MusicDirector.CONTEXT_AETHER};
            default:
                return new String[]{c, MusicDirector.CONTEXT_STORY,
                        MusicDirector.CONTEXT_CALM, MusicDirector.CONTEXT_AETHER};
        }
    }

    private static Track pickFrom(List<Track> pool, Random rng, Collection<File> avoid) {
        if (pool == null || pool.isEmpty()) return null;
        List<Track> poolList = pool;
        if (avoid != null && !avoid.isEmpty() && pool.size() > 1) {
            List<Track> filtered = new ArrayList<>();
            for (Track t : pool) {
                if (!avoid.contains(t.file)) filtered.add(t);
            }
            if (!filtered.isEmpty()) poolList = filtered;
        }
        return poolList.get(Math.floorMod(rng.nextInt(), poolList.size()));
    }
}
