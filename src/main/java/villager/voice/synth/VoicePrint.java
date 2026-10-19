package villager.voice.synth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The voiceprint: the complete control surface for a synthesised voice.
 *
 * <p>A voiceprint is a nested map of {@value #SECTION_COUNT} sections holding
 * 102 knobs, every scalar one bounded by a declared range or enum in
 * {@link VoicePrintTables}. It is the same JSON file the Python engine reads, so
 * one voiceprint drives both implementations.</p>
 *
 * <p><b>Layering.</b> The {@code character} section is a <em>macro</em> layer:
 * saying {@code gender=female, emotion=happy, gruffness=0.7} resolves into
 * concrete register, tract-length, tilt, jitter and aspiration numbers, so a
 * caller does not have to know what open quotient "gruff" implies. But an
 * explicitly set low-level value always wins over a macro. Setting
 * {@code source.f0_mean_hz = 196} together with {@code gender = female} gives
 * 196&nbsp;Hz, not the 274&nbsp;Hz that compounding both would produce;
 * {@link #explicitPaths()} is what distinguishes the two cases, by diffing
 * against the defaults.</p>
 *
 * <p>Mirrors {@code tools/villager_voice/voiceprint.py}. Call
 * {@link #effective()} before synthesising: the synthesiser only ever reads
 * concrete values, never macros.</p>
 */
public final class VoicePrint {

    /** Number of top-level sections in the control surface. */
    static final int SECTION_COUNT = 9;

    public static final String FORMAT = VoicePrintTables.FORMAT;
    public static final int VERSION = VoicePrintTables.VERSION;

    /** Emotion macro rows: rate, f0, range, tilt, breath, jitter, loud, fall. */
    private static final Map<String, double[]> EMOTIONS =
            new LinkedHashMap<String, double[]>();

    static {
        EMOTIONS.put("neutral", new double[]{1.00, 1.00, 1.00, 0.00, 0.00, 1.00, 1.00, 1.00});
        EMOTIONS.put("happy", new double[]{1.08, 1.06, 1.18, +0.10, +0.02, 1.05, 1.05, 0.75});
        EMOTIONS.put("sad", new double[]{0.86, 0.94, 0.72, -0.14, +0.10, 1.10, 0.84, 1.35});
        EMOTIONS.put("angry", new double[]{1.12, 1.09, 1.25, +0.18, -0.06, 1.35, 1.14, 0.85});
        EMOTIONS.put("scared", new double[]{1.16, 1.14, 1.40, +0.06, +0.12, 1.80, 1.02, 0.60});
        EMOTIONS.put("smug", new double[]{0.96, 1.02, 1.10, +0.04, -0.02, 0.95, 1.02, 0.90});
        EMOTIONS.put("bored", new double[]{0.90, 0.97, 0.62, -0.08, +0.06, 1.05, 0.88, 1.30});
        EMOTIONS.put("excited", new double[]{1.20, 1.12, 1.45, +0.14, +0.04, 1.25, 1.16, 0.65});
    }

    private final Map<String, Object> data;

    /** The seed voice: every knob at its default. */
    public VoicePrint() {
        this(VoicePrintTables.defaults(), false);
    }

    /** Wrap a partial or complete voiceprint, filling in any missing defaults. */
    public VoicePrint(Map<String, Object> overrides) {
        this(overrides, true);
    }

    @SuppressWarnings("unchecked")
    private VoicePrint(Map<String, Object> overrides, boolean check) {
        this.data = (Map<String, Object>) VoicePrintTables.deepCopy(
                deepMerge(VoicePrintTables.defaults(), overrides));
        if (check) {
            List<String> errors = validationErrors();
            if (!errors.isEmpty()) {
                StringBuilder message = new StringBuilder("invalid voiceprint:");
                for (String error : errors) {
                    message.append("\n  ").append(error);
                }
                throw new IllegalArgumentException(message.toString());
            }
        }
    }

    /** A copy of the seed voiceprint. */
    public static VoicePrint defaults() {
        return new VoicePrint();
    }

    /** Read a voiceprint JSON file. */
    public static VoicePrint load(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return fromJson(text);
    }

    /** Parse voiceprint JSON. */
    public static VoicePrint fromJson(String json) {
        return new VoicePrint(jsonToMap(new JSONObject(json)));
    }

    /** Write this voiceprint to a JSON file, creating parent directories. */
    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, toJson(2).getBytes(StandardCharsets.UTF_8));
    }

    /** Serialise to JSON. */
    public String toJson(int indent) {
        return mapToJson(data).toString(Math.max(0, indent));
    }

    /** The live nested map. Callers should treat it as read-only. */
    public Map<String, Object> data() {
        return data;
    }

    // ---------------------------------------------------------------------
    // dotted access
    // ---------------------------------------------------------------------

    /** Read a dotted path such as {@code source.f0_mean_hz}, or null. */
    @SuppressWarnings("unchecked")
    public Object get(String dottedPath) {
        Object cursor = data;
        for (String part : dottedPath.split("\\.")) {
            if (!(cursor instanceof Map)) {
                return null;
            }
            cursor = ((Map<String, Object>) cursor).get(part);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    /** Read a section as a map, or an empty map when absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        Object value = data.get(name);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    public double getDouble(String dottedPath, double fallback) {
        Object value = get(dottedPath);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) {
                return number;
            }
        }
        return fallback;
    }

    public int getInt(String dottedPath, int fallback) {
        Object value = get(dottedPath);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public boolean getBoolean(String dottedPath, boolean fallback) {
        Object value = get(dottedPath);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public String getString(String dottedPath, String fallback) {
        Object value = get(dottedPath);
        return value instanceof String ? (String) value : fallback;
    }

    /** Write a dotted path, creating intermediate maps as needed. */
    @SuppressWarnings("unchecked")
    public VoicePrint set(String dottedPath, Object value) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> cursor = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
        return this;
    }

    /** A new voiceprint with {@code overrides} merged over this one. */
    @SuppressWarnings("unchecked")
    public VoicePrint merged(Map<String, Object> overrides) {
        return new VoicePrint(deepMerge(this.data, overrides));
    }

    // ---------------------------------------------------------------------
    // validation
    // ---------------------------------------------------------------------

    /**
     * Every range and enum violation, as human-readable messages.
     *
     * @return an empty list when the voiceprint is valid
     */
    @SuppressWarnings("unchecked")
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<String>();
        Map<String, Object> flat = flatten(data, "", new LinkedHashMap<String, Object>());
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String path = entry.getKey();
            Object value = entry.getValue();
            if (path.startsWith("phone_overrides.")) {
                continue;
            }
            double[] bounds = VoicePrintTables.ranges().get(path);
            if (bounds != null) {
                if (value instanceof Boolean || !(value instanceof Number)) {
                    errors.add(path + " must be a number, got " + value);
                } else {
                    double number = ((Number) value).doubleValue();
                    if (Double.isNaN(number) || Double.isInfinite(number)) {
                        errors.add(path + " must be finite");
                    } else if (number < bounds[0] || number > bounds[1]) {
                        errors.add(String.format("%s must be between %g and %g, got %g",
                                path, bounds[0], bounds[1], number));
                    }
                }
            }
            String[] allowed = VoicePrintTables.enums().get(path);
            if (allowed != null && !Arrays.asList(allowed).contains(String.valueOf(value))) {
                errors.add(path + " must be one of " + Arrays.toString(allowed)
                        + ", got " + value);
            }
        }
        Object scales = get("tract.formant_scale");
        if (scales instanceof Map) {
            for (Map.Entry<String, Object> entry
                    : ((Map<String, Object>) scales).entrySet()) {
                Object value = entry.getValue();
                double number = value instanceof Number
                        ? ((Number) value).doubleValue() : Double.NaN;
                if (Double.isNaN(number) || number < 0.4 || number > 2.5) {
                    errors.add("tract.formant_scale." + entry.getKey()
                            + " must be 0.4..2.5, got " + value);
                }
            }
        }
        if (getDouble("source.f0_floor_hz", 0) >= getDouble("source.f0_ceiling_hz", 1)) {
            errors.add("source.f0_floor_hz must be below source.f0_ceiling_hz");
        }
        Collections.sort(errors);
        return errors;
    }

    /** True when {@link #validationErrors()} is empty. */
    public boolean isValid() {
        return validationErrors().isEmpty();
    }

    /**
     * Dotted paths whose value differs from the defaults, i.e. set on purpose.
     *
     * <p>This is what lets the {@code character} macro layer coexist with the
     * low-level knobs: a macro may move any field the caller left alone, and
     * must leave alone any field the caller set. Setting a field to exactly its
     * default value is indistinguishable from not setting it, and that one case
     * resolves in favour of the macro.</p>
     */
    public Set<String> explicitPaths() {
        Map<String, Object> defaults = flatten(VoicePrintTables.defaults(), "",
                new LinkedHashMap<String, Object>());
        Map<String, Object> mine = flatten(data, "", new LinkedHashMap<String, Object>());
        Set<String> explicit = new LinkedHashSet<String>();
        for (Map.Entry<String, Object> entry : mine.entrySet()) {
            if (!defaults.containsKey(entry.getKey())) {
                continue;
            }
            if (!valuesEqual(entry.getValue(), defaults.get(entry.getKey()))) {
                explicit.add(entry.getKey());
            }
        }
        return explicit;
    }

    /**
     * Pull derived values back inside their declared ranges, in place.
     *
     * <p>Macros can push a value past its bound (a very gruff, very breathy
     * voice adds aspiration twice); clamping afterwards keeps the synthesiser's
     * input legal without rejecting the request.</p>
     */
    public VoicePrint clampDerived() {
        for (Map.Entry<String, double[]> entry : VoicePrintTables.ranges().entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("phone_overrides")) {
                continue;
            }
            Object value = get(path);
            if (value instanceof Boolean || !(value instanceof Number)) {
                continue;
            }
            double number = ((Number) value).doubleValue();
            double[] bounds = entry.getValue();
            if (number < bounds[0]) {
                set(path, bounds[0]);
            } else if (number > bounds[1]) {
                set(path, bounds[1]);
            }
        }
        double floor = getDouble("source.f0_floor_hz", 68.0);
        double ceiling = getDouble("source.f0_ceiling_hz", 400.0);
        if (floor >= ceiling) {
            set("source.f0_floor_hz", Math.min(floor, ceiling * 0.5));
            floor = getDouble("source.f0_floor_hz", 68.0);
        }
        set("source.f0_mean_hz",
                clamp(getDouble("source.f0_mean_hz", 116.0), floor, ceiling));
        return this;
    }

    /**
     * Resolve the {@code character} macros into concrete source, tract, prosody
     * and articulation values.
     *
     * <p>Returns a <em>new</em> voiceprint, leaving this one untouched, so the
     * caller's intent is preserved for editing while the synthesiser reads only
     * concrete numbers. Mirrors {@code SpeechOptions.getEffective*} in the
     * existing Java voice package.</p>
     */
    @SuppressWarnings("unchecked")
    public VoicePrint effective() {
        Map<String, Object> resolved = (Map<String, Object>) VoicePrintTables.deepCopy(data);
        Map<String, Object> character = (Map<String, Object>) resolved.get("character");
        Map<String, Object> source = (Map<String, Object>) resolved.get("source");
        Map<String, Object> tract = (Map<String, Object>) resolved.get("tract");
        Map<String, Object> prosody = (Map<String, Object>) resolved.get("prosody");
        Map<String, Object> articulation = (Map<String, Object>) resolved.get("articulation");
        Map<String, Object> output = (Map<String, Object>) resolved.get("output");
        Map<String, Object> formantScale = (Map<String, Object>) tract.get("formant_scale");

        // Low-level knobs the caller set explicitly; macros must not touch them.
        final Set<String> explicit = explicitPaths();

        String emotion = str(character, "emotion", "neutral");
        double tenseness = dbl(character, "tenseness", 0.5);
        double gruff = dbl(character, "gruffness", 0.0);
        double smile = dbl(character, "smile", 0.0);
        double effort = dbl(character, "effort", 0.5);
        double size = dbl(character, "size", 1.0);
        double age = dbl(character, "age_years", 42.0);
        String gender = str(character, "gender", "male");
        double sarcasm = dbl(character, "sarcasm", 0.0);
        double tone = dbl(character, "tone", 0.0);

        // --- register: gender, age and size move the whole instrument ------
        double register = 1.0;
        if ("female".equals(gender)) {
            register *= 1.32;
        } else if ("child".equals(gender)) {
            register *= 1.62;
        } else if ("zombie".equals(gender)) {
            register *= 0.72;
        } else if ("neutral".equals(gender)) {
            register *= 1.08;
        }
        register *= 1.0 + clamp((42.0 - age) / 130.0, -0.4, 0.4);
        register /= Math.max(0.5, size);
        // An explicit f0 wins: without this guard, setting 196 Hz with
        // gender=female came out at 274 Hz because the macro multiplied it.
        if (!explicit.contains("source.f0_mean_hz")) {
            source.put("f0_mean_hz", clamp(dbl(source, "f0_mean_hz", 116.0) * register,
                    dbl(source, "f0_floor_hz", 68.0), dbl(source, "f0_ceiling_hz", 400.0)));
        }

        // Tract length tracks size and gender (a longer tract lowers formants).
        if (!explicit.contains("tract.length_scale")) {
            double length = dbl(tract, "length_scale", 1.0);
            if ("female".equals(gender)) {
                length *= 0.93;
            } else if ("child".equals(gender)) {
                length *= 0.80;
            } else if ("zombie".equals(gender)) {
                length *= 1.06;
            }
            length *= clamp(size, 0.7, 1.5);
            tract.put("length_scale", clamp(length, 0.6, 1.8));
        }

        // --- emotion -------------------------------------------------------
        double[] row = EMOTIONS.containsKey(emotion)
                ? EMOTIONS.get(emotion) : EMOTIONS.get("neutral");
        if (!explicit.contains("prosody.rate_syllables_per_sec")) {
            prosody.put("rate_syllables_per_sec",
                    dbl(prosody, "rate_syllables_per_sec", 3.31) * row[0]);
        }
        if (!explicit.contains("prosody.rate_words_per_sec")) {
            prosody.put("rate_words_per_sec", dbl(prosody, "rate_words_per_sec", 2.56) * row[0]);
        }
        if (!explicit.contains("source.f0_mean_hz")) {
            source.put("f0_mean_hz", clamp(dbl(source, "f0_mean_hz", 116.0) * row[1],
                    dbl(source, "f0_floor_hz", 68.0), dbl(source, "f0_ceiling_hz", 400.0)));
        }
        if (!explicit.contains("source.f0_semitone_range")) {
            source.put("f0_semitone_range", dbl(source, "f0_semitone_range", 7.0) * row[2]);
        }
        if (!explicit.contains("tract.spectral_tilt_db_per_oct")) {
            tract.put("spectral_tilt_db_per_oct",
                    dbl(tract, "spectral_tilt_db_per_oct", -9.0) + row[3] * 6.0);
        }
        if (!explicit.contains("source.breathiness")) {
            source.put("breathiness", clamp(dbl(source, "breathiness", 0.22) + row[4], 0.0, 1.0));
        }
        if (!explicit.contains("source.jitter_percent")) {
            source.put("jitter_percent", dbl(source, "jitter_percent", 0.85) * row[5]);
        }
        if (!explicit.contains("prosody.phrase_final_fall_semitones")) {
            prosody.put("phrase_final_fall_semitones",
                    dbl(prosody, "phrase_final_fall_semitones", -3.4) * row[7]);
        }
        if (!explicit.contains("output.gain_db")) {
            output.put("gain_db", dbl(output, "gain_db", 0.0)
                    + 20.0 * Math.log10(Math.max(0.05, row[6])));
        }

        // --- tenseness, gruffness, smile, resonance, effort -----------------
        if (!explicit.contains("source.open_quotient")) {
            source.put("open_quotient", clamp(
                    dbl(source, "open_quotient", 0.62) * (1.0 - 0.22 * (tenseness - 0.45)),
                    0.2, 0.95));
        }
        if (!explicit.contains("source.source_tilt_db_per_oct")) {
            source.put("source_tilt_db_per_oct",
                    dbl(source, "source_tilt_db_per_oct", -9.0) + -2.5 * (tenseness - 0.45));
        }
        if (!explicit.contains("source.jitter_percent")) {
            source.put("jitter_percent", dbl(source, "jitter_percent", 0.85)
                    * (1.0 - 0.45 * tenseness) * (1.0 + 1.1 * gruff));
        }
        if (!explicit.contains("source.aspiration_db")) {
            source.put("aspiration_db", dbl(source, "aspiration_db", -62.0) + 9.0 * gruff);
        }
        if (!explicit.contains("source.creak_percent")) {
            source.put("creak_percent",
                    clamp(dbl(source, "creak_percent", 0.0) + 0.35 * gruff, 0.0, 1.0));
        }
        if (!explicit.contains("tract.spectral_tilt_db_per_oct")) {
            tract.put("spectral_tilt_db_per_oct",
                    dbl(tract, "spectral_tilt_db_per_oct", -9.0) - 3.0 * gruff);
        }
        // A smile raises F2/F3 (the classic "smiling voice" brightening) and a
        // wider resonance spreads the whole vowel space.
        double spread = 1.0 + 0.16 * (dbl(character, "resonance", 0.5) - 0.5);
        double f2Gain = (1.0 + 0.045 * smile) * spread;
        if (!explicit.contains("tract.formant_scale.f1")) {
            formantScale.put("f1", dbl(formantScale, "f1", 1.0) * (1.0 + (spread - 1.0) * 0.9));
        }
        if (!explicit.contains("tract.formant_scale.f2")) {
            formantScale.put("f2", dbl(formantScale, "f2", 1.0) * f2Gain);
        }
        if (!explicit.contains("tract.formant_scale.f3")) {
            formantScale.put("f3", dbl(formantScale, "f3", 1.0) * (1.0 + 0.028 * smile));
        }
        if (!explicit.contains("prosody.loudness_range_db")) {
            prosody.put("loudness_range_db",
                    dbl(prosody, "loudness_range_db", 9.0) * (1.0 + 0.4 * (effort - 0.5)));
        }
        if (!explicit.contains("source.f0_semitone_range")) {
            source.put("f0_semitone_range", dbl(source, "f0_semitone_range", 7.0)
                    * (1.0 + 0.25 * (effort - 0.5)));
        }
        if (!explicit.contains("source.breathiness")) {
            source.put("breathiness", clamp(
                    dbl(source, "breathiness", 0.22) - 0.10 * (effort - 0.5), 0.0, 1.0));
        }
        // Breathiness trades source amplitude for aspiration noise. 30 dB of
        // travel across 0..1 was measured to span the whole useful HNR range;
        // 16 dB left the top half of the knob inaudible. This is a derived
        // conversion rather than a macro, so it always applies.
        source.put("aspiration_db", dbl(source, "aspiration_db", -62.0)
                + 30.0 * dbl(source, "breathiness", 0.22));

        // --- sarcasm and tone: drier, flatter, slightly darker --------------
        if (!explicit.contains("prosody.declination_semitones_per_sec")) {
            prosody.put("declination_semitones_per_sec",
                    dbl(prosody, "declination_semitones_per_sec", -0.85) * (1.0 - 0.35 * sarcasm));
        }
        if (!explicit.contains("source.f0_semitone_range")) {
            source.put("f0_semitone_range",
                    dbl(source, "f0_semitone_range", 7.0) * (1.0 - 0.28 * sarcasm));
        }
        if (!explicit.contains("tract.spectral_tilt_db_per_oct")) {
            tract.put("spectral_tilt_db_per_oct", dbl(tract, "spectral_tilt_db_per_oct", -9.0)
                    + 0.5 * tone - 0.12 * 6.0 * sarcasm);
        }
        if (!explicit.contains("articulation.duration_scale")) {
            articulation.put("duration_scale",
                    dbl(articulation, "duration_scale", 1.0) * (1.0 - 0.08 * sarcasm));
        }
        if (!explicit.contains("prosody.rate_syllables_per_sec")) {
            prosody.put("rate_syllables_per_sec",
                    dbl(prosody, "rate_syllables_per_sec", 3.31) * (1.0 + 0.08 * tone));
        }

        // --- zombie villager: lowered register, added creak, narrowed range --
        if ("zombie".equals(gender)) {
            if (!explicit.contains("source.creak_percent")) {
                source.put("creak_percent",
                        clamp(dbl(source, "creak_percent", 0.0) + 0.25, 0.0, 1.0));
            }
            if (!explicit.contains("source.subharmonic_mix")) {
                source.put("subharmonic_mix",
                        clamp(dbl(source, "subharmonic_mix", 0.0) + 0.30, 0.0, 1.0));
            }
            if (!explicit.contains("tract.spectral_tilt_db_per_oct")) {
                tract.put("spectral_tilt_db_per_oct",
                        dbl(tract, "spectral_tilt_db_per_oct", -9.0) - 4.0);
            }
            if (!explicit.contains("source.breathiness")) {
                source.put("breathiness",
                        clamp(dbl(source, "breathiness", 0.22) + 0.18, 0.0, 1.0));
            }
        }

        VoicePrint result = new VoicePrint(resolved, false);
        result.clampDerived();
        return result;
    }

    /** A one-line summary, for logs and the CLI. */
    public String summary() {
        VoicePrint effective = effective();
        return String.format(
                "%s %s | f0 %.0f Hz (range %.1f st) | tract x%.2f | tilt %.1f dB/oct"
                        + " | breathiness %.2f | jitter %.2f%% | %.2f syl/s",
                effective.getString("character.gender", "male"),
                effective.getString("character.accent", "rp-british"),
                effective.getDouble("source.f0_mean_hz", 116.0),
                effective.getDouble("source.f0_semitone_range", 7.0),
                effective.getDouble("tract.length_scale", 1.0),
                effective.getDouble("tract.spectral_tilt_db_per_oct", -9.0),
                effective.getDouble("source.breathiness", 0.22),
                effective.getDouble("source.jitter_percent", 0.85),
                effective.getDouble("prosody.rate_syllables_per_sec", 3.31));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    public static double clamp(double value, double low, double high) {
        return value < low ? low : (value > high ? high : value);
    }

    public static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    public static double linearToDb(double linear) {
        return linear <= 1e-9 ? -120.0 : 20.0 * Math.log10(linear);
    }

    private static double dbl(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /**
     * Compare two config values. Numbers are compared by value, so an
     * {@code Integer} from JSON equals a {@code Double} default of the same
     * magnitude -- comparing with {@code equals} would report a difference and
     * make every integer knob look explicitly set.
     */
    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        return left == null ? right == null : left.equals(right);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base,
                                         Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (base != null) {
            result.putAll(base);
        }
        if (overrides == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            Object value = entry.getValue();
            Object existing = result.get(entry.getKey());
            if (value instanceof Map && existing instanceof Map) {
                result.put(entry.getKey(),
                        deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /** Flatten nested maps to dotted keys, skipping {@code phone_overrides}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> flatten(Map<String, Object> source, String prefix,
                                       Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && !path.startsWith("phone_overrides")) {
                flatten((Map<String, Object>) value, path, out);
            } else {
                out.put(path, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static JSONObject mapToJson(Map<String, Object> map) {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            object.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    private static Object toJsonValue(Object value) {
        if (value instanceof Map) {
            return mapToJson((Map<String, Object>) value);
        }
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<Object>) value) {
                array.put(toJsonValue(item));
            }
            return array;
        }
        if (value instanceof double[]) {
            JSONArray array = new JSONArray();
            for (double item : (double[]) value) {
                array.put(item);
            }
            return array;
        }
        return value == null ? JSONObject.NULL : value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonToMap(JSONObject object) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (String key : object.keySet()) {
            Object value = object.get(key);
            if (value instanceof JSONObject) {
                map.put(key, jsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                map.put(key, jsonToList((JSONArray) value));
            } else if (value == JSONObject.NULL) {
                map.put(key, null);
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jsonToList(JSONArray array) {
        List<Object> list = new ArrayList<Object>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONObject) {
                list.add(jsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                list.add(jsonToList((JSONArray) value));
            } else if (value == JSONObject.NULL) {
                list.add(null);
            } else {
                list.add(value);
            }
        }
        return list;
    }
}
