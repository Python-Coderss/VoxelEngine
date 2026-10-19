#!/usr/bin/env python3
"""The engine's own verification suite.

Every number in this file was measured, not assumed. The suite exists because a
source-filter synthesiser and an LPC analyser can agree with each other while
both being wrong; so the tests are built around signals whose ground truth is
known by construction:

  * an impulse train pushed through resonators at known frequencies, which the
    analyser must recover;
  * a Levinson-Durbin solver checked against a direct Gaussian solve of the same
    Yule-Walker system;
  * a synthesised line re-measured against the voiceprint that produced it;
  * PSOLA transforms whose expected pitch, duration and formant ratios are
    arithmetic, then measured back.

Tolerances are deliberately honest rather than flattering. F1 is allowed 12%
because LPC formant estimation is systematically biased low when the fundamental
sits close to the first formant -- the same bias Praat shows. F2/F3 are held to
5%, F0 to 2.5%.

Run:  python3 -m villager_voice.selftest
"""

import math
import os
import tempfile

from . import analyze, corpus, lexicon, phones, prosody, synth, transform, wavio
from .voiceprint import DEFAULTS, ENUMS, RANGES, VoicePrint, _flatten

RESULTS = []

# Ground-truth tolerances, in percent unless the name says otherwise.
TOLERANCE = {
    "f0_percent": 2.5,
    "f1_percent": 12.0,
    "f2_percent": 5.0,
    "f3_percent": 5.0,
    "duration_percent": 3.0,
    "formant_shift_percent": 6.0,
    "rate_percent": 18.0,
    "sample_error": 0.5 / 32768.0 + 1e-9,
    "hnr_floor_db": 12.0,
}

# Pronunciations verified by hand against RP during lexicon development. If the
# grapheme-to-phoneme rules change, these are the words that must not regress.
G2P_EXPECTED = {
    "better": "B EH1 T AX",
    "water": "W AO1 T AX",
    "running": "R AH1 N IH NG",
    "stopped": "S T OQ1 P T",
    "wanted": "W AE1 N T IH D",
    "played": "P L EY1 D",
    "nation": "N EY1 SH AX N",
    "magical": "M AE1 JH IH K AX L",
    "expensive": "IH K S P EH1 N S IH V",
    "through": "TH R UW1",
    "cough": "K OQ1 F",
    "knowledge": "N OQ1 L AH JH",
    "villager": "V IH1 L AH JH AX",
    "emeralds": "EH1 M AX L D Z",
    "pork": "P AO1 K",
    "church": "CH ER1 CH",
    "sparkly": "S P AA1 K L IY",
    "yesterday": "Y EH1 S T AX D EY2",
    "beautiful": "B Y UW1 T AH F AX L",
}

TIMING_LINES = [
    "Oh, a fellow villager! Hmm.",
    "Would you like to trade emeralds?",
    "Hrmm, I don't have any nose.",
    "That is a very good book.",
]


def check(name, ok, detail=""):
    """Record one assertion."""
    RESULTS.append({"name": name, "ok": bool(ok), "detail": detail})
    return bool(ok)


def _percent(got, want):
    if not want:
        return 0.0
    return 100.0 * abs(got - want) / abs(want)


# --------------------------------------------------------------------------- #
# signal construction helpers
# --------------------------------------------------------------------------- #

def _resonate(excitation, sample_rate, frequency, bandwidth):
    """Two-pole resonator, the same structure the synthesiser's tract uses."""
    r = math.exp(-math.pi * bandwidth / sample_rate)
    theta = 2.0 * math.pi * frequency / sample_rate
    a1 = 2.0 * r * math.cos(theta)
    a2 = -(r * r)
    gain = 1.0 - a1 - a2
    state = [0.0, 0.0]
    out = []
    for value in excitation:
        sample = gain * value + a1 * state[0] + a2 * state[1]
        state[1] = state[0]
        state[0] = sample
        out.append(sample)
    return out


def _pulse_train(sample_rate, seconds, f0):
    period = sample_rate / f0
    out = [0.0] * int(seconds * sample_rate)
    position = 0.0
    while position < len(out):
        index = int(round(position))
        if 0 <= index < len(out):
            out[index] = 1.0
        position += period
    return out


