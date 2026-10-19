package villager.voice.synth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Text to performance score: the timing and prosody layer.
 *
 * <p>Produces the same score structure {@link FormantSynthesizer} consumes, so a
 * score built here, one built by the Python engine, and one written by hand are
 * interchangeable. Timing is scaled to the corpus speaking rate measured from
 * {@code clips_index.json} (3.31 syllables/s over 2,252 clips); the Python
 * implementation generates 3.04&nbsp;syl/s against that target.</p>
 *
 * <p>Mirrors {@code tools/villager_voice/prosody.py}. One difference is worth
 * stating: the voiceprint's {@code phone_overrides} section is applied by the
 * Python engine and not yet by this port.</p>
 */
public final class ProsodyScore {

    public static final String SCORE_FORMAT = "villager-voice-score";
    public static final int SCORE_VERSION = 1;

    public static final String BOUNDARY = "boundary";
    public static final String COMMA = "comma";
    public static final String DASH = "dash";

    private ProsodyScore() {
    }

    // ---------------------------------------------------------------------
    // builder
    // ---------------------------------------------------------------------

    /** Assembles a performance score from words, phones and prosodic marks. */
    static final class Builder {
        final VoicePrint voiceprint;
        final Random rng;
        final List<Map<String, Object>> phones = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> pauses = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> words = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> phrases = new ArrayList<Map<String, Object>>();
        final List<Object> events = new ArrayList<Object>();
        final List<double[]> f0Points = new ArrayList<double[]>();
        final List<double[]> ampPoints = new ArrayList<double[]>();
        double time = 0.0;

        Builder(VoicePrint voiceprint, Integer seed) {
            this.voiceprint = voiceprint;
            long value = seed != null ? seed.longValue()
                    : (long) voiceprint.getDouble("random_seed", 20261004);
            this.rng = new Random(value);
        }

        void addPause(double seconds) {
            if (seconds <= 0) {
                return;
            }
            Map<String, Object> pause = new LinkedHashMap<String, Object>();
            pause.put("at", Double.valueOf(round(time, 4)));
            pause.put("dur", Double.valueOf(round(seconds, 4)));
            pauses.add(pause);
            time += seconds;
        }

        Map<String, Object> addPhone(String symbol, int stress, int wordIndex,
                                     int syllableIndex, double duration, double amp) {
            VillagerPhones.Phone spec = VillagerPhones.get(symbol);
            double[] targets = VillagerPhones.formantTargets(symbol, 0);
            double[] bandwidths = VillagerPhones.bandwidthTargets(symbol, 0);

            Map<String, Object> slot = new LinkedHashMap<String, Object>();
            slot.put("sym", symbol);
            slot.put("start", Double.valueOf(round(time, 5)));
            slot.put("dur", Double.valueOf(round(Math.max(0.005, duration), 5)));
            slot.put("stress", Integer.valueOf(stress));
            slot.put("amp", Double.valueOf(round(amp, 5)));
            slot.put("word", Integer.valueOf(wordIndex));
            slot.put("syll", Integer.valueOf(syllableIndex));
            slot.put("manner", spec.manner);
            slot.put("voiced", Boolean.valueOf(spec.voiced));
            slot.put("f", rounded(targets, 1));
            slot.put("bw", rounded(bandwidths, 1));
            phones.add(slot);
            time += ((Number) slot.get("dur")).doubleValue();
            return slot;
        }

        Map<String, Object> score(String text) {
            Map<String, Object> contours = new LinkedHashMap<String, Object>();
            contours.put("f0", pairs(f0Points));
            contours.put("amp", pairs(ampPoints));
            contours.put("formant_scale", new ArrayList<Object>());
            contours.put("breathiness", new ArrayList<Object>());

            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("format", SCORE_FORMAT);
            out.put("version", Integer.valueOf(SCORE_VERSION));
            out.put("text", text == null ? "" : text);
            out.put("sample_rate",
                    Integer.valueOf(voiceprint.getInt("output.sample_rate", 24000)));
            out.put("duration", Double.valueOf(round(time, 4)));
            out.put("phones", phones);
            out.put("pauses", pauses);
            out.put("words", words);
            out.put("phrases", phrases);
            out.put("contours", contours);
            out.put("events", events);
            out.put("random_seed", Long.valueOf(rng.hashCode() & 0xffffffffL));
            return out;
        }

