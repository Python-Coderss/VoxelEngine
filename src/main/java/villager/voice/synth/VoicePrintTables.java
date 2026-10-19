/*
 * GENERATED FILE -- do not edit by hand.
 *
 * Emitted from the verified Python tables by:
 *     cd tools && python3 -m villager_voice.java_port
 *
 * The Python package is the source of truth for this data. Edit
 * tools/villager_voice/voiceprint.py and regenerate, so the Java voice can never
 * silently drift away from the one that passed selftest.
 */

package villager.voice.synth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The voiceprint control surface's data: 102 defaults, 87 declared
 * ranges and 4 enums.
 *
 * <p>Mirrors {@code tools/villager_voice/voiceprint.py}. The logic that
 * reads these tables lives in {@link VoicePrint}.</p>
 */
public final class VoicePrintTables {

    public static final String FORMAT = "villager-voiceprint";
    public static final int VERSION = 1;

    private static final Map<String, Object> DEFAULTS =
            new LinkedHashMap<String, Object>();
    private static final Map<String, double[]> RANGES =
            new LinkedHashMap<String, double[]>();
    private static final Map<String, String[]> ENUMS =
            new LinkedHashMap<String, String[]>();

    private VoicePrintTables() {
    }

    static {
        Map<String, Object> into = DEFAULTS;
        into.put("format", "villager-voiceprint");
        into.put("version", 1);
        into.put("name", "Villager News anchor");
        into.put("notes", "");
        Map<String, Object> into_provenance = new LinkedHashMap<String, Object>();
            into_provenance.put("kind", "seed");
            into_provenance.put("measured_fields", new java.util.ArrayList<Object>());
            into_provenance.put("audio_seconds", 0.0);
            into_provenance.put("clips", 0);
            into_provenance.put("reference", new java.util.ArrayList<Object>());
        into.put("provenance", into_provenance);
        Map<String, Object> into_output = new LinkedHashMap<String, Object>();
            into_output.put("sample_rate", 24000);
            into_output.put("gain_db", 0.0);
            into_output.put("peak_ceiling", 0.95);
            into_output.put("normalize_peak", true);
            into_output.put("highpass_hz", 70.0);
            into_output.put("lowpass_hz", 9500.0);
            into_output.put("room_mix", 0.0);
            into_output.put("room_decay_ms", 0.0);
            into_output.put("fade_ms", 12.0);
        into.put("output", into_output);
        Map<String, Object> into_source = new LinkedHashMap<String, Object>();
            into_source.put("model", "rosenberg");
            into_source.put("f0_mean_hz", 116.0);
            into_source.put("f0_semitone_range", 7.0);
            into_source.put("f0_floor_hz", 68.0);
            into_source.put("f0_ceiling_hz", 330.0);
            into_source.put("open_quotient", 0.62);
            into_source.put("speed_ratio", 0.42);
            into_source.put("source_tilt_db_per_oct", -9.0);
            into_source.put("aspiration_db", -62.0);
            into_source.put("aspiration_tilt_db_per_oct", -5.0);
            into_source.put("breathiness", 0.22);
            into_source.put("jitter_percent", 0.85);
            into_source.put("shimmer_percent", 3.5);
            into_source.put("subharmonic_mix", 0.0);
            into_source.put("creak_percent", 0.0);
            into_source.put("voicing_hysteresis", 0.5);
            into_source.put("vibrato_rate_hz", 5.1);
            into_source.put("vibrato_depth_cents", 24.0);
            into_source.put("vibrato_onset_ms", 220.0);
            into_source.put("pitch_drift_cents_per_sec", -3.0);
        into.put("source", into_source);
        Map<String, Object> into_tract = new LinkedHashMap<String, Object>();
            into_tract.put("length_scale", 1.0);
            Map<String, Object> into_tract_formant_scale = new LinkedHashMap<String, Object>();
                into_tract_formant_scale.put("f1", 1.0);
                into_tract_formant_scale.put("f2", 1.0);
                into_tract_formant_scale.put("f3", 1.0);
                into_tract_formant_scale.put("f4", 1.0);
                into_tract_formant_scale.put("f5", 1.0);
            into_tract.put("formant_scale", into_tract_formant_scale);
            into_tract.put("bandwidth_scale", 1.0);
            into_tract.put("extra_bandwidth_hz", 25.0);
            into_tract.put("cascade_order", 5);
            into_tract.put("spectral_tilt_db_per_oct", -3.0);
            into_tract.put("radiation_db_per_oct", 6.0);
            into_tract.put("hf_loss_hz", 6800.0);
            into_tract.put("nasality", 0.0);
            into_tract.put("nasal_formant_hz", 285.0);
            into_tract.put("nasal_bandwidth_hz", 95.0);
            into_tract.put("nasal_zero_hz", 950.0);
            into_tract.put("nasal_zero_bandwidth_hz", 130.0);
            into_tract.put("frication_gain_db", 0.0);
            into_tract.put("sibilance_center_hz", 5400.0);
            into_tract.put("sibilance_boost_db", 2.5);
        into.put("tract", into_tract);
        Map<String, Object> into_articulation = new LinkedHashMap<String, Object>();
            into_articulation.put("coarticulation", 0.55);
            into_articulation.put("transition_ms", 40.0);
            into_articulation.put("undershoot", 0.18);
            into_articulation.put("vowel_reduction", 0.5);
            into_articulation.put("duration_scale", 1.0);
            into_articulation.put("vowel_duration_scale", 1.0);
            into_articulation.put("consonant_duration_scale", 1.0);
            into_articulation.put("stop_burst_db", -13.0);
            into_articulation.put("stop_aspiration_ms", 42.0);
            into_articulation.put("release_noise_db", -22.0);
            into_articulation.put("lateral_release", true);
        into.put("articulation", into_articulation);
        Map<String, Object> into_prosody = new LinkedHashMap<String, Object>();
            into_prosody.put("rate_syllables_per_sec", 3.31);
            into_prosody.put("rate_words_per_sec", 2.56);
            into_prosody.put("rate_stdev", 0.9);
            into_prosody.put("declination_semitones_per_sec", -0.85);
            into_prosody.put("stress_rise_semitones", 2.1);
            into_prosody.put("accent_rise_semitones", 3.0);
            into_prosody.put("f0_reset_semitones", 1.6);
            into_prosody.put("phrase_final_fall_semitones", -3.4);
            into_prosody.put("question_rise_semitones", 4.6);
            into_prosody.put("exclamation_rise_semitones", 1.8);
            into_prosody.put("pause_short_ms", 130.0);
            into_prosody.put("pause_comma_ms", 250.0);
            into_prosody.put("pause_sentence_ms", 470.0);
            into_prosody.put("pause_variability", 0.18);
            into_prosody.put("pre_boundary_lengthening", 1.32);
            into_prosody.put("rhythm", 0.38);
            into_prosody.put("loudness_range_db", 9.0);
            into_prosody.put("stress_loudness_db", 2.4);
            into_prosody.put("final_softening_db", -2.0);
        into.put("prosody", into_prosody);
        Map<String, Object> into_character = new LinkedHashMap<String, Object>();
            into_character.put("gender", "male");
            into_character.put("age_years", 42.0);
            into_character.put("accent", "rp-british");
            into_character.put("emotion", "neutral");
            into_character.put("tone", 0.0);
            into_character.put("sarcasm", 0.0);
            into_character.put("tenseness", 0.45);
            into_character.put("resonance", 0.5);
            into_character.put("gruffness", 0.12);
            into_character.put("smile", 0.0);
            into_character.put("effort", 0.5);
            into_character.put("size", 1.0);
            into_character.put("villager_hmm_mix", 0.0);
        into.put("character", into_character);
        Map<String, Object> into_phone_overrides = new LinkedHashMap<String, Object>();
        into.put("phone_overrides", into_phone_overrides);
        into.put("random_seed", 20261004);
        RANGES.put("articulation.coarticulation", new double[]{0.0, 1.0});
        RANGES.put("articulation.consonant_duration_scale", new double[]{0.2, 4.0});
        RANGES.put("articulation.duration_scale", new double[]{0.2, 4.0});
        RANGES.put("articulation.release_noise_db", new double[]{-60.0, 6.0});
        RANGES.put("articulation.stop_aspiration_ms", new double[]{0.0, 200.0});
        RANGES.put("articulation.stop_burst_db", new double[]{-48.0, 6.0});
        RANGES.put("articulation.transition_ms", new double[]{5.0, 200.0});
        RANGES.put("articulation.undershoot", new double[]{0.0, 0.8});
        RANGES.put("articulation.vowel_duration_scale", new double[]{0.2, 4.0});
        RANGES.put("articulation.vowel_reduction", new double[]{0.0, 1.0});
        RANGES.put("character.age_years", new double[]{2.0, 110.0});
        RANGES.put("character.effort", new double[]{0.0, 1.0});
        RANGES.put("character.gruffness", new double[]{0.0, 1.0});
        RANGES.put("character.resonance", new double[]{0.0, 1.0});
        RANGES.put("character.sarcasm", new double[]{0.0, 1.0});
        RANGES.put("character.size", new double[]{0.4, 2.5});
        RANGES.put("character.smile", new double[]{-1.0, 1.0});
        RANGES.put("character.tenseness", new double[]{0.0, 1.0});
        RANGES.put("character.tone", new double[]{-1.0, 1.0});
        RANGES.put("character.villager_hmm_mix", new double[]{0.0, 1.0});
        RANGES.put("output.fade_ms", new double[]{0.0, 500.0});
        RANGES.put("output.gain_db", new double[]{-48.0, 24.0});
        RANGES.put("output.highpass_hz", new double[]{0.0, 2000.0});
        RANGES.put("output.lowpass_hz", new double[]{500.0, 24000.0});
        RANGES.put("output.peak_ceiling", new double[]{0.01, 1.0});
        RANGES.put("output.room_decay_ms", new double[]{0.0, 6000.0});
        RANGES.put("output.room_mix", new double[]{0.0, 1.0});
        RANGES.put("output.sample_rate", new double[]{4000.0, 96000.0});
        RANGES.put("prosody.accent_rise_semitones", new double[]{-6.0, 16.0});
        RANGES.put("prosody.declination_semitones_per_sec", new double[]{-8.0, 4.0});
        RANGES.put("prosody.exclamation_rise_semitones", new double[]{-8.0, 12.0});
        RANGES.put("prosody.f0_reset_semitones", new double[]{-8.0, 12.0});
        RANGES.put("prosody.final_softening_db", new double[]{-24.0, 6.0});
        RANGES.put("prosody.loudness_range_db", new double[]{0.0, 30.0});
        RANGES.put("prosody.pause_comma_ms", new double[]{0.0, 2000.0});
        RANGES.put("prosody.pause_sentence_ms", new double[]{0.0, 4000.0});
        RANGES.put("prosody.pause_short_ms", new double[]{0.0, 1000.0});
        RANGES.put("prosody.pause_variability", new double[]{0.0, 1.0});
        RANGES.put("prosody.phrase_final_fall_semitones", new double[]{-16.0, 8.0});
        RANGES.put("prosody.pre_boundary_lengthening", new double[]{1.0, 2.5});
        RANGES.put("prosody.question_rise_semitones", new double[]{-8.0, 16.0});
        RANGES.put("prosody.rate_stdev", new double[]{0.0, 4.0});
        RANGES.put("prosody.rate_syllables_per_sec", new double[]{0.5, 12.0});
        RANGES.put("prosody.rate_words_per_sec", new double[]{0.3, 9.0});
        RANGES.put("prosody.rhythm", new double[]{0.0, 1.0});
        RANGES.put("prosody.stress_loudness_db", new double[]{0.0, 12.0});
        RANGES.put("prosody.stress_rise_semitones", new double[]{-6.0, 12.0});
        RANGES.put("random_seed", new double[]{0.0, 1099511627776.0});
        RANGES.put("source.aspiration_db", new double[]{-80.0, -6.0});
        RANGES.put("source.aspiration_tilt_db_per_oct", new double[]{-24.0, 12.0});
        RANGES.put("source.breathiness", new double[]{0.0, 1.0});
        RANGES.put("source.creak_percent", new double[]{0.0, 1.0});
        RANGES.put("source.f0_ceiling_hz", new double[]{60.0, 1200.0});
        RANGES.put("source.f0_floor_hz", new double[]{30.0, 400.0});
        RANGES.put("source.f0_mean_hz", new double[]{40.0, 600.0});
        RANGES.put("source.f0_semitone_range", new double[]{0.0, 36.0});
        RANGES.put("source.jitter_percent", new double[]{0.0, 8.0});
        RANGES.put("source.open_quotient", new double[]{0.2, 0.95});
        RANGES.put("source.pitch_drift_cents_per_sec", new double[]{-60.0, 60.0});
        RANGES.put("source.shimmer_percent", new double[]{0.0, 30.0});
        RANGES.put("source.source_tilt_db_per_oct", new double[]{-30.0, 6.0});
        RANGES.put("source.speed_ratio", new double[]{0.05, 0.9});
        RANGES.put("source.subharmonic_mix", new double[]{0.0, 1.0});
        RANGES.put("source.vibrato_depth_cents", new double[]{0.0, 200.0});
        RANGES.put("source.vibrato_onset_ms", new double[]{0.0, 2000.0});
        RANGES.put("source.vibrato_rate_hz", new double[]{0.0, 12.0});
        RANGES.put("source.voicing_hysteresis", new double[]{0.0, 1.0});
        RANGES.put("tract.bandwidth_scale", new double[]{0.4, 3.0});
        RANGES.put("tract.cascade_order", new double[]{1.0, 6.0});
        RANGES.put("tract.extra_bandwidth_hz", new double[]{0.0, 400.0});
        RANGES.put("tract.formant_scale.f1", new double[]{0.5, 2.0});
        RANGES.put("tract.formant_scale.f2", new double[]{0.5, 2.0});
        RANGES.put("tract.formant_scale.f3", new double[]{0.5, 2.0});
        RANGES.put("tract.formant_scale.f4", new double[]{0.5, 2.0});
        RANGES.put("tract.formant_scale.f5", new double[]{0.5, 2.0});
        RANGES.put("tract.frication_gain_db", new double[]{-24.0, 24.0});
        RANGES.put("tract.hf_loss_hz", new double[]{1000.0, 20000.0});
        RANGES.put("tract.length_scale", new double[]{0.6, 1.8});
        RANGES.put("tract.nasal_bandwidth_hz", new double[]{30.0, 400.0});
        RANGES.put("tract.nasal_formant_hz", new double[]{150.0, 800.0});
        RANGES.put("tract.nasal_zero_bandwidth_hz", new double[]{30.0, 600.0});
        RANGES.put("tract.nasal_zero_hz", new double[]{300.0, 3000.0});
        RANGES.put("tract.nasality", new double[]{0.0, 1.0});
        RANGES.put("tract.radiation_db_per_oct", new double[]{0.0, 12.0});
        RANGES.put("tract.sibilance_boost_db", new double[]{-12.0, 18.0});
        RANGES.put("tract.sibilance_center_hz", new double[]{1500.0, 11000.0});
        RANGES.put("tract.spectral_tilt_db_per_oct", new double[]{-24.0, 12.0});
        ENUMS.put("character.accent", new String[]{"rp-british", "general-american", "australian", "irish", "neutral"});
        ENUMS.put("character.emotion", new String[]{"neutral", "happy", "sad", "angry", "scared", "smug", "bored", "excited"});
        ENUMS.put("character.gender", new String[]{"male", "female", "neutral", "child", "zombie"});
        ENUMS.put("source.model", new String[]{"rosenberg", "lf_style", "pulse", "buzz", "whisper"});
    }

    /** A deep copy of the default voiceprint. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> defaults() {
        return (Map<String, Object>) deepCopy(DEFAULTS);
    }

    public static Map<String, double[]> ranges() {
        return Collections.unmodifiableMap(RANGES);
    }

    public static Map<String, String[]> enums() {
        return Collections.unmodifiableMap(ENUMS);
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) value;
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> source = (java.util.List<Object>) value;
            java.util.List<Object> copy = new java.util.ArrayList<Object>();
            for (Object item : source) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
