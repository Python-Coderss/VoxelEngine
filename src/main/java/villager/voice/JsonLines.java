package villager.voice;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON support for [{"id":"...","text":"..."}] line files. */
public final class JsonLines {
    public static final class Entry {
        public final String id;
        public final String text;

        public Entry(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private JsonLines() {
    }

    public static List<Entry> read(Path path) throws IOException {
        String json = new String(Files.readAllBytes(path), Charset.forName("UTF-8"));
        List<Entry> entries = new ArrayList<Entry>();
        int position = 0;
        while (position < json.length()) {
            int objectStart = json.indexOf('{', position);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = findObjectEnd(json, objectStart);
            if (objectEnd < 0) {
                throw new IOException("Malformed JSON object in " + path);
            }
            Map<String, String> fields = parseObject(json.substring(objectStart, objectEnd + 1));
            String id = fields.get("id");
            String text = fields.get("text");
            if (id == null || text == null) {
                throw new IOException("Each line entry needs string fields id and text: " + path);
            }
            entries.add(new Entry(id, text));
            position = objectEnd + 1;
        }
        return entries;
    }

    public static void writeIndex(Path path, Map<String, String> index) throws IOException {
        StringBuilder json = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (count++ > 0) {
                json.append(",\n");
            }
            json.append("  \"").append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append("\"");
        }
        json.append("\n}\n");
        Files.write(path, json.toString().getBytes(Charset.forName("UTF-8")));
    }

    private static Map<String, String> parseObject(String object) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        int i = 1;
        while (i < object.length() - 1) {
            i = skipWhitespaceAndCommas(object, i);
            if (i >= object.length() - 1) {
                break;
            }
            if (object.charAt(i) != '\"') {
                throw new IOException("Expected JSON string key");
            }
            String key = parseString(object, i);
            i = stringEnd(object, i) + 1;
            i = skipWhitespace(object, i);
            if (i >= object.length() || object.charAt(i) != ':') {
                throw new IOException("Expected ':' after JSON key");
            }
            i = skipWhitespace(object, i + 1);
            if (i >= object.length() || object.charAt(i) != '\"') {
                throw new IOException("Expected JSON string value");
            }
            String value = parseString(object, i);
            i = stringEnd(object, i) + 1;
            result.put(key, value);
        }
        return result;
    }

    private static String parseString(String text, int start) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\"') {
                return result.toString();
            }
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (++i >= text.length()) {
                throw new IOException("Unterminated JSON escape");
            }
            c = text.charAt(i);
            switch (c) {
                case '\"': result.append('\"'); break;
                case '\\': result.append('\\'); break;
                case '/': result.append('/'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (i + 4 >= text.length()) {
                        throw new IOException("Short unicode escape");
                    }
                    try {
                        result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid unicode escape", e);
                    }
                    i += 4;
                    break;
                default:
                    throw new IOException("Unsupported JSON escape: \\" + c);
            }
        }
        throw new IOException("Unterminated JSON string");
    }

    private static int stringEnd(String text, int start) throws IOException {
        boolean escaped = false;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\"' && !escaped) {
                return i;
            }
            if (c == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        throw new IOException("Unterminated JSON string");
    }

    private static int findObjectEnd(String json, int start) {
        boolean escaped = false;
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"' && !escaped) {
                inString = !inString;
            }
            if (c == '}' && !inString) {
                return i;
            }
            if (c == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        return -1;
    }

    private static int skipWhitespaceAndCommas(String text, int i) {
        while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
            i++;
        }
        return i;
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
