package villager.voice.synth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grapheme-to-phoneme for the villager voice.
 *
 * <p>Lookup order, matching {@code tools/villager_voice/lexicon.py}:</p>
 *
 * <ol>
 *   <li>{@link VillagerDictionary} -- %d hand-curated pronunciations.</li>
 *   <li>{@link VillagerCorpusLexicon} -- every one of the %d distinct words the
 *       corpus actually uses, with pronunciations computed by the verified
 *       Python engine.</li>
 *   <li>Suffix machinery: a stem from the dictionary plus a known suffix
 *       ({@code -tion}, {@code -ee}, ...).</li>
 *   <li>A rule-based fallback.</li>
 * </ol>
 *
 * <p><b>How faithful the fallback is.</b> Steps 1 to 3 are exact: they use the
 * same data the Python engine uses, so a corpus word pronounced in Java is
 * pronounced identically in Python. Step 4 is a <em>documented simplification</em>
 * of the Python rule path. That path is several hundred lines of order-dependent,
 * position-sensitive rules (soft-g, silent-e, tense/lax choice, stress
 * assignment) whose behaviour depends on absolute cluster positions, and it
 * exists to handle words the corpus never contained. It is not transliterated
 * here, because an unverifiable port of intricate rules is worse than a smaller
 * one that says what it does. English tense/lax vowel choice is lexical rather
 * than derivable from spelling in any case, so the fallback is approximate by
 * nature; if a word matters, add it to the Python dictionary and regenerate.</p>
 *
 * <p>Symbols are ARPAbet-style ASCII with a trailing stress digit (1 = primary,
 * 2 = secondary, 0 or absent = unstressed).</p>
 */
public final class VillagerLexicon {

    /** One phone plus its stress. */
    public static final class Phone {
        public final String symbol;
        public final int stress;

        public Phone(String symbol, int stress) {
            this.symbol = symbol;
            this.stress = stress;
        }

        @Override
        public String toString() {
            return stress > 0 ? symbol + stress : symbol;
        }
    }

    /** A token from {@link #textToWords}: a word, or punctuation as structure. */
    public static final class Token {
        public final String kind;
        public final String text;

        Token(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public boolean isWord() {
            return "word".equals(kind);
        }
    }

    private static final String[][] CONTRACTIONS = {
            {"'re", "re"}, {"'ve", "ve"}, {"'ll", "ll"}, {"'nt", "nt"},
            {"'m", "m"}, {"'d", "d"}, {"'s", "s"},
    };

    /** Suffixes that pull the stress onto the syllable before them. */
    private static final String[] PRE_STRESS_SUFFIXES = {
            "tion", "sion", "tian", "cial", "tial", "ic", "ical", "ity", "ify",
            "ular", "ient", "ious", "ial", "ivity", "ician", "eous",
    };

    /** Suffixes that carry the stress themselves. */
    private static final String[] SELF_STRESS_SUFFIXES = {
            "ee", "eer", "ese", "esque", "ette", "een",
    };

    private static final Map<String, String> SUFFIX_PRON =
            new LinkedHashMap<String, String>();

