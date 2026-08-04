package villager.voice;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hand-authored pronunciation guide for the VITS token vocabulary.
 *
 * This class deliberately does not call eSpeak or any other phonemizer. The
 * representation column is yours to edit; it is only a notation-to-IPA map.
 * Input syntax is phoneme-phoneme per word, with spaces between words.
 */
public final class PhonemeGuide {
    public static final class Entry {
        private final String symbol;
        private final int tokenId;
        private String representation;
        private String description;

        private Entry(String symbol, int tokenId, String representation, String description) {
            this.symbol = symbol;
            this.tokenId = tokenId;
            this.representation = representation;
            this.description = description;
        }

        public String getSymbol() {
            return symbol;
        }

        public int getTokenId() {
            return tokenId;
        }

        public String getRepresentation() {
            return representation;
        }

        public void setRepresentation(String representation) {
            this.representation = representation == null ? "" : representation.trim();
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description.trim();
        }
    }

    private final List<Entry> entries;
    private final Map<String, Entry> bySymbol;
    private final Map<String, Entry> byRepresentation;

    private PhonemeGuide(List<Entry> entries) {
        this.entries = entries;
        this.bySymbol = new LinkedHashMap<String, Entry>();
        this.byRepresentation = new LinkedHashMap<String, Entry>();
        rebuildIndexes();
    }

