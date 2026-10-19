package com.voxel.audio;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A {@link MusicLibrary} whose directory scan happens on a background thread.
 *
 * <p>The shipped soundtrack is large — several hundred tracks across a few
 * hundred MB — and the scan cost is paid per file ({@code length()} to reject
 * stings, {@code getCanonicalPath()} to de-duplicate, plus classification).
 * Doing that walk inline on the boot thread added a measurable stall before the
 * first frame, for a catalogue nothing reads until music is actually requested.
 *
 * <p>This subclass starts empty and swaps in the real catalogue once the scan
 * completes. Every accessor is safe to call at any time:</p>
 * <ul>
 *   <li>before the scan lands, the library reports empty and {@link #pick}
 *       returns {@code null}, so callers simply try again next frame;</li>
 *   <li>after it lands, behaviour is identical to an eagerly scanned library.</li>
 * </ul>
 *
 * <p>The catalogue itself stays resident (it is just file paths and pool maps,
 * a few tens of KB for 250+ tracks — nothing like the audio payloads). Audio
 * <em>content</em> was already streamed: {@link MusicPlayer} decodes exactly one
 * track at a time on a worker thread and keeps only the current and incoming
 * buffers in memory. Nothing here caches decoded PCM.</p>
 */
public final class LazyMusicLibrary extends MusicLibrary {

    private final List<File> roots;
    private final Thread worker;
    private volatile MusicLibrary delegate;

    public LazyMusicLibrary(Collection<File> roots) {
        // Start from an empty catalogue so every accessor is well defined.
        super(Collections.<Track>emptyList());
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                MusicLibrary scanned = MusicLibrary.scan(roots);
                delegate = scanned;
                System.out.println("[audio] music library scanned: " + scanned.size()
                        + " tracks; pools " + scanned.populatedContexts()
                        + " (" + (System.currentTimeMillis() - t0) + "ms, background)");
            }
        }, "music-library-scan");
        t.setDaemon(true);
        // Low priority: this is catalogue metadata, never latency-critical.
        t.setPriority(Thread.MIN_PRIORITY);
        this.worker = t;
    }

    /** Kicks off the scan. Safe to call once; later calls are ignored. */
    public void start() {
        worker.start();
    }

    /** True once the real catalogue is available. */
    public boolean isReady() {
        return delegate != null;
    }

    /** Forces the scan to complete and returns the real catalogue. */
    public MusicLibrary await() throws InterruptedException {
        worker.join();
        MusicLibrary d = delegate;
        return d == null ? MusicLibrary.empty() : d;
    }

    private MusicLibrary ready() {
        return delegate;
    }

    @Override
    public boolean isEmpty() {
        MusicLibrary d = ready();
        return d == null || d.isEmpty();
    }

    @Override
    public int size() {
        MusicLibrary d = ready();
        return d == null ? 0 : d.size();
    }

    @Override
    public List<Track> tracks() {
        MusicLibrary d = ready();
        return d == null ? Collections.<Track>emptyList() : d.tracks();
    }

    @Override
    public int count(String context) {
        MusicLibrary d = ready();
        return d == null ? 0 : d.count(context);
    }

    @Override
    public List<String> populatedContexts() {
        MusicLibrary d = ready();
        return d == null ? Collections.<String>emptyList() : d.populatedContexts();
    }

    @Override
    public Track pick(String context, java.util.Random rng, Collection<File> avoid) {
        MusicLibrary d = ready();
        return d == null ? null : d.pick(context, rng, avoid);
    }
}