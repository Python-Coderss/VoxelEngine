package villager.voice;

/**
 * The neural base TTS backend whose output is immediately converted by the
 * custom RVC villager model. The only implementation is the Coqui VCTK VITS
 * path ({@link CoquiVitsTts}); it runs fully in-process with no Python,
 * eSpeak, subprocess, or network access.
 */
public interface NeuralBaseTts extends AutoCloseable {
    /** Synthesize text to raw mono audio; speed &gt; 1 makes speech slower. */
    WavAudio synthesize(String text, double speed);

    @Override
    void close() throws Exception;
}
