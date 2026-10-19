#!/usr/bin/env python3
"""Generate the Java port's *data* tables from the verified Python tables.

The Java engine under ``src/main/java/villager/voice/synth/`` has two kinds of
content: algorithms, which are hand-written Java mirroring the Python modules,
and data -- the 48-phone inventory, the 102 voiceprint defaults with their
declared ranges and enums, and the 377-entry pronunciation dictionary. The data
is generated rather than transcribed, because a hand-copied table of 377
pronunciations is exactly the kind of thing that drifts silently and then makes
the Java voice disagree with the Python one it is supposed to match.

    cd tools && python3 -m villager_voice.java_port            # write the files
    python3 -m villager_voice.java_port --check                # verify, don't write

Every generated file is marked as generated and should not be edited by hand:
edit the Python source of truth and regenerate.
"""

import argparse
import os
import sys

from . import corpus, lexicon, phones
from .voiceprint import DEFAULTS, ENUMS, RANGES, VoicePrint, _flatten

PACKAGE = "villager.voice.synth"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
JAVA_DIR = os.path.join(ROOT, "src", "main", "java", "villager", "voice", "synth")

GENERATED_BANNER = """/*
 * GENERATED FILE -- do not edit by hand.
 *
 * Emitted from the verified Python tables by:
 *     cd tools && python3 -m villager_voice.java_port
 *
 * The Python package is the source of truth for this data. Edit
 * tools/villager_voice/%s and regenerate, so the Java voice can never
 * silently drift away from the one that passed selftest.
 */
"""


# --------------------------------------------------------------------------- #
# Java literal emission
# --------------------------------------------------------------------------- #

def java_string(value):
    escaped = (str(value).replace("\\", "\\\\").replace('"', '\\"')
               .replace("\n", "\\n").replace("\t", "\\t"))
    return '"%s"' % escaped


