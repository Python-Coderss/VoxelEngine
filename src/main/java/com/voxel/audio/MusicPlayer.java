package com.voxel.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALC10;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background music player.
 *
 * <p>Two modes share one OpenAL source:</p>
 * <ul>
 *   <li><b>Library mode</b> (the game): a {@link MusicLibrary} groups every
 *       bundled MCSM / Aether / Minecraft / fanmade track into mood pools, and
 *       {@link #playContext(String)} crossfades between them.</li>
 *   <li><b>Folder mode</b> (tests and legacy callers): a directory of MP3s is
 *       scanned and played in path order.</li>
 * </ul>
 *
 * <p>Decoding is asynchronous. The old player decoded the next track inline on
 * the render thread, so every track change dropped frames for as long as the
 * decoder took (seconds for a 3-minute MP3). A single daemon worker decodes the
 * next track while the current one is still playing; the render thread only
 * uploads the finished PCM.</p>
 *
 * <p>OpenAL is initialized lazily and defensively: on machines without an
 * audio device the player silently disables itself, so the game and the
 * headless test suite never depend on audio.</p>
 */
public final class MusicPlayer implements AutoCloseable {

    private long device;
    private long context;
    /** True when this player created device+context itself (vs. sharing the
     *  engine's existing context). LWJGL keeps a single global AL capability
     *  set, so only one context may exist per process — we always reuse the
     *  current one when present. */
    private boolean ownsDevice;
    private int source;
    private int currentBuffer;
    private boolean openALReady;
    private boolean closed;

    /** Playlist of real files (folder mode), sorted by path. */
    private final List<File> playlist = new ArrayList<>();
    /** Files named *.mp3 that are not actually MP3s (HTML placeholders). */
    private final List<File> pending = new ArrayList<>();
    /** Classified library (library mode). Null in folder mode. */
    private MusicLibrary library;

    /** Music root; context subfolders hang off it (folder mode). */
    private File rootDir;
    private String currentContext = "";
    private int trackIndex;
    private boolean trackQueued;   // a decoded track is loaded into the source
    private float gain = 0.55f;

    // ── Crossfade ────────────────────────────────────────────────────────────
    private float fade = 0f;          // current fade level 0..1
    private float fadeTarget = 0f;
    private float appliedGain = -1f;

    // ── Async decode ─────────────────────────────────────────────────────────
    private ExecutorService decoder;
    private volatile AudioData decodedAudio;
    private volatile File decodedFile;
    private volatile boolean decoding;
    private volatile long decodeToken;
    private final Set<File> badFiles = new HashSet<>();
    private final ArrayDeque<File> recent = new ArrayDeque<>();
    private final Random random = new Random();
    private File currentFile;
    private File swapFile;
    private AudioData swapAudio;

    /** Joins the engine's OpenAL context. Prefers the context another manager
     *  (the dialogue system) already made current — sharing one context keeps
     *  LWJGL's single global capability set valid. Only when no context exists
     *  yet (standalone use) does this open its own device/context. */
    public void initialize() {
        if (closed || openALReady) return;
        try {
            context = ALC10.alcGetCurrentContext();
            if (context == 0L) {
                device = ALC10.alcOpenDevice((ByteBuffer) null);
                if (device == 0L) {
                    System.err.println("MusicPlayer: no OpenAL device available — music disabled");
                    return;
                }
                ALCCapabilities deviceCapabilities = ALC.createCapabilities(device);
                context = ALC10.alcCreateContext(device, (IntBuffer) null);
                if (context == 0L || !ALC10.alcMakeContextCurrent(context)) {
                    System.err.println("MusicPlayer: unable to create OpenAL context — music disabled");
                    closeOpenAL();
                    return;
                }
                AL.createCapabilities(deviceCapabilities);
                ownsDevice = true;
            }
            source = AL10.alGenSources();
            AL10.alSourcef(source, AL10.AL_GAIN, 0f);
            currentBuffer = 0;
            openALReady = true;
        } catch (Throwable error) {
            System.err.println("MusicPlayer: OpenAL initialization failed: " + error);
            closeOpenAL();
        }
    }

    /** Number of playable tracks (library size, or folder playlist size). */
    public int trackCount() {
        if (library != null) return library.size();
        return playlist.size();
    }

    /** Number of *.mp3 files that are still placeholders (not real audio). */
    public int pendingCount() {
        return pending.size();
    }

    public boolean isReady() {
        return openALReady && !closed;
    }

    public String currentContext() {
        return currentContext;
    }

    /** Title of the track currently playing (or empty). */
    public String currentTrackName() {
        return currentFile == null ? "" : currentFile.getName();
    }

    /** Installs a classified library and leaves folder mode. */
    public void setLibrary(MusicLibrary lib) {
        this.library = lib;
        this.playlist.clear();
        this.pending.clear();
        this.currentContext = "";
        this.trackQueued = false;
        this.currentFile = null;
        this.decoding = false;
        this.decodeToken++;
    }

    /** Points the player at a legacy folder-of-MP3s root (folder mode). */
    public void setRoot(File dir) {
        library = null;
        rootDir = dir;
        currentContext = "";
        setFolder(dir);
    }

    /**
     * Switches the playing pool to a game context (see {@link MusicDirector}).
     * In library mode the next track is chosen from that pool (with fallbacks)
     * and the change is crossfaded. Tracks continue uninterrupted when the
     * context has not changed.
     */
    public void playContext(String context) {
        String want = context == null ? "" : context;
        if (want.equals(currentContext) && (trackQueued || decoding || swapAudio != null)) return;
        currentContext = want;
        if (library != null) {
            if (library.isEmpty()) return;
            requestLibraryTrack();
            return;
        }
        if (rootDir == null || !rootDir.isDirectory()) return;
        File pool = want.isEmpty() ? rootDir : new File(rootDir, want);
        setFolder(pool);
        if (playlist.isEmpty() && pool != rootDir) {
            // Fall back to the root pool until the context folder is stocked.
            setFolder(rootDir);
        }
    }

    /** Scans a folder for *.mp3 files (folder mode). */
    public void setFolder(File dir) {
        playlist.clear();
        pending.clear();
        if (dir == null || !dir.isDirectory()) return;
        List<File> found = new ArrayList<>();
        collectMp3s(dir, found);
        found.sort(Comparator.comparing(File::getAbsolutePath));
        // HTML files renamed to .mp3 are placeholders waiting for the real
        // download; count them as pending and play only genuine MP3s.
        for (File f : found) {
            if (looksLikeMp3(f)) {
                playlist.add(f);
            } else {
                pending.add(f);
            }
        }
        trackIndex = 0;
        trackQueued = false;
    }

    private static void collectMp3s(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectMp3s(entry, out);
            } else if (entry.getName().toLowerCase().endsWith(".mp3")) {
                out.add(entry);
            }
        }
    }

    /**
     * Sniffs the file header: real MP3s start with an ID3 tag or an MPEG
     * frame-sync byte (0xFF), while HTML/text placeholders do not.
     */
    private static boolean looksLikeMp3(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            if (b0 == 'I' && b1 == 'D' && b2 == '3') return true;      // ID3v2 tag
            if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) return true;          // MPEG frame sync
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    public void setGain(float g) {
        gain = Math.max(0.0f, Math.min(1.0f, g));
        appliedGain = -1f; // force reapply
    }

    /** Skips to the next track (queued the next time {@link #update()} runs). */
    public void nextTrack() {
        if (library != null) {
            requestLibraryTrack();
            return;
        }
        if (playlist.isEmpty()) return;
        trackIndex = (trackIndex + 1) % playlist.size();
        trackQueued = false;
        requestFolderTrack();
    }

    /**
     * Called once per frame after the OpenAL context exists: fades, swaps in a
     * decoded track, and starts the next one when the current ends.
     */
    public void update() {
        if (closed) return;
        advanceFade();
        if (!openALReady) return;

        // A finished decode is waiting: let the current track fade out, then
        // swap it in. The upload itself is a single memcpy.
        if (swapAudio == null && decodedAudio != null) {
            swapAudio = decodedAudio;
            swapFile = decodedFile;
            decodedAudio = null;
            decodedFile = null;
            fadeTarget = 0f;
        }
        if (swapAudio != null && fade <= 0.03f) {
            stopSource();
            uploadAndPlay(swapAudio, swapFile);
            swapAudio = null;
            swapFile = null;
            fadeTarget = 1f;
        }

        if (!trackQueued) {
            if (library != null) {
                if (!decoding && swapAudio == null) requestLibraryTrack();
            } else if (!decoding && swapAudio == null && !playlist.isEmpty()) {
                requestFolderTrack();
            }
            return;
        }

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING && fadeTarget > 0.5f) {
            // The track finished: move on, crossfading from silence.
            trackQueued = false;
            fadeTarget = 0f;
            if (library != null) requestLibraryTrack();
            else nextPlaylistIndex();
        }
    }

    // ── decode worker ────────────────────────────────────────────────────────

    private void requestLibraryTrack() {
        if (library == null || library.isEmpty()) return;
        long token = ++decodeToken;
        decoding = true;
        File chosen = chooseLibraryFile();
        if (chosen == null) {
            decoding = false;
            return;
        }
        submitDecode(chosen, token);
    }

    private File chooseLibraryFile() {
        // Prefer a track from the requested pool, avoiding recent repeats.
        Set<File> avoid = new HashSet<>(recent);
        MusicLibrary.Track pick = library.pick(currentContext, random, avoid);
        if (pick == null || badFiles.contains(pick.file)) {
            // The single pick may be a bad file; walk the whole library once.
            for (MusicLibrary.Track t : library.tracks()) {
                if (!badFiles.contains(t.file)) {
                    pick = t;
                    break;
                }
            }
        }
        return pick == null ? null : pick.file;
    }

    private void requestFolderTrack() {
        if (playlist.isEmpty()) return;
        long token = ++decodeToken;
        decoding = true;
        File file = playlist.get(trackIndex);
        if (badFiles.contains(file)) {
            nextPlaylistIndex();
            file = playlist.get(trackIndex);
        }
        submitDecode(file, token);
    }

    private void nextPlaylistIndex() {
        if (playlist.isEmpty()) return;
        trackIndex = (trackIndex + 1) % playlist.size();
    }

    private void submitDecode(File file, long token) {
        if (decoder == null) {
            decoder = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "music-decode");
                t.setDaemon(true);
                return t;
            });
        }
        decoder.submit(() -> {
            AudioData data = null;
            try {
                data = file.getName().toLowerCase().endsWith(".ogg")
                        ? OggDecoder.decode(file)
                        : Mp3Decoder.decode(file);
                if (data == null || data.samples.length == 0) {
                    throw new IllegalStateException("decoded to zero samples");
                }
            } catch (Throwable error) {
                System.err.println("[music] skipping broken track " + file.getName() + ": " + error);
                badFiles.add(file);
                data = null;
            }
            if (token != decodeToken) {
                return; // superseded by a newer request
            }
            if (data != null) {
                decodedAudio = data;
                decodedFile = file;
            }
            decoding = false;
        });
    }

    // ── OpenAL helpers ───────────────────────────────────────────────────────

    private void advanceFade() {
        float step = 1f / 30f; // ~1s fade at 30fps
        if (fade < fadeTarget) fade = Math.min(fadeTarget, fade + step);
        else if (fade > fadeTarget) fade = Math.max(fadeTarget, fade - step);
        if (openALReady) {
            float value = gain * fade;
            if (Math.abs(value - appliedGain) > 0.01f) {
                AL10.alSourcef(source, AL10.AL_GAIN, value);
                appliedGain = value;
            }
        }
    }

    private void uploadAndPlay(AudioData data, File file) {
        try {
            float[] mono = data.channels <= 1 ? data.samples : data.toMono();
            if (currentBuffer != 0) {
                AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                AL10.alDeleteBuffers(currentBuffer);
                currentBuffer = 0;
            }
            ByteBuffer pcm = toMono16(mono);
            currentBuffer = AL10.alGenBuffers();
            AL10.alBufferData(currentBuffer, AL10.AL_FORMAT_MONO16, pcm, data.sampleRate);
            AL10.alSourcei(source, AL10.AL_BUFFER, currentBuffer);
            AL10.alSourcePlay(source);
            trackQueued = true;
            currentFile = file;
            remember(file);
            System.out.println("[music] " + MusicDirector.displayName(currentContext)
                    + " — " + file.getName() + " ("
                    + Math.round(data.durationSeconds()) + "s)");
        } catch (Throwable error) {
            System.err.println("[music] upload failed for " + file.getName() + ": " + error);
            trackQueued = false;
        }
    }

    private void stopSource() {
        if (!openALReady) return;
        try {
            AL10.alSourceStop(source);
        } catch (Throwable ignored) {
        }
        trackQueued = false;
    }

    private void remember(File file) {
        if (file == null) return;
        recent.remove(file);
        recent.addFirst(file);
        while (recent.size() > 6) recent.removeLast();
    }

    /** float mono samples in [-1, 1] → little-endian 16-bit PCM buffer. */
    private static ByteBuffer toMono16(float[] samples) {
        ByteBuffer pcm = ByteBuffer.allocateDirect(samples.length * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : samples) {
            float clipped = Math.max(-1.0f, Math.min(1.0f, value));
            pcm.putShort((short) Math.round(clipped * 32767.0f));
        }
        pcm.flip();
        return pcm;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (decoder != null) decoder.shutdownNow();
        closeOpenAL();
    }

    private void closeOpenAL() {
        if (source != 0) {
            try {
                AL10.alSourceStop(source);
                if (currentBuffer != 0) {
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                    AL10.alDeleteBuffers(currentBuffer);
                    currentBuffer = 0;
                }
                AL10.alDeleteSources(source);
                source = 0;
            } catch (Throwable ignored) {
                // The context may already have been lost during an abnormal exit.
            }
        }
        if (ownsDevice) {
            if (context != 0) {
                try {
                    ALC10.alcDestroyContext(context);
                } catch (Throwable ignored) {
                }
                context = 0;
            }
            if (device != 0) {
                try {
                    ALC10.alcCloseDevice(device);
                } catch (Throwable ignored) {
                }
                device = 0;
            }
        }
        openALReady = false;
    }
}
