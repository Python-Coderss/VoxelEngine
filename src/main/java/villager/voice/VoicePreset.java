package villager.voice;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Named, editable voice settings persisted as a small human-readable JSON file. */
public final class VoicePreset {
    private final String name;
    private final SpeechOptions options;

    public VoicePreset(String name, SpeechOptions options) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        this.name = name.trim();
        this.options = options;
    }

    public String getName() {
        return name;
    }

    public SpeechOptions getOptions() {
        return options;
    }

    public void save(Path path) throws IOException {
        String json = String.format(Locale.ROOT,
                "{\n  \"name\": \"%s\",\n  \"speed\": %.6f,\n  \"pitch\": %.6f,\n  \"volume\": %.6f,\n  \"tone\": %.6f,\n  \"naturalSourceMix\": %.6f,\n  \"emotion\": \"%s\",\n  \"singing\": %.6f\n}\n",
                escape(name), options.getSpeed(), options.getPitchSemitones(),
                options.getVolume(), options.getTone(), options.getNaturalSourceMix(),
                escape(options.getEmotion()), options.getSinging());
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, json.getBytes(Charset.forName("UTF-8")));
    }

    public static VoicePreset load(Path path) throws IOException {
        String json = new String(Files.readAllBytes(path), Charset.forName("UTF-8"));
        String name = stringValue(json, "name", "Voice preset");
        double speed = numberValue(json, "speed", 1.0);
        double pitch = numberValue(json, "pitch", 0.0);
        double volume = numberValue(json, "volume", 1.0);
        double tone = numberValue(json, "tone", -0.10);
        double natural = numberValue(json, "naturalSourceMix", 0.36);
        String emotion = stringValue(json, "emotion", "neutral");
        double singing = numberValue(json, "singing", 1.0);
        return new VoicePreset(name, new SpeechOptions(speed, pitch, volume, tone, natural,
                emotion, singing));
    }

    private static String stringValue(String json, String key, String fallback) {
        String marker = "\"" + key + "\"";
        int keyStart = json.indexOf(marker);
        if (keyStart < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', keyStart + marker.length());
        if (colon < 0) {
            return fallback;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return fallback;
        }
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    default: result.append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return result.toString();
            } else {
                result.append(c);
            }
        }
        return fallback;
    }

    private static double numberValue(String json, String key, double fallback) {
        String marker = "\"" + key + "\"";
        int colon = json.indexOf(marker);
        colon = colon < 0 ? -1 : json.indexOf(':', colon + marker.length());
        if (colon < 0) {
            return fallback;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && "-+.0123456789eE".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
