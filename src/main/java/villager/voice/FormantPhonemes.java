package villager.voice;

import com.voxel.audio.KlattSynth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Readable, hand-authored phoneme inventory for the original formant voice.
 *
 * The notation is deliberately independent of spelling and eSpeak. Use braces
 * around a sequence, hyphens between phonemes in a word, and spaces between
 * words, for example: {@code {dh-schwa f-ah-m-er}}. A final British /r/ can be
 * omitted for a non-rhotic delivery. The inventory contains the 24 consonants
 * and 20 vowel sounds supplied by the project's English phoneme reference.
 */
public final class FormantPhonemes {
    public static final class Sound {
        public final String label;
        public final double f1;
        public final double f2;
        public final double f3;
        public final double endF1;
        public final double endF2;
        public final double endF3;
        public final double durationMs;
        public final boolean voiced;
        public final boolean fricative;
        public final boolean stop;
        public final double noise;
        public final int noiseFrequency;

        private Sound(String label, double f1, double f2, double f3,
                      double endF1, double endF2, double endF3,
                      double durationMs, boolean voiced, boolean fricative,
                      boolean stop, double noise, int noiseFrequency) {
            this.label = label;
            this.f1 = f1;
            this.f2 = f2;
            this.f3 = f3;
            this.endF1 = endF1;
            this.endF2 = endF2;
            this.endF3 = endF3;
            this.durationMs = durationMs;
            this.voiced = voiced;
            this.fricative = fricative;
            this.stop = stop;
            this.noise = noise;
            this.noiseFrequency = noiseFrequency;
        }

        private boolean isDiphthong() {
            return f1 != endF1 || f2 != endF2 || f3 != endF3;
        }
    }

    private static final Map<String, Sound> SOUNDS = new HashMap<String, Sound>();

    static {
        // 24 consonants.
        // Voiced stops need a clearly audible release cue. Keep /b/ low and
        // compact with a low-frequency bilabial burst; give /d/ a brighter,
        // shorter alveolar burst so it cannot collapse into /b/ after the
        // resonator cascade.
        consonant("b", 180, 950, 2450, true, false, true, .16, 1450);
        consonant("d", 220, 2050, 2950, true, false, true, .52, 3200);
        consonant("f", 300, 1900, 3500, false, true, false, .52, 2500);
        consonant("g", 240, 1600, 2900, true, false, true, 0, 0);
        consonant("h", 520, 1450, 2450, false, true, false, .40, 1500);
        consonant("j", 300, 2100, 2800, true, true, false, .18, 2600);
        consonant("k", 240, 1600, 2900, false, false, true, .28, 2300);
        consonant("l", 360, 1300, 2500, true, false, false, .02, 0);
        consonant("m", 280, 1850, 2700, true, false, false, .02, 0);
        consonant("n", 280, 1750, 2700, true, false, false, .02, 0);
        consonant("p", 180, 1100, 2500, false, false, true, .30, 2300);
        consonant("r", 360, 1300, 2500, true, false, false, .01, 0);
        consonant("s", 420, 3000, 3900, false, true, false, .55, 3300);
        consonant("t", 220, 1800, 2800, false, false, true, .28, 2400);
        consonant("v", 300, 1900, 3500, true, true, false, .20, 2500);
        consonant("w", 300, 700, 2200, true, false, false, .02, 0);
        consonant("y", 300, 2100, 2800, true, false, false, .02, 0);
        consonant("z", 420, 3000, 3900, true, true, false, .20, 3300);
        consonant("zh", 420, 2400, 3200, true, true, false, .20, 2600);
        consonant("ch", 300, 2100, 3000, false, true, true, .38, 2800);
        consonant("sh", 420, 2500, 3300, false, true, false, .52, 2800);
        consonant("th", 500, 1800, 3000, false, true, false, .45, 2600);
        consonant("dh", 500, 1700, 2800, true, true, false, .18, 2400);
        consonant("ng", 280, 1750, 2700, true, false, false, .02, 0);

        // 20 vowels. Values are approximate British-oriented F1/F2/F3 targets.
        vowel("ae", 700, 1700, 2600, 700, 1700, 2600, 82);
        vowel("ay", 470, 1850, 2600, 330, 2200, 2850, 100);
        vowel("eh", 500, 1780, 2550, 500, 1780, 2550, 78);
        vowel("ee", 300, 2200, 3000, 300, 2200, 3000, 92);
        vowel("ih", 380, 2000, 2700, 380, 2000, 2700, 70);
        vowel("ie", 650, 1150, 2500, 300, 2200, 2900, 104);
        vowel("oh", 540, 900, 2450, 540, 900, 2450, 84);
        vowel("oa", 500, 1050, 2450, 330, 1850, 2700, 100);
        vowel("uu", 430, 1050, 2300, 430, 1050, 2300, 68);
        vowel("uh", 650, 1200, 2450, 650, 1200, 2450, 82);
        vowel("oo", 300, 900, 2300, 300, 900, 2300, 94);
        vowel("oy", 520, 900, 2450, 300, 2200, 2900, 104);
        vowel("ow", 650, 1200, 2500, 350, 900, 2350, 104);
        vowel("schwa", 500, 1500, 2500, 500, 1500, 2500, 52);
        vowel("air", 600, 1800, 2600, 450, 1100, 2300, 104);
        vowel("ah", 750, 1100, 2500, 750, 1100, 2500, 92);
        vowel("er", 480, 1350, 1700, 480, 1350, 1700, 108);
        vowel("aw", 520, 800, 2450, 520, 800, 2450, 96);
        vowel("ear", 350, 2200, 2900, 450, 1500, 2300, 104);
        vowel("ure", 400, 1000, 2400, 450, 1500, 2300, 108);
    }

