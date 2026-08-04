package villager.voice;

/** Selects the synthesis backend used by the standalone voice and game bridge. */
public enum VoiceMode {
    NEURAL,
    FORMANT,
    REFERENCE;

    public static VoiceMode fromProperty() {
        return parse(System.getProperty("voxel.voice.mode", "neural"));
    }

    public static VoiceMode parse(String value) {
        if (value == null) {
            return NEURAL;
        }
        if ("formant".equalsIgnoreCase(value) || "original".equalsIgnoreCase(value)) {
            return FORMANT;
        }
        if ("reference".equalsIgnoreCase(value) || "corpus".equalsIgnoreCase(value)
                || "teavrsp".equalsIgnoreCase(value)) {
            return REFERENCE;
        }
        if ("neural".equalsIgnoreCase(value) || "rvc".equalsIgnoreCase(value)) {
            return NEURAL;
        }
        throw new IllegalArgumentException("voice mode must be neural, formant, or reference: " + value);
    }
}
