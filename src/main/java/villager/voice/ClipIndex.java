package villager.voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The recorded-clip index: every playable villager voice clip (Villager News
 * addon oggs + TEAVSRP corpus wavs) with its transcript. There is no speech
 * synthesis anymore — a spoken line is played back from the recorded clip that
 * best matches its words.
 *
 * <p>Built by {@code tools/build_voice_clip_index.py} into
 * {@code src/main/resources/voice/clips_index.json}. Pure selection logic,
 * no audio decoding, unit-testable.</p>
 */
public final class ClipIndex {

    public static final String INDEX_PROPERTY = "voxel.voice.index";

    /** One recorded clip. {@code text} is empty for pure vocalizations. */
    public static final class Clip {
        public final String id;
        /** "addon" (Villager News ogg) or "teavrsp" (corpus wav). */
        public final String kind;
        public final String file;
        /** Exact transcript of the recording — the only text ever captioned. */
        public final String text;
        public final float duration;
        /** Addon subtitle cue times in seconds, ascending. */
        public final float[] cueTimes;
        /** Cue fragments; {@code text} is their space-joined concatenation. */
        public final String[] cueTexts;
        final String[] tokens;

        Clip(String id, String kind, String file, String text, float duration,
             float[] cueTimes, String[] cueTexts) {
            this.id = id;
            this.kind = kind;
            this.file = file;
            this.text = text == null ? "" : text;
            this.duration = duration;
            this.cueTimes = cueTimes == null ? new float[0] : cueTimes;
            this.cueTexts = cueTexts == null ? new String[0] : cueTexts;
            this.tokens = tokens(this.text);
        }

        public boolean isVocalization() {
            return tokens.length == 0;
        }

        /**
         * Caption text visible after {@code elapsedSeconds} of playback,
         * revealed fragment by fragment on the addon's own cue timing.
         */
        public String revealedText(float elapsedSeconds) {
            if (cueTimes.length == 0) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < cueTimes.length; i++) {
                if (cueTimes[i] > elapsedSeconds) break;
                if (out.length() > 0) out.append(' ');
                out.append(cueTexts[i]);
            }
            return out.toString();
        }
    }

    private final List<Clip> clips;
    private final List<Clip> spoken;
    private final List<Clip> vocal;

    ClipIndex(List<Clip> clips) {
        this.clips = Collections.unmodifiableList(new ArrayList<>(clips));
        List<Clip> s = new ArrayList<>();
        List<Clip> v = new ArrayList<>();
        for (Clip clip : this.clips) {
            if (clip.isVocalization()) v.add(clip); else s.add(clip);
        }
        this.spoken = Collections.unmodifiableList(s);
        this.vocal = Collections.unmodifiableList(v);
    }

    /** Load from the project resources, with the file path as a dev fallback. */
    public static ClipIndex loadDefault() throws IOException {
        String configured = System.getProperty(INDEX_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return parse(new String(Files.readAllBytes(Paths.get(configured)),
                    StandardCharsets.UTF_8));
        }
        Path dev = Paths.get("src", "main", "resources", "voice", "clips_index.json");
        if (Files.isRegularFile(dev)) {
            return parse(new String(Files.readAllBytes(dev), StandardCharsets.UTF_8));
        }
        InputStream in = ClipIndex.class.getResourceAsStream("/voice/clips_index.json");
        if (in == null) {
            throw new IOException("voice clip index not found (voice/clips_index.json)");
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
    }

    /** Parse a clips_index.json document. */
    public static ClipIndex parse(String json) {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.getJSONArray("clips");
        List<Clip> clips = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            String text = entry.optString("text", "");
            JSONArray cues = entry.optJSONArray("cues");
            float[] cueTimes;
            String[] cueTexts;
            if (cues != null && cues.length() > 0) {
                cueTimes = new float[cues.length()];
                cueTexts = new String[cues.length()];
                for (int c = 0; c < cues.length(); c++) {
                    JSONArray cue = cues.getJSONArray(c);
                    cueTimes[c] = (float) cue.optDouble(0, 0.0);
                    cueTexts[c] = cue.optString(1, "");
                }
            } else {
                cueTimes = text.isEmpty() ? new float[0] : new float[]{0f};
                cueTexts = text.isEmpty() ? new String[0] : new String[]{text};
            }
            clips.add(new Clip(
                    entry.optString("id", "clip:" + i),
                    entry.optString("kind", "addon"),
                    entry.getString("file"),
                    text,
                    (float) entry.optDouble("duration", 0.0),
                    cueTimes, cueTexts));
        }
        return new ClipIndex(clips);
    }

    public List<Clip> clips() { return clips; }
    public List<Clip> spokenClips() { return spoken; }
    public List<Clip> vocalClips() { return vocal; }
    public int size() { return clips.size(); }

    /** Exact transcript hit (normalized), or null. */
    public Clip exact(String text) {
        String key = normalize(text);
        if (key.isEmpty()) return null;
        for (Clip clip : spoken) {
            if (normalize(clip.text).equals(key)) return clip;
        }
        return null;
    }

    /**
     * Pick the recorded clip that best matches {@code text}: exact transcript
     * first, then most shared words (favoring clips that stay on topic), then
     * a random vocalization so even unmatched lines speak in character.
     * Recently used ids in {@code avoidIds} are skipped when possible so a
     * village does not loop one recording.
     */
    public Clip bestMatch(String text, Random rng, List<String> avoidIds) {
        Set<String> avoid = avoidIds == null ? Collections.<String>emptySet()
                : new HashSet<>(avoidIds);
        Clip exact = exact(text);
        if (exact != null && !avoid.contains(exact.id)) return exact;

        String[] query = tokens(text);
        Clip best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Clip bestAvoided = null;
        double bestAvoidedScore = Double.NEGATIVE_INFINITY;
        for (Clip clip : spoken) {
            int overlap = overlap(query, clip.tokens);
            if (overlap == 0) continue;
            double score = overlap - 0.03 * clip.tokens.length;
            if (avoid.contains(clip.id)) {
                if (score > bestAvoidedScore) {
                    bestAvoidedScore = score;
                    bestAvoided = clip;
                }
            } else if (score > bestScore) {
                bestScore = score;
                best = clip;
            }
        }
        if (best != null) return best;

        // A fresh vocalization beats looping the same recording again; only
        // replay the avoided clip when nothing else exists at all.
        List<Clip> pool = new ArrayList<>();
        for (Clip clip : vocal) {
            if (!avoid.contains(clip.id)) pool.add(clip);
        }
        if (!pool.isEmpty()) {
            return pool.get(rng == null ? 0 : Math.floorMod(rng.nextInt(), pool.size()));
        }
        if (bestAvoided != null) return bestAvoided;
        pool.addAll(vocal);
        if (pool.isEmpty()) pool.addAll(spoken);
        if (pool.isEmpty()) return null;
        return pool.get(rng == null ? 0 : Math.floorMod(rng.nextInt(), pool.size()));
    }

    /** Words of a line, lowercased and stripped of punctuation. */
    static String[] tokens(String text) {
        String normalized = normalize(text);
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }

    /** Normalize transcript filenames and caller text to one comparison key. */
    static String normalize(String text) {
        return ReferenceCorpusVoice.normalize(text);
    }

    private static int overlap(String[] query, String[] clip) {
        int total = 0;
        for (String q : query) {
            for (String c : clip) {
                if (q.equals(c)) {
                    total++;
                    break;
                }
            }
        }
        return total;
    }
}
