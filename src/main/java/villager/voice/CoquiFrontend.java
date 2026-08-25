package villager.voice;

import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java text-to-token frontend for the Coqui VCTK VITS model.
 *
 * Graphemes are converted to IPA with the bundled CMU dictionary (ARPAbet to
 * IPA, primary/secondary stress preserved), then mapped onto the model's
 * character vocabulary with the Coqui blank token (178) interspersed between
 * every symbol, exactly like {@code TTSTokenizer.text_to_ids} with
 * {@code add_blank}. No eSpeak, no subprocesses, and no network is used at
 * runtime; the vocabulary is a tiny JSON asset and the dictionary is the
 * shipped {@code cmudict.dict}.
 */
public final class CoquiFrontend {
    /** Coqui VITS blank token id inserted between symbols (and at both ends). */
    public static final int BLANK_ID = 178;

    private final Map<String, Integer> charToId = new HashMap<String, Integer>();
    private final Map<String, List<String>> dictionary = new HashMap<String, List<String>>();

    public CoquiFrontend(Path vocabJson, Path cmudictDict) throws IOException {
        JSONArray symbols = new JSONArray(
                new String(Files.readAllBytes(vocabJson), StandardCharsets.UTF_8));
        for (int i = 0; i < symbols.length(); i++) {
            String symbol = symbols.getString(i);
            if (!symbol.isEmpty()) {
                charToId.put(symbol, i);
            }
        }
        loadDictionary(cmudictDict);
    }

    /** Number of model symbols in the vocabulary (excluding nothing). */
    public int vocabSize() {
        return charToId.size();
    }