    /** Load the model vocabulary and an optional user-edited TSV map. */
    public static PhonemeGuide load(Path tokens, Path mapping) throws IOException {
        if (tokens == null || !Files.isRegularFile(tokens)) {
            throw new IOException("Missing token vocabulary: " + tokens);
        }
        List<Entry> entries = new ArrayList<Entry>();
        for (String line : Files.readAllLines(tokens, Charset.forName("UTF-8"))) {
            if (line.length() == 0) {
                continue;
            }
            String symbol;
            String idText;
            if (line.charAt(0) == ' ') {
                symbol = " ";
                idText = line.trim();
            } else {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) {
                    continue;
                }
                symbol = parts[0];
                idText = parts[1];
            }
            try {
                int tokenId = Integer.parseInt(idText.trim());
                entries.add(new Entry(symbol, tokenId, defaultRepresentation(symbol),
                        defaultDescription(symbol)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed vocabulary lines rather than making the editor unusable.
            }
        }
        PhonemeGuide guide = new PhonemeGuide(entries);
        if (mapping != null && Files.isRegularFile(mapping)) {
            guide.readMapping(mapping);
        }
        guide.rebuildIndexes();
        return guide;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public Entry findByRepresentation(String representation) {
        rebuildIndexes();
        return byRepresentation.get(representation);
    }

    /** Rebuild lookup indexes after the editor changes a representation. */
    public void reindex() {
        rebuildIndexes();
    }

    /** Convert hand-authored dash-separated notation into IPA characters. */
    public String toIpaSequence(String notation) {
        List<Segment> segments = parse(notation);
        StringBuilder result = new StringBuilder();
        String[] words = notation.trim().split("\\s+");
        for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
            if (wordIndex > 0) {
                result.append(' ');
            }
            for (Segment segment : segmentsForWord(words[wordIndex])) {
                result.append(segment.ipa);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one phoneme");
        }
        return result.toString();
    }

    /** Return an English-readable token report, including symbol and token ID. */
    public String inspect(String notation) {
        List<Segment> segments = parse(notation);
        StringBuilder result = new StringBuilder();
        String[] words = notation.trim().split("\\s+");
        for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
            if (wordIndex > 0) {
                result.append('\n');
            }
            result.append("word ").append(wordIndex + 1).append(": ")
                    .append(words[wordIndex]).append('\n');
            List<Segment> wordSegments = segmentsForWord(words[wordIndex]);
            for (Segment segment : wordSegments) {
                result.append("  ").append(segment.input).append(" -> ");
                int[] codePoints = segment.ipa.codePoints().toArray();
                for (int codePoint : codePoints) {
                    String symbol = new String(Character.toChars(codePoint));
                    Entry entry = bySymbol.get(symbol);
                    if (entry == null) {
                        result.append(symbol).append(" [?]");
                    } else {
                        result.append(symbol).append(" [token ").append(entry.tokenId)
                                .append(", ").append(entry.description).append("]");
                    }
                    result.append(' ');
                }
                result.append('\n');
            }
        }
        result.append("IPA: ").append(toIpaSequence(notation)).append('\n');
        result.append("tokens: ");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                result.append(' ');
            }
            Segment segment = segments.get(i);
            for (int codePoint : segment.ipa.codePoints().toArray()) {
                String symbol = new String(Character.toChars(codePoint));
                Entry entry = bySymbol.get(symbol);
                result.append(symbol).append('(')
                        .append(entry == null ? "?" : Integer.toString(entry.tokenId))
                        .append(')');
            }
        }
        return result.toString();
    }

    /** Save the current representation/description assignments as editable TSV. */
    public void save(Path mapping) throws IOException {
        Path parent = mapping.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder output = new StringBuilder();
        output.append("# IPA symbol\\trepresentation\\tEnglish description\n");
        for (Entry entry : entries) {
            if (entry.symbol.equals(" ")) {
                continue;
            }
            output.append(escape(entry.symbol)).append('\t')
                    .append(escape(entry.representation)).append('\t')
                    .append(escape(entry.description)).append('\n');
        }
        Files.write(mapping, output.toString().getBytes(Charset.forName("UTF-8")),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void readMapping(Path mapping) throws IOException {
        for (String line : Files.readAllLines(mapping, Charset.forName("UTF-8"))) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            if (parts.length < 2) {
                continue;
            }
            Entry entry = bySymbol.get(unescape(parts[0]));
            if (entry == null) {
                continue;
            }
            entry.setRepresentation(unescape(parts[1]));
            if (parts.length >= 3 && !unescape(parts[2]).trim().isEmpty()) {
                entry.setDescription(unescape(parts[2]));
            }
        }
    }

    private List<Segment> parse(String notation) {
        rebuildIndexes();
        if (notation == null || notation.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter phonemes separated by dashes");
        }
        List<Segment> all = new ArrayList<Segment>();
        for (String word : notation.trim().split("\\s+")) {
            all.addAll(segmentsForWord(word));
        }
        return all;
    }

    private List<Segment> segmentsForWord(String word) {
        List<Segment> result = new ArrayList<Segment>();
        for (String input : word.split("-", -1)) {
            if (input.isEmpty()) {
                throw new IllegalArgumentException("Empty phoneme in word: " + word);
            }
            Entry entry = byRepresentation.get(input);
            if (entry == null) {
                entry = bySymbol.get(input);
            }
            if (entry == null) {
                throw new IllegalArgumentException("Unknown hand-authored phoneme: " + input);
            }
            result.add(new Segment(input, entry.symbol));
        }
        return result;
    }

    private void rebuildIndexes() {
        bySymbol.clear();
        byRepresentation.clear();
        for (Entry entry : entries) {
            bySymbol.put(entry.symbol, entry);
            if (!entry.representation.isEmpty()) {
                byRepresentation.put(entry.representation, entry);
            }
        }
    }

    private static String defaultRepresentation(String symbol) {
        if ("ə".equals(symbol)) return "uh";
        if ("ɑ".equals(symbol)) return "ah";
        if ("æ".equals(symbol)) return "ae";
        if ("ɪ".equals(symbol)) return "ih";
        if ("ɛ".equals(symbol)) return "eh";
        if ("ʌ".equals(symbol)) return "uh2";
        if ("ɔ".equals(symbol)) return "aw";
        if ("ʊ".equals(symbol)) return "uu";
        if ("i".equals(symbol)) return "ee";
        if ("u".equals(symbol)) return "oo";
        if ("ŋ".equals(symbol)) return "ng";
        if ("ʃ".equals(symbol)) return "sh";
        if ("ʒ".equals(symbol)) return "zh";
        if ("θ".equals(symbol)) return "th";
        if ("ð".equals(symbol)) return "dh";
        if ("ɹ".equals(symbol)) return "r";
        if ("j".equals(symbol)) return "y";
        if ("ˈ".equals(symbol)) return "stress";
        if ("ː".equals(symbol)) return "long";
        return symbol;
    }

    private static String defaultDescription(String symbol) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("ə", "schwa / relaxed mid-central vowel");
        names.put("ɑ", "open back unrounded vowel / ah");
        names.put("æ", "near-open front vowel / ash");
        names.put("ɪ", "near-close near-front vowel / ih");
        names.put("ɛ", "open-mid front vowel / eh");
        names.put("ʌ", "open-mid back vowel / stressed uh");
        names.put("ɔ", "open-mid back rounded vowel / aw");
        names.put("ʊ", "near-close back rounded vowel / short oo");
        names.put("i", "close front vowel / ee");
        names.put("u", "close back rounded vowel / oo");
        names.put("ŋ", "voiced velar nasal / ng");
        names.put("θ", "voiceless dental fricative / th");
        names.put("ð", "voiced dental fricative / th");
        names.put("ʃ", "voiceless postalveolar fricative / sh");
        names.put("ʒ", "voiced postalveolar fricative / zh");
        names.put("tʃ", "voiceless postalveolar affricate / ch");
        names.put("dʒ", "voiced postalveolar affricate / j");
        names.put("ɹ", "voiced alveolar approximant / r");
        names.put("ɾ", "alveolar tap / quick t or d");
        names.put("j", "palatal approximant / y");
        names.put("ˈ", "primary stress");
        names.put("ˌ", "secondary stress");
        names.put("ː", "long vowel");
        String known = names.get(symbol);
        return known == null ? "Unmapped model symbol " + symbol
                + " (see the upstream tokens vocabulary)" : known;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        return value.replace("\\t", "\t").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\\\", "\\");
    }

    private static final class Segment {
        private final String input;
        private final String ipa;

        private Segment(String input, String ipa) {
            this.input = input;
            this.ipa = ipa;
        }
    }
}