    static {
        SUFFIX_PRON.put("tion", "SH AX0 N");
        SUFFIX_PRON.put("sion", "ZH AX0 N");
        SUFFIX_PRON.put("tian", "SH AX0 N");
        SUFFIX_PRON.put("cial", "SH AX0 L");
        SUFFIX_PRON.put("tial", "SH AX0 L");
        SUFFIX_PRON.put("ic", "IH0 K");
        SUFFIX_PRON.put("ical", "IH0 K AX0 L");
        SUFFIX_PRON.put("ity", "IH0 T IY0");
        SUFFIX_PRON.put("ify", "IH0 F AY1");
        SUFFIX_PRON.put("ular", "Y AX0 L AX0");
        SUFFIX_PRON.put("ient", "IY0 AH0 N T");
        SUFFIX_PRON.put("ious", "IY0 AX0 S");
        SUFFIX_PRON.put("ial", "IY0 AX0 L");
        SUFFIX_PRON.put("ivity", "IH1 V AH0 T IY0");
        SUFFIX_PRON.put("ee", "IY1");
        SUFFIX_PRON.put("eer", "IH1 AX0");
        SUFFIX_PRON.put("ese", "IY1 Z");
        SUFFIX_PRON.put("esque", "EH1 S K");
        SUFFIX_PRON.put("ette", "EH1 T");
        SUFFIX_PRON.put("een", "IY1 N");
        SUFFIX_PRON.put("ician", "IH1 SH AX0 N");
        SUFFIX_PRON.put("eous", "IY0 AX0 S");
    }

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z']+|[.,!?;:\\-]+|\\s+");
    private static final Pattern WORD_PATTERN = Pattern.compile("^[A-Za-z']+$");
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z]+");
    private static final Pattern NON_WORD_CHAR = Pattern.compile("[^a-z']+");

    private VillagerLexicon() {
    }

    // ---------------------------------------------------------------------
    // tokenising
    // ---------------------------------------------------------------------

