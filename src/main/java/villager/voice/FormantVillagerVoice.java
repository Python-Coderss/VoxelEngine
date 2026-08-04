package villager.voice;

import com.voxel.audio.KlattSynth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Original non-neural villager voice.
 *
 * Explicit brace notation is the source-of-truth input, for example
 * {@code {dh-schwa f-ah-m-er}}. It is intentionally not English TTS: phoneme
 * labels control the voiced-babble performance directly. A legacy spelling path
 * remains for compatibility with older callers, but sample generation should
 * always use explicit phonemes.
 */
public final class FormantVillagerVoice implements AutoCloseable {
    private static final int SAMPLE_RATE = KlattSynth.FS;
    private static final double TWO_PI = 2.0 * Math.PI;

    // Acoustic targets measured from the local TEAVSRP villager clips. This is
    // a hand-tuned formant profile, not a neural model: the reference clips
    // cluster around a 175-185 Hz speaking center with broad pitch excursions.
    private static final double TEAVSRP_BASE_F0 = 178.0;
    private static final double TEAVSRP_BREATH = 0.055;
    private static final double TEAVSRP_TILT_DB = -5.0;
    // The renamed reference clips contain deliberate held vowels and dramatic
    // pauses. The former spelling renderer was about 1.8x too fast against the
    // labeled corpus, so keep the user-facing speed control while applying the
    // measured TEAVSRP baseline timing to both text and explicit phonemes.
    private static final double TEAVSRP_TIMING_SCALE = 0.58;