    private FormantPhonemes() {
    }

    public static boolean isKnown(String label) {
        return SOUNDS.containsKey(normalize(label));
    }

    public static boolean isNotation(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    public static List<String> labels() {
        return Arrays.asList("b", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r",
                "s", "t", "v", "w", "y", "z", "zh", "ch", "sh", "th", "dh", "ng",
                "ae", "ay", "eh", "ee", "ih", "ie", "oh", "oa", "uu", "uh", "oo", "oy",
                "ow", "schwa", "air", "ah", "er", "aw", "ear", "ure");
    }

    /** Parse braces, spaces between words, hyphens between phonemes, and plus blends. */
    public static List<List<String>> parseWords(String notation) {
        if (notation == null) {
            throw new IllegalArgumentException("Phoneme notation must not be null");
        }
        String clean = notation.trim();
        if (clean.startsWith("{") && clean.endsWith("}")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one phoneme");
        }
        List<List<String>> words = new ArrayList<List<String>>();
        for (String rawWord : clean.split("\\s+")) {
            if (rawWord.equals("|")) {
                words.add(Arrays.asList("|"));
                continue;
            }
            // Accept the readable voiced/unvoiced aliases before splitting the
            // word into hyphen-separated phonemes. A plus group is kept as a
            // connected blend and expanded into its component phonemes below.
            String tokenWord = rawWord.replace("th-voiced", "dh")
                    .replace("th-unvoiced", "th");
            List<String> word = new ArrayList<String>();
            for (String raw : tokenWord.split("-")) {
                String blend = raw.trim();
                if (blend.indexOf('+') >= 0) {
                    String[] parts = blend.split("\\+", -1);
                    if (parts.length < 2) {
                        throw new IllegalArgumentException("Blend must contain at least two phonemes: " + raw);
                    }
                    for (String part : parts) {
                        String label = normalize(part);
                        if (!SOUNDS.containsKey(label)) {
                            throw new IllegalArgumentException("Unknown phoneme in blend: " + part);
                        }
                        word.add("blend-start");
                        word.add(label);
                    }
                    continue;
                }
                String label = normalize(raw);
                if (label.isEmpty()) continue;
                if (label.equals("stress") || label.equals("primary")) {
                    word.add("stress");
                } else if (label.equals("secondary")) {
                    word.add("secondary");
                } else if (label.equals("long")) {
                    word.add("long");
                } else if (label.equals("blend-start")) {
                    word.add(label);
                } else if (!SOUNDS.containsKey(label)) {
                    throw new IllegalArgumentException("Unknown formant phoneme: " + raw
                            + ". Known labels: " + labels());
                } else {
                    word.add(label);
                }
            }
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    public static List<KlattSynth.Seg> toSegments(String notation, double f0,
                                                    double speed, double emphasis) {
        List<KlattSynth.Seg> result = new ArrayList<KlattSynth.Seg>();
        List<List<String>> words = parseWords(notation);
        int wordIndex = 0;
        for (List<String> word : words) {
            if (word.size() == 1 && word.get(0).equals("|")) {
                result.add(silence(75, speed));
                continue;
            }
            boolean stressed = false;
            boolean secondary = false;
            boolean lengthened = false;
            for (String label : word) {
                if (label.equals("stress")) stressed = true;
                if (label.equals("secondary")) secondary = true;
                if (label.equals("long")) lengthened = true;
            }
            double wordF0 = f0 * Math.max(.82, 1.04 - wordIndex * .055)
                    * (stressed ? 1.10 : secondary ? 1.04 : 1.0);
            double gain = (stressed ? 1.02 : secondary ? .90 : .82) + emphasis * .10;
            double wordScale = (lengthened ? 1.18 : 1.0) * (stressed ? 1.05 : 1.0);
            boolean inBlend = false;
            for (String label : word) {
                if (label.equals("stress") || label.equals("secondary") || label.equals("long")) continue;
                if (label.equals("blend-start")) {
                    inBlend = true;
                    continue;
                }
                Sound sound = SOUNDS.get(label);
                if (sound == null) continue;
                double blendScale = inBlend ? 0.66 : 1.0;
                inBlend = false;
                if (sound.fricative && !sound.voiced) {
                    result.add(new KlattSynth.Seg(ms(sound.durationMs * wordScale * blendScale, speed),
                            sound.f1, sound.f2, sound.f3, 0, 0, .04, sound.noise)
                            .withNoiseFreq(sound.noiseFrequency));
                } else if (sound.stop) {
                    double closureMs = sound.label.equals("d") ? 19 : 24;
                    double releaseMs = sound.label.equals("d") ? 13 : 18;
                    double releaseGain = sound.label.equals("d") ? .78 : .68;
                    result.add(new KlattSynth.Seg(ms(closureMs * wordScale * blendScale, speed),
                            sound.f1, sound.f2, sound.f3, 0, 0, .025, 0));
                    result.add(new KlattSynth.Seg(ms(releaseMs * wordScale * blendScale, speed),
                            sound.f1, sound.f2, sound.f3, sound.voiced ? wordF0 * .96 : 0,
                            sound.voiced ? wordF0 : 0, gain * releaseGain, sound.noise)
                            .withNoiseFreq(sound.noiseFrequency == 0 ? 2300 : sound.noiseFrequency));
                } else if (sound.fricative) {
                    result.add(new KlattSynth.Seg(ms(sound.durationMs * wordScale * blendScale, speed),
                            sound.f1, sound.f2, sound.f3, sound.voiced ? wordF0 * .94 : 0,
                            sound.voiced ? wordF0 : 0, gain * .72, sound.noise)
                            .withNoiseFreq(sound.noiseFrequency));
                } else {
                    result.add(new KlattSynth.Seg(ms(sound.durationMs * wordScale * blendScale, speed),
                            sound.f1, sound.f2, sound.f3, sound.voiced ? wordF0 : wordF0,
                            sound.voiced ? wordF0 * .985 : wordF0, gain, .012)
                            .withTransition(sound.endF1, sound.endF2, sound.endF3));
                }
            }
            // Keep words connected. Explicit `|` remains the only inserted
            // full pause; a tiny boundary is handled by the following onset.
            result.add(silence(4, speed));
            wordIndex++;
        }
        return result;
    }

    private static KlattSynth.Seg silence(double duration, double speed) {
        return new KlattSynth.Seg(ms(duration, speed), 500, 1500, 2500, 0, 0, 0, 0);
    }

    private static double ms(double duration, double speed) {
        return duration / speed;
    }

    private static String normalize(String label) {
        String value = label.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("'")) value = value.substring(1);
        if (value.equals("uh2")) return "uh"; // legacy alias; use uh for the supplied inventory
        if (value.equals("a-short")) return "ae";
        if (value.equals("e-short")) return "eh";
        if (value.equals("i-short")) return "ih";
        if (value.equals("o-short")) return "oh";
        if (value.equals("u-short")) return "uh";
        return value;
    }

    private static void consonant(String label, double f1, double f2, double f3,
                                  boolean voiced, boolean fricative, boolean stop,
                                  double noise, int noiseFrequency) {
        SOUNDS.put(label, new Sound(label, f1, f2, f3, f1, f2, f3,
                fricative ? 38 : stop ? 42 : 46, voiced, fricative, stop,
                noise, noiseFrequency));
    }

    private static void vowel(String label, double f1, double f2, double f3,
                              double endF1, double endF2, double endF3,
                              double duration) {
        SOUNDS.put(label, new Sound(label, f1, f2, f3, endF1, endF2, endF3,
                duration, true, false, false, .012, 0));
    }
}
