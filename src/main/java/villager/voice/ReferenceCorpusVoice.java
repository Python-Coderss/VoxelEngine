package villager.voice;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Transcript-indexed reference voice for corpus validation.
 *
 * This backend does not train or synthesize: for a line whose transcript is
 * present in the supplied corpus it returns the recorded clip itself. Lines
 * not present in the corpus raise a clear error, so the CLI can be used to
 * verify exactly which corpus lines are covered. The reference directory is
 * intentionally external/ignored and can be replaced with a licensed corpus.
 */
public final class ReferenceCorpusVoice implements AutoCloseable {
    public static final String REFERENCE_PROPERTY = "voxel.voice.reference";
    public static final Path DEFAULT_REFERENCE = Paths.get(
            "voice", "corpus", "assets", "minecraft", "sounds", "mob", "villager");

    private final Path directory;
    private final Map<String, Path> clips = new HashMap<String, Path>();

    public ReferenceCorpusVoice() throws IOException {
        this(referenceDirectory());
    }

    public ReferenceCorpusVoice(Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("reference directory must not be null");
        }
        this.directory = directory.toAbsolutePath().normalize();
        load();
        if (clips.isEmpty()) {
            throw new IOException("No WAV reference clips found in " + this.directory
                    + ". Rename clips to their transcripts and use .wav files.");
        }
    }

    public Path getDirectory() {
        return directory;
    }

    public int getClipCount() {
        return clips.size();
    }

    public boolean hasExactClip(String text) {
        return clips.containsKey(normalize(text));
    }

    public WavAudio render(String text, SpeechOptions options) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        Path clip = clips.get(normalize(text));
        if (clip == null) {
            throw new IOException("No corpus clip for line: " + text
                    + " (reference mode replays exact transcript-named clips only)");
        }

        WavAudio source = WavAudio.read(clip).resampled(VillagerSynthesizer.DEFAULT_SAMPLE_RATE);
        float[] samples = source.samples.clone();
        if (options.getSpeed() != 1.0) {
            int length = Math.max(1, (int) Math.round(samples.length / options.getSpeed()));
            samples = AudioDsp.resample(samples, length);
        }
        AudioDsp.applyToneTilt(samples, options.getTone());
        AudioDsp.applyGain(samples, options.getVolume());
        AudioDsp.normalizePeak(samples, 0.98f);
        AudioDsp.fadeEdges(samples, Math.min(source.sampleRate / 80, samples.length / 5));
        return new WavAudio(VillagerSynthesizer.DEFAULT_SAMPLE_RATE, samples);
    }

    private void load() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.wav")) {
            for (Path file : files) {
                String key = normalize(removeExtension(file.getFileName().toString()));
                if (!key.isEmpty()) {
                    clips.put(key, file);
                }
            }
        }
    }

    private static Path referenceDirectory() {
        String configured = System.getProperty(REFERENCE_PROPERTY);
        return configured == null || configured.trim().isEmpty()
                ? DEFAULT_REFERENCE : Paths.get(configured);
    }

    /** Normalize transcript filenames and caller text to the same comparison key. */
    static String normalize(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replace("’", "")
                .replace("...", " ");
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space && result.length() > 0) result.append(' ');
                result.append(c);
                space = false;
            } else {
                space = true;
            }
        }
        return result.toString().trim();
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    @Override
    public void close() {
        // No native resources; the clips are plain files.
    }
}
