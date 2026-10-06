package villager.voice;

/**
 * Renders a villager line for the game runtime and the CLI.
 *
 * <p>There is no speech synthesis. A line is resolved to the recorded clip
 * whose transcript best matches it — the Villager News addon voice (Element
 * Animation) and the TEAVSRP corpus — and shaped for playback by
 * {@link ClipVoice}. Reference mode replays exact transcript-named corpus
 * clips instead, so it doubles as a corpus coverage check.</p>
 */
public final class VillagerVoiceRenderer implements AutoCloseable {
    public static final int DEFAULT_SAMPLE_RATE = ClipVoice.DEFAULT_SAMPLE_RATE;

    private final VoiceMode mode;
    private final ClipVoice clipVoice;
    private final ReferenceCorpusVoice referenceVoice;

    public VillagerVoiceRenderer() throws Exception {
        this.mode = VoiceMode.fromProperty();
        if (mode == VoiceMode.REFERENCE) {
            clipVoice = null;
            referenceVoice = new ReferenceCorpusVoice();
        } else {
            clipVoice = new ClipVoice(ClipLibrary.openDefault());
            referenceVoice = null;
        }
    }

    public VoiceMode getMode() {
        return mode;
    }

    /** Backwards-compatible render using the default voice profile. */
    public synchronized WavAudio render(String text, double speed, double pitchSemitones)
            throws Exception {
        return render(text, new SpeechOptions(speed, pitchSemitones));
    }

    /** Play one line as the best-matching recorded clip. */
    public synchronized WavAudio render(String text, SpeechOptions options)
            throws Exception {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        // Legacy/direct callers do not carry catalog metadata, so punctuation
        // supplies the natural question cue without changing ordinary lines.
        SpeechOptions effectiveOptions = options.isQuestion()
                || !options.allowsAutomaticQuestionDetection()
                || !SpeechOptions.looksLikeQuestion(text)
                ? options : options.withQuestion(true);
        if (mode == VoiceMode.REFERENCE) {
            return referenceVoice.render(text, effectiveOptions);
        }
        return clipVoice.render(text, effectiveOptions);
    }

    @Override
    public void close() throws Exception {
        if (mode == VoiceMode.REFERENCE) {
            referenceVoice.close();
        } else {
            clipVoice.close();
        }
    }
}
