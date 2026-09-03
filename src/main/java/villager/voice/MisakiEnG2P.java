package villager.voice;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java port of the misaki English G2P (british=True), the frontend the
 * official Kokoro-82M pipeline uses for en-GB. It converts English text into
 * the exact phoneme string Kokoro expects: British phonemes (ɒ ɑː ɜː əʊ ɔː
 * non-rhotic endings), primary/secondary stress marks placed before stressed
 * vowels, and dictionary-level de-stressing so function words reduce
 * ("I am haggling you" -&gt; {@code ˌI ɐm hˈaɡlɪŋ juː}).
 *
 * <p>Dictionary data (gb_gold.json, gb_silver.json) are the misaki assets;
 * they ship beside the model files. The port mirrors misaki's Lexicon:
 * grown dictionary (capitalized variants), tag-variant picking, NNP
 * spelling via the gold single-letter entries, stemming (s/ed/ing), and
 * number reading that resolves each spoken word through the lexicon.
 */
public final class MisakiEnG2P {
    // Phoneme alphabet used by misaki's British English output. Every
    // character here is a token in the Kokoro vocab.
    private static final String STRESSES = "\u02cc\u02c8";           // ˌ ˈ
    private static final char PRIMARY = '\u02c8';                     // ˈ
    private static final char SECONDARY = '\u02cc';                   // ˌ
    // Exactly misaki's VOWELS frozenset('AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ')
    private static final String VOWEL_CHARS = "AIOQWYaiu"
            + "\u00e6\u0251\u0252\u0254\u0259\u025b\u025c\u026a\u028a\u028c\u1d7b";
    private static final Set<Character> VOWEL_SET = toSet(VOWEL_CHARS);
    // misaki's CONSONANTS frozenset('bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ')
    private static final Set<Character> CONSONANT_SET = toSet(
            "bdfhjklmnpstvwz\u00f0\u014b\u0261\u0279\u027e\u0283\u0292\u02a4\u02a7\u03b8");
    private static final Set<Character> DIPHTHONG_SET = toSet("AIOQWY\u02a4\u02a7");

    private static Set<Character> toSet(String chars) {
        Set<Character> set = new HashSet<Character>();
        for (int i = 0; i < chars.length(); i++) {
            set.add(chars.charAt(i));
        }
        return set;
    }

    private static final String PUNCTS = ";:,.!?\u2026\u2014\"\u201c\u201d";
    private static final Set<Character> PUNCT_SET = toSet(PUNCTS);
    private static final String NON_QUOTE_PUNCTS = ";:,.!?\u2026\u2014";
    private static final String SUBTOKEN_JUNKS = "',-._\u2018\u2019/";
    private static final String[] ORDINAL_SUFFIXES = {"st", "nd", "rd", "th"};

    // ── Dictionaries ────────────────────────────────────────────────────

    private final Map<String, String> gold = new LinkedHashMap<String, String>();
    private final Map<String, String> silver = new LinkedHashMap<String, String>();

    private final Map<String, String> contractions = new LinkedHashMap<String, String>();

    public MisakiEnG2P(Path modelDir) throws IOException {
        loadDictionary(gold, modelDir.resolve("gb_gold.json"));
        loadDictionary(silver, modelDir.resolve("gb_silver.json"));
        loadContractions();
    }

