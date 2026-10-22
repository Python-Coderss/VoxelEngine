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
import villager.voice.CoquiVitsTts;
import villager.voice.SpeechOptions;
import villager.voice.TextToSpeech;
import villager.voice.VoiceProfile;
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
 * Plays recorded villager clips (Villager News addon + TEAVSRP corpus), and
 * speaks lines no clip can cover with the dynamic fixed-voice neural TTS
 * (Coqui VITS, one closed-inventory voice per villager). Clip decoding and
 * playback shaping run on one worker thread; selection and the live caption
 * publish synchronously so the caption always matches the audio. OpenAL
 * calls stay on the render thread: call
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
    /**
     * Set {@code -Dvoxel.voice.villager.dynamic=false} to keep the legacy
     * clips-only villager voice (unmatched lines fall back to a random
     * vocalization, as before).
     */
    public static final String DYNAMIC_PROPERTY = "voxel.voice.villager.dynamic";
    private volatile CoquiVitsTts dynamicNeural;
    private volatile TextToSpeech dynamicOverride;
    private volatile boolean dynamicProbeStarted;
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
     * Play one line for a named speaker. A line a real recording can speak
     * plays that clip — the live caption shows the clip's exact transcript
     * and the audio is that same clip, so text and sound can never drift
     * apart. A line no clip shares words with is spoken by the dynamic
     * fixed-voice neural TTS and captioned with the authored text, instead
     * of the old unrelated random vocalization with no caption.
     *
     * @return the captioned text, or null when nothing could be spoken
     */
    public String requestSpeech(String text, SpeechOptions options, String speaker) {
        return requestSpeech(text, options, speaker,
                speaker == null ? 0 : speaker.hashCode());
    }

    /**
     * Play one line, identifying the villager by {@code voiceSeed} (normally
     * the entity id) so dynamic lines always use that villager's own fixed
     * neural voice.
     */
    public String requestSpeech(String text, SpeechOptions options,
                                String speaker, int voiceSeed) {
        ClipIndex.Clip related = resolveRelatedClip(text);
        if (related != null) {
            return speak(related, options, speaker);
        }
        if (speakDynamic(text, options, speaker, voiceSeed)) {
            return text == null ? null : text.trim();
        }
        return speak(resolveClip(text), options, speaker);
    }

    /** Play the recorded clip that fits a situation (panicked, building, ...). */
    public String requestTopic(ClipScript.Topic topic, SpeechOptions options,
                               String speaker) {
        return speak(resolveTopic(topic), options, speaker);
    }

    /** Inject a deterministic voice for tests (bypasses the model probe). */
    public void setDynamicVoiceForTest(TextToSpeech voice) {
        dynamicOverride = voice;
    }

    /**
     * Start loading the dynamic villager voice now instead of on the first
     * unmatched line. The game calls this at boot so the first villager who
     * says something new already has the model warm; tests never do.
     */
    public void prepareDynamicVoice() {
        startDynamicProbe();
    }

    /**
     * Speak a line no clip shares words with through the dynamic fixed-voice
     * neural TTS: caption the authored text now, render on the clip worker,
     * play through the normal queue. Returns false when the neural voice is
     * disabled or unavailable, so the caller falls back to the legacy
     * vocalization instead of captioning words it cannot speak.
     */
    private boolean speakDynamic(String text, SpeechOptions options,
                                 String speaker, int voiceSeed) {
        if (closed || text == null || text.trim().isEmpty() || options == null) {
            return false;
        }
        TextToSpeech voice = dynamicVoiceOrNull();
        if (voice == null || !voice.isAvailable()) {
            startDynamicProbe();
            return false;
        }
        final String line = text.trim();
        final String name = speaker == null || speaker.trim().isEmpty()
                ? DEFAULT_SPEAKER : speaker.trim();
        final SpeechOptions delivery = options;
        final TextToSpeech engine = voice;
        final String voiceTag = engine instanceof CoquiVitsTts
                ? ((CoquiVitsTts) engine).speakerForSeed(voiceSeed) : "injected";
        final int seed = voiceSeed;
        float reveal = Math.max(0.6f, (float) LiveCaptions.estimateDuration(
                line, options.getEffectiveSpeed()));
        announceLine(line, name, reveal);
        clipExecutor.execute(() -> {
            try {
                String key = "[DYNAMIC:" + voiceTag + "]" + line;
                AudioData cached = cache.load(key, delivery);
                PendingClip pending;
                int words = wordCount(line);
                if (cached != null) {
                    pending = PendingClip.fromSamples(cached.samples, cached.sampleRate, words);
                } else {
                    WavAudio rendered = engine instanceof CoquiVitsTts
                            ? ((CoquiVitsTts) engine).renderLine(line, delivery, seed)
                            : engine.render(line, VoiceProfile.VILLAGER);
                    cache.save(key, delivery, new AudioData(
                            rendered.samples.clone(), 1, rendered.sampleRate));
                    pending = PendingClip.fromSamples(rendered.samples, rendered.sampleRate, words);
                }
                while (pendingClips.poll() != null) {
                    // Keep only the newest pending line.
                }
                pendingClips.offer(pending);
            } catch (Throwable error) {
                System.err.println("VillagerAudioManager: dynamic speech failed: " + error);
            }
        });
        return true;
    }

    /** The ready dynamic voice, or null when disabled, missing, or probing. */
    private TextToSpeech dynamicVoiceOrNull() {
        if (!isDynamicEnabled() || closed) return null;
        TextToSpeech override = dynamicOverride;
        if (override != null) return override;
        CoquiVitsTts neural = dynamicNeural;
        return neural != null && neural.isAvailable() ? neural : null;
    }

    private static boolean isDynamicEnabled() {
        String value = System.getProperty(DYNAMIC_PROPERTY, "true");
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    /** Load the neural model once, on the clip worker — never on a game thread. */
    private void startDynamicProbe() {
        if (dynamicProbeStarted || dynamicOverride != null
                || !isDynamicEnabled() || closed) {
            return;
        }
        synchronized (clipLock) {
            if (dynamicProbeStarted || dynamicOverride != null) return;
            dynamicProbeStarted = true;
        }
        // A dedicated thread: the model is ~137 MB and its load must not sit
        // ahead of a villager clip on the shared clip worker.
        Thread loader = new Thread(() -> {
            try {
                CoquiVitsTts neural = new CoquiVitsTts(CoquiVitsTts.defaultDirectory());
                if (neural.isAvailable()) {
                    dynamicNeural = neural;
                    System.out.println(
                            "VillagerAudioManager: dynamic villager voice ready ("
                                    + neural.speakerForSeed(0) + " is the first voice)");
                }
            } catch (Throwable error) {
                System.err.println(
                        "VillagerAudioManager: dynamic voice unavailable (" + error + ")");
            }
        }, "VillagerVoiceModel");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Publish a line that a local synthetic voice is about to speak. The
     * caption carries the exact authored text, so the subtitle and the audio
     * (rendered from the same text) can never disagree.
     */
    public void announceLine(String text, String speaker, float revealSeconds) {
        if (closed || text == null || text.trim().isEmpty()) return;
        if (openALReady) {
            LiveCaptions.showForAudio(speaker, text.trim(), revealSeconds);
        } else {
            LiveCaptions.show(speaker, text.trim(), revealSeconds);
        }
    }

    /**
     * Queue already-rendered speech (local TTS, an injected test voice, or any
     * other PCM source). The newest line wins, exactly like clip playback, and
     * the caption reveal is retimed to the real audio in {@link #update()}.
     */
    public void playRendered(WavAudio rendered, double volume) {
        if (closed || !openALReady || rendered == null || rendered.samples.length == 0) {
            return;
        }
        float gain = (float) Math.max(0.0, Math.min(2.0, volume));
        float[] samples = rendered.samples;
        if (gain != 1.0f) {
            samples = samples.clone();
            for (int i = 0; i < samples.length; i++) {
                samples[i] = Math.max(-1.0f, Math.min(1.0f, samples[i] * gain));
            }
        }
        while (pendingClips.poll() != null) {
            // Keep only the newest requested line.
        }
        pendingClips.offer(PendingClip.fromSamples(samples, rendered.sampleRate,
                LiveCaptions.pendingWordCount()));
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

    /**
     * Resolve free text to a clip that actually shares its words (exact
     * transcript or word overlap). Null means no recording is on topic and
     * the line wants dynamic neural speech instead of a random take.
     */
    private ClipIndex.Clip resolveRelatedClip(String text) {
        if (closed || text == null || text.trim().isEmpty()) return null;
        if (ClipIndex.tokens(text).length == 0) return null;
        synchronized (clipLock) {
            ClipVoice voice = clipVoiceOrNull();
            return voice == null ? null
                    : voice.library().index().bestWordMatch(
                            text.trim(), clipRng, recentClips);
        }
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
        // The renderer resamples by speed * emotion pitch, so both the reveal
        // window and the add-on cue times must use that real factor; dividing
        // by speed alone made pitched lines run ahead of their voice.
        float factor = (float) ClipVoice.playbackFactor(options);
        float reveal = Math.max(0.6f, (float) (chosen.duration / factor));
        float[] cueTimes = null;
        if (chosen.cueTimes.length > 0) {
            cueTimes = new float[chosen.cueTimes.length];
            for (int i = 0; i < cueTimes.length; i++) {
                cueTimes[i] = (float) (chosen.cueTimes[i] / factor);
            }
        }
        // Word-level timings measured offline (local ASR) beat both the
        // phrase cues and any runtime estimate; the PCM pause reader is the
        // fallback when this clip has no measured timings.
        int words = wordCount(chosen.text);
        float[] measured = measuredTimings(chosen.id, words, factor);
        // Captions publish even when no audio device exists or the clip
        // cannot be loaded, so the line is always readable on screen.
        boolean audible = openALReady;
        if (audible) {
            if (measured != null) {
                LiveCaptions.showForAudio(speaker, chosen.text, reveal, measured);
            } else {
                LiveCaptions.showForAudio(speaker, chosen.text, reveal,
                        cueTimes, chosen.cueTexts);
            }
        } else {
            LiveCaptions.show(speaker, chosen.text, reveal, cueTimes, chosen.cueTexts);
        }
        synchronized (clipLock) {
            recentClips.add(chosen.id);
            while (recentClips.size() > RECENT_CLIPS) {
                recentClips.remove(0);
            }
        }
        if (!audible || !clipPending.compareAndSet(false, true)) {
            return chosen.text;
        }
        final ClipIndex.Clip clip = chosen;
        clipExecutor.execute(() -> {
            try {
                AudioData cached = cache.load(clip.id, options);
                PendingClip pending;
                // Measured timings already sit on the caption; do not let the
                // PCM reader overwrite them with its coarser estimate.
                final float[] measuredTimings = measured;
                int pcmWords = measuredTimings != null ? 0 : words;
                if (cached != null) {
                    pending = PendingClip.fromSamples(cached.samples, cached.sampleRate, pcmWords);
                } else {
                    WavAudio rendered = clipVoice.render(clip, options);
                    cache.save(clip.id, options, new AudioData(
                            rendered.samples.clone(), 1, rendered.sampleRate));
                    pending = PendingClip.fromSamples(rendered.samples, rendered.sampleRate, pcmWords);
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

    /**
     * Play one scripted Villager TV line.
     *
     * <p>The guide decides the voice: an exact recorded clip when the script
     * names one ({@code clip}), otherwise the best recorded match for the
     * caption, otherwise the closest clip for the line's situation
     * ({@code topic}). The caption is always the chosen recording's own
     * transcript, so a TV line can never show different words than it speaks.
     *</p>
     *
     * @return the captioned transcript, or null when nothing could be spoken
     */
    public String requestTvLine(com.voxel.game.TvGuide.Line line, String speaker) {
        if (line == null || !line.isSpoken()) return null;
        SpeechOptions options = new SpeechOptions(line.speed, line.pitch, 1.0, 0.0,
                line.emotion, 0.0, SpeechOptions.looksLikeQuestion(line.text));
        return speak(resolveTvClip(line), options,
                speaker == null || speaker.trim().isEmpty() ? DEFAULT_SPEAKER : speaker);
    }

    /**
     * The only clip collection the Villager TV broadcasts from: the Villager
     * News 1.0 Add-On recordings ({@link ClipIndex.Clip#kind} {@code "addon"};
     * see {@code sources} in {@code voice/clips_index.json}). The TV must never
     * pull a line out of any other corpus, so every lookup below runs against a
     * kind-restricted view of the index, including the caption and situation
     * fallbacks — not just the explicitly pinned clips.
     */
    public static final String TV_CLIP_KIND = "addon";

    private ClipIndex tvIndex;
    private ClipVoice tvIndexOwner;

    /** Addon-only view of the catalog, rebuilt if the clip voice is replaced. */
    private ClipIndex tvIndexOrNull() {
        ClipVoice voice = clipVoiceOrNull();
        if (voice == null) return null;
        if (tvIndex == null || tvIndexOwner != voice) {
            tvIndex = voice.library().index().restrictedToKind(TV_CLIP_KIND);
            tvIndexOwner = voice;
        }
        return tvIndex;
    }

    /** Resolve a scripted TV line to the recorded clip that should speak it. */
    private ClipIndex.Clip resolveTvClip(com.voxel.game.TvGuide.Line line) {
        if (closed) return null;
        synchronized (clipLock) {
            ClipIndex index = tvIndexOrNull();
            if (index == null || index.size() == 0) return null;
            if (line.clipId != null) {
                for (ClipIndex.Clip clip : index.clips()) {
                    if (line.clipId.equals(clip.id)) return clip;
                }
            }
            if (line.topic != null) {
                try {
                    ClipScript.Topic topic = ClipScript.Topic.valueOf(
                            line.topic.toUpperCase(java.util.Locale.ROOT));
                    return ClipScript.pick(index, topic, clipRng, recentClips);
                } catch (IllegalArgumentException unknownTopic) {
                    // Fall through to a caption match.
                }
            }
            return index.bestMatch(line.text, clipRng, recentClips);
        }
    }

    /**
     * Queue a Villager TV music cue. Cues are rendered through the same
     * clip-voice arrangement as the Villager News intro, so a theme or sting
     * is sung in villager syllables rather than played by an instrument. The
     * cue's notes are fingerprinted into the cache key, so editing the score
     * in the guide always produces fresh audio.
     */
    public void requestTvMusicCue(com.voxel.game.TvMusicCue cue) {
        if (closed || cue == null || !openALReady
                || !clipPending.compareAndSet(false, true)) {
            return;
        }
        final com.voxel.game.TvMusicCue queued = cue;
        clipExecutor.execute(() -> {
            try {
                if (renderer == null) {
                    renderer = new VillagerVoiceRenderer();
                }
                SpeechOptions profile = SpeechOptions.DEFAULT;
                VillagerNewsIntro arrangement = queued.toIntro();
                if (!arrangement.supports(renderer.getMode())) {
                    throw new IllegalStateException("TV cues need clip voice mode");
                }
                String cacheText = "[TV_CUE]" + queued.getId() + ";" + queued.cacheKey()
                        + ";backend=" + renderer.getMode().name();
                AudioData cached = cache.load(cacheText, profile);
                PendingClip clip;
                if (cached != null) {
                    clip = PendingClip.fromSamples(cached.samples, cached.sampleRate, 0);
                } else {
                    WavAudio rendered = arrangement.render(renderer, profile);
                    float[] scaled = rendered.samples.clone();
                    float gain = queued.getGain();
                    if (gain != 1.0f) {
                        for (int i = 0; i < scaled.length; i++) {
                            scaled[i] = Math.max(-1.0f, Math.min(1.0f, scaled[i] * gain));
                        }
                    }
                    cache.save(cacheText, profile,
                            new AudioData(scaled.clone(), 1, rendered.sampleRate));
                    clip = PendingClip.fromSamples(scaled, rendered.sampleRate, 0);
                }
                while (pendingClips.poll() != null) {
                    // Keep only the newest requested clip.
                }
                pendingClips.offer(clip);
            } catch (Throwable error) {
                System.err.println("VillagerAudioManager: TV cue '" + queued.getId()
                        + "' failed: " + error);
            } finally {
                clipPending.set(false);
            }
        });
    }

    private static int wordCount(String text) {
        return PendingClip.wordCountOf(text);
    }

    /** Offline-measured word starts for this clip, or null when unavailable. */
    private static float[] measuredTimings(String clipId, int words, double factor) {
        ClipWordTiming timingDoc = ClipWordTiming.get();
        return timingDoc == null ? null : timingDoc.scaled(clipId, words, factor);
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
                    clip = PendingClip.fromSamples(cached.samples, cached.sampleRate, 0);
                    System.out.println("VillagerAudioManager: Villager News intro cache hit");
                } else {
                    villager.voice.WavAudio rendered = intro.render(renderer, profile);
                    cache.save(cacheText, profile,
                            new AudioData(rendered.samples.clone(), 1, rendered.sampleRate));
                    clip = PendingClip.fromSamples(rendered.samples, rendered.sampleRate, 0);
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
                villager.aiDisplayName(), villager.id);
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
        // The clip is actually starting now: start the caption clock here, not
        // when the line was requested, and reveal each word at its measured
        // place in this recording.
        LiveCaptions.beginAudio(clip.durationSeconds(), clip.wordTimes);
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
        /** Per-word starts in seconds, from the audio itself; null when unknown. */
        private final float[] wordTimes;

        private PendingClip(ByteBuffer pcm, int sampleRate, float[] wordTimes) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.wordTimes = wordTimes;
        }

        float durationSeconds() {
            return sampleRate <= 0 ? 1f : (pcm.capacity() / 2f) / sampleRate;
        }

        private static PendingClip fromPcm(ByteBuffer pcm, int sampleRate) {
            return new PendingClip(pcm, sampleRate, null);
        }

        private static PendingClip fromSamples(float[] samples, int sampleRate,
                                               int wordCount) {
            ByteBuffer pcm = ByteBuffer.allocateDirect(samples.length * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (float value : samples) {
                float clipped = Math.max(-1.0f, Math.min(1.0f, value));
                pcm.putShort((short) Math.round(clipped * 32767.0f));
            }
            pcm.flip();
            float[] wordTimes = wordCount > 0
                    ? CaptionTiming.wordTimes(wordCount, samples, sampleRate) : null;
            return new PendingClip(pcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
                    sampleRate, wordTimes);
        }

        private static int wordCountOf(String text) {
            return text == null || text.trim().isEmpty()
                    ? 0 : text.trim().split("\\s+").length;
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