        private static List<Object> pairs(List<double[]> points) {
            List<Object> out = new ArrayList<Object>(points.size());
            for (double[] point : points) {
                List<Object> pair = new ArrayList<Object>(2);
                pair.add(Double.valueOf(point[0]));
                pair.add(Double.valueOf(point[1]));
                out.add(pair);
            }
            return out;
        }

        private static List<Object> rounded(double[] values, int places) {
            List<Object> out = new ArrayList<Object>(values.length);
            for (double value : values) {
                out.add(Double.valueOf(round(value, places)));
            }
            return out;
        }
    }

    static double round(double value, int places) {
        double factor = Math.pow(10.0, places);
        return Math.round(value * factor) / factor;
    }

    // ---------------------------------------------------------------------
    // public entry point
    // ---------------------------------------------------------------------

    /**
     * Generate a performance score for one line of text.
     *
     * @param text     the line to speak
     * @param print    the voiceprint
     * @param delivery optional per-line overrides matching the existing
     *                 {@code SpeechOptions} fields: {@code emotion},
     *                 {@code tone}, {@code sarcasm}, {@code speed},
     *                 {@code question}
     * @param seed     random seed override, or null for the voiceprint's
     */
    public static Map<String, Object> build(String text, VoicePrint print,
                                            Map<String, Object> delivery, Integer seed) {
        VoicePrint effective = print.effective();
        if (delivery != null && !delivery.isEmpty()) {
            Map<String, Object> overrides = new LinkedHashMap<String, Object>();
            if (delivery.containsKey("emotion")) {
                section(overrides, "character").put("emotion", delivery.get("emotion"));
            }
            if (delivery.containsKey("tone")) {
                section(overrides, "character").put("tone", delivery.get("tone"));
            }
            if (delivery.containsKey("sarcasm")) {
                section(overrides, "character").put("sarcasm", delivery.get("sarcasm"));
            }
            if (delivery.containsKey("speed")) {
                double speed = Math.max(0.2,
                        FormantSynthesizer.num(delivery.get("speed"), 1.0));
                section(overrides, "articulation")
                        .put("duration_scale", Double.valueOf(1.0 / speed));
                section(overrides, "prosody").put("rate_syllables_per_sec",
                        Double.valueOf(effective.getDouble(
                                "prosody.rate_syllables_per_sec", 3.31) * speed));
            }
            if (!overrides.isEmpty()) {
                effective = effective.merged(overrides).effective();
            }
        }

        boolean question = delivery != null
                && FormantSynthesizer.bool(delivery.get("question"), false);
        String line = text == null ? "" : text;
        if (!question) {
            question = line.trim().endsWith("?");
        }

        List<VillagerLexicon.Token> tokens = VillagerLexicon.textToWords(line);
        List<VillagerLexicon.Token> words = new ArrayList<VillagerLexicon.Token>();
        for (VillagerLexicon.Token token : tokens) {
            if (token.isWord()) {
                words.add(token);
            }
        }

        double durationScale = effective.getDouble("articulation.duration_scale", 1.0);
        double vowelScale = effective.getDouble("articulation.vowel_duration_scale", 1.0);
        double consonantScale = effective.getDouble("articulation.consonant_duration_scale", 1.0);
        double rate = effective.getDouble("prosody.rate_syllables_per_sec", 3.31);
        if (rate <= 0) {
            rate = 3.31;
        }

        Builder builder = new Builder(effective, seed);

        // --- pass 1: the phone stream with intrinsic durations -------------
        List<StreamPhone> stream = new ArrayList<StreamPhone>();
        List<Object[]> wordSpans = new ArrayList<Object[]>();
        int syllableCount = 0;
        for (int wordIndex = 0; wordIndex < words.size(); wordIndex++) {
            List<VillagerLexicon.Phone> wordPhones =
                    VillagerLexicon.pronounce(words.get(wordIndex).text);
            if (wordPhones.isEmpty()) {
                continue;
            }
            int first = stream.size();
            List<List<VillagerLexicon.Phone>> groups = syllableGroups(wordPhones);
            for (int syllableIndex = 0; syllableIndex < groups.size(); syllableIndex++) {
                boolean nucleusSeen = false;
                for (VillagerLexicon.Phone phone : groups.get(syllableIndex)) {
                    VillagerPhones.Phone spec = VillagerPhones.get(phone.symbol);
                    double base = spec.dur;
                    if (VillagerPhones.isSyllabic(phone.symbol)) {
                        base *= vowelScale;
                        if (!nucleusSeen) {
                            syllableCount++;
                            nucleusSeen = true;
                        }
                    } else {
                        base *= consonantScale;
                    }
                    if (phone.stress == 1) {
                        base *= 1.18;
                    } else if (phone.stress == 2) {
                        base *= 1.06;
                    } else if (VillagerPhones.isSyllabic(phone.symbol)) {
                        base *= 1.0 - 0.12
                                * effective.getDouble("articulation.vowel_reduction", 0.5);
                    }
                    StreamPhone item = new StreamPhone();
                    item.symbol = phone.symbol;
                    item.stress = phone.stress;
                    item.word = wordIndex;
                    item.syllable = syllableIndex;
                    item.base = base;
                    item.manner = spec.manner;
                    stream.add(item);
                }
            }
            wordSpans.add(new Object[]{words.get(wordIndex).text,
                    Integer.valueOf(first), Integer.valueOf(stream.size() - 1)});
        }

        if (stream.isEmpty()) {
            // Nothing pronounceable: fall back to the villager murmur.
            builder.addPhone("HMM", 1, 0, 0, 0.45, VillagerPhones.get("HMM").amp);
            Map<String, Object> word = new LinkedHashMap<String, Object>();
            word.put("text", line);
            word.put("start", Double.valueOf(0.0));
            word.put("end", Double.valueOf(0.45));
            builder.words.add(word);
            Map<String, Object> score = builder.score(line);
            score.put("voiceprint", effective.data());
            return score;
        }

        // --- pass 2: phrase structure -------------------------------------
        // Walk the token stream so punctuation lands at the right place in time.
        List<Object[]> phraseMarks = new ArrayList<Object[]>();
        int phraseIndex = 0;
        int streamWordCursor = 0;
        for (VillagerLexicon.Token token : tokens) {
            if (token.isWord()) {
                if (streamWordCursor < wordSpans.size()) {
                    streamWordCursor++;
                }
            } else if (BOUNDARY.equals(token.kind) || COMMA.equals(token.kind)
                    || DASH.equals(token.kind)) {
                if (streamWordCursor > 0 && streamWordCursor <= wordSpans.size()) {
                    int lastIndex = ((Integer) wordSpans.get(streamWordCursor - 1)[2]).intValue();
                    phraseMarks.add(new Object[]{Integer.valueOf(lastIndex), token.kind,
                            token.text});
                }
            }
        }
        if (phraseMarks.isEmpty()
                || ((Integer) phraseMarks.get(phraseMarks.size() - 1)[0]).intValue()
                        != stream.size() - 1) {
            // The last phone always closes a phrase, even without punctuation.
            phraseMarks.add(new Object[]{Integer.valueOf(stream.size() - 1), BOUNDARY, "."});
        }

        // --- pass 3: scale to the measured speaking rate -------------------
        // The corpus rate (syllables per clip second) already includes the pauses
        // inside a clip, so the pause budget comes out of the total before phones
        // are scaled. Otherwise generated lines run slower than recorded ones even
        // though every phone sits at its intrinsic length.
        double pauseBudget = estimatePauseBudget(effective, words.size(), phraseMarks);
        double totalBase = 0.0;
        for (StreamPhone item : stream) {
            totalBase += item.base;
        }
        if (totalBase <= 0) {
            totalBase = 1.0;
        }
        double gross = Math.max(0.25, syllableCount / rate);
        double targetSpeech = Math.max(0.35 * gross, gross - pauseBudget);

        double undershoot = effective.getDouble("articulation.undershoot", 0.0);
        double scale = totalBase > 0 ? targetSpeech / totalBase : 1.0;
        if (scale < 1.0 && undershoot > 0.0) {
            // At fast rates, consonants compress more than vowels.
            for (StreamPhone item : stream) {
                if (!VillagerPhones.isSyllabic(item.symbol)) {
                    item.base *= 1.0 - undershoot * (1.0 - scale);
                }
            }
            totalBase = 0.0;
            for (StreamPhone item : stream) {
                totalBase += item.base;
            }
            if (totalBase <= 0) {
                totalBase = 1.0;
            }
        }
        scale = totalBase > 0 ? targetSpeech / totalBase : 1.0;

        // --- pass 4: emit phones, pauses and word timings ------------------
        Map<Integer, Double> wordStartTimes = new LinkedHashMap<Integer, Double>();
        double preBoundary = effective.getDouble("prosody.pre_boundary_lengthening", 1.3);
        double pauseShort = effective.getDouble("prosody.pause_short_ms", 130.0) / 1000.0;
        double pauseComma = effective.getDouble("prosody.pause_comma_ms", 250.0) / 1000.0;
        double pauseSentence = effective.getDouble("prosody.pause_sentence_ms", 470.0) / 1000.0;
        double variability = effective.getDouble("prosody.pause_variability", 0.18);

        Set<Integer> phraseLastIndices = new LinkedHashSet<Integer>();
        for (Object[] mark : phraseMarks) {
            phraseLastIndices.add((Integer) mark[0]);
        }
        phraseLastIndices.add(Integer.valueOf(stream.size() - 1));

        builder.addPause(pauseShort * 0.5);
        for (int index = 0; index < stream.size(); index++) {
            StreamPhone item = stream.get(index);
            double duration = item.base * scale * durationScale;
            if (isPhraseFinal(stream, phraseLastIndices, index)
                    && VillagerPhones.isSyllabic(item.symbol)) {
                duration *= preBoundary;
            }

            // A small between-word gap, jittered like real speech.
            if (index > 0 && stream.get(index - 1).word != item.word) {
                double gap = pauseShort * 0.22
                        * (1.0 + variability * (builder.rng.nextDouble() * 2 - 1));
                if (VillagerPhones.STOP.equals(item.manner)
                        || VillagerPhones.AFFRICATE.equals(item.manner)) {
                    gap *= 0.4;
                }
                builder.addPause(gap);
            }

            if (!wordStartTimes.containsKey(Integer.valueOf(item.word))) {
                wordStartTimes.put(Integer.valueOf(item.word), Double.valueOf(builder.time));
            }

            double amp = VillagerPhones.get(item.symbol).amp;
            Map<String, Object> slot = builder.addPhone(item.symbol, item.stress,
                    item.word, item.syllable, duration, amp);
            slot.put("phrase", Integer.valueOf(phraseIndex));

            for (Object[] mark : phraseMarks) {
                if (((Integer) mark[0]).intValue() == index) {
                    String kind = (String) mark[1];
                    String token = (String) mark[2];
                    if (COMMA.equals(kind)) {
                        builder.addPause(pauseComma
                                * (1.0 + variability * (builder.rng.nextDouble() * 2 - 1)));
                    } else if (BOUNDARY.equals(kind)) {
                        builder.addPause(pauseSentence
                                * (1.0 + variability * (builder.rng.nextDouble() * 2 - 1)));
                    }
                    Map<String, Object> phrase = new LinkedHashMap<String, Object>();
                    phrase.put("index", Integer.valueOf(phraseIndex));
                    phrase.put("end", Double.valueOf(round(builder.time, 4)));
                    phrase.put("punctuation", token);
                    phrase.put("kind", "?".equals(token) ? "question"
                            : ("!".equals(token) ? "exclamation" : "declarative"));
                    builder.phrases.add(phrase);
                    phraseIndex++;
                }
            }
        }

        // Word timings, for captions and JSON inspection.
        for (int wordIndex = 0; wordIndex < wordSpans.size(); wordIndex++) {
            Object[] span = wordSpans.get(wordIndex);
            Double start = wordStartTimes.get(Integer.valueOf(wordIndex));
            double end = 0.0;
            for (Map<String, Object> slot : builder.phones) {
                if (((Integer) slot.get("word")).intValue() == wordIndex) {
                    end = Math.max(end, ((Number) slot.get("start")).doubleValue()
                            + ((Number) slot.get("dur")).doubleValue());
                }
            }
            Map<String, Object> word = new LinkedHashMap<String, Object>();
            word.put("text", span[0]);
            word.put("start", Double.valueOf(round(start != null ? start.doubleValue() : 0.0, 4)));
            word.put("end", Double.valueOf(round(end, 4)));
            builder.words.add(word);
        }
        if (builder.phrases.isEmpty()) {
            Map<String, Object> phrase = new LinkedHashMap<String, Object>();
            phrase.put("index", Integer.valueOf(0));
            phrase.put("end", Double.valueOf(round(builder.time, 4)));
            phrase.put("punctuation", ".");
            phrase.put("kind", "declarative");
            builder.phrases.add(phrase);
        }
        builder.addPause(pauseShort * 0.6);

        buildF0Contour(builder, effective, question, phraseMarks);
        buildAmpContour(builder, effective);

        Map<String, Object> score = builder.score(line);
        score.put("voiceprint", effective.data());
        return score;
    }

