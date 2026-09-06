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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Background music player. Plays a folder of MP3 files (decoded by the
 * project's existing JLayer-based {@link Mp3Decoder}) through its own
 * OpenAL source, one track at a time in filename order, looping the
 * playlist forever.
 *
 * <p>Files are downmixed to mono before upload ({@code AL_FORMAT_MONO16}) —
 * the game only needs a single speaker and mono halves both decode work and
 * the source audio size. Decoding happens on the caller's thread when a track
 * starts, so keep individual tracks short or accept the one-time hitch.
 *
 * <p>OpenAL is initialized lazily and defensively: on machines without an
 * audio device the player silently disables itself, so the game and the
 * headless test suite never depend on audio.
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

    /** Playlist of real MP3 files, sorted by path. */
    private final List<File> playlist = new ArrayList<>();
    /** Files named *.mp3 that are not actually MP3s (HTML placeholders). */
    private final List<File> pending = new ArrayList<>();
    /** Music root; context subfolders hang off it (see {@link #playContext}). */
    private File rootDir;
    private String currentContext = "";
    private int trackIndex;
    private boolean trackQueued;   // a decoded track is loaded into the source
    private float gain = 0.6f;

    /**
     * Joins the engine's OpenAL context. Prefers the context another manager
     * (the dialogue system) already made current — sharing one context keeps
     * LWJGL's single global capability set valid. Only when no context exists
     * yet (standalone use) does this open its own device/context.
     */
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
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            currentBuffer = 0;
            openALReady = true;
        } catch (Throwable error) {
            System.err.println("MusicPlayer: OpenAL initialization failed: " + error);
            closeOpenAL();
        }
    }

    /** Number of real MP3 tracks found in the playlist folder. */
    public int trackCount() {
        return playlist.size();
    }

    /** Number of *.mp3 files that are still placeholders (not real audio). */
    public int pendingCount() {
        return pending.size();
    }

    public boolean isReady() {
        return openALReady && !closed;
    }

    /**
     * Scans a folder (recursively — subfolders like episodes/scenes are
     * fine) for *.mp3 files and resets the playlist to the first track.
     * Tracks play in path order, so prefix filenames with 01_, 02_, ... to
     * force an order.
     */
    /**
     * Points the player at the music root. The root's own files form the
     * default ({@code calm}) pool and its subfolders are the per-context
     * pools named by {@link MusicDirector}.
     */
    public void setRoot(File dir) {
        rootDir = dir;
        currentContext = "";
        setFolder(dir);
    }

    /**
     * Switches the playing pool to a game context (see {@link MusicDirector}).
     * Tracks continue uninterrupted when the context has not changed. A
     * context with no real tracks yet falls back to the root pool, so an
     * unorganized folder keeps playing instead of going silent.
     */
    public void playContext(String context) {
        String want = context == null ? "" : context;
        if (want.equals(currentContext) && trackQueued) return;
        currentContext = want;
        if (rootDir == null || !rootDir.isDirectory()) return;
        File pool = want.isEmpty() ? rootDir : new File(rootDir, want);
        setFolder(pool);
        if (playlist.isEmpty() && pool != rootDir) {
            // Fall back to the root pool until the context folder is stocked.
            setFolder(rootDir);
        }
    }

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
        if (openALReady) AL10.alSourcef(source, AL10.AL_GAIN, gain);
    }

    /** Skips to the next track (queued the next time {@link #update()} runs). */
    public void nextTrack() {
        if (playlist.isEmpty()) return;
        trackIndex = (trackIndex + 1) % playlist.size();
        trackQueued = false;
    }

    /**
     * Called once per frame after the OpenAL context exists: starts the first
     * track, then advances to the next file whenever the current one ends.
     */
    public void update() {
        if (!openALReady || closed || playlist.isEmpty()) return;
        if (!trackQueued) {
            loadCurrent();
            return;
        }
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING) return;
        // The track finished: move to the next file (looping the playlist).
        nextTrack();
        loadCurrent();
    }

    /** Decodes the current playlist file to mono and starts playing it. */
    private void loadCurrent() {
        if (playlist.isEmpty()) return;
        File file = playlist.get(trackIndex);
        try {
            AudioData data = Mp3Decoder.decode(file);
            float[] mono = (data.channels <= 1) ? data.samples : data.toMono();
            if (currentBuffer != 0) {
                AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                AL10.alDeleteBuffers(currentBuffer);
                currentBuffer = 0;
            }
            ByteBuffer pcm = toMono16(mono);
            currentBuffer = AL10.alGenBuffers();
            AL10.alBufferData(currentBuffer, AL10.AL_FORMAT_MONO16, pcm, data.sampleRate);
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            AL10.alSourcei(source, AL10.AL_BUFFER, currentBuffer);
            AL10.alSourcePlay(source);
            trackQueued = true;
            System.out.println("[music] playing " + file.getName()
                    + " (" + (mono.length / (float) data.sampleRate / 60.0f) + " min, mono "
                    + data.sampleRate + " Hz)");
        } catch (Throwable error) {
            // A real MP3 that fails to decode is removed so it is not retried
            // every frame; placeholders never reach this point (sniffed out).
            System.err.println("[music] skipping broken track " + file.getName() + ": " + error);
            playlist.remove(file);
            trackQueued = false;
            nextTrack();
        }
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
