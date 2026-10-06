package villager.voice;

/** Selects the playback backend used by the standalone voice and game bridge. */
public enum VoiceMode {
    /** Best-matching recorded clips (Villager News addon + TEAVSRP corpus). */
    CLIP,
    /** Exact transcript-named corpus replay, for corpus validation. */
    REFERENCE;

    public static VoiceMode fromProperty() {
        return parse(System.getProperty("voxel.voice.mode", "clip"));
    }

    public static VoiceMode parse(String value) {
        if (value == null) {
            return CLIP;
        }
        if ("reference".equalsIgnoreCase(value) || "corpus".equalsIgnoreCase(value)
                || "teavrsp".equalsIgnoreCase(value)) {
            return REFERENCE;
        }
        if ("clip".equalsIgnoreCase(value) || "clips".equalsIgnoreCase(value)
                || "recorded".equalsIgnoreCase(value) || "addon".equalsIgnoreCase(value)) {
            return CLIP;
        }
        if ("neural".equalsIgnoreCase(value) || "rvc".equalsIgnoreCase(value)
                || "coqui".equalsIgnoreCase(value) || "kokoro".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("neural synthesis has been removed;"
                    + " voice plays recorded clips only (mode: clip or reference)");
        }
        throw new IllegalArgumentException("voice mode must be clip or reference: " + value);
    }
}
