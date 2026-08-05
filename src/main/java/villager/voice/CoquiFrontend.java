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

    /** Convert a sentence to blank-interspersed token ids, ready for VITS. */
    public int[] textToIds(String text) {
        List<Integer> symbols = new ArrayList<Integer>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                word.append(Character.toLowerCase(c));
            } else {
                if (word.length() > 0) {
                    addWordSymbols(symbols, word.toString());
                    word.setLength(0);
                }
                if (c == ' ') {
                    addSymbol(symbols, " ");
                } else {
                    addSymbol(symbols, String.valueOf(c));
                }
            }
        }
        if (word.length() > 0) {
            addWordSymbols(symbols, word.toString());
        }
        int[] ids = new int[symbols.size() * 2 + 1];
        ids[0] = BLANK_ID;
        for (int i = 0; i < symbols.size(); i++) {
            ids[i * 2 + 1] = symbols.get(i);
            ids[i * 2 + 2] = BLANK_ID;
        }
        return ids;
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