    /** Build a score with no delivery overrides. */
    public static Map<String, Object> build(String text, VoicePrint print) {
        return build(text, print, null, null);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** One phone in the pre-timing stream. */
    private static final class StreamPhone {
        String symbol;
        int stress;
        int word;
        int syllable;
        double base;
        String manner;
    }

    /** True for phones in the final syllable of a phrase. */
    private static boolean isPhraseFinal(List<StreamPhone> stream,
                                         Set<Integer> phraseLastIndices, int index) {
        StreamPhone item = stream.get(index);
        for (Integer position : phraseLastIndices) {
            int at = position.intValue();
            if (at < 0 || at >= stream.size()) {
                continue;
            }
            StreamPhone target = stream.get(at);
            if (target.word == item.word && target.syllable == item.syllable) {
                return true;
            }
        }
        return false;
    }

    /** Total seconds the pauses will occupy, for rate compensation. */
    private static double estimatePauseBudget(VoicePrint effective, int wordCount,
                                              List<Object[]> phraseMarks) {
        double pauseShort = effective.getDouble("prosody.pause_short_ms", 130.0) / 1000.0;
        double comma = effective.getDouble("prosody.pause_comma_ms", 250.0) / 1000.0;
        double sentence = effective.getDouble("prosody.pause_sentence_ms", 470.0) / 1000.0;
        int commas = 0;
        int ends = 0;
        for (Object[] mark : phraseMarks) {
            if (COMMA.equals(mark[1])) {
                commas++;
            } else if (BOUNDARY.equals(mark[1])) {
                ends++;
            }
        }
        double gaps = Math.max(0, wordCount - 1) * pauseShort * 0.22;
        return commas * comma + ends * sentence + gaps + pauseShort * 1.1;
    }

    /** Group phones into syllables around each nucleus. */
    static List<List<VillagerLexicon.Phone>> syllableGroups(
            List<VillagerLexicon.Phone> phones) {
        List<List<VillagerLexicon.Phone>> groups =
                new ArrayList<List<VillagerLexicon.Phone>>();
        List<VillagerLexicon.Phone> current = new ArrayList<VillagerLexicon.Phone>();
        for (VillagerLexicon.Phone phone : phones) {
            if (VillagerPhones.isSyllabic(phone.symbol) && !current.isEmpty()) {
                boolean alreadyHasNucleus = false;
                for (VillagerLexicon.Phone earlier : current) {
                    if (VillagerPhones.isSyllabic(earlier.symbol)) {
                        alreadyHasNucleus = true;
                        break;
                    }
                }
                if (alreadyHasNucleus) {
                    groups.add(current);
                    current = new ArrayList<VillagerLexicon.Phone>();
                }
            }
            current.add(phone);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** Pitch contour: declination, stress accents and boundary movements. */
    private static void buildF0Contour(Builder builder, VoicePrint effective,
                                       boolean question, List<Object[]> phraseMarks) {
        double declination = effective.getDouble("prosody.declination_semitones_per_sec", -0.85);
        double accent = effective.getDouble("prosody.accent_rise_semitones", 3.0);
        double stressRise = effective.getDouble("prosody.stress_rise_semitones", 2.1);
        double reset = effective.getDouble("prosody.f0_reset_semitones", 1.6);
        double finalFall = effective.getDouble("prosody.phrase_final_fall_semitones", -3.4);
        double questionRise = effective.getDouble("prosody.question_rise_semitones", 4.6);
        double exclamationRise = effective.getDouble("prosody.exclamation_rise_semitones", 1.8);

        Map<Integer, String> marksByIndex = new LinkedHashMap<Integer, String>();
        for (Object[] mark : phraseMarks) {
            marksByIndex.put((Integer) mark[0], (String) mark[2]);
        }

        List<double[]> points = new ArrayList<double[]>();
        double phraseStartTime = 0.0;
        int total = builder.phones.size();
        for (int index = 0; index < total; index++) {
            Map<String, Object> slot = builder.phones.get(index);
            double start = ((Number) slot.get("start")).doubleValue();
            double duration = ((Number) slot.get("dur")).doubleValue();
            double end = start + duration;
            int stress = ((Number) slot.get("stress")).intValue();
            if (index == 0) {
                points.add(new double[]{start, reset * 0.5});
            }
            if (!VillagerPhones.isSyllabic((String) slot.get("sym"))) {
                continue;
            }

            // Declination runs from the start of the current phrase.
            double base = declination * Math.max(0.0, start - phraseStartTime);
            double value = base;
            if (stress == 1) {
                value += accent;
            } else if (stress == 2) {
                value += stressRise;
            }

            String token = marksByIndex.get(Integer.valueOf(index));
            boolean isFinal = token != null || index == total - 1;
            if (isFinal) {
                if ("?".equals(token) || (question && index >= total - 3)) {
                    // Rise across the last syllable, peaking at the very end.
                    points.add(new double[]{start, base + 0.4 * Math.abs(questionRise)});
                    points.add(new double[]{end, base + questionRise});
                } else if ("!".equals(token)) {
                    points.add(new double[]{start, value + exclamationRise});
                    points.add(new double[]{end, base + finalFall * 0.5});
                } else {
                    points.add(new double[]{start, value});
                    points.add(new double[]{end, base + finalFall});
                }
                phraseStartTime = end;
                points.add(new double[]{end + 1e-4, reset});
                continue;
            }

            // Accented syllables peak early, then decay back to the baseline.
            if (stress != 0) {
                points.add(new double[]{start, base + value * 0.55});
                points.add(new double[]{start + duration * 0.32, value});
                points.add(new double[]{end, base + value * 0.35});
            } else {
                points.add(new double[]{start, base});
                points.add(new double[]{end, base});
            }
        }

        if (points.isEmpty()) {
            points.add(new double[]{0.0, 0.0});
            points.add(new double[]{builder.time, 0.0});
        }
        Collections.sort(points, new Comparator<double[]>() {
            @Override
            public int compare(double[] left, double[] right) {
                return Double.compare(left[0], right[0]);
            }
        });
        builder.f0Points.clear();
        for (double[] point : points) {
            builder.f0Points.add(new double[]{round(point[0], 4), round(point[1], 3)});
        }
    }

    /** Loudness contour: a phrase arc plus stress dynamics. */
    private static void buildAmpContour(Builder builder, VoicePrint effective) {
        double loudnessRange = effective.getDouble("prosody.loudness_range_db", 9.0);
        double stressDb = effective.getDouble("prosody.stress_loudness_db", 2.4);
        double softening = effective.getDouble("prosody.final_softening_db", -2.0);

        double total = Math.max(1e-6, builder.time);
        List<double[]> points = new ArrayList<double[]>();
        for (Map<String, Object> slot : builder.phones) {
            double start = ((Number) slot.get("start")).doubleValue();
            double end = start + ((Number) slot.get("dur")).doubleValue();
            int stress = ((Number) slot.get("stress")).intValue();
            double value = 0.0;
            if (stress == 1) {
                value += stressDb;
            } else if (stress == 2) {
                value += stressDb * 0.5;
            }
            // A gentle phrase arc: swell to a third of the way, ease off at the end.
            double progress = (start + end) * 0.5 / total;
            double arc = Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, progress))) - 0.6;
            value += arc * loudnessRange * 0.35;
            if (progress > 0.88) {
                value += softening * (progress - 0.88) / 0.12;
            }
            double scaled = value * (loudnessRange / 9.0);
            points.add(new double[]{round(start, 4), round(scaled, 3)});
            points.add(new double[]{round(end, 4), round(scaled, 3)});
        }
        if (points.isEmpty()) {
            points.add(new double[]{0.0, 0.0});
            points.add(new double[]{round(total, 4), 0.0});
        }
        builder.ampPoints.clear();
        builder.ampPoints.addAll(points);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> target, String name) {
        Object existing = target.get(name);
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        target.put(name, created);
        return created;
    }
}