    public WavAudio render(String text, SpeechOptions options) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }

        String normalized = text.trim();
        if (FormantPhonemes.isNotation(normalized)) {
            return renderPhonemes(normalized, options);
        }
        int hash = normalized.toLowerCase(Locale.ROOT).hashCode();
        double speed = options.getEffectiveSpeed() * TEAVSRP_TIMING_SCALE;
        double pitch = options.getEffectivePitchSemitones();
        double emphasis = emphasis(normalized);
        // TEAVSRP's villager clips have a higher conversational center and a
        // lively pitch range rather than a flat low male baseline. Keep the
        // profile restrained enough for dialogue, while leaving pitch controls
        // available to callers.
        double baseF0 = TEAVSRP_BASE_F0 * Math.pow(2.0, pitch / 12.0)
                * (1.0 + emphasis * 0.08);
        double warm = options.getEffectiveTone();
        double breath = TEAVSRP_BREATH + Math.max(0.0, warm) * 0.025;
        double tilt = TEAVSRP_TILT_DB - warm * 3.0;

        List<KlattSynth.Seg> segments = new ArrayList<KlattSynth.Seg>();
        addPhrase(segments, normalized, baseF0, speed, emphasis, hash);

        KlattSynth synth = new KlattSynth(breath, tilt);
        double singing = options.getSinging();
        float[] rendered = synth.render(segments,
                singing > 0.0 ? 4.8 + positiveMod(hash, 12) * 0.14 : 0.0,
                singing > 0.0 ? 0.020 + singing * 0.035 : 0.0);
        applyCharacter(rendered, hash, options.getEffectiveTone(),
                options.getEffectiveVolume(), emphasis);
        return new WavAudio(SAMPLE_RATE, rendered)
                .resampled(VillagerSynthesizer.DEFAULT_SAMPLE_RATE);
    }

    private static WavAudio renderPhonemes(String notation, SpeechOptions options) {
        int hash = notation.toLowerCase(Locale.ROOT).hashCode();
        double emphasis = notation.contains("stress") || notation.contains("'") ? 0.35 : 0.0;
        double speed = options.getEffectiveSpeed() * TEAVSRP_TIMING_SCALE;
        double f0 = TEAVSRP_BASE_F0
                * Math.pow(2.0, options.getEffectivePitchSemitones() / 12.0)
                * (1.0 + emphasis * 0.08);
        List<KlattSynth.Seg> segments = FormantPhonemes.toSegments(
                notation, f0, speed, emphasis);
        // One gentle phrase tail; do not append a pause after every phoneme
        // word, because that makes a hand-authored sentence sound chopped up.
        segments.add(new KlattSynth.Seg(70.0 / speed,
                500, 1500, 2500, f0 * 0.82, f0 * 0.72, 0.30, 0.02));
        segments.add(new KlattSynth.Seg(48.0 / speed,
                500, 1500, 2500, 0, 0, 0, 0));
        double warm = options.getEffectiveTone();
        KlattSynth synth = new KlattSynth(
                TEAVSRP_BREATH + Math.max(0.0, warm) * 0.02,
                TEAVSRP_TILT_DB - warm * 3.0);
        double singing = options.getSinging();
        float[] rendered = synth.render(segments,
                singing > 0.0 ? 4.8 : 0.0,
                singing > 0.0 ? 0.018 + singing * 0.035 : 0.0);
        applyCharacter(rendered, hash, warm, options.getEffectiveVolume(), emphasis);
        return new WavAudio(SAMPLE_RATE, rendered)
                .resampled(VillagerSynthesizer.DEFAULT_SAMPLE_RATE);
    }

    private static void addPhrase(List<KlattSynth.Seg> segments, String text, double f0,
                                  double speed, double emphasis, int hash) {
        String[] words = text.split("[^A-Za-z']+");
        int wordIndex = 0;
        for (String word : words) {
            if (word.length() == 0) {
                continue;
            }
            int currentWord = wordIndex++;
            addWord(segments, word, f0, speed, emphasis, hash, currentWord);
            // British rhythm is stress-timed: function words are shorter and
            // less prominent, while content-word boundaries get more space.
            double gap = isWeakWord(word) ? 10 : 25 + (currentWord % 2) * 7;
            segments.add(new KlattSynth.Seg(ms(gap, speed),
                    500, 1400, 2400, 0, 0, 0, 0));
        }

        if (wordIndex == 0) {
            addWord(segments, "hmm", f0, speed, emphasis, hash, 0);
        }

        char last = lastVisible(text);
        if (last == '!') {
            addExclamationTail(segments, f0, speed, emphasis);
        } else if (last == '?') {
            addQuestionTail(segments, f0, speed, emphasis);
        } else {
            segments.add(new KlattSynth.Seg(ms(75, speed), 520, 1400, 2400,
                    f0 * 0.78, f0 * 0.68, 0.28, 0.025));
            segments.add(new KlattSynth.Seg(ms(65, speed), 520, 1400, 2400,
                    0, 0, 0, 0));
        }
    }

    private static void addWord(List<KlattSynth.Seg> segments, String word, double f0,
                                double speed, double emphasis, int hash, int wordIndex) {
        String lower = word.toLowerCase(Locale.ROOT);
        int letters = Math.max(1, lower.length());
        boolean weak = isWeakWord(lower);
        // Gradual declination with small deterministic stress variation avoids
        // the repeated rise/fall pattern that made the old voice sound Indian.
        double declination = Math.max(0.82, 1.04 - wordIndex * 0.055);
        double wordF0 = f0 * declination
                * (weak ? 0.91 : 1.0 + 0.012 * positiveMod(hash + wordIndex, 3));
        double wordGain = (weak ? 0.58 : 0.82) + emphasis * (weak ? 0.04 : 0.14);
        double wordSpeed = speed * (weak ? 1.22 : 1.0);

        int vowelCount = 0;
        for (int i = 0; i < letters; i++) {
            char c = lower.charAt(i);
            if (isVowel(c) && !isSilentVowel(lower, i) && !isDiphthongTail(lower, i)) {
                vowelCount++;
                char next = i + 1 < letters ? lower.charAt(i + 1) : 0;
                double fraction = vowelCount == 1 ? 1.0 : 0.82;
                addVowel(segments, vowelFor(lower, i), wordF0 * fraction, wordSpeed, wordGain,
                        isDiphthongStart(lower, i) || (next != 0 && isVowel(next)));
            } else if (Character.isLetter(c) && !(c == 'r' && isNonRhoticR(lower, i))) {
                addConsonant(segments, c, wordF0, wordSpeed, wordGain);
            }
        }

        if (vowelCount == 0) {
            addVowel(segments, 'u', wordF0 * 0.86, speed, wordGain, false);
        }
    }

    private static void addVowel(List<KlattSynth.Seg> segments, char c, double f0,
                                 double speed, double gain, boolean glide) {
        double[] start = vowel(c);
        double[] end = glide ? vowel(nextVowel(c)) : start;
        double duration = vowelDuration(c);
        KlattSynth.Seg segment = new KlattSynth.Seg(ms(duration, speed),
                start[0], start[1], start[2], f0, f0 * 0.985, gain, 0.012);
        if (glide) {
            segment.withTransition(end[0], end[1], end[2]);
        }
        segments.add(segment);
    }

    private static void addConsonant(List<KlattSynth.Seg> segments, char c, double f0,
                                     double speed, double gain) {
        double[] formants = consonantFormants(c);
        if (isFricative(c)) {
            segments.add(new KlattSynth.Seg(ms(38, speed), formants[0], formants[1], formants[2],
                    0, 0, 0.04, 0.52).withNoiseFreq(noiseFrequency(c)));
        } else if (isStop(c)) {
            segments.add(new KlattSynth.Seg(ms(25, speed), formants[0], formants[1], formants[2],
                    0, 0, 0.03, 0));
            segments.add(new KlattSynth.Seg(ms(18, speed), formants[0], formants[1], formants[2],
                    f0 * 0.96, f0, gain * 0.38, 0.34).withNoiseFreq(2300));
        } else {
            segments.add(new KlattSynth.Seg(ms(42, speed), formants[0], formants[1], formants[2],
                    f0 * 0.94, f0, gain * 0.52, 0.035));
        }
    }

    private static void addExclamationTail(List<KlattSynth.Seg> segments, double f0,
                                           double speed, double emphasis) {
        double peak = f0 * (1.18 + emphasis * 0.12);
        segments.add(new KlattSynth.Seg(ms(95, speed), 620, 1250, 2450,
                f0, peak, 0.85 + emphasis * 0.1, 0.018));
        segments.add(new KlattSynth.Seg(ms(90, speed), 320, 1800, 2700,
                peak, f0 * 0.72, 0.68, 0.045));
        segments.add(new KlattSynth.Seg(ms(70, speed), 500, 1400, 2400,
                0, 0, 0, 0));
    }

    private static void addQuestionTail(List<KlattSynth.Seg> segments, double f0,
                                        double speed, double emphasis) {
        segments.add(new KlattSynth.Seg(ms(90, speed), 570, 1350, 2450,
                f0 * 0.84, f0 * (1.15 + emphasis * 0.08), 0.72, 0.015));
        segments.add(new KlattSynth.Seg(ms(75, speed), 500, 1400, 2400,
                f0 * 1.15, f0 * 0.92, 0.34, 0.025));
    }

    /** Approximate neutral Southern British targets, in Hz (F1/F2/F3). */
    private static double[] vowel(char c) {
        switch (c) {
            case 'a': return new double[]{680, 1320, 2500}; // TRAP
            case 'e': return new double[]{500, 1780, 2550}; // DRESS
            case 'i': return new double[]{360, 2050, 2700}; // KIT
            case 'o': return new double[]{520, 900, 2450};  // LOT
            case 'u': return new double[]{650, 1050, 2450}; // STRUT
            case 'y': return new double[]{330, 2100, 2850}; // FLEECE/YES
            case 'A': return new double[]{760, 1100, 2500}; // PALM/BATH
            case 'O': return new double[]{550, 800, 2450};  // THOUGHT
            case 'G': return new double[]{330, 900, 2300};  // GOOSE
            case 'N': return new double[]{480, 1350, 1700}; // NURSE
            case 'Y': return new double[]{500, 1000, 2450}; // GOAT onset
            default: return new double[]{500, 1500, 2500}; // schwa
        }
    }

    /** Select a few important British lexical spellings instead of raw letters. */
    private static char vowelFor(String word, int index) {
        char c = word.charAt(index);
        if ("the".equals(word) || "a".equals(word) || "an".equals(word)) {
            return 'q'; // weak schwa
        }
        if ("you".equals(word)) {
            return 'G'; // GOOSE /uː/
        }
        if (isNurseWord(word)) {
            return 'N'; // NURSE vowel; the following r is non-rhotic
        }
        if (isPalmWord(word)) {
            return 'A'; // PALM/BATH vowel; the following r is non-rhotic
        }
        if ("thought".equals(word)) {
            return 'O'; // THOUGHT; the written u is part of the spelling
        }
        if (isGoatWord(word)) {
            return 'Y'; // GOAT onset, gliding toward GOOSE
        }
        if (isMouthWord(word) && c == 'o') {
            return 'A'; // MOUTH /aʊ/ onset, gliding toward STRUT
        }
        if ("the".equals(word) || "a".equals(word) || "an".equals(word)
                || ("about".equals(word) && index == 0)) {
            return 'q'; // weak schwa
        }
        if (c == 'o' && index + 1 < word.length() && word.charAt(index + 1) == 'o') {
            return 'G'; // GOOSE/FOOT family, not two LOT vowels
        }
        return c;
    }

    private static boolean isSilentVowel(String word, int index) {
        return "e".equals(word.substring(index, index + 1))
                && index == word.length() - 1 && !"the".equals(word);
    }

    private static boolean isDiphthongStart(String word, int index) {
        return ("about".equals(word) && index == 2)
                || (isMouthWord(word) && word.charAt(index) == 'o')
                || (isGoatWord(word) && word.charAt(index) == 'o');
    }

    private static boolean isDiphthongTail(String word, int index) {
        return ("about".equals(word) && index == 3)
                || ("thought".equals(word) && index == 3)
                || ("you".equals(word) && index > 0)
                || (isGoatWord(word) && index == 2);
    }

    private static boolean isNurseWord(String word) {
        return "bird".equals(word) || "her".equals(word) || "word".equals(word)
                || "first".equals(word) || "work".equals(word);
    }

    private static boolean isPalmWord(String word) {
        return "car".equals(word) || "far".equals(word) || "farm".equals(word)
                || "farmer".equals(word) || "father".equals(word) || "calm".equals(word)
                || "palm".equals(word) || "talk".equals(word);
    }

    private static boolean isGoatWord(String word) {
        return "no".equals(word) || "go".equals(word) || "so".equals(word)
                || "goat".equals(word) || "home".equals(word) || "old".equals(word);
    }

    private static boolean isMouthWord(String word) {
        return "about".equals(word) || "now".equals(word) || "how".equals(word)
                || "out".equals(word) || "down".equals(word);
    }

    private static boolean isNonRhoticR(String word, int index) {
        if (word.charAt(index) != 'r') return false;
        int next = index + 1;
        // Retain linking/intervocalic r; drop word-final and pre-consonantal r.
        return next >= word.length() || !isVowel(word.charAt(next));
    }

    private static char nextVowel(char c) {
        switch (c) {
            case 'a': return 'o';
            case 'e': return 'i';
            case 'i': return 'e';
            case 'o': return 'u';
            case 'u': return 'o';
            case 'A': return 'u';
            case 'Y': return 'G';
            default: return 'u';
        }
    }

    private static double vowelDuration(char c) {
        switch (c) {
            case 'a': return 86;
            case 'e': return 78;
            case 'i': return 74;
            case 'o': return 92;
            case 'u': return 88;
            case 'y': return 82;
            case 'q': return 54; // weak schwa
            default: return 80;
        }
    }

    private static double[] consonantFormants(char c) {
        if (c == 'm' || c == 'n') return new double[]{280, 1850, 2700};
        if (c == 'l' || c == 'r') return new double[]{360, 1300, 2500};
        if (c == 'w') return new double[]{300, 700, 2200};
        if (c == 'y') return new double[]{300, 2100, 2800};
        if (c == 's' || c == 'z') return new double[]{420, 3000, 3900};
        if (c == 'f' || c == 'v') return new double[]{300, 1900, 3500};
        if (c == 'h') return new double[]{520, 1450, 2450};
        if (c == 't' || c == 'd') return new double[]{220, 1800, 2800};
        if (c == 'k' || c == 'g') return new double[]{240, 1600, 2900};
        if (c == 'p' || c == 'b') return new double[]{180, 1100, 2500};
        return new double[]{430, 1450, 2500};
    }

    private static int noiseFrequency(char c) {
        if (c == 's' || c == 'z') return 3300;
        if (c == 'f' || c == 'v') return 2500;
        if (c == 'h') return 1500;
        return 2200;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o'
                || c == 'u' || c == 'y';
    }

    private static boolean isWeakWord(String word) {
        return "a".equals(word) || "an".equals(word) || "the".equals(word)
                || "am".equals(word) || "is".equals(word) || "are".equals(word)
                || "to".equals(word) || "of".equals(word) || "and".equals(word)
                || "you".equals(word);
    }

    private static boolean isConsonant(char c) {
        return Character.isLetter(c) && !isVowel(c);
    }

    private static boolean isFricative(char c) {
        return c == 'f' || c == 'h' || c == 's' || c == 'v' || c == 'z';
    }

    private static boolean isStop(char c) {
        return c == 'b' || c == 'd' || c == 'g' || c == 'k' || c == 'p' || c == 't';
    }

    private static double emphasis(String text) {
        int upper = 0;
        int letters = 0;
        int exclamations = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            } else if (c == '!') {
                exclamations++;
            }
        }
        return Math.min(1.0, (letters == 0 ? 0 : upper / (double) letters) * 0.55
                + Math.min(3, exclamations) * 0.15);
    }

    private static char lastVisible(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return text.charAt(i);
            }
        }
        return '.';
    }

    private static void applyCharacter(float[] samples, int hash, double tone,
                                       double volume, double emphasis) {
        double wobbleRate = 3.0 + positiveMod(hash, 9) * 0.19;
        double drive = 1.015 + Math.max(0.0, -tone) * 0.10 + emphasis * 0.04;
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) SAMPLE_RATE;
            double wobble = 1.0 + (0.025 + emphasis * 0.014)
                    * Math.sin(TWO_PI * wobbleRate * t + positiveMod(hash, 31));
            double value = Math.tanh(samples[i] * drive) * wobble * volume;
            samples[i] = (float) Math.max(-0.98, Math.min(0.98, value));
        }
        AudioDsp.fadeEdges(samples, Math.min(SAMPLE_RATE / 80, samples.length / 5));
    }

    private static double ms(double value, double speed) {
        return value / speed;
    }

    private static int positiveMod(int value, int divisor) {
        return Math.floorMod(value, divisor);
    }

    @Override
    public void close() {
        // No native resources: this backend is intentionally pure Java.
    }
}
