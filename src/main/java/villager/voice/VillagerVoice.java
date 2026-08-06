package villager.voice;

import java.nio.file.Path;

/**
 * Public game API for the custom villager voice.
 *
 * Construct once during game startup, reuse for every dialogue line, and call
 * close() during shutdown. Synthesis is CPU-heavy and blocking; run speak()
 * on a worker thread, then send the returned VoiceClip to the audio thread.
 */
public final class VillagerVoice implements AutoCloseable {
    private final VillagerSynthesizer synthesizer;
    private boolean closed;

    /** Load the default model bundle from models/java. */
    public VillagerVoice() throws Exception {
        this(new VillagerSynthesizer());
    }

    /** Load a model bundle from an explicit directory. */
    public VillagerVoice(Path modelDirectory) throws Exception {
        this(new VillagerSynthesizer(modelDirectory));
    }

    private VillagerVoice(VillagerSynthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    /** Backend currently used by this voice instance. */
    public VoiceMode getMode() {
        return synthesizer.getMode();
    }

    /** Synthesize with normal speed and pitch settings. */
    public VoiceClip speak(String text) throws Exception {
        return speak(text, SpeechOptions.DEFAULT);
    }

    /** Synthesize with per-line speed and pitch settings. */
    public synchronized VoiceClip speak(String text, SpeechOptions options) throws Exception {
        ensureOpen();
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        WavAudio audio = synthesizer.render(text, options);
        return new VoiceClip(audio);
    }

    /** Convenience overload for callers that do not need SpeechOptions. */
    public VoiceClip speak(String text, double speed, double pitchSemitones) throws Exception {
        return speak(text, new SpeechOptions(speed, pitchSemitones));
    }


    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("VillagerVoice is already closed");
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (!closed) {
            closed = true;
            synthesizer.close();
        }
    }
}