    /**
     * Convert a text to blank-interspersed token ids, ready for VITS.
     *
     * Normalization happens before phonemization so nothing the model cannot
     * pronounce is silently dropped: digit runs become spoken numbers,
     * apostrophes stay inside words (so "what's" reaches the dictionary),
     * all-caps words are spelled with letter names ("TV"), and money/percent
     * signs attach to the number that precedes them.
     */
    public int[] textToIds(String text) {
        List<Integer> symbols = new ArrayList<Integer>();
        StringBuilder word = new StringBuilder();
        // Kept in original case so all-caps acronyms can be detected.
        StringBuilder rawWord = new StringBuilder();
        boolean lastTokenWasNumber = false;
        // "$" seen before the number it applies to ("$5" -> "five dollars").
        String pendingCurrency = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                pendingCurrency = null;
                word.append(Character.toLowerCase(c));
                rawWord.append(c);
            } else if (c == '\'' && word.length() > 0) {
                // Contraction/possessive apostrophe: keep it glued to the word
                // so "what's" and "we're" hit their CMU dictionary entries
                // instead of being split into "what" + "'" + "s".
                if (i + 1 < text.length() && Character.isLetter(text.charAt(i + 1))) {
                    word.append('\'');
                    rawWord.append(c);
                } else {
                    flushWord(symbols, word, rawWord);
                    lastTokenWasNumber = false;
                    pendingCurrency = null;
                    addSymbol(symbols, "'");
                }
            } else {
                flushWord(symbols, word, rawWord);
                // Important: do NOT reset lastTokenWasNumber here — the digit
                // handler just set it true, and a trailing "$" or "%" in the
                // same run must be able to attach ("5$" -> "five dollars").
                if (c == ' ') {
                    lastTokenWasNumber = false;
                    pendingCurrency = null;
                    addSymbol(symbols, " ");
                } else if (Character.isDigit(c)) {
                    // Re-accumulate a digit run including internal commas,
                    // then expand it to spoken words ("1,000" -> "one thousand").
                    // The inner loop stops at the first non-digit (so a trailing
                    // "$" or "%" is handled by the next iteration).
                    StringBuilder digits = new StringBuilder();
                    while (i < text.length()) {
                        char d = text.charAt(i);
                        if (Character.isDigit(d)) {
                            digits.append(d);
                        } else if (d == ',' && i + 1 < text.length()
                                && Character.isDigit(text.charAt(i + 1))) {
                            digits.append(d); // skip the group separator
                        } else {
                            break;
                        }
                        i++;
                    }
                    i--;
                    List<String> spoken = numberToWords(digits.toString());
                    if (pendingCurrency != null) {
                        spoken.add(pendingCurrency); // "$5" -> "five dollars"
                        pendingCurrency = null;
                    }
                    for (String wordPart : spoken) {
                        addWordSymbols(symbols, wordPart);
                    }
                    lastTokenWasNumber = true;
                } else if (c == '%' && lastTokenWasNumber) {
                    addWordSymbols(symbols, "percent");
                    lastTokenWasNumber = false;
                } else if ((c == '$' || c == '\u00a3' || c == '\u20ac') && lastTokenWasNumber) {
                    addWordSymbols(symbols, currencyName(c));
                    lastTokenWasNumber = false;
                } else if (c == '$' && i + 1 < text.length()
                        && Character.isDigit(text.charAt(i + 1))) {
                    pendingCurrency = "dollars"; // applies to the next number run
                } else if (c == '\u00a3' && i + 1 < text.length()
                        && Character.isDigit(text.charAt(i + 1))) {
                    pendingCurrency = "pounds";
                } else if (c == '\u20ac' && i + 1 < text.length()
                        && Character.isDigit(text.charAt(i + 1))) {
                    pendingCurrency = "euros";
                } else if (c == '.' && lastTokenWasNumber && i + 1 < text.length()
                        && Character.isDigit(text.charAt(i + 1))) {
                    // Decimal point: "3.5" -> "three point five", merging the
                    // decimal digits into the following number run.
                    addWordSymbols(symbols, "point");
                    StringBuilder decimals = new StringBuilder();
                    i++;
                    while (i < text.length() && Character.isDigit(text.charAt(i))) {
                        decimals.append(text.charAt(i));
                        i++;
                    }
                    i--;
                    for (String spoken : numberToWords(decimals.toString())) {
                        addWordSymbols(symbols, spoken);
                    }
                    lastTokenWasNumber = true;
                } else {
                    lastTokenWasNumber = false;
                    pendingCurrency = null;
                    addSymbol(symbols, String.valueOf(c));
                }
            }
        }
        flushWord(symbols, word, rawWord);
        int[] ids = new int[symbols.size() * 2 + 1];
        ids[0] = BLANK_ID;
        for (int i = 0; i < symbols.size(); i++) {
            ids[i * 2 + 1] = symbols.get(i);
            ids[i * 2 + 2] = BLANK_ID;
        }
        return ids;
    }

    private void flushWord(List<Integer> symbols, StringBuilder word,
                           StringBuilder rawWord) {
        if (word.length() > 0) {
            String lower = word.toString();
            String raw = rawWord.toString();
            if (lower.length() >= 2 && raw.equals(raw.toUpperCase(Locale.ROOT))
                    && !raw.equals(raw.toLowerCase(Locale.ROOT))) {
                // All-caps word: spell it with letter names ("TV" -> "tee vee")
                // instead of letter-by-letter fallback pronunciations.
                for (int i = 0; i < lower.length(); i++) {
                    addWordSymbols(symbols, acronymLetter(lower.charAt(i)));
                }
            } else {
                addWordSymbols(symbols, lower);
            }
            word.setLength(0);
            rawWord.setLength(0);
        }
    }

    /**
     * Expand an integer digit run into spoken English words ("310" ->
     * "three hundred ten"). Pure text, so it is unit-testable without a
     * dictionary; the returned words are then phonemized normally.
     */
    static List<String> numberToWords(String rawDigits) {
        List<String> words = new ArrayList<String>();
        String digits = rawDigits.replace(",", "").trim();
        if (!digits.matches("[0-9]+")) {
            for (int i = 0; i < rawDigits.length(); i++) {
                char d = rawDigits.charAt(i);
                if (Character.isDigit(d)) {
                    words.add(digitName(d));
                }
            }
            return words;
        }
        if (digits.length() > 12) {
            // Spell absurdly long runs digit by digit instead of overflowing.
            for (int i = 0; i < digits.length(); i++) {
                words.add(digitName(digits.charAt(i)));
            }
            return words;
        }
        long value = Long.parseLong(digits);
        if (value == 0L) {
            words.add("zero");
            return words;
        }
        String[] scales = {"", "thousand", "million", "billion"};
        int scaleIndex = 0;
        while (value > 0L) {
            int chunk = (int) (value % 1000L);
            if (chunk != 0) {
                List<String> part = upToThreeDigits(chunk);
                if (scaleIndex > 0) {
                    part.add(scales[scaleIndex]);
                }
                words.addAll(0, part);
            }
            value /= 1000L;
            scaleIndex++;
        }
        return words;
    }

    private static List<String> upToThreeDigits(int value) {
        List<String> words = new ArrayList<String>();
        if (value >= 100) {
            words.add(digitName((char) ('0' + value / 100)));
            words.add("hundred");
            value %= 100;
            if (value == 0) {
                return words;
            }
            words.add("and");
        }
        if (value >= 20) {
            words.add(tensName(value / 10));
            value %= 10;
            if (value == 0) {
                return words;
            }
            words.add(digitName((char) ('0' + value)));
        } else if (value >= 10) {
            words.add(teensName(value));
        } else if (value > 0) {
            words.add(digitName((char) ('0' + value)));
        }
        return words;
    }

    private static String digitName(char digit) {
        switch (digit) {
            case '1': return "one";
            case '2': return "two";
            case '3': return "three";
            case '4': return "four";
            case '5': return "five";
            case '6': return "six";
            case '7': return "seven";
            case '8': return "eight";
            case '9': return "nine";
            default: return "zero";
        }
    }

    private static String tensName(int tens) {
        switch (tens) {
            case 2: return "twenty";
            case 3: return "thirty";
            case 4: return "forty";
            case 5: return "fifty";
            case 6: return "sixty";
            case 7: return "seventy";
            case 8: return "eighty";
            default: return "ninety";
        }
    }

    private static String teensName(int value) {
        switch (value) {
            case 10: return "ten";
            case 11: return "eleven";
            case 12: return "twelve";
            case 13: return "thirteen";
            case 14: return "fourteen";
            case 15: return "fifteen";
            case 16: return "sixteen";
            case 17: return "seventeen";
            case 18: return "eighteen";
            default: return "nineteen";
        }
    }

    /**
     * Spoken name for one acronym letter, chosen so the CMU dictionary entry
     * is the letter name, not the indefinite article: "a" is remapped to
     * "ay" (/eɪ/) because the dictionary's first "a" entry is the schwa
     * article pronunciation.
     */
    private static String acronymLetter(char letter) {
        return letter == 'a' ? "ay" : String.valueOf(letter);
    }

    private static String currencyName(char symbol) {
        if (symbol == '\u00a3') return "pounds";
        if (symbol == '\u20ac') return "euros";
        return "dollars";
    }

    /** IPA symbol sequence for one word (dictionary lookup, letter fallback). */
    List<String> wordToIpa(String word) {
        List<String> phones = dictionary.get(word);
        if (phones == null) {
            phones = dictionary.get(word.replace("'", ""));
        }
        if (phones == null) {
            List<String> fallback = new ArrayList<String>();
            for (int i = 0; i < word.length(); i++) {
                fallback.add(letterPhone(word.charAt(i)));
            }
            return fallback;
        }
        List<String> ipa = new ArrayList<String>();
        for (String phone : phones) {
            appendPhone(ipa, phone);
        }
        return ipa;
    }

    private void addWordSymbols(List<Integer> symbols, String word) {
        for (String symbol : wordToIpa(word)) {
            addSymbol(symbols, symbol);
        }
    }

    private void addSymbol(List<Integer> symbols, String symbol) {
        Integer id = charToId.get(symbol);
        if (id != null && id != BLANK_ID) {
            symbols.add(id);
        }
    }

    private void loadDictionary(Path cmudict) throws IOException {
        for (String line : Files.readAllLines(cmudict, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int first = line.indexOf(' ');
            if (first < 0) {
                continue;
            }
            String word = line.substring(0, first).toLowerCase(Locale.ROOT);
            if (dictionary.containsKey(word)) {
                continue; // keep the first (most common) pronunciation
            }
            String[] phones = line.substring(first + 1).trim().split("\\s+");
            List<String> phoneList = new ArrayList<String>();
            for (String phone : phones) {
                if (!phone.isEmpty()) {
                    phoneList.add(phone);
                }
            }
            dictionary.put(word, phoneList);
        }
    }

    private static void appendPhone(List<String> ipa, String phone) {
        int stress = 0;
        String base = phone;
        char last = phone.charAt(phone.length() - 1);
        if (last == '0' || last == '1' || last == '2') {
            stress = last - '0';
            base = phone.substring(0, phone.length() - 1);
        }
        if (stress > 0 && isVowel(base)) {
            ipa.add(stress == 1 ? "\u02c8" : "\u02cc"); // primary/secondary stress mark
        }
        String mapped = mapPhone(base, stress);
        if (mapped == null) {
            return;
        }
        for (int i = 0; i < mapped.length(); i++) {
            ipa.add(String.valueOf(mapped.charAt(i)));
        }
    }

    private static boolean isVowel(String base) {
        return base.equals("AA") || base.equals("AE") || base.equals("AH")
                || base.equals("AO") || base.equals("AW") || base.equals("AY")
                || base.equals("EH") || base.equals("ER") || base.equals("EY")
                || base.equals("IH") || base.equals("IY") || base.equals("OW")
                || base.equals("OY") || base.equals("UH") || base.equals("UW");
    }

    /** ARPAbet to Coqui-vocab IPA. All produced characters exist in the vocab. */
    private static String mapPhone(String base, int stress) {
        if (base.equals("AA")) return "\u0251";          // ɑ
        if (base.equals("AE")) return "\u00e6";          // æ
        if (base.equals("AH")) return stress > 0 ? "\u028c" : "\u0259"; // ʌ / ə
        if (base.equals("AO")) return "\u0254";          // ɔ
        if (base.equals("AW")) return "a\u028a";         // aʊ
        if (base.equals("AY")) return "a\u026a";         // aɪ
        if (base.equals("EH")) return "\u025b";          // ɛ
        if (base.equals("ER")) return stress > 0 ? "\u025d" : "\u025a"; // ɝ / ɚ
        if (base.equals("EY")) return "e\u026a";         // eɪ
        if (base.equals("IH")) return "\u026a";          // ɪ
        if (base.equals("IY")) return "i";
        if (base.equals("OW")) return "o\u028a";         // oʊ
        if (base.equals("OY")) return "\u0254\u026a";    // ɔɪ
        if (base.equals("UH")) return "\u028a";          // ʊ
        if (base.equals("UW")) return "u";
        if (base.equals("B")) return "b";
        if (base.equals("CH")) return "t\u0283";         // tʃ
        if (base.equals("D")) return "d";
        if (base.equals("DH")) return "\u00f0";          // ð
        if (base.equals("F")) return "f";
        if (base.equals("G")) return "\u0261";           // ɡ
        if (base.equals("HH")) return "h";
        if (base.equals("JH")) return "d\u0292";         // dʒ
        if (base.equals("K")) return "k";
        if (base.equals("L")) return "l";
        if (base.equals("M")) return "m";
        if (base.equals("N")) return "n";
        if (base.equals("NG")) return "\u014b";          // ŋ
        if (base.equals("P")) return "p";
        if (base.equals("R")) return "\u0279";           // ɹ
        if (base.equals("S")) return "s";
        if (base.equals("SH")) return "\u0283";          // ʃ
        if (base.equals("T")) return "t";
        if (base.equals("TH")) return "\u03b8";          // θ
        if (base.equals("V")) return "v";
        if (base.equals("W")) return "w";
        if (base.equals("Y")) return "j";
        if (base.equals("Z")) return "z";
        if (base.equals("ZH")) return "\u0292";          // ʒ
        return null;
    }

    private static String letterPhone(char c) {
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
            case 'o': return "\u0251";
            case 'p': return "p";
            case 'q': return "k";
            case 'r': return "\u0279";
            case 's': return "s";
            case 't': return "t";
            case 'u': return "\u028c";
            case 'v': return "v";
            case 'w': return "w";
            case 'x': return "k";
            case 'y': return "j";
            case 'z': return "z";
            default: return "";
        }
    }
}
