package villager.voice;

/**
 * Renders arbitrary authored text to speech with a local operating-system
 * voice.
 *
 * <p>The engine ships no neural model and calls no network service: the only
 * implementations are thin wrappers around the voices the player's machine
 * already has installed (Windows SAPI, for example). When no local voice is
 * available, callers fall back to the recorded clip corpus, so the game never
 * becomes dependent on synthesis.</p>
 */
public interface TextToSpeech {

    /** True when this renderer can actually produce audio on this machine. */
    boolean isAvailable();

    /**
     * Render one line in the profile's voice.
     *
     * @throws Exception when the local voice is missing or the render fails
     */
    WavAudio render(String text, VoiceProfile profile) throws Exception;
}
