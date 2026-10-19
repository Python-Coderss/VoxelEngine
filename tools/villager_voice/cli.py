#!/usr/bin/env python3
"""Command line entry point for the villager voice engine.

Every command is JSON-driven: the engine reads a JSON description of what to
say (a *score*), how the voice sounds (a *voiceprint*), or how to re-voice an
existing recording (a *transform spec*), and writes WAV or a JSON report.

    python3 -m villager_voice render  --text "Hrmm!" --out out.wav
    python3 -m villager_voice render  --score score.json --out out.wav
    python3 -m villager_voice convert --in take.wav --spec spec.json --out out.wav
    python3 -m villager_voice analyze --in out.wav
    python3 -m villager_voice schema  voiceprint|transform|score
    python3 -m villager_voice selftest
    python3 -m villager_voice init    --out voiceprint.json

Nothing here needs numpy, scipy or a network connection: the whole package is
standard library only, which is what makes it portable to the Java voice
package in ``src/main/java/villager/voice``.
"""

import argparse
import json
import os
import sys

from . import analyze, corpus, lexicon, phones, prosody, synth, transform, wavio
from .voiceprint import (DEFAULTS, RANGES, ENUMS, VoicePrint, schema_document)

PROGRAM = "villager_voice"


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def _emit(payload, path=None, indent=2):
    """Write JSON to a file or stdout."""
    text = json.dumps(payload, indent=indent, sort_keys=False)
    if path:
        directory = os.path.dirname(os.path.abspath(path))
        if directory and not os.path.isdir(directory):
            os.makedirs(directory, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print("wrote %s" % path)
    else:
        print(text)


def _load_voiceprint(path):
    if not path:
        return VoicePrint.default()
    return VoicePrint.load(path)


def _ensure_parent(path):
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #

def command_render(arguments):
    """Synthesise speech from text or from a score JSON."""
    voiceprint = _load_voiceprint(arguments.voiceprint)
    if arguments.set:
        overrides = {}
        for item in arguments.set:
            if "=" not in item:
                raise SystemExit("--set expects dotted.path=value, got %r" % item)
            key, _, raw = item.partition("=")
            overrides.setdefault(key.strip(), _coerce(raw.strip()))
        voiceprint = voiceprint.merged(_unflatten(overrides))

    profile = None
    if arguments.corpus:
        profile = corpus.load_profile(arguments.corpus)

    if arguments.score:
        with open(arguments.score, "r", encoding="utf-8") as handle:
            score = json.load(handle)
    else:
        text = arguments.text
        if text is None:
            if not sys.stdin.isatty():
                text = sys.stdin.read().strip()
            if not text:
                raise SystemExit("render needs --text or --score")
        score = prosody.build_score(
            text, voiceprint, corpus=profile,
            seed=arguments.seed if arguments.seed is not None else None,
            delivery=_delivery(arguments))

    if arguments.dump_score:
        _emit(score, arguments.dump_score)
        if not arguments.out:
            return 0

    if not arguments.out:
        raise SystemExit("render needs --out (or --dump-score alone)")

    _ensure_parent(arguments.out)
    rate, samples = synth.render_score(score, voiceprint)
    wavio.write_wav(arguments.out, rate, samples)
    duration = len(samples) / rate
    syllables = sum(1 for phone in score.get("phones", [])
                    if phone.get("manner") in ("vowel", "diphthong"))
    print("rendered %s" % arguments.out)
    print("  %.2f s at %d Hz, peak %.2f, rms %.1f dB" % (
        duration, rate, wavio.peak(samples), wavio.rms_db(samples)))
    if syllables and duration > 0:
        print("  %.2f syllables/s (corpus median %.2f)" % (
            syllables / duration,
            (profile or corpus.load_profile()).syllables_per_sec))
    return 0


def _delivery(arguments):
    delivery = {}
    if arguments.emotion:
        delivery["emotion"] = arguments.emotion
    if arguments.rate is not None:
        delivery["rate"] = arguments.rate
    if arguments.pitch is not None:
        delivery["pitch_semitones"] = arguments.pitch
    if arguments.volume is not None:
        delivery["volume"] = arguments.volume
    if arguments.tone is not None:
        delivery["tone"] = arguments.tone
    if arguments.sarcasm is not None:
        delivery["sarcasm"] = arguments.sarcasm
    if arguments.question:
        delivery["question"] = True
    return delivery or None


def _coerce(raw):
    """Parse a --set value into bool/int/float/JSON/string."""
    lowered = raw.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    if lowered in ("null", "none"):
        return None
    try:
        return json.loads(raw)
    except ValueError:
        pass
    try:
        return int(raw)
    except ValueError:
        pass
    try:
        return float(raw)
    except ValueError:
        return raw


def _unflatten(flat):
    """Turn ``{"a.b": 1}`` into ``{"a": {"b": 1}}``."""
    out = {}
    for key, value in flat.items():
        parts = key.split(".")
        cursor = out
        for part in parts[:-1]:
            cursor = cursor.setdefault(part, {})
            if not isinstance(cursor, dict):
                raise SystemExit("conflicting --set paths at %r" % key)
        cursor[parts[-1]] = value
    return out


# --------------------------------------------------------------------------- #
# convert
# --------------------------------------------------------------------------- #

def command_convert(arguments):
    """Re-voice an existing WAV from a transform spec."""
    spec = {}
    if arguments.spec:
        with open(arguments.spec, "r", encoding="utf-8") as handle:
            spec = json.load(handle)
    if arguments.set:
        overrides = {}
        for item in arguments.set:
            key, _, raw = item.partition("=")
            overrides.setdefault(key.strip(), _coerce(raw.strip()))
        spec = transform.merge_spec(spec, _unflatten(overrides))
    if arguments.pitch is not None:
        spec = transform.merge_spec(spec, {"pitch": {"semitones": arguments.pitch}})
    if arguments.speed is not None:
        spec = transform.merge_spec(spec, {"time": {"scale": arguments.speed}})
    if arguments.formant is not None:
        spec = transform.merge_spec(spec, {"timbre": {"formant_semitones": arguments.formant}})

    voiceprint = None
    if arguments.voiceprint:
        voiceprint = _load_voiceprint(arguments.voiceprint)

    _ensure_parent(arguments.out)
    report = transform.convert_file(arguments.in_file, arguments.out, spec,
                                    voiceprint=voiceprint,
                                    rng_seed=arguments.seed)
    print("converted %s -> %s" % (arguments.in_file, arguments.out))
    print("  %.2f s -> %.2f s, pitch x%.3f, time x%.3f, formants x%.3f" % (
        report.get("input_duration_sec", 0.0), report.get("duration_sec", 0.0),
        report.get("pitch_factor", 1.0), report.get("time_factor", 1.0),
        report.get("formant_scale", 1.0)))
    if arguments.report:
        _emit(report, arguments.report)
    return 0


# --------------------------------------------------------------------------- #
# analyze / calibrate
# --------------------------------------------------------------------------- #

def command_analyze(arguments):
    """Measure a WAV file or a directory of them."""
    path = arguments.in_file
    per_file = None
    if os.path.isdir(path):
        result, per_file = analyze.analyze_directory(path, limit=arguments.limit)
    else:
        result = analyze.analyze_file(path)
    if arguments.json:
        payload = result if per_file is None else {
            "aggregate": result, "files": per_file}
        _emit(payload, None if arguments.json == "-" else arguments.json)
        return 0
    if per_file is not None:
        print(analyze.format_aggregate(result, per_file))
    else:
        print(analyze.format_measurements(result))
    if arguments.voiceprint_out:
        voiceprint = analyze.voiceprint_from_measurements(result)
        _ensure_parent(arguments.voiceprint_out)
        voiceprint.save(arguments.voiceprint_out)
        print("wrote %s" % arguments.voiceprint_out)
    return 0


def command_calibrate(arguments):
    """Re-measure the knob-to-acoustic calibration tables."""
    if arguments.breathiness or not arguments.all:
        table = analyze.calibrate_breathiness(verbose=True)
        print()
        print("paste into analyze.CALIBRATION['breathiness_hnr']:")
        print(json.dumps([[round(value, 2), round(db, 2)] for value, db in table]))
    if arguments.round_trip or arguments.all:
        print()
        print(analyze.format_report(analyze.round_trip_report()))
    return 0


def command_selftest(arguments):
    from . import selftest
    return selftest.run(verbose=not arguments.quiet)


# --------------------------------------------------------------------------- #
# schema / init / describe / phones
# --------------------------------------------------------------------------- #

def command_schema(arguments):
    if arguments.what == "voiceprint":
        _emit(schema_document(), arguments.out)
    elif arguments.what == "transform":
        _emit(transform.spec_schema(), arguments.out)
    elif arguments.what == "score":
        _emit(prosody.score_schema(), arguments.out)
    else:
        _emit({"voiceprint": schema_document(),
               "transform": transform.spec_schema(),
               "score": prosody.score_schema()}, arguments.out)
    return 0


def command_init(arguments):
    """Write a starter voiceprint (optionally seeded from measurements)."""
    voiceprint = VoicePrint.default()
    if arguments.from_audio and os.path.exists(arguments.from_audio):
        measured = (analyze.analyze_directory(arguments.from_audio)
                    if os.path.isdir(arguments.from_audio)
                    else analyze.analyze_file(arguments.from_audio))
        voiceprint = analyze.voiceprint_from_measurements(measured)
    if arguments.name:
        voiceprint = voiceprint.merged({"provenance": {"name": arguments.name}})
    _ensure_parent(arguments.out)
    voiceprint.save(arguments.out)
    print(voiceprint.summary())
    return 0


def command_describe(arguments):
    """Print the measured corpus profile."""
    profile = corpus.load_profile(arguments.corpus)
    print(profile.describe())
    return 0


def command_phones(arguments):
    """Print the phone inventory."""
    print(phones.inventory_report())
    return 0


def command_pronounce(arguments):
    """Show the grapheme-to-phoneme result for a line of text."""
    text = arguments.text or sys.stdin.read().strip()
    tokens = lexicon.text_to_words(text)
    words = [token for kind, token in tokens if kind == "word"]
    inside, total = lexicon.coverage_report(words)
    print("dictionary coverage: %d of %d words (%.0f%%)" % (
        inside, total, 100.0 * inside / max(1, total)))
    print()
    for word in words:
        # pronounce() yields (symbol, stress) pairs; digits are ARPAbet stress.
        rendered = " ".join("%s%s" % (symbol, stress if stress else "")
                            for symbol, stress in lexicon.pronounce(word))
        print("%-16s %s" % (word, rendered))
    return 0


# --------------------------------------------------------------------------- #
# argument parser
# --------------------------------------------------------------------------- #

def build_parser():
    parser = argparse.ArgumentParser(
        prog=PROGRAM,
        description="Analysis-synthesis voice engine for the Villager News corpus.")
    parser.add_argument("--version", action="version",
                        version="%s %s" % (PROGRAM, prosody.SCORE_VERSION))
    sub = parser.add_subparsers(dest="command")

    render = sub.add_parser("render", help="synthesise speech from text or a score")
    render.add_argument("--text", help="line to speak")
    render.add_argument("--score", help="score JSON to render instead of text")
    render.add_argument("--voiceprint", help="voiceprint JSON (default: built-in seed)")
    render.add_argument("--corpus", help="clips_index.json for measured timing")
    render.add_argument("--out", help="output WAV path")
    render.add_argument("--dump-score", help="also write the generated score JSON")
    render.add_argument("--seed", type=int, help="random seed override")
    render.add_argument("--set", action="append",
                        help="override a voiceprint field: --set source.f0_mean_hz=128")
    render.add_argument("--emotion",
                        choices=["neutral", "happy", "sad", "angry", "scared"],
                        help="matches SpeechOptions.emotion in the Java engine")
    render.add_argument("--rate", type=float, help="speed factor (1.0 = normal)")
    render.add_argument("--pitch", type=float, help="pitch offset in semitones")
    render.add_argument("--volume", type=float, help="volume 0..2")
    render.add_argument("--tone", type=float, help="tone -1..1 (dark..bright)")
    render.add_argument("--sarcasm", type=float, help="sarcasm 0..1")
    render.add_argument("--question", action="store_true")
    render.set_defaults(handler=command_render)

    convert = sub.add_parser("convert", help="re-voice an existing WAV from a spec")
    convert.add_argument("--in", dest="in_file", required=True, help="input WAV")
    convert.add_argument("--out", required=True, help="output WAV")
    convert.add_argument("--spec", help="transform spec JSON")
    convert.add_argument("--voiceprint", help="adopt a target voice's timbre")
    convert.add_argument("--pitch", type=float, help="semitones (spec shortcut)")
    convert.add_argument("--speed", type=float, help="time scale (spec shortcut)")
    convert.add_argument("--formant", type=float, help="formant semitones (shortcut)")
    convert.add_argument("--set", action="append", help="spec.path=value overrides")
    convert.add_argument("--seed", type=int)
    convert.add_argument("--report", help="write the transform report JSON here")
    convert.set_defaults(handler=command_convert)

    analyse = sub.add_parser("analyze", help="measure a WAV file or directory")
    analyse.add_argument("--in", dest="in_file", required=True)
    analyse.add_argument("--json", nargs="?", const="-", help="emit JSON")
    analyse.add_argument("--limit", type=int, default=64, help="max files for a directory")
    analyse.add_argument("--voiceprint-out", help="write a voiceprint fitted to the audio")
    analyse.set_defaults(handler=command_analyze)

    calibrate = sub.add_parser("calibrate", help="re-measure calibration tables")
    calibrate.add_argument("--breathiness", action="store_true")
    calibrate.add_argument("--round-trip", action="store_true")
    calibrate.add_argument("--all", action="store_true")
    calibrate.set_defaults(handler=command_calibrate)

    selftest = sub.add_parser("selftest", help="run the engine's own verification")
    selftest.add_argument("--quiet", action="store_true")
    selftest.set_defaults(handler=command_selftest)

    schema = sub.add_parser("schema", help="print a JSON schema")
    schema.add_argument("what", nargs="?", default="all",
                        choices=["all", "voiceprint", "transform", "score"])
    schema.add_argument("--out")
    schema.set_defaults(handler=command_schema)

    init = sub.add_parser("init", help="write a starter voiceprint JSON")
    init.add_argument("--out", required=True)
    init.add_argument("--name", help="provenance name")
    init.add_argument("--from-audio", help="seed it from a WAV file or directory")
    init.set_defaults(handler=command_init)

    describe = sub.add_parser("describe", help="print the measured corpus profile")
    describe.add_argument("--corpus")
    describe.set_defaults(handler=command_describe)

    phone = sub.add_parser("phones", help="print the phone inventory")
    phone.set_defaults(handler=command_phones)

    pronounce = sub.add_parser("pronounce", help="show grapheme-to-phoneme output")
    pronounce.add_argument("text", nargs="?")
    pronounce.set_defaults(handler=command_pronounce)

    return parser


def main(argv=None):
    parser = build_parser()
    arguments = parser.parse_args(argv)
    if not getattr(arguments, "handler", None):
        parser.print_help()
        return 1
    try:
        return arguments.handler(arguments) or 0
    except SystemExit:
        raise
    except Exception as error:  # a clean message beats a traceback for CLI users
        if os.environ.get("VILLAGER_VOICE_DEBUG"):
            raise
        print("error: %s" % error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
