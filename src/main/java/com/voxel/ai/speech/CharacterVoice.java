package com.voxel.ai.speech;

import com.voxel.audio.LiveCaptions;
import com.voxel.audio.VillagerAudioManager;

import villager.voice.TextToSpeech;
import villager.voice.VoiceProfile;
import villager.voice.WavAudio;
import villager.voice.WindowsSapiTts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single voice bridge for characters whose lines are authored text: the
 * ancient builders, the player, the narrator and bosses.
 *
 * <p>Every character is now audible. A line is spoken by a local operating
 * system voice in that character's {@link VoiceProfile}; the caption shows the
 * exact authored text, so sound and subtitle always agree. When the machine has
 * no local voice (or the render fails), the line falls back to the recorded
 * clip voice — the caption then becomes that recording's transcript, exactly
 * like villager speech — and finally to caption-only when no clips exist.
 * Nothing is ever invented.</p>
 *
 * <p>Rendered lines are cached as WAV files, so each line is synthesized once
 * per profile. Rendering runs on a worker thread; the game loop never blocks.
 * The static bridge is bound from {@code Main} and is a caption-only no-op in
 * tests and headless runs.</p>
 */
public final class CharacterVoice {

    /** Minimum gap between two lines from the same speaker id. */
    private static final long MIN_INTERVAL_MILLIS = 7000L;

    /** A repeated line from one speaker inside this window is not replayed. */
    private static final long REPEAT_WINDOW_MILLIS = 4000L;

    private static final String CACHE_PROPERTY = "voxel.voice.cache";

    private static final Map<Integer, Long> lastSpoken = new HashMap<Integer, Long>();
    private static final Map<String, SpokenLine> recentLines =
            new ConcurrentHashMap<String, SpokenLine>();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CharacterVoice");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile VillagerAudioManager audio;
    private static volatile TextToSpeech synthesizer;
    private static volatile boolean probeStarted;
    private static volatile WindowsSapiTts localSynthesizer;

    private CharacterVoice() {
    }

    /** Bind the audio stack and start discovering the machine's local voice. */
    public static void bind(VillagerAudioManager manager) {
        audio = manager;
        startProbe();
    }

    public static void unbind() {
        audio = null;
    }

    public static boolean isBound() {
        return audio != null;
    }

    /** True once a local voice (or an injected test voice) can speak. */
    public static boolean isSynthesizedSpeechAvailable() {
        TextToSpeech tts = synthesizer;
        return tts != null && tts.isAvailable();
    }

    /**
     * Speak a line as a character, rate-limited per speaker id.
     *
     * @return the text that was displayed (authored line, or the clip transcript
     *         on the recorded fallback), or null when rate-limited/blank
     */
    public static String speak(int speakerId, String speaker, String line,
                               VoiceProfile profile) {
        return speak(speakerId, speaker, line, profile, 0f);
    }

    /** Speak a line with an explicit caption reveal time (0 = estimate). */
    public static String speak(int speakerId, String speaker, String line,
                               VoiceProfile profile, float durationSeconds) {
        // A blank line must not start the speaker's cooldown.
        if (line == null || line.trim().isEmpty()) return null;
        if (!allow(speakerId)) return null;
        return say(speakerId, speaker, line, profile, durationSeconds);
    }

    /**
     * Speak a scripted line (a cutscene beat). Scripted beats are never
     * rate-limited — the scene's timing already decided when they happen — but
     * an identical line from the same speaker inside the repeat window is
     * still suppressed so a direct interaction and its cutscene cannot double
     * the same sentence.
     */
    public static String speakScripted(String speaker, String line) {
        return say(speaker == null ? "narrator".hashCode() : speaker.hashCode(),
                speaker, line, VoiceProfile.forSpeaker(speaker), 0f);
    }

    /** Speak a scripted line in an explicit profile. */
    public static String speakScripted(String speaker, String line,
                                       VoiceProfile profile) {
        return say(speaker == null ? "narrator".hashCode() : speaker.hashCode(),
                speaker, line, profile, 0f);
    }

