package com.voxel.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALC10;
import com.voxel.entity.VillagerEntity;
import villager.voice.ClipIndex;
import villager.voice.ClipLibrary;
import villager.voice.ClipScript;
import villager.voice.ClipVoice;
import villager.voice.SpeechOptions;
import villager.voice.VillagerNewsIntro;
import villager.voice.VillagerVoiceRenderer;
import villager.voice.VoiceMode;
import villager.voice.WavAudio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges the recorded villager voice to the engine's OpenAL context.
 *
 * Plays recorded villager clips (Villager News addon + TEAVSRP corpus). Clip
 * decoding and playback shaping run on one worker thread; selection and the
 * live caption publish synchronously so the caption always matches the clip.
 * Nothing is ever synthesized. OpenAL calls stay on the render thread: call
 * {@link #update()} once per frame after the OpenAL context has been
 * initialized.
 */
public final class VillagerAudioManager implements AutoCloseable {
    private static final String DEFAULT_LINE = "Hmm...";
    private static final String DEFAULT_SPEAKER = "Villager";
    private static final int RECENT_CLIPS = 6;

    private final VoiceCache cache;
    // Clip selection state, guarded by clipLock (request threads).
    private final Object clipLock = new Object();
    private final java.util.Random clipRng = new java.util.Random();
    private final java.util.List<String> recentClips = new java.util.ArrayList<String>();
    private ClipVoice clipVoice;
    private final ClipLibrary injectedClips;
    private boolean clipsUnavailable;
    private final DialogueCatalog dialogueCatalog;
    private final ExecutorService clipExecutor;
    private final ConcurrentLinkedQueue<PendingClip> pendingClips = new ConcurrentLinkedQueue<PendingClip>();
    private final ConcurrentHashMap<Integer, Integer> interactionCounts = new ConcurrentHashMap<Integer, Integer>();
    private final AtomicBoolean clipPending = new AtomicBoolean(false);
    private volatile boolean closed;
    private volatile boolean openALReady;

    // Only accessed by the render thread after initialization.
    private long device;
    private long context;
    private int source;
    private int currentBuffer;

    // Only accessed by the clip worker after construction.
    private VillagerVoiceRenderer renderer;

    public VillagerAudioManager() {
        this(Paths.get(System.getProperty("voxel.voice.cache", "dev/voice-cache")), null);
    }

    public VillagerAudioManager(Path cacheDirectory) {
        this(cacheDirectory, null);
    }

    /**
     * Full constructor. {@code clipLibrary} is normally null (the standard
     * locations are discovered on first use); tests inject a fixed library.
     */
    public VillagerAudioManager(Path cacheDirectory, ClipLibrary clipLibrary) {
        this.injectedClips = clipLibrary;
        if (clipLibrary != null) {
            this.clipVoice = new ClipVoice(clipLibrary);
        }
        this.cache = new VoiceCache(cacheDirectory);
        this.dialogueCatalog = DialogueCatalog.loadDefault();
        this.clipExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VillagerVoiceClips");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Initializes OpenAL on the current GLFW/render thread. Failure is non-fatal;
     * the engine can continue without voice playback when no audio device exists.
     */
    public void initialize() {
        if (closed || openALReady) {
            return;
        }
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) {
                System.err.println("VillagerAudioManager: no OpenAL device available");
                return;
            }
            ALCCapabilities deviceCapabilities = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0L || !ALC10.alcMakeContextCurrent(context)) {
                System.err.println("VillagerAudioManager: unable to create OpenAL context");
                closeOpenAL();
                return;
            }
            AL.createCapabilities(deviceCapabilities);
            source = AL10.alGenSources();
            AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);
            currentBuffer = 0;
            openALReady = true;
        } catch (Throwable error) {
            System.err.println("VillagerAudioManager: OpenAL initialization failed: " + error);
            closeOpenAL();
        }
    }

    /** Play one line; the text is a hint, the recording decides the words. */
    public String requestSpeech(String text) {
        return requestSpeech(text, SpeechOptions.DEFAULT, DEFAULT_SPEAKER);
    }

    /** Play one line with a complete voice profile. */
    public String requestSpeech(String text, SpeechOptions options) {
        return requestSpeech(text, options, DEFAULT_SPEAKER);
    }

    /**
     * Play one line for a named speaker. The request is resolved to a real
     * recorded clip first; the live caption shows that clip's exact transcript
     * (revealed on the recording's own subtitle-cue timing) and the audio is
     * that same clip — text and sound can never drift apart. Nothing is ever
     * synthesized or invented: no clip, no line.
     *
     * @return the captioned transcript, or null when the catalog has nothing
     */
    public String requestSpeech(String text, SpeechOptions options, String speaker) {
        return speak(resolveClip(text), options, speaker);
    }

    /** Play the recorded clip that fits a situation (panicked, building, ...). */
    public String requestTopic(ClipScript.Topic topic, SpeechOptions options,
                               String speaker) {
        return speak(resolveTopic(topic), options, speaker);
    }

    /** Lazily discover the clip catalog; null when no clips exist on disk. */
    private synchronized ClipVoice clipVoiceOrNull() {
        if (clipVoice == null && injectedClips == null && !closed && !clipsUnavailable) {
            try {
                clipVoice = new ClipVoice(ClipLibrary.openDefault());
            } catch (Throwable error) {
                clipsUnavailable = true;
                System.err.println("VillagerAudioManager: no voice clips available: " + error);
            }
        }
        return clipVoice;
    }

    /** Resolve a free-text hint to the best matching recorded clip. */
    private ClipIndex.Clip resolveClip(String text) {
        if (closed || text == null || text.trim().isEmpty()) return null;
        synchronized (clipLock) {
            return clipVoiceOrNull() == null ? null
                    : clipVoice.library().index().bestMatch(text.trim(), clipRng, recentClips);
        }
    }

    /** Resolve a situation to the best fitting recorded clip. */
    private ClipIndex.Clip resolveTopic(ClipScript.Topic topic) {
        if (closed || topic == null) return null;
        synchronized (clipLock) {
            return clipVoiceOrNull() == null ? null
                    : ClipScript.pick(clipVoice.library().index(), topic, clipRng, recentClips);
        }
    }

    /** Caption the clip transcript and queue that exact clip for playback. */
    private String speak(ClipIndex.Clip chosen, SpeechOptions options, String speaker) {
        if (closed || chosen == null || options == null || chosen.text.isEmpty()) {
            return null;
        }
        float speed = (float) options.getEffectiveSpeed();
        float reveal = Math.max(0.6f, chosen.duration / speed);
        float[] cueTimes = null;
        if (chosen.cueTimes.length > 0) {
            cueTimes = new float[chosen.cueTimes.length];
            for (int i = 0; i < cueTimes.length; i++) {
                cueTimes[i] = chosen.cueTimes[i] / speed;
            }
        }
        // Captions publish even when no audio device exists or the clip
        // cannot be loaded, so the line is always readable on screen.
        LiveCaptions.show(speaker, chosen.text, reveal, cueTimes, chosen.cueTexts);
        synchronized (clipLock) {
            recentClips.add(chosen.id);
            while (recentClips.size() > RECENT_CLIPS) {
                recentClips.remove(0);
            }
        }
        if (!openALReady || !clipPending.compareAndSet(false, true)) {
            return chosen.text;
        }
        final ClipIndex.Clip clip = chosen;
        clipExecutor.execute(() -> {
            try {
                AudioData cached = cache.load(clip.id, options);
                PendingClip pending;
                if (cached != null) {
                    pending = PendingClip.fromSamples(cached.samples, cached.sampleRate);
                } else {
                    WavAudio rendered = clipVoice.render(clip, options);
                    cache.save(clip.id, options, new AudioData(
                            rendered.samples.clone(), 1, rendered.sampleRate));
                    pending = PendingClip.fromSamples(rendered.samples, rendered.sampleRate);
                }
                // Keep only the newest pending line so an active player cannot
                // accumulate a large queue during rapid interactions.
                while (pendingClips.poll() != null) {
                    // PendingClip owns only heap/native PCM memory reclaimed by the JVM.
                }
                pendingClips.offer(pending);
            } catch (Throwable error) {
                System.err.println("VillagerAudioManager: clip playback failed: " + error);
            } finally {
                clipPending.set(false);
            }
        });
        return chosen.text;
    }

    /** Convenience method for the default villager greeting. */
    public void requestVillagerGreeting() {
        requestSpeech(DEFAULT_LINE);
    }

    /**
     * Queue the editable, community-arranged Villager News opening theme.
     * The note asset is fingerprinted into the cache key, so tuning the JSON
     * automatically produces a fresh clip without manual cache cleanup.
     */
    public void requestNewsIntro() {
        requestNewsIntro(SpeechOptions.DEFAULT);
    }

    /** Queue the news intro with an optional overall voice profile. */
    public void requestNewsIntro(SpeechOptions profile) {
        if (closed || !openALReady || profile == null
                || !clipPending.compareAndSet(false, true)) {
            return;
        }
        clipExecutor.execute(() -> {
            try {
                VillagerNewsIntro intro = VillagerNewsIntro.loadDefault();
                if (renderer == null) {
                    renderer = new VillagerVoiceRenderer();
                    System.out.println("VillagerAudioManager: villager voice ready (recorded clips)");
                }
                if (!intro.supports(renderer.getMode())) {
                    throw new IllegalStateException("Villager News intro is available only in clip voice mode");
                }
                String cacheText = "[VNN_INTRO]" + intro.cacheKey(profile)
                        + ";backend=" + renderer.getMode().name();
                AudioData cached = cache.load(cacheText, profile);
                PendingClip clip;
                if (cached != null) {
                    clip = PendingClip.fromSamples(cached.samples, cached.sampleRate);
                    System.out.println("VillagerAudioManager: Villager News intro cache hit");
                } else {
                    villager.voice.WavAudio rendered = intro.render(renderer, profile);
                    cache.save(cacheText, profile,
                            new AudioData(rendered.samples.clone(), 1, rendered.sampleRate));
                    clip = PendingClip.fromSamples(rendered.samples, rendered.sampleRate);
                    System.out.println("VillagerAudioManager: rendered and cached Villager News intro"
                            + " (" + intro.getAttribution() + ")");
                }
                while (pendingClips.poll() != null) {
                    // Keep only the newest requested clip.
                }
                pendingClips.offer(clip);
            } catch (Throwable error) {
                System.err.println("VillagerAudioManager: Villager News intro failed: " + error);
            } finally {
                clipPending.set(false);
            }
        });
    }

    /** Select and queue a profession/time-aware line, returning it for the HUD. */
    public String requestVillagerDialogue(VillagerEntity villager, float worldTime) {
        if (villager == null) {
            requestVillagerGreeting();
            return DEFAULT_LINE;
        }
        Integer oldCount = interactionCounts.get(villager.id);
        int count = oldCount == null ? 0 : oldCount;
        interactionCounts.put(villager.id, count + 1);
        String period = VillagerDialogue.periodName(worldTime);
        // Improved dialogue path: temperament + mood + no-repeat memory, with
        // the editable catalog merged over the built-in lines.
        DialogueLine selected = DialogueDirector.choose(villager, period, count,
                dialogueCatalog.getLines());
        // The catalog line is only a hint: what plays and captions is the
        // chosen clip's exact transcript.
        String spoken = requestSpeech(selected.getText(), selected.getOptions(),
                villager.aiDisplayName());
        String display = spoken != null ? spoken : selected.getText();
        // Expressive talk gesture + lip sync while the line plays
        villager.startTalking(Math.max(1.2f, display.length() * 0.052f + 0.4f));
        return display;
    }

    /**
     * Pump completed clips and play them. This method must run on the thread that
     * owns the OpenAL context (the VoxelEngine render thread).
     */
    public void update() {
        if (!openALReady || closed) {
            return;
        }
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING) {
            return;
        }
        if (currentBuffer != 0) {
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            AL10.alDeleteBuffers(currentBuffer);
            currentBuffer = 0;
        }

        PendingClip clip = pendingClips.poll();
        if (clip == null) {
            return;
        }
        // The clip is actually starting now: sync the live caption reveal to
        // the recording's real length.
        LiveCaptions.retimeCurrent(clip.durationSeconds());
        ByteBuffer pcm = clip.pcm.order(ByteOrder.LITTLE_ENDIAN);
        currentBuffer = AL10.alGenBuffers();
        AL10.alBufferData(currentBuffer, AL10.AL_FORMAT_MONO16, pcm, clip.sampleRate);
        // Volume is already baked into the rendered PCM; keep OpenAL at unity
        // so cached and freshly shaped clips behave identically.
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);
        AL10.alSourcei(source, AL10.AL_BUFFER, currentBuffer);
        AL10.alSourcePlay(source);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clipExecutor.shutdown();
        boolean workerStopped = false;
        try {
            workerStopped = clipExecutor.awaitTermination(5, TimeUnit.SECONDS);
            if (!workerStopped) {
                clipExecutor.shutdownNow();
                // Do not release the clip library while a decode could still be
                // running on the worker.
                workerStopped = clipExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            clipExecutor.shutdownNow();
            try {
                workerStopped = clipExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Preserve the original interruption below.
            }
            Thread.currentThread().interrupt();
        }
        if (renderer != null && workerStopped) {
            try {
                renderer.close();
            } catch (Exception error) {
                System.err.println("VillagerAudioManager: voice shutdown failed: " + error);
            }
        } else if (renderer != null) {
            System.err.println("VillagerAudioManager: clip worker did not stop; leaving the clip library open");
        }
        pendingClips.clear();
        closeOpenAL();
    }

    private static final class PendingClip {
        private final ByteBuffer pcm;
        private final int sampleRate;

        private PendingClip(ByteBuffer pcm, int sampleRate) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
        }

        float durationSeconds() {
            return sampleRate <= 0 ? 1f : (pcm.capacity() / 2f) / sampleRate;
        }

        private static PendingClip fromPcm(ByteBuffer pcm, int sampleRate) {
            return new PendingClip(pcm, sampleRate);
        }

        private static PendingClip fromSamples(float[] samples, int sampleRate) {
            ByteBuffer pcm = ByteBuffer.allocateDirect(samples.length * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (float value : samples) {
                float clipped = Math.max(-1.0f, Math.min(1.0f, value));
                pcm.putShort((short) Math.round(clipped * 32767.0f));
            }
            pcm.flip();
            return new PendingClip(pcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN), sampleRate);
        }
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
            } catch (Throwable ignored) {
                // The context may already have been lost during an abnormal exit.
            }
            source = 0;
        }
        if (context != 0L) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(context);
            context = 0L;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
        }
        openALReady = false;
    }
}