def java_literal(value):
    """Emit a Python scalar as a Java literal."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, int):
        return "%d" % value
    if isinstance(value, float):
        text = repr(value)
        if text.endswith(".0"):
            return text
        return text
    if isinstance(value, str):
        return java_string(value)
    raise TypeError("cannot emit %r as Java" % (value,))


def java_array(values):
    return "new double[]{%s}" % ", ".join(
        java_literal(float(v)) for v in values)


# --------------------------------------------------------------------------- #
# VillagerPhones.java
# --------------------------------------------------------------------------- #

PHONE_FIELDS = [
    ("manner", "String"), ("voiced", "boolean"), ("dur", "double"),
    ("amp", "double"), ("targets", "int"), ("longVowel", "boolean"),
    ("tense", "boolean"), ("stressable", "boolean"), ("rounded", "boolean"),
    ("rhotic", "boolean"), ("lateral", "boolean"), ("sibilant", "boolean"),
    ("closure", "double"), ("nasal", "double"), ("aspiration", "double"),
    ("aspirationAny", "boolean"), ("voicingDuringClosure", "double"),
    ("place", "String"), ("contour", "String"), ("glideFrom", "String"),
    ("glideTo", "String"),
]


def _flatten_formants(entry):
    """Diphthongs store two formant targets; everything else stores one."""
    f = entry["f"]
    bw = entry["bw"]
    if f and isinstance(f[0], (list, tuple)):
        flat_f = [float(v) for target in f for v in target]
        flat_bw = [float(v) for target in bw for v in target]
        return flat_f, flat_bw, len(f)
    return [float(v) for v in f], [float(v) for v in bw], 1


def emit_phones():
    out = [GENERATED_BANNER % "phones.py",
           "package %s;" % PACKAGE,
           "",
           "import java.util.Collections;",
           "import java.util.LinkedHashMap;",
           "import java.util.Map;",
           "",
           "/**",
           " * Articulatory phone inventory for the source-filter synthesiser.",
           " *",
           " * <p>Every phone carries the vocal-tract <em>targets</em> the filter is",
           " * driven to, how it is voiced, and how it is released. Vowel values are",
           " * Received-Pronunciation male references at {@code tract.length_scale = 1.0};",
           " * the voiceprint scales the whole space.</p>",
           " *",
           " * <p>Mirrors {@code tools/villager_voice/phones.py}. Symbols are",
           " * ARPAbet-style ASCII so they survive JSON, logs and filenames.</p>",
           " */",
           "public final class VillagerPhones {",
           "",
           "    public static final String VOWEL = \"vowel\";",
           "    public static final String DIPHTHONG = \"diphthong\";",
           "    public static final String NASAL = \"nasal\";",
           "    public static final String STOP = \"stop\";",
           "    public static final String FRICATIVE = \"fricative\";",
           "    public static final String AFFRICATE = \"affricate\";",
           "    public static final String APPROXIMANT = \"approximant\";",
           "    public static final String GLIDE = \"glide\";",
           "    public static final String SILENCE = \"silence\";",
           "    public static final String VOCALISATION = \"vocalisation\";",
           "",
           "    /** Formant count in the cascade. */",
           "    public static final int FORMANTS = 5;",
           "",
           "    /** Neutral tract used where a score names no phone. */",
           "    public static final double[] NEUTRAL =",
           "            {500.0, 1500.0, 2500.0, 3400.0, 4300.0};",
           "    public static final double[] NEUTRAL_BW = {70.0, 100.0, 160.0, 210.0, 270.0};",
           "",
           "    /** One phone's articulatory description. */",
           "    public static final class Phone {",
           ]
    for name, kind in PHONE_FIELDS:
        out.append("        public final %s %s;" % (kind, name))
    out.extend([
        "        /** Formant targets, 5 per target set (diphthongs carry 2 sets). */",
        "        public final double[] f;",
        "        /** Bandwidths, matching {@link #f}. */",
        "        public final double[] bw;",
        "        /** Diphthong weighting between the two target sets. */",
        "        public final double[] kernel;",
        "        /** Frication noise as {amp, center, q, tilt}, or null. */",
        "        public final double[] noise;",
        "        /** Stop burst as {amp, center, q, tilt}, or null. */",
        "        public final double[] burst;",
        "",
        "        Phone(String manner, boolean voiced, double dur, double amp,",
        "              int targets, boolean longVowel, boolean tense, boolean stressable,",
        "              boolean rounded, boolean rhotic, boolean lateral, boolean sibilant,",
        "              double closure, double nasal, double aspiration,",
        "              boolean aspirationAny, double voicingDuringClosure,",
        "              String place, String contour, String glideFrom, String glideTo,",
        "              double[] f, double[] bw, double[] kernel, double[] noise,",
        "              double[] burst) {",
    ])
    for name, _kind in PHONE_FIELDS:
        out.append("            this.%s = %s;" % (name, name))
    out.extend([
        "            this.f = f;",
        "            this.bw = bw;",
        "            this.kernel = kernel;",
        "            this.noise = noise;",
        "            this.burst = burst;",
        "        }",
        "",
        "        /** Formant target set ``index`` (0, or 1 for a diphthong's glide). */",
        "        public double[] formants(int index) {",
        "            int which = Math.max(0, Math.min(targets - 1, index));",
        "            double[] out = new double[FORMANTS];",
        "            System.arraycopy(f, which * FORMANTS, out, 0, FORMANTS);",
        "            return out;",
        "        }",
        "",
        "        /** Bandwidths for target set ``index``. */",
        "        public double[] bandwidths(int index) {",
        "            int which = Math.max(0, Math.min(targets - 1, index));",
        "            double[] out = new double[FORMANTS];",
        "            System.arraycopy(bw, which * FORMANTS, out, 0, FORMANTS);",
        "            return out;",
        "        }",
        "    }",
        "",
        "    private static final Map<String, Phone> TABLE =",
        "            new LinkedHashMap<String, Phone>();",
        "",
        "    private VillagerPhones() {",
        "    }",
        "",
    ])

    # static registration block
    out.append("    static {")
    for symbol in sorted(phones.PHONES):
        entry = phones.PHONES[symbol]
        flat_f, flat_bw, targets = _flatten_formants(entry)
        aspiration = entry.get("aspiration", 0.0)
        aspiration_any = bool(aspiration) if isinstance(aspiration, bool) else False
        aspiration_value = 0.0 if isinstance(aspiration, bool) else float(aspiration)
        kernel = entry.get("kernel")
        noise = entry.get("noise")
        burst = entry.get("burst")
        args = [
            java_string(entry["manner"]),
            "true" if entry.get("voiced") else "false",
            java_literal(float(entry.get("dur", 0.0))),
            java_literal(float(entry.get("amp", 1.0))),
            str(targets),
            "true" if entry.get("long") else "false",
            "true" if entry.get("tense") else "false",
            "true" if entry.get("stressable") else "false",
            "true" if entry.get("rounded") else "false",
            "true" if entry.get("rhotic") else "false",
            "true" if entry.get("lateral") else "false",
            "true" if entry.get("sibilant") else "false",
            java_literal(float(entry.get("closure", 0.0))),
            java_literal(float(entry.get("nasal", 0.0))),
            java_literal(aspiration_value),
            "true" if aspiration_any else "false",
            java_literal(float(entry.get("voicing_during_closure", 0.0))),
            java_string(entry["place"]) if entry.get("place") else "null",
            java_string(entry["contour"]) if entry.get("contour") else "null",
            java_string(entry["glide_from"]) if entry.get("glide_from") else "null",
            java_string(entry["glide_to"]) if entry.get("glide_to") else "null",
            java_array(flat_f),
            java_array(flat_bw),
            java_array(kernel) if kernel else "null",
            ("new double[]{%s}" % ", ".join(java_literal(float(noise[k]))
                                            for k in ("amp", "center", "q", "tilt"))
             if noise else "null"),
            ("new double[]{%s}" % ", ".join(java_literal(float(burst[k]))
                                            for k in ("amp", "center", "q", "tilt"))
             if burst else "null"),
        ]
        out.append("        TABLE.put(%s, new Phone(%s));" % (
            java_string(symbol), ",\n                ".join(
                [args[0]] + args[1:])))
    out.extend([
        "    }",
        "",
        "    /** The whole inventory, keyed by ARPAbet-ASCII symbol. */",
        "    public static Map<String, Phone> all() {",
        "        return Collections.unmodifiableMap(TABLE);",
        "    }",
        "",
        "    /** How many phones the inventory holds (%d). */" % len(phones.PHONES),
        "    public static int size() {",
        "        return TABLE.size();",
        "    }",
        "",
        "    public static boolean contains(String symbol) {",
        "        return symbol != null && TABLE.containsKey(symbol);",
        "    }",
        "",
        "    /** Look a phone up, falling back to a neutral vowel-like target. */",
        "    public static Phone get(String symbol) {",
        "        Phone phone = symbol == null ? null : TABLE.get(symbol);",
        "        return phone != null ? phone : TABLE.get(\"AX\");",
        "    }",
        "",
        "    /**",
        "     * Formant targets for a symbol, at ``index`` 0 (or 1 for a diphthong's",
        "     * second half). Falls back to {@link #NEUTRAL} for an unknown symbol.",
        "     */",
        "    public static double[] formantTargets(String symbol, int index) {",
        "        Phone phone = symbol == null ? null : TABLE.get(symbol);",
        "        if (phone == null) {",
        "            return NEUTRAL.clone();",
        "        }",
        "        return phone.formants(index);",
        "    }",
        "",
        "    /** Bandwidths matching {@link #formantTargets(String, int)}. */",
        "    public static double[] bandwidthTargets(String symbol, int index) {",
        "        Phone phone = symbol == null ? null : TABLE.get(symbol);",
        "        if (phone == null) {",
        "            return NEUTRAL_BW.clone();",
        "        }",
        "        return phone.bandwidths(index);",
        "    }",
        "",
        "    /** True for vowels, diphthongs and the ``HMM`` vocalisation. */",
        "    public static boolean isSyllabic(String symbol) {",
        "        Phone phone = symbol == null ? null : TABLE.get(symbol);",
        "        if (phone == null) {",
        "            return false;",
        "        }",
        "        return VOWEL.equals(phone.manner) || DIPHTHONG.equals(phone.manner)",
        "                || VOCALISATION.equals(phone.manner);",
        "    }",
        "",
        "    /** True for manners that carry a glottal source. */",
        "    public static boolean isVoicedManner(String manner) {",
        "        return VOWEL.equals(manner) || DIPHTHONG.equals(manner)",
        "                || NASAL.equals(manner) || APPROXIMANT.equals(manner)",
        "                || GLIDE.equals(manner) || VOCALISATION.equals(manner);",
        "    }",
        "}",
        "",
    ])
    return "\n".join(out)


# --------------------------------------------------------------------------- #
# VoicePrintTables.java
# --------------------------------------------------------------------------- #

def _emit_nested_map(data, indent, out, prefix="into"):
    """Emit a nested Python dict as Java ``map.put`` statements."""
    for key in data:
        value = data[key]
        path = java_string(key)
        if isinstance(value, dict):
            variable = _var_name(prefix + "_" + key)
            out.append("%sMap<String, Object> %s = new LinkedHashMap<String, Object>();"
                       % (indent, variable))
            _emit_nested_map(value, indent + "    ", out, prefix=variable)
            out.append("%s%s.put(%s, %s);" % (indent, prefix, path, variable))
        elif isinstance(value, list):
            if value:
                items = ", ".join(java_literal(v) for v in value)
                out.append("%s%s.put(%s, new java.util.ArrayList<Object>("
                           "java.util.Arrays.<Object>asList(%s)));"
                           % (indent, prefix, path, items))
            else:
                out.append("%s%s.put(%s, new java.util.ArrayList<Object>());"
                           % (indent, prefix, path))
        else:
            out.append("%s%s.put(%s, %s);" % (indent, prefix, path, java_literal(value)))


def _var_name(text):
    cleaned = "".join(character if character.isalnum() else "_" for character in text)
    if cleaned and cleaned[0].isdigit():
        cleaned = "_" + cleaned
    return cleaned or "v"


def emit_tables():
    flat = _flatten(DEFAULTS)
    out = [GENERATED_BANNER % "voiceprint.py",
           "package %s;" % PACKAGE,
           "",
           "import java.util.Collections;",
           "import java.util.LinkedHashMap;",
           "import java.util.Map;",
           "",
           "/**",
           " * The voiceprint control surface's data: %d defaults, %d declared" % (
               len(flat), len(RANGES)),
           " * ranges and %d enums." % len(ENUMS),
           " *",
           " * <p>Mirrors {@code tools/villager_voice/voiceprint.py}. The logic that",
           " * reads these tables lives in {@link VoicePrint}.</p>",
           " */",
           "public final class VoicePrintTables {",
           "",
           "    public static final String FORMAT = %s;" % java_string(
               DEFAULTS.get("format", "villager_voice.voiceprint")),
           "    public static final int VERSION = %d;" % int(DEFAULTS.get("version", 1)),
           "",
           "    private static final Map<String, Object> DEFAULTS =",
           "            new LinkedHashMap<String, Object>();",
           "    private static final Map<String, double[]> RANGES =",
           "            new LinkedHashMap<String, double[]>();",
           "    private static final Map<String, String[]> ENUMS =",
           "            new LinkedHashMap<String, String[]>();",
           "",
           "    private VoicePrintTables() {",
           "    }",
           "",
           "    static {",
           "        Map<String, Object> into = DEFAULTS;",
           ]
    _emit_nested_map(DEFAULTS, "        ", out)
    for path in sorted(RANGES):
        low, high = RANGES[path]
        out.append("        RANGES.put(%s, new double[]{%s, %s});" % (
            java_string(path), java_literal(float(low)), java_literal(float(high))))
    for path in sorted(ENUMS):
        values = ", ".join(java_string(v) for v in ENUMS[path])
        out.append("        ENUMS.put(%s, new String[]{%s});" % (
            java_string(path), values))
    out.extend([
        "    }",
        "",
        "    /** A deep copy of the default voiceprint. */",
        "    @SuppressWarnings(\"unchecked\")",
        "    public static Map<String, Object> defaults() {",
        "        return (Map<String, Object>) deepCopy(DEFAULTS);",
        "    }",
        "",
        "    public static Map<String, double[]> ranges() {",
        "        return Collections.unmodifiableMap(RANGES);",
        "    }",
        "",
        "    public static Map<String, String[]> enums() {",
        "        return Collections.unmodifiableMap(ENUMS);",
        "    }",
        "",
        "    @SuppressWarnings(\"unchecked\")",
        "    static Object deepCopy(Object value) {",
        "        if (value instanceof Map) {",
        "            Map<String, Object> source = (Map<String, Object>) value;",
        "            Map<String, Object> copy = new LinkedHashMap<String, Object>();",
        "            for (Map.Entry<String, Object> entry : source.entrySet()) {",
        "                copy.put(entry.getKey(), deepCopy(entry.getValue()));",
        "            }",
        "            return copy;",
        "        }",
        "        if (value instanceof java.util.List) {",
        "            java.util.List<Object> source = (java.util.List<Object>) value;",
        "            java.util.List<Object> copy = new java.util.ArrayList<Object>();",
        "            for (Object item : source) {",
        "                copy.add(deepCopy(item));",
        "            }",
        "            return copy;",
        "        }",
        "        return value;",
        "    }",
        "}",
        "",
    ])
    return "\n".join(out)


# --------------------------------------------------------------------------- #
# VillagerDictionary.java
# --------------------------------------------------------------------------- #

def emit_dictionary():
    out = [GENERATED_BANNER % "lexicon.py",
           "package %s;" % PACKAGE,
           "",
           "import java.util.Collections;",
           "import java.util.LinkedHashMap;",
           "import java.util.Map;",
           "",
           "/**",
           " * Curated pronunciations for the words the corpus actually uses.",
           " *",
           " * <p>English tense/lax vowel choice is lexical, not derivable from",
           " * spelling, so correctness on frequent words is bought by curation",
           " * rather than by rules. The %d entries here cover the words that" % len(lexicon.DICT),
           " * dominate the corpus: its 72 most frequent words are half of all",
           " * 16,146 tokens. {@link VillagerLexicon} falls back to rules for",
           " * everything else.</p>",
           " *",
           " * <p>Values are ARPAbet-ASCII with trailing digits for stress",
           " * (1 = primary, 2 = secondary, 0 or absent = unstressed).</p>",
           " */",
           "public final class VillagerDictionary {",
           "",
           "    private static final Map<String, String> TABLE =",
           "            new LinkedHashMap<String, String>();",
           "",
           "    private VillagerDictionary() {",
           "    }",
           "",
           "    static {"]
    for word in sorted(lexicon.DICT):
        out.append("        TABLE.put(%s, %s);" % (
            java_string(word), java_string(lexicon.DICT[word])))
    out.extend([
        "    }",
        "",
        "    /** The curated table, keyed by normalised word. */",
        "    public static Map<String, String> all() {",
        "        return Collections.unmodifiableMap(TABLE);",
        "    }",
        "",
        "    /** How many words are curated (%d). */" % len(lexicon.DICT),
        "    public static int size() {",
        "        return TABLE.size();",
        "    }",
        "",
        "    /** The pronunciation for a normalised word, or null if not curated. */",
        "    public static String get(String normalisedWord) {",
        "        return normalisedWord == null ? null : TABLE.get(normalisedWord);",
        "    }",
        "",
        "    public static boolean contains(String normalisedWord) {",
        "        return normalisedWord != null && TABLE.containsKey(normalisedWord);",
        "    }",
        "}",
        "",
    ])
    return "\n".join(out)


# --------------------------------------------------------------------------- #

# --------------------------------------------------------------------------- #
# VillagerCorpusLexicon.java
# --------------------------------------------------------------------------- #

def corpus_vocabulary():
    """Every distinct normalised word the corpus actually uses."""
    index_path = corpus.DEFAULT_INDEX
    if not os.path.isfile(index_path):
        return []
    import json

    with open(index_path, "r", encoding="utf-8") as handle:
        payload = json.load(handle)
    clips = payload.get("clips") if isinstance(payload, dict) else payload
    words = set()
    for clip in clips or []:
        texts = [clip.get("text") or ""]
        for cue in clip.get("cues") or []:
            if len(cue) > 1 and isinstance(cue[1], str):
                texts.append(cue[1])
        for text in texts:
            for kind, token in lexicon.text_to_words(text):
                if kind == "word":
                    words.update(lexicon.normalize_word(token))
    return sorted(words)


def emit_corpus_lexicon():
    """Pronunciations for the whole corpus vocabulary, computed by the Python G2P.

    Porting the rule-based grapheme-to-phoneme path to Java would mean
    transliterating several hundred lines of intricate, order-dependent rules
    that cannot be compile-checked here. Pre-computing its *output* over the
    corpus vocabulary is both smaller and exactly faithful: the Java engine then
    agrees with the verified Python engine on every word the corpus uses, and
    only falls back to rules for words the corpus never contained.
    """
    vocabulary = corpus_vocabulary()
    rows = []
    for word in vocabulary:
        phones = lexicon.pronounce(word)
        rendered = " ".join("%s%s" % (symbol, stress if stress else "")
                            for symbol, stress in phones)
        if rendered:
            rows.append((word, rendered))
    curated = sum(1 for word, _ in rows if word in lexicon.DICT)
    out = [GENERATED_BANNER % "lexicon.py (pronounce over the corpus vocabulary)",
           "package %s;" % PACKAGE,
           "",
           "import java.util.Collections;",
           "import java.util.LinkedHashMap;",
           "import java.util.Map;",
           "",
           "/**",
           " * Pronunciations for every word the Villager News corpus uses.",
           " *",
           " * <p>%d words, of which %d are hand-curated in" % (len(rows), curated),
           " * {@link VillagerDictionary} and the rest were produced by the Python",
           " * rule engine over the corpus text. Computing the rules' <em>output</em>",
           " * rather than transliterating the rules themselves keeps the Java engine",
           " * in exact agreement with the verified Python one on every word the",
           " * corpus contains, and is far less code to get wrong.</p>",
           " *",
           " * <p>Words outside this table fall back to {@link VillagerLexicon}'s",
           " * own rules, which are a documented simplification of the Python path.</p>",
           " */",
           "public final class VillagerCorpusLexicon {",
           "",
           "    private static final Map<String, String> TABLE =",
           "            new LinkedHashMap<String, String>();",
           "",
           "    private VillagerCorpusLexicon() {",
           "    }",
           "",
           "    static {"]
    for word, rendered in rows:
        out.append("        TABLE.put(%s, %s);" % (java_string(word),
                                                   java_string(rendered)))
    out.extend([
        "    }",
        "",
        "    /** The whole table, keyed by normalised word. */",
        "    public static Map<String, String> all() {",
        "        return Collections.unmodifiableMap(TABLE);",
        "    }",
        "",
        "    /** How many corpus words carry a pronunciation (%d). */" % len(rows),
        "    public static int size() {",
        "        return TABLE.size();",
        "    }",
        "",
        "    /** The pronunciation for a normalised word, or null if not covered. */",
        "    public static String get(String normalisedWord) {",
        "        return normalisedWord == null ? null : TABLE.get(normalisedWord);",
        "    }",
        "",
        "    public static boolean contains(String normalisedWord) {",
        "        return normalisedWord != null && TABLE.containsKey(normalisedWord);",
        "    }",
        "}",
        "",
    ])
    return "\n".join(out)


FILES = {
    "VillagerPhones.java": emit_phones,
    "VoicePrintTables.java": emit_tables,
    "VillagerDictionary.java": emit_dictionary,
    "VillagerCorpusLexicon.java": emit_corpus_lexicon,
}


def generate():
    return {name: builder() for name, builder in FILES.items()}


def main(argv=None):
    parser = argparse.ArgumentParser(prog="villager_voice.java_port")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed files match, without writing")
    parser.add_argument("--out", default=JAVA_DIR, help="target directory")
    arguments = parser.parse_args(argv)

    generated = generate()
    if arguments.check:
        drifted = []
        for name, text in sorted(generated.items()):
            path = os.path.join(arguments.out, name)
            if not os.path.isfile(path):
                drifted.append("%s (missing)" % name)
                continue
            with open(path, "r", encoding="utf-8") as handle:
                if handle.read() != text:
                    drifted.append(name)
        if drifted:
            print("Java data tables drifted from Python: %s" % ", ".join(drifted))
            print("regenerate with: python3 -m villager_voice.java_port")
            return 1
        print("Java data tables match Python (%d files, %d phones, %d knobs, "
              "%d dictionary entries)" % (len(generated), len(phones.PHONES),
                                          len(_flatten(DEFAULTS)), len(lexicon.DICT)))
        return 0

    os.makedirs(arguments.out, exist_ok=True)
    for name, text in sorted(generated.items()):
        path = os.path.join(arguments.out, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("wrote %-26s %6d bytes  %5d lines" % (
            name, len(text), text.count("\n")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