    /**
     * Split a line into typed tokens, keeping punctuation as prosodic structure
     * rather than discarding it: commas, boundaries and dashes drive pauses and
     * pitch movements downstream.
     */
    public static List<Token> textToWords(String text) {
        List<Token> out = new ArrayList<Token>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (WORD_PATTERN.matcher(token).matches()) {
                out.add(new Token("word", token));
            } else if (",".equals(token) || ";".equals(token) || ":".equals(token)) {
                out.add(new Token("comma", token));
            } else if (".".equals(token) || "!".equals(token) || "?".equals(token)) {
                out.add(new Token("boundary", token));
            } else if ("-".equals(token)) {
                out.add(new Token("dash", token));
            } else if (token.trim().isEmpty()) {
                out.add(new Token("space", token));
            }
        }
        return out;
    }

    /** Lowercase, strip punctuation, and split contractions into dictionary keys. */
    public static List<String> normalizeWord(String word) {
        List<String> out = new ArrayList<String>();
        if (word == null || word.isEmpty()) {
            return out;
        }
        String value = word.toLowerCase().replace('\u2019', '\'').trim();
        value = NON_WORD_CHAR.matcher(value).replaceAll("");
        if (value.isEmpty()) {
            return out;
        }
        if (value.indexOf('\'') >= 0) {
            List<String> parts = new ArrayList<String>();
            parts.add(value);
            for (String[] contraction : CONTRACTIONS) {
                List<String> expanded = new ArrayList<String>();
                for (String part : parts) {
                    String suffix = contraction[0];
                    if (part.endsWith(suffix) && part.length() > suffix.length()) {
                        expanded.add(part.substring(0, part.length() - suffix.length()));
                        expanded.add(contraction[1]);
                    } else {
                        expanded.add(part);
                    }
                }
                parts = expanded;
            }
            for (String part : parts) {
                String cleaned = part.replace("'", "");
                if (!cleaned.isEmpty()) {
                    out.add(cleaned);
                }
            }
            return out;
        }
        out.add(value);
        return out;
    }

    // ---------------------------------------------------------------------
    // pronunciation
    // ---------------------------------------------------------------------

    /** Parse {@code "V IH1 L AH0 JH AX0"} into phones. */
    public static List<Phone> parsePronunciation(String text) {
        List<Phone> out = new ArrayList<Phone>();
        if (text == null) {
            return out;
        }
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String symbol;
            int stress = 0;
            char last = token.charAt(token.length() - 1);
            if (last >= '0' && last <= '9') {
                symbol = token.substring(0, token.length() - 1).toUpperCase();
                stress = last - '0';
            } else {
                symbol = token.toUpperCase();
            }
            if (VillagerPhones.contains(symbol)) {
                out.add(new Phone(symbol, stress));
            }
        }
        return out;
    }

    /**
     * Pronounce a word: dictionary first, corpus vocabulary second, suffix
     * machinery third, rules last.
     */
    public static List<Phone> pronounce(String word) {
        List<String> keys = normalizeWord(word);
        if (keys.isEmpty()) {
            return new ArrayList<Phone>();
        }
        List<Phone> result = new ArrayList<Phone>();
        for (String key : keys) {
            String curated = VillagerDictionary.get(key);
            if (curated != null) {
                result.addAll(parsePronunciation(curated));
                continue;
            }
            String fromCorpus = VillagerCorpusLexicon.get(key);
            if (fromCorpus != null) {
                result.addAll(parsePronunciation(fromCorpus));
                continue;
            }
            String[] split = matchSuffix(key);
            String stem = split[0];
            String suffix = split[1];
            String stemPronunciation = VillagerDictionary.get(stem);
            if (stemPronunciation == null) {
                stemPronunciation = VillagerCorpusLexicon.get(stem);
            }
            if (!suffix.isEmpty() && stemPronunciation != null
                    && SUFFIX_PRON.containsKey(suffix)) {
                List<Phone> phones = parsePronunciation(stemPronunciation);
                phones.addAll(parsePronunciation(SUFFIX_PRON.get(suffix)));
                result.addAll(cleanup(phones));
                continue;
            }
            result.addAll(spellToPhones(key));
        }
        return cleanup(result);
    }

    /** The phones for a whole line, with word and punctuation structure. */
    public static List<Object> phonesForLine(String text) {
        List<Object> plan = new ArrayList<Object>();
        for (Token token : textToWords(text)) {
            if (token.isWord()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("kind", "word");
                entry.put("text", token.text);
                entry.put("phones", pronounce(token.text));
                plan.add(entry);
            } else {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("kind", token.kind);
                entry.put("text", token.text);
                plan.add(entry);
            }
        }
        return plan;
    }

    /** Render a phone list the way the Python CLI does, for logs and tests. */
    public static String display(List<Phone> phones) {
        StringBuilder builder = new StringBuilder();
        for (Phone phone : phones) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(phone.toString());
        }
        return builder.toString();
    }

    /** Longest known suffix, longest first so {@code -ition} beats {@code -ion}. */
    private static String[] matchSuffix(String word) {
        List<String> suffixes = new ArrayList<String>();
        suffixes.addAll(Arrays.asList(PRE_STRESS_SUFFIXES));
        suffixes.addAll(Arrays.asList(SELF_STRESS_SUFFIXES));
        Collections.sort(suffixes, new java.util.Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return right.length() - left.length();
            }
        });
        for (String suffix : suffixes) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= 2) {
                return new String[]{word.substring(0, word.length() - suffix.length()), suffix};
            }
        }
        return new String[]{word, ""};
    }

    /** Drop unknown symbols, collapse accidental repeats, remove stray R. */
    private static List<Phone> cleanup(List<Phone> phones) {
        List<Phone> out = new ArrayList<Phone>();
        for (Phone phone : phones) {
            if (!VillagerPhones.contains(phone.symbol)) {
                continue;
            }
            if (!out.isEmpty()) {
                Phone previous = out.get(out.size() - 1);
                if (previous.symbol.equals(phone.symbol) && !"HMM".equals(phone.symbol)) {
                    out.set(out.size() - 1, new Phone(phone.symbol,
                            Math.max(previous.stress, phone.stress)));
                    continue;
                }
            }
            out.add(phone);
        }
        List<Phone> cleaned = new ArrayList<Phone>();
        for (int i = 0; i < out.size(); i++) {
            Phone phone = out.get(i);
            if ("R".equals(phone.symbol) && i > 0 && i < out.size() - 1) {
                if (!VillagerPhones.isSyllabic(out.get(i - 1).symbol)
                        && !VillagerPhones.isSyllabic(out.get(i + 1).symbol)) {
                    continue;
                }
            }
            cleaned.add(phone);
        }
        return cleaned;
    }

    // ---------------------------------------------------------------------
    // rule fallback (simplified; see the class documentation)
    // ---------------------------------------------------------------------

    /** Single-letter grapheme to phone, for the fallback path. */
    private static final Map<Character, String> CONSONANTS =
            new LinkedHashMap<Character, String>();
    /** Vowel grapheme to phone when short and when long. */
    private static final Map<Character, String[]> VOWELS =
            new LinkedHashMap<Character, String[]>();

    static {
        CONSONANTS.put('b', "B");
        CONSONANTS.put('c', "K");
        CONSONANTS.put('d', "D");
        CONSONANTS.put('f', "F");
        CONSONANTS.put('g', "G");
        CONSONANTS.put('h', "HH");
        CONSONANTS.put('j', "JH");
        CONSONANTS.put('k', "K");
        CONSONANTS.put('l', "L");
        CONSONANTS.put('m', "M");
        CONSONANTS.put('n', "N");
        CONSONANTS.put('p', "P");
        CONSONANTS.put('q', "K W");
        CONSONANTS.put('r', "R");
        CONSONANTS.put('s', "S");
        CONSONANTS.put('t', "T");
        CONSONANTS.put('v', "V");
        CONSONANTS.put('w', "W");
        CONSONANTS.put('x', "K S");
        CONSONANTS.put('y', "Y");
        CONSONANTS.put('z', "Z");
        VOWELS.put('a', new String[]{"AE", "EY"});
        VOWELS.put('e', new String[]{"EH", "IY"});
        VOWELS.put('i', new String[]{"IH", "AY"});
        VOWELS.put('o', new String[]{"OQ", "OW"});
        VOWELS.put('u', new String[]{"AH", "UW"});
        VOWELS.put('y', new String[]{"IH", "AY"});
    }

    /** Digraphs the fallback recognises, longest first. */
    private static final String[][] DIGRAPHS = {
            {"tion", "SH AX0 N"}, {"sion", "ZH AX0 N"}, {"tch", "CH"},
            {"igh", "AY1"}, {"ough", "OQ1 F"}, {"ough", "OW1"},
            {"sch", "S K"}, {"ch", "CH"}, {"sh", "SH"}, {"th", "TH"},
            {"ph", "F"}, {"wh", "W"}, {"ck", "K"}, {"ng", "NG"},
            {"qu", "K W"}, {"gh", ""}, {"kn", "N"}, {"wr", "R"},
            {"ee", "IY1"}, {"ea", "IY1"}, {"oo", "UW1"}, {"ou", "AW1"},
            {"ow", "OW1"}, {"ai", "EY1"}, {"ay", "EY1"}, {"au", "AO1"},
            {"aw", "AO1"}, {"oi", "OY1"}, {"oy", "OY1"}, {"ei", "EY1"},
            {"ie", "AY1"}, {"oa", "OW1"}, {"ue", "UW1"}, {"ar", "AA1 R"},
            {"er", "AX"}, {"ir", "ER1"}, {"or", "AO1 R"}, {"ur", "ER1"},
    };

    /**
     * Rule-based fallback for words outside the dictionary and the corpus
     * vocabulary. See the class documentation for exactly how this differs from
     * the Python rule path.
     */
    static List<Phone> spellToPhones(String word) {
        String text = NON_ALPHA.matcher(word.toLowerCase()).replaceAll("");
        if (text.isEmpty()) {
            return new ArrayList<Phone>();
        }
        List<Phone> out = new ArrayList<Phone>();
        // A silent final "e" lengthens the preceding vowel instead of sounding.
        boolean silentE = text.length() > 2 && text.charAt(text.length() - 1) == 'e'
                && !isVowel(text.charAt(text.length() - 2));

        int i = 0;
        int syllableIndex = 0;
        int vowelGroups = countVowelGroups(text, silentE);
        int vowelsSeen = 0;
        while (i < text.length()) {
            if (silentE && i == text.length() - 1) {
                break;
            }
            if (isVowel(text.charAt(i))) {
                String matched = null;
                String phones = null;
                for (String[] digraph : DIGRAPHS) {
                    if (text.regionMatches(i, digraph[0], 0, digraph[0].length())
                            && digraph[0].length() >= 2
                            && isVowel(digraph[0].charAt(0))) {
                        matched = digraph[0];
                        phones = digraph[1];
                        break;
                    }
                }
                if (matched == null) {
                    int run = 1;
                    while (i + run < text.length() && isVowel(text.charAt(i + run))
                            && !(silentE && i + run == text.length() - 1)) {
                        run++;
                    }
                    matched = text.substring(i, i + run);
                    char nucleus = text.charAt(i);
                    String[] pair = VOWELS.get(Character.valueOf(nucleus));
                    boolean longVowel = run > 1
                            || (vowelGroups > 0 && vowelsSeen == vowelGroups - 1
                                    && run == 1 && matched.length() == 1
                                    && i + 2 >= text.length());
                    phones = pair != null ? (longVowel ? pair[1] : pair[0]) : "AX";
                    phones = phones + "1";
                }
                for (Phone phone : parsePronunciation(phones)) {
                    out.add(phone);
                }
                vowelsSeen++;
                syllableIndex++;
                i += matched.length();
                continue;
            }

            // Consonant cluster.
            String matched = null;
            String phones = null;
            for (String[] digraph : DIGRAPHS) {
                if (!isVowel(digraph[0].charAt(0))
                        && text.regionMatches(i, digraph[0], 0, digraph[0].length())) {
                    matched = digraph[0];
                    phones = digraph[1];
                    break;
                }
            }
            if (matched == null) {
                char letter = text.charAt(i);
                matched = String.valueOf(letter);
                phones = CONSONANTS.get(Character.valueOf(letter));
                if (phones == null) {
                    i++;
                    continue;
                }
                // Soft c and g before a front vowel.
                char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
                if ((letter == 'c' || letter == 'g')
                        && (next == 'e' || next == 'i' || next == 'y')) {
                    phones = letter == 'c' ? "S" : "JH";
                }
                // Voiced s between vowels, and -s after a voiced sound.
                if (letter == 's' && i > 0 && isVowel(text.charAt(i - 1))
                        && i + 1 < text.length() && isVowel(next)) {
                    phones = "Z";
                }
            }
            for (Phone phone : parsePronunciation(phones)) {
                out.add(phone);
            }
            i += matched.length();
        }

        // Stress the first syllable unless a later one is already marked; the
        // Python path uses suffix tables and syllable weight for this.
        boolean stressed = false;
        for (Phone phone : out) {
            if (phone.stress > 0) {
                stressed = true;
                break;
            }
        }
        if (!stressed) {
            for (int k = 0; k < out.size(); k++) {
                if (VillagerPhones.isSyllabic(out.get(k).symbol)) {
                    out.set(k, new Phone(out.get(k).symbol, 1));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean isVowel(char letter) {
        return "aeiouy".indexOf(letter) >= 0;
    }

    private static int countVowelGroups(String text, boolean silentE) {
        int groups = 0;
        boolean previousVowel = false;
        int limit = silentE ? text.length() - 1 : text.length();
        for (int i = 0; i < limit; i++) {
            boolean vowel = isVowel(text.charAt(i));
            if (vowel && !previousVowel) {
                groups++;
            }
            previousVowel = vowel;
        }
        return groups;
    }

    /**
     * How much of a vocabulary the curated and corpus tables handle directly,
     * i.e. without falling back to rules.
     *
     * @return {@code [directHits, totalWords]}
     */
    public static int[] coverage(List<String> words) {
        int inside = 0;
        for (String word : words) {
            List<String> keys = normalizeWord(word);
            if (!keys.isEmpty() && (VillagerDictionary.contains(keys.get(0))
                    || VillagerCorpusLexicon.contains(keys.get(0)))) {
                inside++;
            }
        }
        return new int[]{inside, words.size()};
    }
}
