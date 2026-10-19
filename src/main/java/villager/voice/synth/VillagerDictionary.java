/*
 * GENERATED FILE -- do not edit by hand.
 *
 * Emitted from the verified Python tables by:
 *     cd tools && python3 -m villager_voice.java_port
 *
 * The Python package is the source of truth for this data. Edit
 * tools/villager_voice/lexicon.py and regenerate, so the Java voice can never
 * silently drift away from the one that passed selftest.
 */

package villager.voice.synth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curated pronunciations for the words the corpus actually uses.
 *
 * <p>English tense/lax vowel choice is lexical, not derivable from
 * spelling, so correctness on frequent words is bought by curation
 * rather than by rules. The 377 entries here cover the words that
 * dominate the corpus: its 72 most frequent words are half of all
 * 16,146 tokens. {@link VillagerLexicon} falls back to rules for
 * everything else.</p>
 *
 * <p>Values are ARPAbet-ASCII with trailing digits for stress
 * (1 = primary, 2 = secondary, 0 or absent = unstressed).</p>
 */
public final class VillagerDictionary {

    private static final Map<String, String> TABLE =
            new LinkedHashMap<String, String>();

    private VillagerDictionary() {
    }

    static {
        TABLE.put("a", "AX0");
        TABLE.put("about", "AX0 B AW1 T");
        TABLE.put("after", "AA1 F T AX0");
        TABLE.put("again", "AX0 G EH1 N");
        TABLE.put("ah", "AA1");
        TABLE.put("ahh", "AA1");
        TABLE.put("all", "AO1 L");
        TABLE.put("also", "AO1 L S OW0");
        TABLE.put("always", "AO1 L W EY2 Z");
        TABLE.put("am", "AE1 M");
        TABLE.put("an", "AX0 N");
        TABLE.put("and", "AX0 N D");
        TABLE.put("another", "AX0 N AH1 DH AX0");
        TABLE.put("any", "EH1 N IY0");
        TABLE.put("anything", "EH1 N IY0 TH IH0 NG");
        TABLE.put("are", "AA1");
        TABLE.put("arent", "AA1 R AH0 N T");
        TABLE.put("as", "AE1 Z");
        TABLE.put("at", "AE1 T");
        TABLE.put("away", "AX0 W EY1");
        TABLE.put("aye", "AY1");
        TABLE.put("baby", "B EY1 B IY0");
        TABLE.put("back", "B AE1 K");
        TABLE.put("bad", "B AE1 D");
        TABLE.put("be", "B IY1");
        TABLE.put("beautiful", "B Y UW1 T AH0 F AX0 L");
        TABLE.put("been", "B IY1 N");
        TABLE.put("before", "B IH0 F AO1");
        TABLE.put("being", "B IY1 IH0 NG");
        TABLE.put("better", "B EH1 T AX0");
        TABLE.put("big", "B IH1 G");
        TABLE.put("bit", "B IH1 T");
        TABLE.put("body", "B OQ1 D IY0");
        TABLE.put("book", "B UH1 K");
        TABLE.put("books", "B UH1 K S");
        TABLE.put("both", "B OW1 TH");
        TABLE.put("bough", "B AW1");
        TABLE.put("boy", "B OY1");
        TABLE.put("breaking", "B R EY1 K IH0 NG");
        TABLE.put("but", "B AH1 T");
        TABLE.put("buy", "B AY1");
        TABLE.put("by", "B AY1");
        TABLE.put("can", "K AE1 N");
        TABLE.put("cannot", "K AE1 N OQ2 T");
        TABLE.put("cant", "K AA1 N T");
        TABLE.put("citizen", "S IH1 T AH0 Z AX0 N");
        TABLE.put("citizens", "S IH1 T AH0 Z AX0 N Z");
        TABLE.put("city", "S IH1 T IY0");
        TABLE.put("come", "K AH1 M");
        TABLE.put("comes", "K AH1 M Z");
        TABLE.put("cough", "K OQ1 F");
        TABLE.put("could", "K UH1 D");
        TABLE.put("couldnt", "K UH1 D AH0 N T");
        TABLE.put("d", "D");
        TABLE.put("day", "D EY1");
        TABLE.put("days", "D EY1 Z");
        TABLE.put("deal", "D IY1 L");
        TABLE.put("delicious", "D IH0 L IH1 SH AX0 S");
        TABLE.put("did", "D IH1 D");
        TABLE.put("didnt", "D IH1 D AH0 N T");
        TABLE.put("do", "D UW1");
        TABLE.put("does", "D AH1 Z");
        TABLE.put("done", "D AH1 N");
        TABLE.put("dont", "D OW1 N T");
        TABLE.put("each", "IY1 CH");
        TABLE.put("email", "IY1 M EY2 L");
        TABLE.put("emerald", "EH1 M AX0 L D");
        TABLE.put("emeralds", "EH1 M AX0 L D Z");
        TABLE.put("enjoy", "IH0 N JH OY1");
        TABLE.put("enough", "IH0 N AH1 F");
        TABLE.put("er", "ER0");
        TABLE.put("even", "IY1 V AH0 N");
        TABLE.put("every", "EH1 V R IY0");
        TABLE.put("everything", "EH1 V R IY0 TH IH0 NG");
        TABLE.put("expensive", "IH0 K S P EH1 N S IH0 V");
        TABLE.put("eye", "AY1");
        TABLE.put("eyes", "AY1 Z");
        TABLE.put("face", "F EY1 S");
        TABLE.put("farm", "F AA1 M");
        TABLE.put("fellow", "F EH1 L OW0");
        TABLE.put("few", "F Y UW1");
        TABLE.put("find", "F AY1 N D");
        TABLE.put("fine", "F AY1 N");
        TABLE.put("first", "F ER1 S T");
        TABLE.put("five", "F AY1 V");
        TABLE.put("food", "F UW1 D");
        TABLE.put("for", "F AO1");
        TABLE.put("found", "F AW1 N D");
        TABLE.put("four", "F AO1");
        TABLE.put("friend", "F R EH1 N D");
        TABLE.put("friends", "F R EH1 N D Z");
        TABLE.put("from", "F R OQ1 M");
        TABLE.put("gave", "G EY1 V");
        TABLE.put("gentle", "JH EH1 N T AH0 L");
        TABLE.put("get", "G EH1 T");
        TABLE.put("gets", "G EH1 T S");
        TABLE.put("girl", "G ER1 L");
        TABLE.put("give", "G IH1 V");
        TABLE.put("gives", "G IH1 V Z");
        TABLE.put("gnome", "N OW1 M");
        TABLE.put("go", "G OW1");
        TABLE.put("goes", "G OW1 Z");
        TABLE.put("going", "G OW1 IH0 NG");
        TABLE.put("gone", "G OQ1 N");
        TABLE.put("good", "G UH1 D");
        TABLE.put("goodbye", "G UH2 D B AY1");
        TABLE.put("gosh", "G OQ1 SH");
        TABLE.put("got", "G OQ1 T");
        TABLE.put("great", "G R EY1 T");
        TABLE.put("had", "HH AE1 D");
        TABLE.put("haggle", "HH AE1 G AH0 L");
        TABLE.put("haggling", "HH AE1 G L IH0 NG");
        TABLE.put("hand", "HH AE1 N D");
        TABLE.put("has", "HH AE1 Z");
        TABLE.put("have", "HH AE1 V");
        TABLE.put("he", "HH IY1");
        TABLE.put("head", "HH EH1 D");
        TABLE.put("heavy", "HH EH1 V IY0");
        TABLE.put("hello", "HH EH0 L OW1");
        TABLE.put("help", "HH EH1 L P");
        TABLE.put("her", "HH ER1");
        TABLE.put("here", "HH IH1 AX0");
        TABLE.put("heres", "HH IH1 AX0 Z");
        TABLE.put("hers", "HH ER1 Z");
        TABLE.put("hey", "HH EY1");
        TABLE.put("hi", "HH AY1");
        TABLE.put("him", "HH IH1 M");
        TABLE.put("his", "HH IH1 Z");
        TABLE.put("hmm", "HMM");
        TABLE.put("hmmm", "HMM");
        TABLE.put("hmmmm", "HMM");
        TABLE.put("home", "HH OW1 M");
        TABLE.put("house", "HH AW1 S");
        TABLE.put("how", "HH AW1");
        TABLE.put("i", "AY1");
        TABLE.put("id", "AY1 D");
        TABLE.put("if", "IH1 F");
        TABLE.put("ill", "AY1 L");
        TABLE.put("im", "AY1 M");
        TABLE.put("in", "IH0 N");
        TABLE.put("into", "IH1 N T UW0");
        TABLE.put("is", "IH1 Z");
        TABLE.put("island", "AY1 L AX0 N D");
        TABLE.put("isnt", "IH1 Z AH0 N T");
        TABLE.put("it", "IH1 T");
        TABLE.put("its", "IH1 T S");
        TABLE.put("ive", "AY1 V");
        TABLE.put("just", "JH AH1 S T");
        TABLE.put("kind", "K AY1 N D");
        TABLE.put("know", "N OW1");
        TABLE.put("knowing", "N OW1 IH0 NG");
        TABLE.put("knowledge", "N OQ1 L AH0 JH");
        TABLE.put("known", "N OW1 N");
        TABLE.put("knows", "N OW1 Z");
        TABLE.put("left", "L EH1 F T");
        TABLE.put("less", "L EH1 S");
        TABLE.put("lets", "L EH1 T S");
        TABLE.put("life", "L AY1 F");
        TABLE.put("like", "L AY1 K");
        TABLE.put("likes", "L AY1 K S");
        TABLE.put("listen", "L IH1 S AH0 N");
        TABLE.put("little", "L IH1 T AH0 L");
        TABLE.put("ll", "L");
        TABLE.put("look", "L UH1 K");
        TABLE.put("looking", "L UH1 K IH0 NG");
        TABLE.put("lookit", "L UH1 K IH0 T");
        TABLE.put("looks", "L UH1 K S");
        TABLE.put("lot", "L OQ1 T");
        TABLE.put("love", "L AH1 V");
        TABLE.put("lovely", "L AH1 V L IY0");
        TABLE.put("loves", "L AH1 V Z");
        TABLE.put("m", "M");
        TABLE.put("made", "M EY1 D");
        TABLE.put("magical", "M AE1 JH IH0 K AX0 L");
        TABLE.put("magnificent", "M AE2 G N IH1 F AH0 S AH0 N T");
        TABLE.put("make", "M EY1 K");
        TABLE.put("makes", "M EY1 K S");
        TABLE.put("man", "M AE1 N");
        TABLE.put("many", "M EH1 N IY0");
        TABLE.put("may", "M EY1");
        TABLE.put("me", "M IY1");
        TABLE.put("men", "M EH1 N");
        TABLE.put("might", "M AY1 T");
        TABLE.put("mm", "HMM");
        TABLE.put("mmm", "HMM");
        TABLE.put("money", "M AH1 N IY0");
        TABLE.put("more", "M AO1");
        TABLE.put("morning", "M AO1 N IH0 NG");
        TABLE.put("most", "M OW1 S T");
        TABLE.put("much", "M AH1 CH");
        TABLE.put("must", "M AH1 S T");
        TABLE.put("my", "M AY1");
        TABLE.put("nah", "N AA1");
        TABLE.put("nation", "N EY1 SH AX0 N");
        TABLE.put("need", "N IY1 D");
        TABLE.put("needs", "N IY1 D Z");
        TABLE.put("never", "N EH1 V AX0");
        TABLE.put("new", "N Y UW1");
        TABLE.put("news", "N Y UW1 Z");
        TABLE.put("nice", "N AY1 S");
        TABLE.put("night", "N AY1 T");
        TABLE.put("no", "N OW1");
        TABLE.put("nope", "N OW1 P");
        TABLE.put("nose", "N OW1 Z");
        TABLE.put("not", "N OQ1 T");
        TABLE.put("nothing", "N AH1 TH IH0 NG");
        TABLE.put("now", "N AW1");
        TABLE.put("nt", "AH0 N T");
        TABLE.put("odd", "OQ1 D");
        TABLE.put("of", "AX0 V");
        TABLE.put("off", "OQ1 F");
        TABLE.put("often", "OQ1 F AH0 N");
        TABLE.put("oh", "OW1");
        TABLE.put("ok", "OW2 K EY1");
        TABLE.put("okay", "OW1 K EY1");
        TABLE.put("old", "OW1 L D");
        TABLE.put("on", "OQ1 N");
        TABLE.put("one", "W AH1 N");
        TABLE.put("only", "OW1 N L IY0");
        TABLE.put("onto", "OQ1 N T UW0");
        TABLE.put("ooh", "UW1");
        TABLE.put("oops", "UW1 P S");
        TABLE.put("open", "OW1 P AH0 N");
        TABLE.put("or", "AO1");
        TABLE.put("other", "AH1 DH AX0");
        TABLE.put("ouch", "AW1 CH");
        TABLE.put("out", "AW1 T");
        TABLE.put("over", "OW1 V AX0");
        TABLE.put("ow", "AW1");
        TABLE.put("own", "OW1 N");
        TABLE.put("pay", "P EY1");
        TABLE.put("people", "P IY1 P AH0 L");
        TABLE.put("perfect", "P ER1 F AH0 K T");
        TABLE.put("place", "P L EY1 S");
        TABLE.put("please", "P L IY1 Z");
        TABLE.put("pork", "P AO1 K");
        TABLE.put("pretty", "P R IH1 T IY0");
        TABLE.put("price", "P R AY1 S");
        TABLE.put("prices", "P R AY1 S IH0 Z");
        TABLE.put("put", "P UH1 T");
        TABLE.put("re", "AX0");
        TABLE.put("ready", "R EH1 D IY0");
        TABLE.put("really", "R IY1 L IY0");
        TABLE.put("receipt", "R IH0 S IY1 T");
        TABLE.put("report", "R IH0 P AO1 T");
        TABLE.put("right", "R AY1 T");
        TABLE.put("rough", "R AH1 F");
        TABLE.put("running", "R AH1 N IH0 NG");
        TABLE.put("s", "Z");
        TABLE.put("said", "S EH1 D");
        TABLE.put("same", "S EY1 M");
        TABLE.put("saw", "S AO1");
        TABLE.put("say", "S EY1");
        TABLE.put("says", "S EH1 Z");
        TABLE.put("see", "S IY1");
        TABLE.put("seen", "S IY1 N");
        TABLE.put("sees", "S IY1 Z");
        TABLE.put("sell", "S EH1 L");
        TABLE.put("shall", "SH AE1 L");
        TABLE.put("she", "SH IY1");
        TABLE.put("sheep", "SH IY1 P");
        TABLE.put("shop", "SH OQ1 P");
        TABLE.put("should", "SH UH1 D");
        TABLE.put("sign", "S AY1 N");
        TABLE.put("small", "S M AO1 L");
        TABLE.put("so", "S OW1");
        TABLE.put("some", "S AH1 M");
        TABLE.put("something", "S AH1 M TH IH0 NG");
        TABLE.put("sorry", "S OQ1 R IY0");
        TABLE.put("sort", "S AO1 T");
        TABLE.put("sparkly", "S P AA1 K L IY0");
        TABLE.put("special", "S P EH1 SH AX0 L");
        TABLE.put("station", "S T EY1 SH AX0 N");
        TABLE.put("still", "S T IH1 L");
        TABLE.put("stopped", "S T OQ1 P T");
        TABLE.put("story", "S T AO1 R IY0");
        TABLE.put("such", "S AH1 CH");
        TABLE.put("sure", "SH UH1 AX0");
        TABLE.put("take", "T EY1 K");
        TABLE.put("takes", "T EY1 K S");
        TABLE.put("tell", "T EH1 L");
        TABLE.put("ten", "T EH1 N");
        TABLE.put("testificate", "T EH1 S T IH0 F AH0 K EY2 T");
        TABLE.put("than", "DH AE1 N");
        TABLE.put("thank", "TH AE1 NG K");
        TABLE.put("thanks", "TH AE1 NG K S");
        TABLE.put("that", "DH AE1 T");
        TABLE.put("thats", "DH AE1 T S");
        TABLE.put("the", "DH AX0");
        TABLE.put("theend", "DH IY1 EH1 N D");
        TABLE.put("their", "DH EH1 AX0");
        TABLE.put("them", "DH EH1 M");
        TABLE.put("then", "DH EH1 N");
        TABLE.put("there", "DH EH1 AX0");
        TABLE.put("theres", "DH EH1 AX0 Z");
        TABLE.put("these", "DH IY1 Z");
        TABLE.put("they", "DH EY1");
        TABLE.put("thing", "TH IH1 NG");
        TABLE.put("things", "TH IH1 NG Z");
        TABLE.put("think", "TH IH1 NG K");
        TABLE.put("this", "DH IH1 S");
        TABLE.put("those", "DH OW1 Z");
        TABLE.put("though", "DH OW1");
        TABLE.put("thought", "TH AO1 T");
        TABLE.put("three", "TH R IY1");
        TABLE.put("through", "TH R UW1");
        TABLE.put("thrown", "TH R OW1 N");
        TABLE.put("time", "T AY1 M");
        TABLE.put("to", "T AX0");
        TABLE.put("today", "T AX0 D EY1");
        TABLE.put("told", "T OW1 L D");
        TABLE.put("tomorrow", "T AX0 M OQ1 R OW0");
        TABLE.put("tonight", "T AX0 N AY1 T");
        TABLE.put("too", "T UW1");
        TABLE.put("tough", "T AH1 F");
        TABLE.put("trade", "T R EY1 D");
        TABLE.put("trades", "T R EY1 D Z");
        TABLE.put("trading", "T R EY1 D IH0 NG");
        TABLE.put("two", "T UW1");
        TABLE.put("uh", "AX0");
        TABLE.put("um", "AX0 M");
        TABLE.put("under", "AH1 N D AX0");
        TABLE.put("up", "AH1 P");
        TABLE.put("us", "AH1 S");
        TABLE.put("ve", "V");
        TABLE.put("very", "V EH1 R IY0");
        TABLE.put("village", "V IH1 L AH0 JH");
        TABLE.put("villager", "V IH1 L AH0 JH AX0");
        TABLE.put("villagers", "V IH1 L AH0 JH AX0 Z");
        TABLE.put("wait", "W EY1 T");
        TABLE.put("want", "W OQ1 N T");
        TABLE.put("wants", "W OQ1 N T S");
        TABLE.put("was", "W OQ1 Z");
        TABLE.put("wasnt", "W OQ1 Z AH0 N T");
        TABLE.put("water", "W AO1 T AX0");
        TABLE.put("way", "W EY1");
        TABLE.put("we", "W IY1");
        TABLE.put("well", "W EH1 L");
        TABLE.put("were", "W ER1");
        TABLE.put("what", "W OQ1 T");
        TABLE.put("whats", "W OQ1 T S");
        TABLE.put("when", "W EH1 N");
        TABLE.put("where", "W EH1 AX0");
        TABLE.put("which", "W IH1 CH");
        TABLE.put("while", "W AY1 L");
        TABLE.put("who", "HH UW1");
        TABLE.put("whoa", "W OW1");
        TABLE.put("whom", "HH UW1 M");
        TABLE.put("whos", "HH UW1 Z");
        TABLE.put("whose", "HH UW1 Z");
        TABLE.put("why", "W AY1");
        TABLE.put("will", "W IH1 L");
        TABLE.put("with", "W IH1 DH");
        TABLE.put("without", "W IH0 DH AW1 T");
        TABLE.put("woman", "W UH1 M AX0 N");
        TABLE.put("wonderful", "W AH1 N D AX0 F AX0 L");
        TABLE.put("wont", "W OW1 N T");
        TABLE.put("work", "W ER1 K");
        TABLE.put("working", "W ER1 K IH0 NG");
        TABLE.put("works", "W ER1 K S");
        TABLE.put("world", "W ER1 L D");
        TABLE.put("would", "W UH1 D");
        TABLE.put("wouldnt", "W UH1 D AH0 N T");
        TABLE.put("wow", "W AW1");
        TABLE.put("yay", "Y EY1");
        TABLE.put("yep", "Y EH1 P");
        TABLE.put("yes", "Y EH1 S");
        TABLE.put("yesterday", "Y EH1 S T AX0 D EY2");
        TABLE.put("you", "Y UW1");
        TABLE.put("youd", "Y UW1 D");
        TABLE.put("youll", "Y UW1 L");
        TABLE.put("your", "Y AO1");
        TABLE.put("youre", "Y UH1 AX0");
        TABLE.put("yours", "Y AO1 Z");
        TABLE.put("youve", "Y UW1 V");
        TABLE.put("zombie", "Z AO1 M B IY0");
        TABLE.put("zombies", "Z AO1 M B IY0 Z");
    }

    /** The curated table, keyed by normalised word. */
    public static Map<String, String> all() {
        return Collections.unmodifiableMap(TABLE);
    }

    /** How many words are curated (377). */
    public static int size() {
        return TABLE.size();
    }

    /** The pronunciation for a normalised word, or null if not curated. */
    public static String get(String normalisedWord) {
        return normalisedWord == null ? null : TABLE.get(normalisedWord);
    }

    public static boolean contains(String normalisedWord) {
        return normalisedWord != null && TABLE.containsKey(normalisedWord);
    }
}