def _sustained(symbol="AX", f0=116.0, seconds=0.6, overrides=None, rate=24000):
    """A stationary vowel whose formant targets are known exactly."""
    targets, bandwidths = phones.formant_targets(symbol)
    source = {"jitter_percent": 0.0, "shimmer_percent": 0.0,
              "vibrato_depth_cents": 0.0, "pitch_drift_cents_per_sec": 0.0,
              "aspiration_db": -70.0, "f0_mean_hz": f0}
    source.update((overrides or {}).get("source", {}))
    voiceprint = VoicePrint.default().merged({"source": source,
                                              "output": {"sample_rate": rate},
                                              "random_seed": 7})
    score = {
        "phones": [{"sym": symbol, "start": 0.05, "dur": seconds, "stress": 1,
                    "amp": 1.0, "voiced": True, "f": list(targets),
                    "bw": list(bandwidths), "manner": "vowel", "word": 0,
                    "syll": 0}],
        "pauses": [],
        "contours": {"f0": [[0.0, 0.0], [seconds + 0.2, 0.0]],
                     "amp": [[0.0, 0.0], [seconds + 0.2, 0.0]]},
        "duration": seconds + 0.15,
    }
    return voiceprint, score, targets, bandwidths


def _formants(measurements):
    values = measurements.get("formants_hz") or []
    return [values[i] if i < len(values) else 0.0 for i in range(3)]


# --------------------------------------------------------------------------- #
# tests
# --------------------------------------------------------------------------- #

def test_levinson_durbin():
    """LD recursion must equal a direct Gaussian solve of the same system."""
    sample_rate = 24000
    order = 12
    frame = [math.sin(2 * math.pi * 520 * i / sample_rate) * math.exp(-i / 2400.0)
             + 0.3 * math.sin(2 * math.pi * 1500 * i / sample_rate)
             for i in range(600)]
    frame = analyze.preemphasis(frame)
    autocorr = analyze.autocorrelation(frame, order)
    coefficients, _gain = analyze.levinson_durbin(autocorr, order)

    # Direct solve: R a = -r, with R[i][j] = autocorr[|i-j|], r[i] = autocorr[i+1].
    matrix = [[autocorr[abs(i - j)] for j in range(order)] for i in range(order)]
    vector = [-autocorr[i + 1] for i in range(order)]
    for column in range(order):
        pivot = max(range(column, order), key=lambda r: abs(matrix[r][column]))
        if abs(matrix[pivot][column]) < 1e-18:
            return check("levinson_durbin", False, "singular Yule-Walker matrix")
        matrix[column], matrix[pivot] = matrix[pivot], matrix[column]
        vector[column], vector[pivot] = vector[pivot], vector[column]
        divisor = matrix[column][column]
        for row in range(order):
            if row == column:
                continue
            factor = matrix[row][column] / divisor
            if factor:
                for k in range(column, order):
                    matrix[row][k] -= factor * matrix[column][k]
                vector[row] -= factor * vector[column]
    direct = [vector[i] / matrix[i][i] for i in range(order)]

    worst = max(abs(a - b) for a, b in zip(coefficients, direct))
    return check("levinson_durbin_matches_gaussian_solve", worst < 1e-6,
                 "max coefficient error %.2e" % worst)