    private static String say(int speakerId, String speaker, String line,
                              VoiceProfile profile, float durationSeconds) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty()) return null;
        String name = speaker == null || speaker.trim().isEmpty()
                ? "Narrator" : speaker.trim();
        VoiceProfile voice = profile == null ? VoiceProfile.NARRATOR : profile;
        if (isRepeat(name, text)) return text;

        float reveal = durationSeconds > 0f
                ? durationSeconds
                : LiveCaptions.estimateDuration(text, voice.options().getEffectiveSpeed());

        // Villagers keep their recorded add-on voice; every other character
        // speaks authored text through a local synthetic voice.
        TextToSpeech tts = voice.usesSynthesis() ? synthesizer() : null;
        if (tts != null && tts.isAvailable()) {
            // The authored line is exactly what the local voice says.
            announce(name, text, reveal);
            renderAndPlay(text, voice, tts);
            return text;
        }

        // Fallback: a real recorded clip, captioned with its own transcript.
        if (audio != null) {
            String spoken = audio.requestSpeech(text, voice.options(), name);
            if (spoken != null) return spoken;
        }

        CaptionSpeech.say(speakerId, name, text, reveal);
        return text;
    }

    private static void announce(String speaker, String text, float revealSeconds) {
        if (audio != null) {
            audio.announceLine(text, speaker, revealSeconds);
        } else {
            LiveCaptions.show(speaker, text, revealSeconds);
        }
    }

    private static void renderAndPlay(final String text, final VoiceProfile profile,
                                      final TextToSpeech tts) {
        WORKER.execute(() -> {
            try {
                Path cacheFile = cacheFile(text, profile);
                WavAudio rendered = null;
                if (Files.isRegularFile(cacheFile)) {
                    try {
                        rendered = WavAudio.read(cacheFile);
                    } catch (IOException unreadable) {
                        rendered = null; // Re-render a corrupted cache entry.
                    }
                }
                if (rendered == null) {
                    rendered = tts.render(text, profile);
                    try {
                        rendered.write(cacheFile);
                    } catch (IOException cacheError) {
                        System.err.println("CharacterVoice: cannot cache line: "
                                + cacheError.getMessage());
                    }
                }
                if (audio != null) {
                    audio.playRendered(rendered, profile.options().getEffectiveVolume());
                }
            } catch (Exception error) {
                System.err.println("CharacterVoice: speech failed for '" + text
                        + "': " + error);
            }
        });
    }

    private static TextToSpeech synthesizer() {
        TextToSpeech injected = synthesizer;
        if (injected != null) return injected;
        WindowsSapiTts local = localSynthesizer;
        if (local == null) return null;
        return local;
    }

    private static void startProbe() {
        if (probeStarted) return;
        synchronized (CharacterVoice.class) {
            if (probeStarted) return;
            probeStarted = true;
            WORKER.execute(() -> {
                WindowsSapiTts candidate = new WindowsSapiTts(cacheDirectory());
                if (candidate.isAvailable()) {
                    localSynthesizer = candidate;
                    System.out.println("CharacterVoice: local speech ready ("
                            + candidate.resolveVoice(VoiceProfile.ANCIENT_BUILDER)
                            + " for builders)");
                }
            });
        }
    }

    private static boolean allow(int speakerId) {
        long now = System.currentTimeMillis();
        Long last = lastSpoken.get(speakerId);
        if (last != null && now - last < MIN_INTERVAL_MILLIS) return false;
        lastSpoken.put(speakerId, now);
        return true;
    }

    private static boolean isRepeat(String speaker, String text) {
        String key = speaker + "\u0000" + text;
        long now = System.currentTimeMillis();
        SpokenLine previous = recentLines.get(key);
        if (previous != null && now - previous.atMillis < REPEAT_WINDOW_MILLIS) {
            return true;
        }
        recentLines.put(key, new SpokenLine(now));
        if (recentLines.size() > 256) {
            recentLines.clear();
        }
        return false;
    }

    private static Path cacheFile(String text, VoiceProfile profile) throws IOException {
        Path directory = cacheDirectory().resolve("characters");
        Files.createDirectories(directory);
        return directory.resolve(hash(profile.name() + "\u0000" + text) + ".wav");
    }

    private static Path cacheDirectory() {
        String configured = System.getProperty(CACHE_PROPERTY);
        Path root = configured != null && !configured.trim().isEmpty()
                ? Paths.get(configured.trim())
                : Paths.get("dev", "voice-cache");
        return root.resolve("tts");
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception unavailable) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Clears rate limits and repeat memory (tests / fresh scenes). */
    public static void resetForTest() {
        lastSpoken.clear();
        recentLines.clear();
        audio = null;
        synthesizer = null;
        localSynthesizer = null;
        probeStarted = true; // tests never spawn a real voice process
    }

    /** Inject a deterministic voice for tests, bypassing the OS probe. */
    public static void setSynthesizerForTest(TextToSpeech tts) {
        synthesizer = tts;
        probeStarted = true;
    }

    private static final class SpokenLine {
        final long atMillis;

        SpokenLine(long atMillis) {
            this.atMillis = atMillis;
        }
    }
}
