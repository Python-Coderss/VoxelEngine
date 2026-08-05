package com.voxel.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALC10;
import com.voxel.entity.VillagerEntity;
import villager.voice.SpeechOptions;
import villager.voice.VillagerVoice;
import villager.voice.VoiceClip;

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
 * Bridges the Java villager voice runtime to the engine's OpenAL context.
 *
 * Model loading and synthesis are CPU/native work and run on one worker thread.
 * OpenAL calls stay on the render thread: call {@link #update()} once per frame
 * after the OpenAL context has been initialized.
 */
public final class VillagerAudioManager implements AutoCloseable {
    private static final String DEFAULT_MODEL_DIRECTORY = "models/java";
    private static final String DEFAULT_LINE = "Hmm...";

    private final Path modelDirectory;
    private final VoiceCache cache;
    private final ExecutorService synthesisExecutor;
    private final ConcurrentLinkedQueue<PendingClip> pendingClips = new ConcurrentLinkedQueue<PendingClip>();
    private final ConcurrentHashMap<Integer, Integer> interactionCounts = new ConcurrentHashMap<Integer, Integer>();
    private final AtomicBoolean synthesisPending = new AtomicBoolean(false);
    private volatile boolean closed;
    private volatile boolean openALReady;

    // Only accessed by the render thread after initialization.
    private long device;
    private long context;
    private int source;
    private int currentBuffer;

    // Only accessed by the synthesis worker after construction.
    private VillagerVoice voice;

    public VillagerAudioManager() {
        this(Paths.get(System.getProperty("voxel.voice.models", DEFAULT_MODEL_DIRECTORY)),
                Paths.get(System.getProperty("voxel.voice.cache", "dev/voice-cache")));
    }

    public VillagerAudioManager(Path modelDirectory) {
        this(modelDirectory, Paths.get(System.getProperty("voxel.voice.cache", "dev/voice-cache")));
    }

    public VillagerAudioManager(Path modelDirectory, Path cacheDirectory) {
        if (modelDirectory == null) {
            throw new IllegalArgumentException("modelDirectory must not be null");
        }
        this.modelDirectory = modelDirectory.toAbsolutePath().normalize();
        this.cache = new VoiceCache(cacheDirectory);
        this.synthesisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VillagerVoiceSynthesis");
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

    /** Queue one line for synthesis; repeated requests while busy are coalesced. */
    public void requestSpeech(String text) {
        requestSpeech(text, SpeechOptions.DEFAULT);
    }

    /** Queue one line with a complete voice profile. */
    public void requestSpeech(String text, SpeechOptions options) {
        if (closed || !openALReady || text == null || text.trim().isEmpty()
                || options == null) {
            return;
        }
        if (!synthesisPending.compareAndSet(false, true)) {
            return;
        }
        synthesisExecutor.execute(() -> {
            try {
                String line = text.trim();
                AudioData cached = cache.load(line, options);
                PendingClip clip;
                if (cached != null) {
                    clip = PendingClip.fromSamples(cached.samples, cached.sampleRate);
                    System.out.println("VillagerAudioManager: cache hit for dialogue");
                } else {
                    if (voice == null) {
                        voice = new VillagerVoice(modelDirectory);
                        System.out.println("VillagerAudioManager: dialogue voice loaded (Coqui VITS + RVC, Java only)");
                    }
                    VoiceClip generated = voice.speak(line, options);
                    cache.save(line, options, new AudioData(generated.getSamples(), 1,
                            generated.getSampleRate()));
                    clip = PendingClip.fromPcm(generated.getPcm16(), generated.getSampleRate());
                    System.out.println("VillagerAudioManager: generated and cached dialogue");
                }
                // Keep only the newest pending line so an active player cannot
                // accumulate a large queue during rapid interactions.
                while (pendingClips.poll() != null) {
                    // PendingClip owns only heap/native PCM memory reclaimed by the JVM.
                }
                pendingClips.offer(clip);
            } catch (Throwable error) {
                System.err.println("VillagerAudioManager: speech synthesis failed: " + error);
            } finally {
                synthesisPending.set(false);
            }
        });
    }

    /** Convenience method for the default villager greeting. */
    public void requestVillagerGreeting() {
        requestSpeech(DEFAULT_LINE);
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
        String line = VillagerDialogue.choose(villager, worldTime, count);
        requestSpeech(line);
        return line;
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
        ByteBuffer pcm = clip.pcm.order(ByteOrder.LITTLE_ENDIAN);
        currentBuffer = AL10.alGenBuffers();
        AL10.alBufferData(currentBuffer, AL10.AL_FORMAT_MONO16, pcm, clip.sampleRate);
        // Volume is already baked into the generated PCM; keep OpenAL at unity
        // so cached and freshly generated clips behave identically.
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
        synthesisExecutor.shutdown();
        boolean workerStopped = false;
        try {
            workerStopped = synthesisExecutor.awaitTermination(5, TimeUnit.SECONDS);
            if (!workerStopped) {
                synthesisExecutor.shutdownNow();
                // Do not release the native voice models while an inference task
                // could still be running.
                workerStopped = synthesisExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            synthesisExecutor.shutdownNow();
            try {
                workerStopped = synthesisExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Preserve the original interruption below.
            }
            Thread.currentThread().interrupt();
        }
        if (voice != null && workerStopped) {
            try {
                voice.close();
            } catch (Exception error) {
                System.err.println("VillagerAudioManager: voice shutdown failed: " + error);
            }
        } else if (voice != null) {
            System.err.println("VillagerAudioManager: synthesis worker did not stop; leaving native voice models allocated");
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
