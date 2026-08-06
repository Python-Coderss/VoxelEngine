package com.voxel.audio;

import com.voxel.entity.VillagerEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import villager.voice.SpeechOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Editable dialogue catalog. The game deliberately keeps its built-in lines in
 * VillagerDialogue and overlays this catalog when it is present, so a bad or
 * missing external file never makes the game unable to talk.
 */
public final class DialogueCatalog {
    public static final String PROPERTY = "voxel.voice.dialogue";
    public static final Path DEFAULT_PATH = Paths.get("dev", "dialogue.json");
    public static final String ANY = "*";

    private final List<DialogueLine> lines;

    public DialogueCatalog(List<DialogueLine> lines) {
        if (lines == null) {
            throw new IllegalArgumentException("lines must not be null");
        }
        this.lines = Collections.unmodifiableList(new ArrayList<DialogueLine>(lines));
    }

    public static DialogueCatalog empty() {
        return new DialogueCatalog(Collections.<DialogueLine>emptyList());
    }

    /** Load a catalog, returning an empty catalog and logging a warning on failure. */
    public static DialogueCatalog loadDefault() {
        String configured = System.getProperty(PROPERTY);
        Path path = configured == null || configured.trim().isEmpty()
                ? DEFAULT_PATH : Paths.get(configured);
        if (Files.isRegularFile(path)) {
            try {
                return load(path);
            } catch (Exception error) {
                System.err.println("DialogueCatalog: ignoring " + path + ": " + error.getMessage());
            }
        }
        try (java.io.InputStream input = DialogueCatalog.class.getClassLoader()
                .getResourceAsStream("dialogue.json")) {
            if (input != null) {
                byte[] bytes = readAll(input);
                return fromJson(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            System.err.println("DialogueCatalog: packaged catalog unavailable: " + error.getMessage());
        }
        return empty();
    }

    public static DialogueCatalog load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return fromJson(json);
    }

    private static DialogueCatalog fromJson(String json) throws IOException {
        try {
            JSONArray array = new JSONArray(json);
            List<DialogueLine> result = new ArrayList<DialogueLine>();
            for (int i = 0; i < array.length(); i++) {
                result.add(parse(array.getJSONObject(i), i));
            }
            return new DialogueCatalog(result);
        } catch (JSONException error) {
            throw new IOException("expected a JSON array of dialogue objects", error);
        }
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public void save(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        JSONArray array = new JSONArray();
        for (DialogueLine line : lines) {
            array.put(toJson(line));
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, (array.toString(2) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8));
    }

    public List<DialogueLine> getLines() {
        return lines;
    }

    /**
     * Select an authored line using exact context first, then wildcard context.
     * A deterministic variant seed keeps interactions repeatable.
     */
    public DialogueLine choose(VillagerEntity villager, String period, int interactionIndex) {
        if (villager == null || lines.isEmpty()) {
            return null;
        }
        return choose(villager.getProfession().name(), period, interactionIndex);
    }

    /** Select by profession for tooling and tests that do not have a live entity. */
    public DialogueLine choose(String profession, String period, int interactionIndex) {
        if (profession == null || profession.trim().isEmpty() || lines.isEmpty()) {
            return null;
        }
        String selectedProfession = profession.trim();
        String selectedPeriod = period == null ? ANY : period;
        List<DialogueLine> candidates = matching(selectedProfession, selectedPeriod);
        if (candidates.isEmpty()) {
            return null;
        }
        int seed = interactionIndex * 17 + selectedPeriod.hashCode() * 7
                + selectedProfession.hashCode() * 31;
        return candidates.get(Math.floorMod(seed, candidates.size()));
    }

    private List<DialogueLine> matching(String profession, String period) {
        List<DialogueLine> exact = new ArrayList<DialogueLine>();
        List<DialogueLine> professionOnly = new ArrayList<DialogueLine>();
        List<DialogueLine> periodOnly = new ArrayList<DialogueLine>();
        List<DialogueLine> global = new ArrayList<DialogueLine>();
        for (DialogueLine line : lines) {
            boolean professionMatch = matches(line.getProfession(), profession);
            boolean periodMatch = matches(line.getPeriod(), period);
            if (professionMatch && periodMatch
                    && !ANY.equals(line.getProfession()) && !ANY.equals(line.getPeriod())) {
                exact.add(line);
            } else if (!ANY.equals(line.getProfession()) && professionMatch
                    && ANY.equals(line.getPeriod())) {
                professionOnly.add(line);
            } else if (ANY.equals(line.getProfession()) && periodMatch
                    && !ANY.equals(line.getPeriod())) {
                periodOnly.add(line);
            } else if (ANY.equals(line.getProfession()) && ANY.equals(line.getPeriod())) {
                global.add(line);
            }
        }
        if (!exact.isEmpty()) return exact;
        if (!professionOnly.isEmpty()) return professionOnly;
        if (!periodOnly.isEmpty()) return periodOnly;
        return global;
    }

    private static boolean matches(String value, String expected) {
        return ANY.equals(value) || value.equalsIgnoreCase(expected);
    }

    private static DialogueLine parse(JSONObject json, int index) {
        String id = json.optString("id", "line_" + index);
        String text = json.optString("text", "").trim();
        String profession = json.optString("profession", ANY).trim().toUpperCase(Locale.ROOT);
        String period = json.optString("period", ANY).trim().toUpperCase(Locale.ROOT);
        int variant = Math.max(0, json.optInt("variant", index));
        JSONObject voice = json.optJSONObject("voice");
        if (voice == null) voice = json;
        boolean questionPresent = voice.has("question");
        boolean question = questionPresent
                ? voice.optBoolean("question", false)
                : SpeechOptions.looksLikeQuestion(text);
        SpeechOptions options = new SpeechOptions(
                voice.optDouble("speed", 1.0),
                voice.optDouble("pitch", 0.0),
                voice.optDouble("volume", 1.0),
                voice.optDouble("tone", 0.0),
                voice.optDouble("naturalSourceMix", 0.36),
                voice.optString("emotion", "happy"),
                voice.optDouble("singing", 0.0),
                voice.optDouble("sarcasm", 0.0), question);
        return new DialogueLine(id, text, profession, period, variant,
                questionPresent ? options : options.withAutomaticQuestionDetection());
    }

    private static JSONObject toJson(DialogueLine line) {
        SpeechOptions options = line.getOptions();
        JSONObject voice = new JSONObject();
        voice.put("speed", options.getSpeed());
        voice.put("pitch", options.getPitchSemitones());
        voice.put("volume", options.getVolume());
        voice.put("tone", options.getTone());
        voice.put("naturalSourceMix", options.getNaturalSourceMix());
        voice.put("emotion", options.getEmotion());
        voice.put("singing", options.getSinging());
        voice.put("sarcasm", options.getSarcasm());
        voice.put("question", options.isQuestion());

        JSONObject json = new JSONObject();
        json.put("id", line.getId());
        json.put("text", line.getText());
        json.put("profession", line.getProfession());
        json.put("period", line.getPeriod());
        json.put("variant", line.getVariant());
        json.put("voice", voice);
        return json;
    }
}