    /**
     * Load a JSON dictionary and grow it exactly like misaki's
     * Lexicon.grow_dictionary: lowercase keys get their capitalized variant,
     * capitalized keys get the lowercase form.
     */
    private static void loadDictionary(Map<String, String> target, Path json)
            throws IOException {
        JSONObject root = new JSONObject(new String(
                Files.readAllBytes(json), StandardCharsets.UTF_8));
        for (String key : root.keySet()) {
            Object value = root.get(key);
            if (value instanceof String) {
                target.put(key, (String) value);
            } else if (value instanceof JSONObject) {
                JSONObject variants = (JSONObject) value;
                StringBuilder merged = new StringBuilder();
                for (String tag : variants.keySet()) {
                    String ps = variants.optString(tag, null);
                    if (ps == null) {
                        continue;
                    }
                    if (merged.length() > 0) {
                        merged.append('\u0001');
                    }
                    merged.append(tag).append('\u0002').append(ps);
                }
                if (merged.length() > 0) {
                    target.put(key, merged.toString());
                }
            }
        }
        // grow_dictionary
        Map<String, String> grown = new LinkedHashMap<String, String>(target);
        for (Map.Entry<String, String> entry : target.entrySet()) {
            String k = entry.getKey();
            if (k.length() < 2) {
                continue;
            }
            if (k.equals(k.toLowerCase(Locale.ROOT))) {
                String cap = capitalize(k);
                if (!k.equals(cap)) {
                    grown.put(cap, entry.getValue());
                }
            } else if (k.equals(capitalize(k.toLowerCase(Locale.ROOT)))) {
                grown.put(k.toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        target.clear();
        target.putAll(grown);
    }

    private void loadContractions() {
        // The classic n't/'re/'ll/'d/'ve family. These words resolve
        // naturally via subtokenization in misaki; a fixed table keeps them
        // natural here. Citation phonemes match the espeak en-GB output.
        putContraction("i'm", "\u02ccI" + "m");
        putContraction("i've", "\u02ccIv");
        putContraction("i'll", "\u02ccIl");
        putContraction("i'd", "\u02ccId");
        putContraction("you're", "j\u02cc\u0254\u02d0");
        putContraction("you've", "j\u02ccu\u02d0v");
        putContraction("you'll", "j\u02ccu\u02d0l");
        putContraction("you'd", "j\u02ccu\u02d0d");
        putContraction("we're", "w\u02cc\u026a\u0259");
        putContraction("we've", "w\u02cci\u02d0v");
        putContraction("we'll", "w\u02cci\u02d0l");
        putContraction("we'd", "w\u02cci\u02d0d");
        putContraction("they're", "\u00f0\u02ccA");
        putContraction("they've", "\u00f0\u02ccAv");
        putContraction("they'll", "\u00f0\u02ccAl");
        putContraction("they'd", "\u00f0\u02ccAd");
        putContraction("he's", "h\u02cci\u02d0z");
        putContraction("he'll", "h\u02cci\u02d0l");
        putContraction("he'd", "h\u02cci\u02d0d");
        putContraction("she's", "\u0283\u02cci\u02d0z");
        putContraction("she'll", "\u0283\u02cci\u02d0l");
        putContraction("she'd", "\u0283\u02cci\u02d0d");
        putContraction("it's", "\u02cc\u026ats");
        putContraction("that's", "\u00f0ats");
        putContraction("what's", "w\u02cc\u0252ts");
        putContraction("who's", "h\u02ccu\u02d0z");
        putContraction("here's", "h\u02c8\u026a\u0259z");
        putContraction("there's", "\u00f0\u02cc\u025b\u02d0z");
        putContraction("let's", "l\u02cc\u025bts");
        putContraction("don't", "d\u02c8Qnt");
        putContraction("won't", "w\u02c8Qnt");
        putContraction("can't", "k\u02c8\u0251\u02d0nt");
        putContraction("isn't", "\u02cc\u026az\u0259nt");
        putContraction("aren't", "\u02cc\u0251\u02d0\u0279\u0259nt");
        putContraction("wasn't", "w\u02cc\u0252z\u0259nt");
        putContraction("weren't", "w\u02cc\u025c\u02d0nt");
        putContraction("hasn't", "h\u02cc\u00e6z\u0259nt");
        putContraction("haven't", "h\u02cc\u00e6v\u0259nt");
        putContraction("hadn't", "h\u02cc\u00e6d\u0259nt");
        putContraction("doesn't", "d\u02cc\u028cz\u0259nt");
        putContraction("didn't", "d\u02cc\u026ad\u0259nt");
        putContraction("wouldn't", "w\u02cc\u028ad\u0259nt");
        putContraction("couldn't", "k\u02cc\u028ad\u0259nt");
        putContraction("shouldn't", "\u0283\u02cc\u028ad\u0259nt");
        putContraction("mustn't", "m\u02cc\u028as\u0259nt");
        putContraction("ain't", "\u02c8A" + "nt");
        putContraction("o'clock", "\u0259kl\u02cc\u0252k");
        putContraction("ma'am", "m\u02c8\u0251\u02d0m");
        putContraction("gonna", "\u0261\u02cc\u0252n\u0259");
        putContraction("gotta", "\u0261\u02cc\u0252t\u0259");
        putContraction("wanna", "w\u02cc\u0252n\u0259");
    }

    private void putContraction(String word, String phonemes) {
        contractions.put(word, phonemes);
        contractions.put(capitalize(word), phonemes);
    }

    private static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    // ── Tokens ──────────────────────────────────────────────────────────

    private static final class Token {
        String text;
        String tag;
        String whitespace = "";
        boolean isHead = true;
        String phonemes;
        Double stress;
        String currency;
        String numFlags = "";
        int rating = 0;
        boolean prespace;

        Token(String text, String tag) {
            this.text = text;
            this.tag = tag;
        }

        boolean isTo() {
            return "to".equals(text) || "To".equals(text)
                    || ("TO".equals(text) && ("TO".equals(tag) || "IN".equals(tag)));
        }

        int stressWeight() {
            if (phonemes == null) {
                return 0;
            }
            int sum = 0;
            for (int i = 0; i < phonemes.length(); i++) {
                sum += DIPHTHONG_SET.contains(phonemes.charAt(i)) ? 2 : 1;
            }
            return sum;
        }
    }

    private static final class TokenContext {
        Boolean futureVowel;
        boolean futureTo;

        TokenContext() {
        }

        TokenContext(Boolean futureVowel, boolean futureTo) {
            this.futureVowel = futureVowel;
            this.futureTo = futureTo;
        }
    }

    // ── Stress engine (exact misaki ports) ──────────────────────────────

    /**
     * misaki apply_stress: re-anchor stress marks onto the following vowel.
     */
    private static String restress(String ps) {
        List<Object[]> ips = new ArrayList<Object[]>(); // {pos, char}
        for (int i = 0; i < ps.length(); i++) {
            ips.add(new Object[]{(double) i, ps.charAt(i)});
        }
        // stress mark i moves to just before its following vowel
        for (int i = 0; i < ps.length(); i++) {
            char c = ps.charAt(i);
            if (c != PRIMARY && c != SECONDARY) {
                continue;
            }
            int j = i;
            while (j < ps.length() && !VOWEL_SET.contains(ps.charAt(j))) {
                j++;
            }
            if (j < ps.length()) {
                ips.set(i, new Object[]{j - 0.5, c});
            }
        }
        Collections.sort(ips, new java.util.Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[0], (Double) b[0]);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Object[] pair : ips) {
            sb.append((Character) pair[1]);
        }
        return sb.toString();
    }

    private static String applyStress(String ps, Double stress) {
        if (stress == null) {
            return ps;
        }
        if (stress < -1) {
            return ps.replace(String.valueOf(PRIMARY), "")
                    .replace(String.valueOf(SECONDARY), "");
        }
        if (stress == -1 || ((stress == 0 || stress == -0.5)
                && ps.indexOf(PRIMARY) >= 0)) {
            return ps.replace(String.valueOf(SECONDARY), "")
                    .replace(String.valueOf(PRIMARY), String.valueOf(SECONDARY));
        }
        if ((stress == 0 || stress == 0.5 || stress == 1)
                && ps.indexOf(PRIMARY) < 0 && ps.indexOf(SECONDARY) < 0) {
            if (!hasVowel(ps)) {
                return ps;
            }
            return restress(SECONDARY + ps);
        }
        if (stress >= 1 && ps.indexOf(PRIMARY) < 0 && ps.indexOf(SECONDARY) >= 0) {
            return ps.replace(String.valueOf(SECONDARY), String.valueOf(PRIMARY));
        }
        if (stress > 1 && ps.indexOf(PRIMARY) < 0 && ps.indexOf(SECONDARY) < 0) {
            if (!hasVowel(ps)) {
                return ps;
            }
            return restress(PRIMARY + ps);
        }
        return ps;
    }

    private static boolean hasVowel(String ps) {
        for (int i = 0; i < ps.length(); i++) {
            if (VOWEL_SET.contains(ps.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ── Lexicon ─────────────────────────────────────────────────────────

    private static String parentTag(String tag) {
        if (tag == null) {
            return null;
        }
        if (tag.startsWith("VB")) {
            return "VERB";
        }
        if (tag.startsWith("NN")) {
            return "NOUN";
        }
        if (tag.startsWith("ADV") || tag.startsWith("RB")) {
            return "ADV";
        }
        if (tag.startsWith("ADJ") || tag.startsWith("JJ")) {
            return "ADJ";
        }
        return tag;
    }

    private static final Set<String> SYMBOLS = new HashSet<String>(Arrays.asList(
            "%", "&", "+", "@"));
    private static final Map<String, String> ADD_SYMBOLS =
            new HashMap<String, String>();
    static {
        ADD_SYMBOLS.put(".", "dot");
        ADD_SYMBOLS.put("/", "slash");
    }

    private boolean isKnown(String word, String tag) {
        if (gold.containsKey(word) || silver.containsKey(word)
                || SYMBOLS.contains(word) || contractions.containsKey(word)) {
            return true;
        }
        if (!isAlpha(word)) {
            return false;
        }
        if (word.length() == 1) {
            return true;
        }
        if (word.equals(word.toUpperCase(Locale.ROOT))
                && gold.containsKey(word.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return word.substring(1).equals(word.substring(1).toUpperCase(Locale.ROOT));
    }

    /** misaki get_NNP: spell with the gold single-letter phonemes. */
    private String[] getNNP(String word) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            String letter = gold.get(String.valueOf(Character.toUpperCase(c)));
            if (letter == null) {
                return null;
            }
            joined.append(letter);
        }
        String ps = applyStress(joined.toString(), 0.0);
        int last = ps.lastIndexOf(SECONDARY);
        if (last >= 0) {
            ps = ps.substring(0, last) + PRIMARY + ps.substring(last + 1);
        }
        return new String[]{ps, "3"};
    }

    private String lookup(String word, String tag, Double stress, TokenContext ctx) {
        boolean isNNP = false;
        String lookupWord = word;
        if (word.equals(word.toUpperCase(Locale.ROOT)) && !gold.containsKey(word)) {
            lookupWord = word.toLowerCase(Locale.ROOT);
            isNNP = "NNP".equals(tag);
        }
        String ps = gold.get(lookupWord);
        int rating = 4;
        if (ps == null && !isNNP) {
            String s = silver.get(lookupWord);
            if (s != null) {
                ps = s;
                rating = 3;
            }
        }
        if (ps == null && contractions.containsKey(lookupWord)) {
            ps = contractions.get(lookupWord);
        }
        ps = pickVariant(ps, tag, ctx);
        if (ps == null || (isNNP && ps.indexOf(PRIMARY) < 0)) {
            String[] spelled = getNNP(lookupWord);
            if (spelled != null) {
                return spelled[0];
            }
        }
        return ps == null ? null : applyStress(ps, stress);
    }

    /** Pick a tag variant from an encoded "TAG\u0002ps\u0001TAG\u0002ps" value. */
    private String pickVariant(String encoded, String tag, TokenContext ctx) {
        if (encoded == null || encoded.indexOf('\u0001') < 0) {
            return encoded;
        }
        Map<String, String> variants = new LinkedHashMap<String, String>();
        for (String part : encoded.split("\u0001", -1)) {
            int split = part.indexOf('\u0002');
            if (split > 0) {
                variants.put(part.substring(0, split), part.substring(split + 1));
            }
        }
        String useTag = tag;
        if (ctx != null && ctx.futureVowel == null && variants.containsKey("None")) {
            useTag = "None";
        } else if (!variants.containsKey(useTag)) {
            useTag = parentTag(useTag);
        }
        String ps = variants.get(useTag);
        return ps != null ? ps : variants.get("DEFAULT");
    }

    // ── Special cases (exact misaki get_special_case) ───────────────────

    private String[] specialCase(String word, String tag, Double stress,
                                 TokenContext ctx) {
        if ("ADD".equals(tag) && ADD_SYMBOLS.containsKey(word)) {
            return new String[]{lookup(ADD_SYMBOLS.get(word), null, -0.5, ctx), "4"};
        }
        if (SYMBOLS.contains(word)) {
            String symbolWord = "%".equals(word) ? "percent"
                    : ("&".equals(word) ? "and" : ("+".equals(word) ? "plus" : "at"));
            return new String[]{lookup(symbolWord, null, null, ctx), "4"};
        }
        if (word.indexOf('.') >= 0 && word.replace(".", "").length() > 0
                && isAlpha(word.replace(".", ""))
                && maxWordLen(word.split("\\.")) < 3) {
            String[] nnp = getNNP(word);
            if (nnp != null) {
                return nnp;
            }
        }
        if ("a".equals(word) || ("A".equals(word) && "DT".equals(tag))) {
            return new String[]{"\u0250", "4"};
        }
        if ("am".equals(word) || "Am".equals(word) || "AM".equals(word)) {
            if (tag != null && tag.startsWith("NN")) {
                String[] nnp = getNNP(word);
                if (nnp != null) {
                    return nnp;
                }
            }
            if (ctx.futureVowel == null || !"am".equals(word)
                    || (stress != null && stress > 0)) {
                return new String[]{gold.get("am"), "4"};
            }
            return new String[]{"\u0250m", "4"};
        }
        if ("an".equals(word) || "An".equals(word) || "AN".equals(word)) {
            if ("AN".equals(word) && tag != null && tag.startsWith("NN")) {
                String[] nnp = getNNP(word);
                if (nnp != null) {
                    return nnp;
                }
            }
            return new String[]{"\u0250n", "4"};
        }
        if ("I".equals(word) && "PRP".equals(tag)) {
            return new String[]{String.valueOf(SECONDARY) + "I", "4"};
        }
        if (("by".equals(word) || "By".equals(word) || "BY".equals(word))
                && "ADV".equals(parentTag(tag))) {
            return new String[]{"b" + PRIMARY + "I", "4"};
        }
        if ("to".equals(word) || "To".equals(word)
                || ("TO".equals(word) && ("TO".equals(tag) || "IN".equals(tag)))) {
            String to = ctx.futureVowel == null ? gold.get("to")
                    : (ctx.futureVowel ? "t\u028a" : "t\u0259");
            return new String[]{to, "4"};
        }
        if ("the".equals(word) || "The".equals(word)
                || ("THE".equals(word) && "DT".equals(tag))) {
            return new String[]{Boolean.TRUE.equals(ctx.futureVowel) ? "\u00f0i" : "\u00f0\u0259", "4"};
        }
        if ("IN".equals(tag) && word.toLowerCase(Locale.ROOT).matches("vs\\.?")) {
            return new String[]{lookup("versus", null, null, ctx), "4"};
        }
        if ("used".equals(word) || "Used".equals(word) || "USED".equals(word)) {
            String used = gold.get("used");
            if (("VBD".equals(tag) || "JJ".equals(tag)) && ctx.futureTo) {
                return new String[]{pickVariant(used, "VBD", ctx), "4"};
            }
            return new String[]{pickVariant(used, "DEFAULT", ctx), "4"};
        }
        return null;
    }

    private static boolean isAlpha(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int maxWordLen(String[] parts) {
        int max = 0;
        for (String part : parts) {
            max = Math.max(max, part.length());
        }
        return max;
    }

    // ── Stemming (exact misaki _s/_ed/_ing) ─────────────────────────────

    private String sEnding(String stem) {
        if (stem == null || stem.isEmpty()) {
            return null;
        }
        char last = stem.charAt(stem.length() - 1);
        if ("ptkf\u03b8".indexOf(last) >= 0) {
            return stem + "s";
        }
        if ("sz\u0283\u0292\u02a4\u02a7".indexOf(last) >= 0) {
            return stem + "\u026az";
        }
        return stem + "z";
    }

    private String edEnding(String stem) {
        if (stem == null || stem.isEmpty()) {
            return null;
        }
        char last = stem.charAt(stem.length() - 1);
        if ("pkf\u03b8\u0283s\u02a7".indexOf(last) >= 0) {
            return stem + "t";
        }
        if (last == 'd') {
            return stem + "\u026ad";
        }
        if (last != 't') {
            return stem + "d";
        }
        return stem + "\u026ad";
    }

    private String ingEnding(String stem) {
        if (stem == null || stem.isEmpty()) {
            return null;
        }
        char last = stem.charAt(stem.length() - 1);
        if (last == '\u0259' || last == '\u02d0') {
            return null;
        }
        return stem + "\u026a\u014b";
    }

    private String[] stemS(String word, String tag, Double stress, TokenContext ctx) {
        String stem = null;
        if (word.length() > 2 && word.endsWith("s") && !word.endsWith("ss")
                && isKnown(word.substring(0, word.length() - 1), tag)) {
            stem = word.substring(0, word.length() - 1);
        } else if ((word.endsWith("'s") || (word.length() > 4 && word.endsWith("es")))
                && isKnown(word.substring(0, word.length() - 2), tag)) {
            stem = word.substring(0, word.length() - 2);
        } else if (word.length() > 4 && word.endsWith("ies")
                && isKnown(word.substring(0, word.length() - 3) + "y", tag)) {
            stem = word.substring(0, word.length() - 3) + "y";
        } else {
            return null;
        }
        String stemPs = lookup(stem, tag, stress, ctx);
        return stemPs == null ? null : new String[]{sEnding(stemPs), "4"};
    }

    private String[] stemEd(String word, String tag, Double stress, TokenContext ctx) {
        String stem = null;
        if (word.endsWith("d") && !word.endsWith("dd")
                && isKnown(word.substring(0, word.length() - 1), tag)) {
            stem = word.substring(0, word.length() - 1);
        } else if (word.endsWith("ed") && !word.endsWith("eed")
                && isKnown(word.substring(0, word.length() - 2), tag)) {
            stem = word.substring(0, word.length() - 2);
        } else {
            return null;
        }
        String stemPs = lookup(stem, tag, stress, ctx);
        return stemPs == null ? null : new String[]{edEnding(stemPs), "4"};
    }

    private String[] stemIng(String word, String tag, Double stress, TokenContext ctx) {
        String stem = null;
        if (word.endsWith("ing") && isKnown(word.substring(0, word.length() - 3), tag)) {
            stem = word.substring(0, word.length() - 3);
        } else if (word.endsWith("ing")
                && isKnown(word.substring(0, word.length() - 3) + "e", tag)) {
            stem = word.substring(0, word.length() - 3) + "e";
        } else if (word.matches(".*([bcdgklmnprstvxz])\\1ing$|.*cking$")
                && isKnown(word.substring(0, word.length() - 4), tag)) {
            stem = word.substring(0, word.length() - 4);
        } else {
            return null;
        }
        String stemPs = lookup(stem, tag, stress, ctx);
        return stemPs == null ? null : new String[]{ingEnding(stemPs), "4"};
    }

    private String[] getWord(String word, String tag, Double stress, TokenContext ctx) {
        String[] special = specialCase(word, tag, stress, ctx);
        if (special != null) {
            return special;
        }
        if (isKnown(word, tag)) {
            String ps = lookup(word, tag, stress, ctx);
            if (ps != null) {
                return new String[]{ps, "4"};
            }
        }
        if (word.endsWith("s'") && isKnown(word.substring(0, word.length() - 2) + "'s", tag)) {
            String ps = lookup(word.substring(0, word.length() - 2) + "'s", tag, stress, ctx);
            if (ps != null) {
                return new String[]{ps, "4"};
            }
        }
        if (word.endsWith("'") && isKnown(word.substring(0, word.length() - 1), tag)) {
            String ps = lookup(word.substring(0, word.length() - 1), tag, stress, ctx);
            if (ps != null) {
                return new String[]{ps, "4"};
            }
        }
        String[] s = stemS(word, tag, stress, ctx);
        if (s != null) {
            return s;
        }
        String[] ed = stemEd(word, tag, stress, ctx);
        if (ed != null) {
            return ed;
        }
        String[] ing = stemIng(word, tag, stress == null ? 0.5 : stress, ctx);
        if (ing != null) {
            return ing;
        }
        return null;
    }

    /** misaki __call__ lexicon path: word, then number, then lowercase retry. */
    private String[] lexic(String word, String tag, Double stress, TokenContext ctx,
                           String currency) {
        String[] result = getWord(word, tag, stress, ctx);
        if (result != null) {
            return result;
        }
        if (isNumber(word)) {
            return getNumber(word, currency);
        }
        if (!word.equals(word.toLowerCase(Locale.ROOT))
                && (word.equals(word.toUpperCase(Locale.ROOT))
                    || word.substring(1).equals(word.substring(1).toLowerCase(Locale.ROOT)))) {
            String[] lower = getWord(word.toLowerCase(Locale.ROOT), tag, stress, ctx);
            if (lower != null) {
                return lower;
            }
        }
        return null;
    }

    // ── Numbers (misaki get_number; spoken words resolved via lexicon) ──

    private static final String[] DIGITS = {"zero", "one", "two", "three", "four",
            "five", "six", "seven", "eight", "nine"};
    private static final String[] TEENS = {"ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty",
            "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String[] ORDINALS = {"first", "second", "third", "fourth",
            "fifth", "sixth", "seventh", "eighth", "ninth"};

    private static String cardinalWords(long value) {
        if (value == 0) {
            return "zero";
        }
        return cardinalChunk(value);
    }

    private static String cardinalChunk(long value) {
        if (value >= 1000000000L) {
            return combine(cardinalChunk(value / 1000000000L) + " billion",
                    cardinalChunk(value % 1000000000L));
        }
        if (value >= 1000000) {
            return combine(cardinalChunk(value / 1000000) + " million",
                    cardinalChunk(value % 1000000));
        }
        if (value >= 1000) {
            return combine(cardinalChunk(value / 1000) + " thousand",
                    cardinalChunk(value % 1000));
        }
        if (value >= 100) {
            String head = DIGITS[(int) (value / 100)] + " hundred";
            long rest = value % 100;
            if (rest == 0) {
                return head;
            }
            return head + " " + twoDigits(rest);
        }
        return twoDigits(value);
    }

    private static String twoDigits(long value) {
        if (value >= 20) {
            String tens = TENS[(int) (value / 10)];
            long rest = value % 10;
            return rest == 0 ? tens : tens + " " + DIGITS[(int) rest];
        }
        if (value >= 10) {
            return TEENS[(int) (value - 10)];
        }
        return DIGITS[(int) value];
    }

    private static String combine(String a, String b) {
        return b.isEmpty() ? a : a + " " + b;
    }

    private static String ordinalWords(long value) {
        if (value % 100 >= 11 && value % 100 <= 13) {
            return cardinalWords(value) + "th";
        }
        long last = value % 10;
        if (last >= 1 && last <= 3 && (value < 10 || value % 100 >= 20)) {
            String ordinalWord = ORDINALS[(int) last - 1];
            if (value < 20) {
                return ordinalWord;
            }
            String head = cardinalWords(value - last);
            return head + " " + ordinalWord;
        }
        return cardinalWords(value) + "th";
    }

    /** num2words to='year': 1842 -> eighteen forty-two; 1900 -> nineteen hundred. */
    private static String yearWords(long value) {
        if (value >= 1000 && value < 2000) {
            long first = value / 100;
            long rest = value % 100;
            if (rest == 0) {
                return cardinalWords(first) + " hundred";
            }
            return cardinalWords(first) + " " + twoDigits(rest);
        }
        if (value >= 2000 && value < 2010) {
            long rest = value % 100;
            return rest == 0 ? "two thousand" : "two thousand " + cardinalWords(rest);
        }
        if (value >= 2010 && value < 2100) {
            return "twenty " + twoDigits(value % 100);
        }
        return cardinalWords(value);
    }

    private boolean isNumber(String word) {
        boolean anyDigit = false;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                anyDigit = true;
                break;
            }
        }
        if (!anyDigit) {
            return false;
        }
        String w = word;
        for (String suffix : new String[]{"ing", "'d", "ed", "'s", "st", "nd",
                "rd", "th", "s"}) {
            if (w.endsWith(suffix)) {
                w = w.substring(0, w.length() - suffix.length());
                break;
            }
        }
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!(Character.isDigit(c) || c == ',' || c == '.'
                    || (i == 0 && c == '-'))) {
                return false;
            }
        }
        return true;
    }

    private String[] getNumber(String word, String currency) {
        String suffix = null;
        Matcher m = Pattern.compile("[a-z']+$").matcher(word);
        if (m.find()) {
            suffix = m.group();
            word = word.substring(0, word.length() - suffix.length());
        }
        boolean ordinal = false;
        for (String os : ORDINAL_SUFFIXES) {
            if (os.equals(suffix)) {
                ordinal = true;
                break;
            }
        }
        String spoken;
        if (word.startsWith("-")) {
            spoken = "minus " + numberBody(word.substring(1), ordinal, currency);
        } else {
            spoken = numberBody(word, ordinal, currency);
        }
        String phonemes = numberToPhonemes(spoken);
        if ("s".equals(suffix) || "'s".equals(suffix)) {
            phonemes = sEnding(phonemes);
        } else if ("ed".equals(suffix) || "'d".equals(suffix)) {
            phonemes = edEnding(phonemes);
        } else if ("ing".equals(suffix)) {
            phonemes = ingEnding(phonemes);
        }
        return new String[]{phonemes, "4"};
    }

    private String numberBody(String word, boolean ordinal, String currency) {
        String digits = word.replace(",", "");
        if (currency != null && isCurrencyLike(digits)) {
            return currencyWords(digits, currency);
        }
        if (digits.indexOf('-') >= 0) {
            // hyphenated digit group: read each group, digit-by-digit
            // when a group has a leading zero, cardinal otherwise
            StringBuilder sb = new StringBuilder();
            for (String group : digits.split("-", -1)) {
                if (group.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(digitGroup(group, ordinal));
            }
            return sb.toString();
        }
        if (!digits.matches("[0-9]+")) {
            if (digits.indexOf('.') >= 0) {
                return decimalWords(digits);
            }
            // mixed digits/letters: spell digit by digit
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                char c = digits.charAt(i);
                sb.append(Character.isDigit(c) ? DIGITS[c - '0'] : String.valueOf(c));
            }
            return sb.toString();
        }
        if (digits.charAt(0) == '0' || digits.length() > 12) {
            return digitByDigit(digits);
        }
        long value = Long.parseLong(digits);
        if (!ordinal && digits.length() == 4 && value >= 1000 && value < 2100) {
            return yearWords(value);
        }
        return ordinal ? ordinalWords(value) : cardinalWords(value);
    }

    private String digitGroup(String group, boolean ordinal) {
        if (group.charAt(0) == '0' || group.length() > 12) {
            return digitByDigit(group);
        }
        long value = Long.parseLong(group);
        return ordinal ? ordinalWords(value) : cardinalWords(value);
    }

    private String digitByDigit(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(DIGITS[digits.charAt(i) - '0']);
        }
        return sb.toString();
    }

    private String decimalWords(String digits) {
        String[] parts = digits.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        if (parts[0].isEmpty()) {
            sb.append("point");
        } else {
            sb.append(cardinalWords(Long.parseLong(parts[0])));
        }
        if (parts.length > 1 && !parts[1].isEmpty()) {
            sb.append(" point");
            for (int i = 0; i < parts[1].length(); i++) {
                sb.append(' ').append(DIGITS[parts[1].charAt(i) - '0']);
            }
        }
        return sb.toString();
    }

    private boolean isCurrencyLike(String digits) {
        if (digits.indexOf('.') < 0) {
            return true;
        }
        if (digits.indexOf('.') != digits.lastIndexOf('.')) {
            return false;
        }
        String cents = digits.split("\\.", -1)[1];
        if (cents.length() >= 3) {
            boolean allZero = true;
            for (int i = 0; i < cents.length(); i++) {
                if (cents.charAt(i) != '0') {
                    allZero = false;
                    break;
                }
            }
            if (!allZero) {
                return false;
            }
        }
        return true;
    }

    private String currencyWords(String digits, String currency) {
        String[] units = currencyUnits(currency);
        if (units == null) {
            return cardinalWords(Long.parseLong(digits.replace(".", "")));
        }
        String[] parts = digits.split("\\.", -1);
        long whole = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
        long frac = parts.length > 1 && !parts[1].isEmpty()
                ? Long.parseLong(parts[1]) : 0;
        if (frac != 0 && parts.length > 1 && parts[1].length() < 2) {
            frac *= 10; // "$5.5" -> 50 cents
        }
        StringBuilder sb = new StringBuilder();
        if (whole == 0 && frac == 0) {
            sb.append("zero ");
            sb.append(units[0]).append('s');
            return sb.toString();
        }
        if (whole != 0) {
            sb.append(cardinalWords(whole)).append(' ').append(units[0]);
            if (whole != 1) {
                sb.append('s');
            }
        }
        if (frac != 0) {
            if (whole != 0) {
                sb.append(" and ");
            }
            sb.append(cardinalWords(frac)).append(' ').append(units[1]);
            if (frac != 1 && !"pence".equals(units[1])) {
                sb.append('s');
            }
        }
        return sb.toString();
    }

    private String[] currencyUnits(String currency) {
        if ("$".equals(currency)) {
            return new String[]{"dollar", "cent"};
        }
        if ("\u00a3".equals(currency)) {
            return new String[]{"pound", "pence"};
        }
        if ("\u20ac".equals(currency)) {
            return new String[]{"euro", "cent"};
        }
        return null;
    }

    /** Resolve spoken number words through the lexicon (misaki extend_num). */
    private String numberToPhonemes(String spoken) {
        StringBuilder sb = new StringBuilder();
        for (String w : spoken.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            String ps;
            if ("point".equals(w)) {
                ps = lookup("point", null, -2.0, null);
            } else if ("minus".equals(w)) {
                ps = lookup("minus", null, null, null);
            } else {
                ps = lookup(w, null, null, null);
            }
            if (ps == null) {
                // plural currency units (dollars, cents) resolve by stemming
                String[] stemmed = stemS(w, null, null, null);
                if (stemmed != null) {
                    ps = stemmed[0];
                }
            }
            if (ps == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(ps);
        }
        return sb.toString();
    }

    // ── Tokenizer + POS (pragmatic spacy substitute) ────────────────────

    private static final Set<String> FUNCTION_TAGS = new HashSet<String>(Arrays.asList(
            "the", "a", "an", "this", "that", "these", "those"));
    private static final Set<String> PRONOUNS = new HashSet<String>(Arrays.asList(
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
            "us", "them", "my", "your", "his", "its", "our", "their"));
    private static final Set<String> PREPOSITIONS = new HashSet<String>(Arrays.asList(
            "in", "on", "at", "by", "for", "of", "from", "with", "without",
            "about", "under", "over", "through", "between", "among", "into",
            "onto", "upon", "after", "before", "during", "against", "near"));
    private static final Set<String> VERBS = new HashSet<String>(Arrays.asList(
            "is", "am", "are", "was", "were", "be", "been", "being", "have",
            "has", "had", "do", "does", "did", "get", "gets", "got", "take",
            "takes", "took", "taken", "make", "makes", "made", "go", "goes",
            "went", "gone", "see", "sees", "saw", "seen", "say", "says",
            "said", "want", "wants", "wanted", "need", "needs", "needed",
            "like", "likes", "liked", "love", "loves", "loved", "know",
            "knows", "knew", "known", "think", "thinks", "thought", "come",
            "comes", "came", "give", "gives", "gave", "given", "find",
            "finds", "found", "tell", "tells", "told", "buy", "buys",
            "bought", "sell", "sells", "sold", "pay", "pays", "paid",
            "bring", "brings", "brought", "put", "puts", "let", "lets",
            "keep", "keeps", "kept", "feel", "feels", "felt", "hold",
            "holds", "held", "meet", "meets", "met", "run", "runs", "ran",
            "eat", "eats", "ate", "drink", "drinks", "drank", "sleep",
            "sleeps", "slept", "speak", "speaks", "spoke", "hear", "hears",
            "heard", "read", "reads", "write", "writes", "wrote", "stand",
            "stands", "stood", "sit", "sits", "sat", "win", "wins", "won",
            "lose", "loses", "lost", "throw", "throws", "threw", "catch",
            "catches", "caught", "teach", "teaches", "taught", "build",
            "builds", "built", "send", "sends", "sent", "spend", "spends",
            "spent", "cut", "cuts", "shut", "hit", "hits", "hurt", "cost",
            "costs", "trade", "trades", "traded", "trading", "look", "looks",
            "looked", "watch", "watches", "watched", "watching", "help",
            "helps", "helped", "please", "welcome", "thanks", "thank"));

    private static final Set<String> ADVERBS = new HashSet<String>(Arrays.asList(
            "not", "very", "quite", "too", "so", "really", "just", "now",
            "here", "there", "then", "soon", "today", "tomorrow", "also",
            "always", "never", "often", "well", "yes", "no", "maybe",
            "perhaps", "again", "away", "back", "out", "up", "down", "off"));

    private static String tagFor(String word) {
        if (word.isEmpty()) {
            return null;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9.,\\-]+")) {
            return "CD";
        }
        if ("to".equals(lower)) {
            return "TO";
        }
        if (FUNCTION_TAGS.contains(lower)) {
            return "DT";
        }
        if (PRONOUNS.contains(lower)) {
            return "PRP";
        }
        if (PREPOSITIONS.contains(lower)) {
            return "IN";
        }
        if ("and".equals(lower) || "but".equals(lower) || "or".equals(lower)) {
            return "CC";
        }
        if (lower.matches("what|which|who|whom|whose|how|why|where|when")) {
            return "WP";
        }
        if ("this".equals(lower) || "that".equals(lower)) {
            return "DT";
        }
        if (VERBS.contains(lower)) {
            if (lower.endsWith("ed")) {
                return "VBD";
            }
            if (lower.endsWith("ing")) {
                return "VBG";
            }
            return "VB";
        }
        if (ADVERBS.contains(lower)) {
            return "ADV";
        }
        if (lower.endsWith("ly")) {
            return "ADV";
        }
        if (lower.endsWith("ed")) {
            return "VBD";
        }
        if (lower.endsWith("ing")) {
            return "VBG";
        }
        if (lower.endsWith("tion") || lower.endsWith("sion")
                || lower.endsWith("ness") || lower.endsWith("ment")
                || lower.endsWith("ity") || lower.endsWith("ance")
                || lower.endsWith("ence") || lower.endsWith("er")
                || lower.endsWith("or")) {
            return "NN";
        }
        if (lower.endsWith("ous") || lower.endsWith("ive")
                || lower.endsWith("ful") || lower.endsWith("al")
                || lower.endsWith("ic") || lower.endsWith("able")
                || lower.endsWith("ible")) {
            return "JJ";
        }
        if (!lower.equals(word)) {
            // Capitalized, not a sentence-initial function word: proper noun.
            return "NNP";
        }
        return "NN";
    }

    /**
     * Split text into tokens the way spacy would. Whitespace is recorded on
     * the preceding token's whitespace field and never becomes a token.
     */
    private List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<Token>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                int start = i;
                i++;
                while (i < n) {
                    char d = text.charAt(i);
                    if (Character.isLetter(d)) {
                        i++;
                    } else if (d == '\'' && i + 1 < n
                            && Character.isLetter(text.charAt(i + 1))) {
                        i += 2;
                    } else if (d == '.' && i + 1 < n
                            && Character.isLetter(text.charAt(i + 1))) {
                        // dotted abbreviation (U.S., e.g.): keep as one token
                        i++;
                    } else {
                        break;
                    }
                }
                // trailing period of a dotted abbreviation ("U.S.")
                if (i < n && text.charAt(i) == '.'
                        && text.indexOf('.', start) >= 0 && text.indexOf('.', start) < i) {
                    i++;
                }
                String word = text.substring(start, i);
                Token token = new Token(word, tagFor(word));
                token.whitespace = nextWhitespace(text, i);
                tokens.add(token);
            } else if (Character.isDigit(c)) {
                int start = i;
                i++;
                while (i < n && (Character.isDigit(text.charAt(i))
                        || text.charAt(i) == ',')) {
                    i++;
                }
                // decimal point: consume dot + following digits
                if (i < n && text.charAt(i) == '.'
                        && i + 1 < n && Character.isDigit(text.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(text.charAt(i))) {
                        i++;
                    }
                }
                // ordinal suffixes (4th, 21st, 32nd, 43rd) stay in the token
                for (String os : ORDINAL_SUFFIXES) {
                    if (i + 2 <= n && text.startsWith(os, i)) {
                        i += 2;
                        break;
                    }
                }
                Token token = new Token(text.substring(start, i), "CD");
                token.whitespace = nextWhitespace(text, i);
                tokens.add(token);
            } else {
                String word = String.valueOf(c);
                String tag = "$".equals(word) ? "$"
                        : (c == '-' || c == '\u2013') ? ":"
                        : (":;,.!?\u2026\u2014".indexOf(c) >= 0) ? "PUNCT" : "SYM";
                Token token = new Token(word, tag);
                // the punctuation char itself occupies position i
                token.whitespace = nextWhitespace(text, i + 1);
                tokens.add(token);
                i++;
            }
        }
        // Sentence-initial function words are not proper nouns.
        boolean firstWord = true;
        for (Token token : tokens) {
            if ("NNP".equals(token.tag) && firstWord
                    && token.text.length() > 1) {
                token.tag = "NN";
            }
            firstWord = false;
        }
        return tokens;
    }

    private static String nextWhitespace(String text, int from) {
        if (from < text.length() && Character.isWhitespace(text.charAt(from))) {
            return " ";
        }
        return "";
    }

    // ── Main pipeline ────────────────────────────────────────────────────

    private static boolean isJunk(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (SUBTOKEN_JUNKS.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPunct(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!PUNCT_SET.contains(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Punctuation token that breaks a stress-demotion group (not hyphen). */
    private static boolean isPunctToken(Token token) {
        if (token == null) {
            return false;
        }
        if ("PUNCT".equals(token.tag) || "$".equals(token.tag)
                || "#".equals(token.tag) || "SYM".equals(token.tag)) {
            return true;
        }
        if (":".equals(token.tag)) {
            return !"-".equals(token.text) && !"\u2013".equals(token.text);
        }
        return false;
    }

    /**
     * misaki token_context: vowel flag of the token after the current one.
     * The previous context's vowel carries through when the phoneme string
     * has no vowel/consonant/punct marker (e.g. "a" is spelled ɐ, which is
     * not in VOWELS/CONSONANTS, so it keeps the surrounding context).
     */
    private TokenContext contextAfter(TokenContext prev, Token token) {
        Boolean vowel = prev.futureVowel;
        if (token != null && token.phonemes != null) {
            for (int i = 0; i < token.phonemes.length(); i++) {
                char c = token.phonemes.charAt(i);
                if (NON_QUOTE_PUNCTS.indexOf(c) >= 0) {
                    vowel = null;
                    break;
                }
                if (VOWEL_SET.contains(c) || CONSONANT_SET.contains(c)) {
                    vowel = VOWEL_SET.contains(c);
                    break;
                }
            }
        }
        return new TokenContext(vowel, token != null && token.isTo());
    }

    /**
     * misaki resolve_tokens: sentence-level stress demotion within a
     * no-whitespace token group (a hyphenated compound or a number group).
     * At most half of a short group may carry primary stress; excess
     * primaries are demoted to secondary, lightest-weight first.
     */
    private void resolveGroup(List<Token> group) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            text.append(group.get(i).text);
            if (i < group.size() - 1) {
                text.append(group.get(i).whitespace);
            }
        }
        String joined = text.toString();
        boolean prespace = joined.indexOf(' ') >= 0 || joined.indexOf('/') >= 0;
        if (!prespace) {
            Set<Integer> kinds = new HashSet<Integer>();
            for (int i = 0; i < joined.length(); i++) {
                char c = joined.charAt(i);
                if (SUBTOKEN_JUNKS.indexOf(c) >= 0) {
                    continue;
                }
                kinds.add(Character.isLetter(c) ? 0 : (Character.isDigit(c) ? 1 : 2));
            }
            if (kinds.size() > 1) {
                prespace = true;
            }
        }
        if (prespace) {
            return;
        }
        // indices of tokens with non-empty phonemes (misaki: `if t.phonemes`)
        List<int[]> indices = new ArrayList<int[]>();
        for (int i = 0; i < group.size(); i++) {
            Token t = group.get(i);
            if (t.phonemes == null || t.phonemes.isEmpty()) {
                continue;
            }
            indices.add(new int[]{t.phonemes.indexOf(PRIMARY) >= 0 ? 1 : 0,
                    t.stressWeight(), i});
        }
        if (indices.size() == 2 && group.get(indices.get(0)[2]).text.length() == 1) {
            Token t = group.get(indices.get(1)[2]);
            t.phonemes = applyStress(t.phonemes, -0.5);
            return;
        }
        int sum = 0;
        for (int[] idx : indices) {
            sum += idx[0];
        }
        if (indices.size() < 2 || sum <= (indices.size() + 1) / 2) {
            return;
        }
        java.util.Collections.sort(indices, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) {
                    return a[0] - b[0];
                }
                if (a[1] != b[1]) {
                    return a[1] - b[1];
                }
                return a[2] - b[2];
            }
        });
        for (int k = 0; k < indices.size() / 2; k++) {
            Token t = group.get(indices.get(k)[2]);
            t.phonemes = applyStress(t.phonemes, -0.5);
        }
    }

    /**
     * misaki resolve_tokens: called per word-group (a single token in our
     * flat tokenizer), so it only acts when a word is spelled across several
     * subtokens without spaces. Here it assigns punctuation and junk
     * phonemes; the sentence-level stress demotion misaki performs inside
     * subtoken groups does not apply to plain words.
     */
    private void resolveTokens(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.phonemes != null) {
                continue;
            }
            if (isPunct(t.text)) {
                t.phonemes = t.text;
                t.rating = 3;
            } else if (isJunk(t.text)) {
                t.phonemes = "";
                t.rating = 3;
            }
        }
    }

    /** Dev-only: dump per-token phonemes for debugging. */
    String phonemizeTokens(String text) {
        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return "";
        }
        resolveTokens(tokens);
        TokenContext ctx = new TokenContext();
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.phonemes == null) {
                phonemizeToken(token, ctx);
            }
            ctx = contextAfter(ctx, token);
        }
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            out.append('[').append(token.text).append(" tag=").append(token.tag)
                    .append(" ps=").append(token.phonemes)
                    .append(" ws=").append(token.whitespace.length()).append("] ");
        }
        return out.toString();
    }

    private void phonemizeToken(Token token, TokenContext ctx) {
        String word = token.text.replace('\u2018', '\'')
                .replace('\u2019', '\'');
        String lower = word.toLowerCase(Locale.ROOT);
        Double stress = null;
        if (!word.equals(lower)) {
            stress = word.equals(word.toUpperCase(Locale.ROOT)) ? 2.0 : 0.5;
        }
        String[] result = lexic(word, token.tag, stress, ctx, token.currency);
        if (result == null && contractions.containsKey(lower)) {
            result = new String[]{contractions.get(lower), "4"};
        }
        if (result != null) {
            token.phonemes = result[0];
            token.rating = Integer.parseInt(result[1]);
        } else {
            token.phonemes = fallbackLetters(word);
        }
    }

    /**
     * Return the syllable-level decomposition of {@code text}. Each syllable
     * covers one vowel nucleus (including secondary-stressed nuclei) plus its
     * onset consonants and any word-boundary whitespace that precedes it.
     * Punctuation tokens are attached to the preceding syllable when present,
     * or emitted standalone otherwise.
     *
     * <p>The phoneme string for each syllable is exactly what Kokoro's vocab
     * expects for that syllable's tokens: British phonemes, stress marks before
     * stressed vowels, spaces between words preserved.
     */
    public List<Syllable> syllabify(String text) {
        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }
        // Attach currency symbols to the following number.
        String currency = null;
        for (Token token : tokens) {
            if ("$".equals(token.tag) && "$".equals(token.text)) {
                currency = token.text;
                token.phonemes = "";
                token.rating = 4;
            } else if (currency != null) {
                if (!"CD".equals(token.tag)) {
                    currency = null;
                } else {
                    token.currency = currency;
                    currency = null;
                }
            }
            if (token.phonemes == null && isPunct(token.text)) {
                token.phonemes = token.text;
                token.rating = 3;
            } else if (token.phonemes == null && isJunk(token.text)) {
                token.phonemes = "";
                token.rating = 3;
            }
        }

        // Backward pass with future-vowel / future-"to" context.
        TokenContext ctx = new TokenContext();
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.phonemes == null) {
                phonemizeToken(token, ctx);
            }
            ctx = contextAfter(ctx, token);
        }

        // misaki resolve_tokens: demote excess primary stress within each
        // no-whitespace group (hyphenated compounds, number groups).
        int groupStart = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            boolean boundary = i == tokens.size()
                    || (i > groupStart && tokens.get(i - 1).whitespace.length() > 0)
                    || isPunctToken(tokens.get(i));
            if (boundary) {
                if (i - groupStart >= 2) {
                    resolveGroup(tokens.subList(groupStart, i));
                }
                groupStart = i;
            }
        }

        List<Syllable> syllables = new ArrayList<Syllable>();
        int syllableStart = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.phonemes == null || token.phonemes.isEmpty()) {
                continue;
            }
            // Find vowel nuclei in this token's phoneme string.
            // Each primary or secondary stress mark anchors a syllable.
            for (int j = 0; j < token.phonemes.length(); j++) {
                char c = token.phonemes.charAt(j);
                if (c == PRIMARY || c == SECONDARY) {
                    // Emit the syllable from the last anchor to this one.
                    if (j > syllableStart) {
                        appendSyllable(syllables, tokens, i, syllableStart,
                                j, token.phonemes, token.whitespace, i > 0);
                    }
                    syllableStart = j;
                }
            }
            // Handle trailing unstressed material after the last stress mark.
            if (syllableStart < token.phonemes.length()) {
                boolean hasVowel = false;
                for (int j = syllableStart; j < token.phonemes.length(); j++) {
                    if (VOWEL_SET.contains(token.phonemes.charAt(j))) {
                        hasVowel = true;
                        break;
                    }
                }
                if (hasVowel) {
                    appendSyllable(syllables, tokens, i, syllableStart,
                            token.phonemes.length(), token.phonemes, token.whitespace,
                            i > 0);
                }
                syllableStart = token.phonemes.length();
            }
        }
        if (syllables.isEmpty() && tokens.size() > 0) {
            // Edge case: no stress marks but there is phoneme content.
            for (Token token : tokens) {
                if (token.phonemes != null && !token.phonemes.isEmpty()) {
                    Syllable s = new Syllable();
                    s.text = token.text;
                    s.phonemes = token.phonemes;
                    s.whitespaceBefore = "";
                    s.tokenIndex = tokens.indexOf(token);
                    syllables.add(s);
                }
            }
        }
        return syllables;
    }

    /**
     * Build one syllable from a contiguous phoneme span. The span starts at
     * {@code phonemeStart} (exclusive of any earlier stress anchor) and ends
     * at {@code phonemeEnd} (inclusive of the vowel nucleus and any coda).
     */
    private void appendSyllable(List<Syllable> syllables, List<Token> tokens,
                                int tokenIndex, int phonemeStart, int phonemeEnd,
                                String phonemeString, String followingWhitespace,
                                boolean hasPrevToken) {
        Syllable s = new Syllable();
        s.tokenIndex = tokenIndex;
        // Reconstruct text: all tokens from syllableStart to this token.
        StringBuilder text = new StringBuilder();
        // Whitespace before the first token of this syllable.
        Token firstToken = tokens.get(tokenIndex);
        if (tokenIndex > 0) {
            Token prev = tokens.get(tokenIndex - 1);
            s.whitespaceBefore = prev.whitespace;
        }
        s.text = firstToken.text;
        // Phoneme substring for this syllable.
        // Stress marks before the vowel are part of this syllable's onset.
        int stressStart = phonemeStart;
        while (stressStart > 0 && (phonemeString.charAt(stressStart - 1) == PRIMARY
                || phonemeString.charAt(stressStart - 1) == SECONDARY)) {
            stressStart--;
        }
        s.phonemes = phonemeString.substring(stressStart, phonemeEnd);
        // Trailing whitespace after this token.
        s.whitespaceAfter = followingWhitespace;
        syllables.add(s);
    }

    /**
     * Phonemize a full sentence into the misaki-format phoneme string
     * (spaces between words, punctuation preserved, stress marks included).
     */
    public String phonemize(String text) {
        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return "";
        }
        // Attach currency symbols to the following number.
        String currency = null;
        for (Token token : tokens) {
            if ("$".equals(token.tag) && "$".equals(token.text)) {
                currency = token.text;
                token.phonemes = "";
                token.rating = 4;
            } else if (currency != null) {
                if (!"CD".equals(token.tag)) {
                    currency = null;
                } else {
                    token.currency = currency;
                    currency = null;
                }
            }
            if (token.phonemes == null && isPunct(token.text)) {
                token.phonemes = token.text;
                token.rating = 3;
            } else if (token.phonemes == null && isJunk(token.text)) {
                token.phonemes = "";
                token.rating = 3;
            }
        }

        // Backward pass with future-vowel / future-"to" context.
        TokenContext ctx = new TokenContext();
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.phonemes == null) {
                phonemizeToken(token, ctx);
            }
            ctx = contextAfter(ctx, token);
        }

        // misaki resolve_tokens: demote excess primary stress within each
        // no-whitespace group (hyphenated compounds, number groups).
        // Punctuation tokens break groups, exactly like misaki's retokenize
        // assigns them phonemes and appends them standalone.
        int groupStart = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            boolean boundary = i == tokens.size()
                    || (i > groupStart && tokens.get(i - 1).whitespace.length() > 0)
                    || isPunctToken(tokens.get(i));
            if (boundary) {
                if (i - groupStart >= 2) {
                    resolveGroup(tokens.subList(groupStart, i));
                }
                groupStart = i;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.phonemes != null) {
                out.append(token.phonemes);
            }
            if (i < tokens.size() - 1 && token.whitespace.length() > 0) {
                out.append(' ');
            }
        }
        return out.toString().trim();
    }

    /**
     * Last-resort fallback: approximate letter-to-sound for unknown words
     * using British vowel conventions.
     */
    private String fallbackLetters(String word) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < word.length(); i++) {
            char c = Character.toLowerCase(word.charAt(i));
            String ps = fallbackPhone(c);
            if (ps == null) {
                continue;
            }
            if (!first) {
                sb.append('\u02cc');
            }
            sb.append(ps);
            first = false;
        }
        if (sb.length() == 0) {
            return "";
        }
        return PRIMARY + sb.toString();
    }

    /**
     * One syllable nucleus: the text, phoneme string, and surrounding whitespace
     * for a single spoken beat. Used by {@link KokoroVoice} to apply per-syllable
     * emotion snapshots.
     */
    public static final class Syllable {
        /** The source word/token text for this syllable. */
        String text;
        /** Kokoro phoneme string for this syllable (no spaces between tokens). */
        String phonemes;
        /** Whitespace after the previous token (before this syllable's first token). */
        String whitespaceBefore;
        /** Whitespace after this syllable's last token. */
        String whitespaceAfter;
        /** Index of the token in the original token list. */
        int tokenIndex;
    }

    private static String fallbackPhone(char c) {
        switch (c) {
            case 'a': return "\u00e6";
            case 'b': return "b";
            case 'c': return "k";
            case 'd': return "d";
            case 'e': return "\u025b";
            case 'f': return "f";
            case 'g': return "\u0261";
            case 'h': return "h";
            case 'i': return "\u026a";
            case 'j': return "d\u0292";
            case 'k': return "k";
            case 'l': return "l";
            case 'm': return "m";
            case 'n': return "n";
            case 'o': return "\u0252";
            case 'p': return "p";
            case 'q': return "k";
            case 'r': return "\u0279";
            case 's': return "s";
            case 't': return "t";
            case 'u': return "\u028c";
            case 'v': return "v";
            case 'w': return "w";
            case 'x': return "ks";
            case 'y': return "j";
            case 'z': return "z";
            default: return null;
        }
    }
}