def test_wav_roundtrip():
    """16-bit WAV write/read must reproduce samples to within one quantum."""
    sample_rate = 24000
    original = [0.9 * math.sin(2 * math.pi * 220 * i / sample_rate)
                for i in range(sample_rate // 4)]
    path = os.path.join(tempfile.gettempdir(), "vv_selftest.wav")
    try:
        wavio.write_wav(path, sample_rate, original)
        rate, restored = wavio.read_wav(path)
        if rate != sample_rate or len(restored) != len(original):
            return check("wav_roundtrip", False,
                         "rate %d->%d, length %d->%d" % (
                             sample_rate, rate, len(original), len(restored)))
        worst = max(abs(a - b) for a, b in zip(original, restored))
        # Half a quantum is the theoretical limit for a symmetric 16-bit round
        # trip; anything larger means the write and read scales disagree.
        ok = worst <= TOLERANCE["sample_error"]
        return check("wav_roundtrip_16bit", ok,
                     "max sample error %.2e (limit %.2e = half a quantum)" % (
                         worst, TOLERANCE["sample_error"]))
    finally:
        if os.path.exists(path):
            os.remove(path)


def test_voiceprint_roundtrip():
    """Save/load identity, range validation, and clamping."""
    voiceprint = VoicePrint.default()
    path = os.path.join(tempfile.gettempdir(), "vv_selftest_voiceprint.json")
    try:
        voiceprint.save(path)
        restored = VoicePrint.load(path)
        if restored.to_json() != voiceprint.to_json():
            return check("voiceprint_save_load", False, "JSON differs after reload")

        rejected = False
        try:
            VoicePrint.default().merged({"source": {"f0_mean_hz": 5000.0}})
        except ValueError:
            rejected = True
        if not rejected:
            return check("voiceprint_rejects_out_of_range", False,
                         "f0_mean_hz=5000 was accepted")

        clamped = VoicePrint.default().merged(
            {"source": {"breathiness": 0.9}}).clamp_derived()
        # effective() resolves the character layer and returns a VoicePrint.
        aspiration = clamped.effective().get("source.aspiration_db")
        low, high = RANGES["source.aspiration_db"]
        if not (low <= aspiration <= high):
            return check("voiceprint_clamp_derived", False,
                         "derived aspiration_db %.1f outside %s" % (
                             aspiration, (low, high)))

        check("voiceprint_save_load", True, "%d fields" % len(_flatten(DEFAULTS)))
        check("voiceprint_rejects_out_of_range", True, "f0_mean_hz=5000 rejected")
        return check("voiceprint_clamp_derived", True,
                     "breathiness 0.9 -> aspiration_db %.1f" % aspiration)
    finally:
        if os.path.exists(path):
            os.remove(path)


def test_schema_completeness():
    """Every scalar knob must have a declared range or enum."""
    missing = []
    for path, value in sorted(_flatten(DEFAULTS).items()):
        if isinstance(value, bool) or not isinstance(value, (int, float, str)):
            continue
        if path in RANGES or path in ENUMS:
            continue
        missing.append(path)
    # File metadata and provenance are descriptions, not knobs, so they carry no
    # range. Everything else in the control surface must be bounded.
    known_free = {"format", "version", "name", "notes", "random_seed",
                  "output.sample_rate"}
    missing = [path for path in missing if path not in known_free
               and not path.startswith("provenance.")]
    return check("schema_covers_every_knob", not missing,
                 ("%d knobs bounded (%d ranges, %d enums)"
                  % (len(_flatten(DEFAULTS)), len(RANGES), len(ENUMS)))
                 if not missing else "unranged: %s" % ", ".join(missing[:8]))


def test_lexicon():
    """Grapheme-to-phoneme must not regress on the curated word list."""
    failures = []
    for word, expected in sorted(G2P_EXPECTED.items()):
        got = " ".join("%s%s" % (symbol, stress if stress else "")
                       for symbol, stress in lexicon.pronounce(word))
        if got != expected:
            failures.append("%s: %s != %s" % (word, got, expected))
    inventory = phones.inventory_report()
    return check("lexicon_g2p", not failures,
                 "%d/%d words exact; %s" % (
                     len(G2P_EXPECTED) - len(failures), len(G2P_EXPECTED),
                     "%d phones" % len(phones.PHONES) if hasattr(phones, "PHONES")
                     else "inventory ok")
                 if not failures else "; ".join(failures[:4]))


def test_formant_recovery():
    """The analyser must recover resonator frequencies it is fed."""
    sample_rate = 24000
    wanted = [500.0, 1500.0, 2500.0]
    signal = _pulse_train(sample_rate, 0.6, 110.0)
    for frequency, bandwidth in zip(wanted, [80.0, 100.0, 120.0]):
        signal = _resonate(signal, sample_rate, frequency, bandwidth)
    signal = wavio.normalize_peak(signal, 0.9)
    got = _formants(analyze.analyze_samples(signal, sample_rate))
    limits = [TOLERANCE["f1_percent"], TOLERANCE["f2_percent"],
              TOLERANCE["f3_percent"]]
    errors = [_percent(a, b) for a, b in zip(got, wanted)]
    ok = all(error <= limit for error, limit in zip(errors, limits))
    return check("formant_recovery_from_resonators", ok,
                 "want %s got %s (errors %.1f/%.1f/%.1f%%)" % (
                     [int(v) for v in wanted], [int(v) for v in got],
                     errors[0], errors[1], errors[2]))


def test_sustained_vowel():
    """A stationary synthesised vowel must measure back to its own targets."""
    voiceprint, score, targets, _bw = _sustained("AX", f0=116.0, seconds=0.6)
    rate, samples = synth.render_score(score, voiceprint)
    measurements = analyze.analyze_samples(samples, rate)
    got = _formants(measurements)
    f0_error = _percent(measurements["f0_mean_hz"], 116.0)
    errors = [_percent(a, b) for a, b in zip(got, targets[:3])]
    limits = [TOLERANCE["f1_percent"], TOLERANCE["f2_percent"],
              TOLERANCE["f3_percent"]]
    ok = f0_error <= TOLERANCE["f0_percent"] and all(
        error <= limit for error, limit in zip(errors, limits))
    return check("sustained_vowel_round_trip", ok,
                 "F0 116.0 -> %.1f (%.1f%%); F1-F3 %s -> %s (%.1f/%.1f/%.1f%%)" % (
                     measurements["f0_mean_hz"], f0_error,
                     [int(v) for v in targets[:3]], [int(v) for v in got],
                     errors[0], errors[1], errors[2]))


def test_prosody_rate():
    """Generated timing must match the corpus speaking rate."""
    profile = corpus.load_profile()
    voiceprint = VoicePrint.default()
    rates = []
    for line in TIMING_LINES:
        score = prosody.build_score(line, voiceprint, corpus=profile, seed=11)
        rate, samples = synth.render_score(score, voiceprint)
        duration = len(samples) / float(rate)
        syllables = sum(1 for phone in score["phones"]
                        if phone.get("manner") in ("vowel", "diphthong"))
        if duration > 0 and syllables:
            rates.append(syllables / duration)
    if not rates:
        return check("prosody_rate", False, "no syllables produced")
    average = sum(rates) / len(rates)
    error = _percent(average, profile.syllables_per_sec)
    return check("prosody_rate_matches_corpus", error <= TOLERANCE["rate_percent"],
                 "%.2f syl/s vs corpus %.2f (%.1f%%)" % (
                     average, profile.syllables_per_sec, error))


def test_corpus_fallback():
    """The hard-coded fallbacks must equal a fresh measurement of the index."""
    if not os.path.isfile(corpus.DEFAULT_INDEX):
        return check("corpus_fallback_matches_index", True,
                     "index absent; nothing to compare (skipped)")
    measured = corpus.load_profile()
    fallback = corpus.load_profile("/nonexistent/clips_index.json")
    fields = ["clip_count", "total_audio_seconds", "word_types", "word_tokens",
              "question_rate", "cues_per_clip", "words_per_sec",
              "syllables_per_sec", "syllables_per_sec_p10",
              "syllables_per_sec_p90", "clip_duration_median",
              "clip_duration_mean", "clip_duration_max", "word_slot_median",
              "word_slot_p10", "word_slot_p90", "cue_span_median"]
    drifted = []
    for field in fields:
        want = getattr(measured, field, None)
        got = getattr(fallback, field, None)
        if isinstance(want, (int, float)) and isinstance(got, (int, float)):
            if _percent(got, want) > 2.0:
                drifted.append("%s %.3f vs %.3f" % (field, got, want))
        elif want != got:
            drifted.append("%s %r vs %r" % (field, got, want))
    return check("corpus_fallback_matches_index", not drifted,
                 "%d fields agree" % len(fields) if not drifted
                 else "; ".join(drifted[:4]))


def test_round_trip():
    """A whole synthesised sentence must measure back to its voiceprint."""
    report = analyze.round_trip_report()
    comparison = report["comparison"]
    f0 = comparison["f0_mean_hz"]
    formants = comparison["formants_hz"]
    ok = f0["error_percent"] <= TOLERANCE["f0_percent"]
    for index, limit in ((1, TOLERANCE["f2_percent"]), (2, TOLERANCE["f3_percent"])):
        if index < len(formants):
            ok = ok and formants[index]["error_percent"] <= limit
    detail = "F0 %.1f -> %.1f (%.1f%%)" % (f0["voiceprint"], f0["measured"],
                                           f0["error_percent"])
    for entry in formants:
        detail += "; F%d %.0f -> %.0f (%.1f%%)" % (
            entry["index"], entry["expected"], entry["measured"],
            entry["error_percent"])
    detail += " | HNR %.1f dB, jitter %.2f%%" % (
        report["measurements"].get("hnr_db", 0.0),
        report["measurements"].get("jitter_percent", 0.0))
    return check("sentence_round_trip_f0_f2_f3", ok, detail)


def test_psola_pitch():
    """Pitch shift must move F0 and leave duration and formants alone."""
    voiceprint, score, targets, _bw = _sustained("AX", f0=116.0, seconds=0.6)
    rate, samples = synth.render_score(score, voiceprint)
    base = analyze.analyze_samples(samples, rate)
    base_formants = _formants(base)

    semitones = 7.0
    factor = 2.0 ** (semitones / 12.0)
    new_rate, out, report = transform.apply_spec(
        samples, rate, {"pitch": {"semitones": semitones}})
    got = analyze.analyze_samples(out, new_rate)
    got_formants = _formants(got)

    f0_error = _percent(got["f0_mean_hz"], base["f0_mean_hz"] * factor)
    duration_error = _percent(len(out) / float(new_rate),
                              len(samples) / float(rate))
    formant_errors = [_percent(a, b) for a, b in zip(got_formants, base_formants)]
    ok = (f0_error <= 4.0 and duration_error <= TOLERANCE["duration_percent"]
          and max(formant_errors[1:]) <= TOLERANCE["formant_shift_percent"])
    return check("psola_pitch_preserves_timbre_and_length", ok,
                 "+%.0f st: F0 %.1f -> %.1f (want %.1f, %.1f%%); dur %.1f%%; "
                 "F2/F3 drift %.1f/%.1f%%; %d marks" % (
                     semitones, base["f0_mean_hz"], got["f0_mean_hz"],
                     base["f0_mean_hz"] * factor, f0_error, duration_error,
                     formant_errors[1], formant_errors[2],
                     report.get("pitch_marks", 0)))


def test_psola_time():
    """Time scaling must move duration and leave pitch and formants alone."""
    voiceprint, score, _targets, _bw = _sustained("AX", f0=116.0, seconds=0.6)
    rate, samples = synth.render_score(score, voiceprint)
    base = analyze.analyze_samples(samples, rate)
    base_formants = _formants(base)

    scale = 1.5
    new_rate, out, _report = transform.apply_spec(
        samples, rate, {"time": {"scale": scale}})
    got = analyze.analyze_samples(out, new_rate)
    got_formants = _formants(got)

    duration_error = _percent(len(out) / float(new_rate),
                              (len(samples) / float(rate)) / scale)
    f0_error = _percent(got["f0_mean_hz"], base["f0_mean_hz"])
    formant_errors = [_percent(a, b) for a, b in zip(got_formants, base_formants)]
    ok = (duration_error <= 2.0 and f0_error <= 4.0
          and max(formant_errors[1:]) <= TOLERANCE["formant_shift_percent"])
    return check("psola_time_preserves_pitch_and_timbre", ok,
                 "x%.2f speed: duration error %.2f%%; F0 drift %.1f%%; "
                 "F2/F3 drift %.1f/%.1f%%" % (
                     scale, duration_error, f0_error,
                     formant_errors[1], formant_errors[2]))


def test_formant_shift():
    """Formant shift must move F2/F3 by the requested ratio, pitch unchanged."""
    voiceprint, score, _targets, _bw = _sustained("AX", f0=116.0, seconds=0.6)
    rate, samples = synth.render_score(score, voiceprint)
    base = analyze.analyze_samples(samples, rate)
    base_formants = _formants(base)

    semitones = 3.0
    scale = 2.0 ** (semitones / 12.0)
    new_rate, out, _report = transform.apply_spec(
        samples, rate, {"timbre": {"formant_semitones": semitones}})
    got = analyze.analyze_samples(out, new_rate)
    got_formants = _formants(got)

    f0_error = _percent(got["f0_mean_hz"], base["f0_mean_hz"])
    shift_errors = [_percent(a, b * scale) for a, b in zip(got_formants, base_formants)]
    ok = (f0_error <= 4.0
          and max(shift_errors[1:]) <= TOLERANCE["formant_shift_percent"])
    return check("formant_shift_independent_of_pitch", ok,
                 "+%.0f st formants: F2 %.0f -> %.0f (want %.0f, %.1f%%); "
                 "F3 %.0f -> %.0f (%.1f%%); F0 drift %.1f%%" % (
                     semitones, base_formants[1], got_formants[1],
                     base_formants[1] * scale, shift_errors[1],
                     base_formants[2], got_formants[2], shift_errors[2],
                     f0_error))


def test_breathiness_calibration():
    """The breathiness knob must produce a monotonic, wide HNR sweep."""
    table = analyze.calibrate_breathiness(seconds=0.4)
    usable = [(value, db) for value, db in table
              if value <= analyze.CALIBRATION.get("usable_max", 0.6)]
    if len(usable) < 4:
        return check("breathiness_calibration_monotonic", False,
                     "only %d usable points" % len(usable))
    inversions = [(usable[i], usable[i + 1]) for i in range(len(usable) - 1)
                  if usable[i][1] < usable[i + 1][1] - 0.25]
    span = usable[0][1] - min(db for _value, db in usable)
    floor = usable[0][1]
    ok = not inversions and span >= 8.0 and floor >= TOLERANCE["hnr_floor_db"]
    detail = ("clean HNR %.1f dB, span %.1f dB over breathiness 0..%.2f"
              % (floor, span, usable[-1][0]))
    if inversions:
        detail += "; inversions %s" % inversions[:2]
    return check("breathiness_calibration_monotonic", ok, detail)


def test_noise_floor():
    """A clean voice must measure near the HNR floor, not as breathy."""
    voiceprint, score, _t, _b = _sustained("AX", f0=116.0, seconds=0.5,
                                           overrides={"source": {"breathiness": 0.0}})
    rate, samples = synth.render_score(score, voiceprint)
    measurements = analyze.analyze_samples(samples, rate)
    hnr = measurements["hnr_db"]
    jitter = measurements["jitter_percent"]
    ok = hnr >= TOLERANCE["hnr_floor_db"] and jitter <= 2.0
    return check("clean_voice_hnr_floor", ok,
                 "HNR %.1f dB (floor %.1f), jitter %.2f%%, %d cycles" % (
                     hnr, TOLERANCE["hnr_floor_db"], jitter,
                     measurements.get("pitch_sync_cycles", 0)))


ALL_TESTS = [
    ("levinson_durbin", test_levinson_durbin),
    ("wav_io", test_wav_roundtrip),
    ("voiceprint", test_voiceprint_roundtrip),
    ("schema", test_schema_completeness),
    ("lexicon", test_lexicon),
    ("corpus_fallback", test_corpus_fallback),
    ("formant_recovery", test_formant_recovery),
    ("sustained_vowel", test_sustained_vowel),
    ("noise_floor", test_noise_floor),
    ("prosody_rate", test_prosody_rate),
    ("round_trip", test_round_trip),
    ("psola_pitch", test_psola_pitch),
    ("psola_time", test_psola_time),
    ("formant_shift", test_formant_shift),
    ("breathiness_calibration", test_breathiness_calibration),
]


def run(verbose=True, only=None):
    """Run the suite. Returns a process exit code (0 = everything passed)."""
    del RESULTS[:]
    for name, function in ALL_TESTS:
        if only and name not in only:
            continue
        try:
            function()
        except Exception as error:  # a crash is a failure, not an abort
            import traceback
            check(name, False, "EXCEPTION %s: %s" % (
                type(error).__name__, error))
            if verbose and os.environ.get("VILLAGER_VOICE_DEBUG"):
                traceback.print_exc()

    if verbose:
        width = max([len(item["name"]) for item in RESULTS] or [10])
        for item in RESULTS:
            print("%-*s  %s  %s" % (width, item["name"],
                                    "PASS" if item["ok"] else "FAIL",
                                    item["detail"]))
        passed = sum(1 for item in RESULTS if item["ok"])
        print()
        print("%d/%d checks passed" % (passed, len(RESULTS)))
    failed = [item for item in RESULTS if not item["ok"]]
    return 1 if failed else 0


def report_json():
    return {"checks": list(RESULTS),
            "passed": sum(1 for item in RESULTS if item["ok"]),
            "total": len(RESULTS)}


if __name__ == "__main__":
    import sys
    raise SystemExit(run(only=set(sys.argv[1:]) or None))
