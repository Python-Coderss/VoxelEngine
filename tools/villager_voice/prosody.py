"""Prosody: turn text into a timed, editable performance score.

The output is a *score* -- phones with start times and durations, pitch and
loudness contours, pauses and phrase structure -- in exactly the same JSON
shape the voice changer accepts as input. That is the point of the split:
:mod:`prosody` generates a score automatically, but a score can also be written
or edited by hand and handed straight to :mod:`synth`. Nothing in the
synthesiser requires this module.

Timing is anchored to the corpus: ``prosody.rate_syllables_per_sec`` is the
value measured from ``clips_index.json`` (3.31 syl/s across 2252 clips), so a
generated line lasts about as long as the recorded villagers would take.
"""

import math
import random

from . import lexicon
from .phones import (PHONES, intrinsic_duration, is_syllabic, is_voiced, manner,
                     formant_targets, DIPHTHONG, SILENCE, STOP, FRICATIVE,
                     AFFRICATE, NASAL, APPROXIMANT, GLIDE, VOCALISATION)

SCORE_FORMAT = "villager-voice-score"
SCORE_VERSION = 1

BOUNDARY = "boundary"
COMMA = "comma"
DASH = "dash"


def semitone_offset(f0_hz, mean_hz):
    if f0_hz <= 0 or mean_hz <= 0:
        return 0.0
    return 12.0 * math.log(f0_hz / mean_hz) / math.log(2.0)


def semitone_to_hz(semitones, mean_hz):
    return mean_hz * (2.0 ** (semitones / 12.0))


class ScoreBuilder(object):
    """Assembles a performance score from words, phones and prosodic marks."""

    def __init__(self, voiceprint, corpus=None, seed=None):
        self.voiceprint = voiceprint
        self.corpus = corpus
        seed = voiceprint.get("random_seed") if seed is None else seed
        self.rng = random.Random(int(seed) if seed is not None else 20261004)
        self.phones = []
        self.pauses = []
        self.words = []
        self.f0_points = []
        self.amp_points = []
        self.contours = {"f0": [], "amp": [], "formant_scale": [],
                         "breathiness": []}
        self.events = []
        self.phrases = []
        self.time = 0.0
        self.text = ""

    # -- timing -------------------------------------------------------------
    def add_pause(self, seconds):
        if seconds <= 0:
            return
        self.pauses.append({"at": round(self.time, 4), "dur": round(seconds, 4)})
        self.time += seconds

    def add_phone(self, symbol, stress=0, word_index=-1, syllable_index=-1,
                  duration=None, amp=None, f0_semitones=None, formants=None,
                  bandwidths=None, phone_override=None):
        spec = PHONES.get(symbol) or PHONES["IX"]
        if duration is None:
            duration = intrinsic_duration(symbol)
        if amp is None:
            amp = spec.get("amp", 1.0)
        targets, bws = formant_targets(symbol)
        if formants is not None:
            targets = list(formants)
        if bandwidths is not None:
            bws = list(bandwidths)
        slot = {
            "sym": symbol,
            "start": round(self.time, 5),
            "dur": round(max(0.005, duration), 5),
            "stress": int(stress),
            "amp": round(float(amp), 5),
            "word": word_index,
            "syll": syllable_index,
            "manner": spec.get("manner"),
            "voiced": bool(is_voiced(symbol)),
            "f": [round(float(x), 1) for x in targets],
            "bw": [round(float(x), 1) for x in bws],
        }
        if f0_semitones is not None:
            slot["f0_semitones"] = round(float(f0_semitones), 3)
        if phone_override:
            slot["override"] = dict(phone_override)
        self.phones.append(slot)
        self.time += slot["dur"]
        return slot

    # -- export -------------------------------------------------------------
    def score(self, text=""):
        return {
            "format": SCORE_FORMAT,
            "version": SCORE_VERSION,
            "text": text or self.text,
            "sample_rate": int(self.voiceprint.get("output.sample_rate", 24000)),
            "duration": round(self.time, 4),
            "phones": self.phones,
            "pauses": self.pauses,
            "words": self.words,
            "phrases": self.phrases,
            "contours": self.contours,
            "events": self.events,
            "random_seed": int(self.voiceprint.get("random_seed", 20261004)),
        }


def _estimate_pause_budget(effective, words, phrase_marks):
    """Total seconds the pauses will occupy, for rate compensation."""
    pros = effective.section("prosody")
    short = float(pros.get("pause_short_ms", 130.0)) / 1000.0
    comma = float(pros.get("pause_comma_ms", 250.0)) / 1000.0
    sentence = float(pros.get("pause_sentence_ms", 470.0)) / 1000.0
    commas = sum(1 for _pos, kind, _tok in phrase_marks if kind == COMMA)
    ends = sum(1 for _pos, kind, _tok in phrase_marks if kind == BOUNDARY)
    gaps = max(0, len(words) - 1) * short * 0.22
    return commas * comma + ends * sentence + gaps + short * 1.1


def _syllable_groups(phone_list):
    """Group ``(symbol, stress)`` pairs into syllables around each nucleus."""
    groups = []
    current = []
    for symbol, stress in phone_list:
        if is_syllabic(symbol) and current and any(is_syllabic(s) for s, _ in current):
            groups.append(current)
            current = []
        current.append((symbol, stress))
    if current:
        groups.append(current)
    return groups


def build_score(text, voiceprint, corpus=None, seed=None, delivery=None):
    """Generate a performance score for one line of text.

    ``delivery`` optionally carries ``emotion``/``tone``/``speed``/``question``
    overrides, matching the Java ``SpeechOptions`` fields so the game bridge can
    drive it without knowing the new parameter names.
    """
    effective = voiceprint.effective()
    if delivery:
        overrides = {}
        if "emotion" in delivery:
            overrides.setdefault("character", {})["emotion"] = delivery["emotion"]
        if "tone" in delivery:
            overrides.setdefault("character", {})["tone"] = delivery["tone"]
        if "sarcasm" in delivery:
            overrides.setdefault("character", {})["sarcasm"] = delivery["sarcasm"]
        if "speed" in delivery:
            overrides.setdefault("articulation", {})["duration_scale"] = \
                1.0 / max(0.2, float(delivery["speed"]))
            overrides.setdefault("prosody", {})["rate_syllables_per_sec"] = \
                effective.get("prosody.rate_syllables_per_sec") * float(delivery["speed"])
        if overrides:
            effective = effective.merged(overrides).effective()

    builder = ScoreBuilder(effective, corpus, seed)
    builder.text = text or ""
    builder.voiceprint = effective

    question = bool((delivery or {}).get("question"))
    if not question:
        question = lexicon and bool((text or "").strip().endswith("?"))

    tokens = lexicon.text_to_words(text or "")
    words = [token for token in tokens if token[0] == "word"]

    art = effective.section("articulation")
    pros = effective.section("prosody")
    duration_scale = float(art.get("duration_scale", 1.0))
    vowel_scale = float(art.get("vowel_duration_scale", 1.0))
    consonant_scale = float(art.get("consonant_duration_scale", 1.0))
    rate = float(pros.get("rate_syllables_per_sec", 3.31)) or 3.31

    # --- pass 1: collect the phone stream with intrinsic durations ---------
    stream = []  # dicts: sym, stress, word, syll, base_dur, is_nucleus
    word_spans = []  # (word_text, first_stream_index, last_stream_index)
    syllable_count = 0
    for word_index, (_kind, word_text) in enumerate(words):
        phones = lexicon.pronounce(word_text)
        if not phones:
            continue
        first = len(stream)
        for syllable_index, group in enumerate(_syllable_groups(phones)):
            nucleus_seen = False
            for symbol, stress in group:
                spec = PHONES.get(symbol) or {}
                phone_manner = spec.get("manner", SILENCE)
                base = intrinsic_duration(symbol)
                if is_syllabic(symbol):
                    base *= vowel_scale
                    if not nucleus_seen:
                        syllable_count += 1
                        nucleus_seen = True
                else:
                    base *= consonant_scale
                # Stress lengthening.
                if stress == 1:
                    base *= 1.18
                elif stress == 2:
                    base *= 1.06
                elif is_syllabic(symbol):
                    base *= 1.0 - 0.12 * float(art.get("vowel_reduction", 0.5))
                stream.append({
                    "sym": symbol, "stress": stress, "word": word_index,
                    "syll": syllable_index, "base": base, "manner": phone_manner,
                    "nucleus": bool(nucleus_seen and is_syllabic(symbol)),
                })
        word_spans.append((word_text, first, len(stream) - 1))

    if not stream:
        # Nothing pronounceable: fall back to the villager murmur.
        builder.add_phone("HMM", 1, 0, 0, 0.45)
        builder.words.append({"text": text or "", "start": 0.0, "end": 0.45})
        score = builder.score(text or "")
        score["voiceprint"] = effective.data
        return score

    # --- pass 2: phrase structure -----------------------------------------
    # Walk the token stream so punctuation lands at the right place in time.
    phrase_marks = []  # (index of phrase-final phone, kind, token)
    phrase_index = 0
    stream_word_cursor = 0
    for kind, token in tokens:
        if kind == "word":
            if stream_word_cursor < len(word_spans):
                stream_word_cursor += 1
        elif kind in (BOUNDARY, COMMA, DASH):
            if 0 < stream_word_cursor <= len(word_spans):
                last_index = word_spans[stream_word_cursor - 1][2]
                phrase_marks.append((last_index, kind, token))
    if not phrase_marks or phrase_marks[-1][0] != len(stream) - 1:
        # The last phone always closes a phrase, even without punctuation.
        phrase_marks.append((len(stream) - 1, BOUNDARY, "."))

    # --- pass 3: scale to the measured speaking rate -----------------------
    # The corpus rate (syllables per clip second) already includes the pauses
    # inside a clip, so the pause budget comes out of the total before phones
    # are scaled -- otherwise generated lines run slower than recorded ones
    # even though every phone sits at its intrinsic length.
    pause_budget = _estimate_pause_budget(effective, words, phrase_marks)
    total_base = sum(item["base"] for item in stream) or 1.0
    gross = max(0.25, syllable_count / rate)
    target_speech = max(0.35 * gross, gross - pause_budget)

    # Undershoot: at fast rates, consonants compress more than vowels.
    undershoot = float(art.get("undershoot", 0.0))
    scale = target_speech / total_base if total_base > 0 else 1.0
    if scale < 1.0 and undershoot > 0.0:
        for item in stream:
            if not is_syllabic(item["sym"]):
                item["base"] *= 1.0 - undershoot * (1.0 - scale)
        total_base = sum(item["base"] for item in stream) or 1.0
    scale = target_speech / total_base if total_base > 0 else 1.0

    # --- pass 4: emit phones, pauses and word timings ----------------------
    word_start_times = {}
    pre_boundary = float(pros.get("pre_boundary_lengthening", 1.3))
    pause_short = float(pros.get("pause_short_ms", 130.0)) / 1000.0
    pause_comma = float(pros.get("pause_comma_ms", 250.0)) / 1000.0
    pause_sentence = float(pros.get("pause_sentence_ms", 470.0)) / 1000.0
    variability = float(pros.get("pause_variability", 0.18))

    # Which stream indices are the last syllable of a phrase?
    phrase_last_indices = set()
    for position, _kind, _token in phrase_marks:
        phrase_last_indices.add(position)
    phrase_last_indices.add(len(stream) - 1)

    def is_phrase_final(index):
        """True for phones in the final syllable of a phrase."""
        item = stream[index]
        for position in phrase_last_indices:
            if position < 0 or position >= len(stream):
                continue
            target = stream[position]
            if target["word"] == item["word"] and target["syll"] == item["syll"]:
                return True
        return False

    builder.add_pause(pause_short * 0.5)
    for index, item in enumerate(stream):
        symbol = item["sym"]
        duration = item["base"] * scale * duration_scale
        if is_phrase_final(index) and is_syllabic(symbol):
            duration *= pre_boundary

        # Small between-word gap, jittered like real speech.
        if index > 0 and stream[index - 1]["word"] != item["word"]:
            gap = pause_short * 0.22 * (1.0 + variability * (builder.rng.random() * 2 - 1))
            if manner(symbol) in (STOP, AFFRICATE):
                gap *= 0.4
            builder.add_pause(gap)

        if item["word"] not in word_start_times:
            word_start_times[item["word"]] = builder.time

        amp = float((PHONES.get(symbol) or {}).get("amp", 1.0))
        slot = builder.add_phone(symbol, item["stress"], item["word"],
                                 item["syll"], duration, amp)
        slot["phrase"] = phrase_index
        for position, kind, token in phrase_marks:
            if position == index:
                if kind == COMMA:
                    builder.add_pause(pause_comma *
                                      (1.0 + variability * (builder.rng.random() * 2 - 1)))
                elif kind == BOUNDARY:
                    builder.add_pause(pause_sentence *
                                      (1.0 + variability * (builder.rng.random() * 2 - 1)))
                phrase_index += 1
                builder.phrases.append({
                    "index": phrase_index - 1, "end": round(builder.time, 4),
                    "punctuation": token,
                    "kind": "question" if token == "?" else
                            ("exclamation" if token == "!" else "declarative"),
                })

    # Word timings for captions / JSON inspection.
    for word_index, (word_text, first, last) in enumerate(word_spans):
        start = word_start_times.get(word_index, 0.0)
        end = 0.0
        for slot in builder.phones:
            if slot["word"] == word_index:
                end = max(end, slot["start"] + slot["dur"])
        builder.words.append({"text": word_text, "start": round(start, 4),
                              "end": round(end, 4)})
    if not builder.phrases:
        builder.phrases.append({"index": 0, "end": round(builder.time, 4),
                                "punctuation": ".", "kind": "declarative"})
    builder.add_pause(pause_short * 0.6)

    _build_f0_contour(builder, effective, question, phrase_marks)
    _build_amp_contour(builder, effective)
    _apply_phone_overrides(builder, effective)

    score = builder.score(text or "")
    score["voiceprint"] = effective.data
    return score


def _phrase_ranges(builder):
    """Yield (start_index, end_index, kind) for each phrase."""
    ranges = []
    start = 0
    phrase_lookup = {entry["index"]: entry for entry in builder.phrases}
    last_index = -1
    for slot in builder.phones:
        if slot.get("phrase", 0) != last_index and last_index != -1:
            ranges.append((start, previous, phrase_lookup.get(last_index, {}).get(
                "kind", "declarative")))
            start = previous + 1
        last_index = slot.get("phrase", 0)
        previous = builder.phones.index(slot)
    ranges.append((start, len(builder.phones) - 1,
                   phrase_lookup.get(last_index, {}).get("kind", "declarative")))
    return ranges


def _build_f0_contour(builder, effective, question, phrase_marks):
    """Pitch contour: declination + stress accents + boundary movements."""
    pros = effective.section("prosody")
    declination = float(pros.get("declination_semitones_per_sec", -0.85))
    accent = float(pros.get("accent_rise_semitones", 3.0))
    stress_rise = float(pros.get("stress_rise_semitones", 2.1))
    reset = float(pros.get("f0_reset_semitones", 1.6))
    final_fall = float(pros.get("phrase_final_fall_semitones", -3.4))
    question_rise = float(pros.get("question_rise_semitones", 4.6))
    exclamation_rise = float(pros.get("exclamation_rise_semitones", 1.8))

    points = []
    phrase_marks_by_index = {}
    for position, kind, token in phrase_marks:
        phrase_marks_by_index[position] = token

    phrase_number = 0
    phrase_start_time = 0.0
    total = len(builder.phones)
    for index, slot in enumerate(builder.phones):
        start = slot["start"]
        end = start + slot["dur"]
        if index == 0:
            points.append((start, reset * 0.5))
        if not is_syllabic(slot["sym"]):
            continue

        # Declination from the start of the current phrase.
        base = declination * max(0.0, start - phrase_start_time)
        value = base
        if slot["stress"] == 1:
            value += accent
        elif slot["stress"] == 2:
            value += stress_rise

        token = phrase_marks_by_index.get(index)
        is_final = token is not None or index == total - 1
        if is_final:
            if token == "?" or (question and index >= total - 3):
                # Rise across the last syllable, peak at the very end.
                points.append((start, base + 0.4 * abs(question_rise)))
                points.append((end, base + question_rise))
            elif token == "!":
                points.append((start, value + exclamation_rise))
                points.append((end, base + final_fall * 0.5))
            else:
                points.append((start, value))
                points.append((end, base + final_fall))
            phrase_number += 1
            phrase_start_time = end
            points.append((end + 1e-4, reset))
            continue

        # Accented syllables peak early, then decay back to the baseline.
        if slot["stress"]:
            points.append((start, base + value * 0.55))
            points.append((start + slot["dur"] * 0.32, value))
            points.append((end, base + value * 0.35))
        else:
            points.append((start, base))
            points.append((end, base))

    if not points:
        points = [(0.0, 0.0), (builder.time, 0.0)]
    points.sort(key=lambda pair: pair[0])
    builder.contours["f0"] = [[round(t, 4), round(v, 3)] for t, v in points]


def _build_amp_contour(builder, effective):
    """Loudness contour: phrase arc plus stress dynamics."""
    pros = effective.section("prosody")
    loudness_range = float(pros.get("loudness_range_db", 9.0))
    stress_db = float(pros.get("stress_loudness_db", 2.4))
    softening = float(pros.get("final_softening_db", -2.0))

    total = max(1e-6, builder.time)
    points = []
    for slot in builder.phones:
        start = slot["start"]
        end = start + slot["dur"]
        value = 0.0
        if slot["stress"] == 1:
            value += stress_db
        elif slot["stress"] == 2:
            value += stress_db * 0.5
        # Gentle phrase arc: swell to a third of the way, ease off at the end.
        progress = (start + end) * 0.5 / total
        arc = math.sin(math.pi * min(1.0, max(0.0, progress))) - 0.6
        value += arc * loudness_range * 0.35
        if progress > 0.88:
            value += softening * (progress - 0.88) / 0.12
        points.append([round(start, 4), round(value * (loudness_range / 9.0), 3)])
        points.append([round(end, 4), round(value * (loudness_range / 9.0), 3)])
    if not points:
        points = [[0.0, 0.0], [round(total, 4), 0.0]]
    builder.contours["amp"] = points


def _apply_phone_overrides(builder, effective):
    """Fold ``phone_overrides`` (and measured voiceprint trims) into targets."""
    overrides = effective.get("phone_overrides") or {}
    if not overrides:
        return
    tract = effective.section("tract")
    scales = tract.get("formant_scale") or {}
    length = float(tract.get("length_scale", 1.0))
    for slot in builder.phones:
        rule = overrides.get(slot["sym"])
        if not rule:
            continue
        for i, key in enumerate(("f1_hz", "f2_hz", "f3_hz", "f4_hz", "f5_hz")):
            if key in rule:
                slot["f"][i] = float(rule[key]) * length * float(
                    scales.get("f%d" % (i + 1), 1.0))
        for i, key in enumerate(("b1_hz", "b2_hz", "b3_hz", "b4_hz", "b5_hz")):
            if key in rule:
                slot["bw"][i] = float(rule[key])
        if "dur_scale" in rule:
            slot["dur"] = round(slot["dur"] * float(rule["dur_scale"]), 5)
        if "amp_db" in rule:
            slot["amp"] = round(slot["amp"] *
                                (10.0 ** (float(rule["amp_db"]) / 20.0)), 5)
        if "voiced" in rule:
            slot["voiced"] = bool(rule["voiced"])


def apply_voiceprint_scaling(score, effective):
    """Scale stored formant targets by the voiceprint's tract parameters.

    Scores keep phone targets in *reference* Hz so one score can be spoken by
    any voiceprint; the synthesiser applies tract length and per-formant
    scaling at render time. This helper does the same job for callers that want
    the numbers baked in (inspectors, the Java port's tests).
    """
    tract = effective.section("tract")
    scales = tract.get("formant_scale") or {}
    length = float(tract.get("length_scale", 1.0))
    for slot in score.get("phones", []):
        for i in range(min(5, len(slot.get("f", [])))):
            factor = length * float(scales.get("f%d" % (i + 1), 1.0))
            slot["f"][i] = round(slot["f"][i] * factor, 1)
        slot["bw"] = [round(b * float(tract.get("bandwidth_scale", 1.0))
                            + float(tract.get("extra_bandwidth_hz", 0.0)), 1)
                      for b in slot.get("bw", [])]
    return score


def score_schema():
    """Describe the score JSON: the engine's performance-input contract.

    This is the file a voice changer is driven by. It can be produced by
    ``build_score`` from plain text, or written by hand and fed straight to
    ``synth.render_score`` -- the synthesiser cannot tell the difference.
    """
    return {
        "format": SCORE_FORMAT,
        "version": SCORE_VERSION,
        "description": "A timed performance score: what to say, when, and how.",
        "top_level": {
            "text": "the source line (informational; phones drive the audio)",
            "sample_rate": "int, output rate in Hz",
            "duration": "float, total seconds",
            "phones": "list of phone slots, in time order",
            "pauses": "list of {at, dur} silence insertions in seconds",
            "words": "list of {text, start, end} for alignment/subtitles",
            "phrases": "list of {index, end, punctuation, kind}",
            "contours": "time-varying control tracks (see below)",
            "events": "list of {at, kind, dur} non-speech overlays",
            "random_seed": "int, makes a score reproducible",
            "voiceprint": "optional embedded voiceprint overrides",
        },
        "phone_slot": {
            "sym": "ARPAbet-ASCII symbol, e.g. 'AX', 'SH', 'EY1'",
            "start": "float, onset in seconds from the start of the line",
            "dur": "float, duration in seconds",
            "stress": "int 0/1/2 (2 = primary)",
            "amp": "float, per-phone amplitude scale",
            "word": "int, index into 'words'",
            "syll": "int, syllable index within the word",
            "manner": "vowel|diphthong|fricative|stop|nasal|glide|"
                      "approximant|affricate|silence|vocalisation",
            "voiced": "bool",
            "f": "[F1..F5] target formant frequencies in Hz",
            "bw": "[B1..B5] formant bandwidths in Hz",
            "phrase": "int, index into 'phrases'",
            "f0_semitones": "optional fixed pitch offset for this phone",
            "override": "optional dict of voiceprint overrides active here",
        },
        "contours": {
            "f0": "[[time_sec, semitones_relative_to_f0_mean], ...]",
            "amp": "[[time_sec, dB], ...]",
            "formant_scale": "[[time_sec, linear_factor], ...]",
            "breathiness": "[[time_sec, 0..1], ...]",
        },
        "notes": [
            "All times are seconds from the start of the line.",
            "Contour lists are linearly interpolated between breakpoints and "
            "must be sorted by time.",
            "A hand-written score needs at minimum: phones (with sym, start, "
            "dur, f, bw, voiced), contours.f0, contours.amp and duration.",
        ],
    }


def describe_score(score):
    """One-line human summary of a score, useful in CLI output."""
    phones = score.get("phones", [])
    syllables = sum(1 for slot in phones if is_syllabic(slot["sym"]))
    voiced = sum(1 for slot in phones if slot.get("voiced"))
    duration = score.get("duration") or 0.0
    rate = syllables / duration if duration > 0 else 0.0
    contour = score.get("contours", {}).get("f0") or []
    span = 0.0
    if contour:
        values = [point[1] for point in contour]
        span = max(values) - min(values)
    return ("phones=%d syllables=%d voiced=%d duration=%.2fs rate=%.2f syl/s "
            "f0 span=%.1f st pauses=%d"
            % (len(phones), syllables, voiced, duration, rate, span,
               len(score.get("pauses", []))))


if __name__ == "__main__":
    import json

    from .voiceprint import VoicePrint
    from .corpus import load_profile

    profile = load_profile()
    print(profile.describe())
    print()
    for line in ("Whoa, that's so sparkly!",
                 "I am haggling you. Mmm, pork.",
                 "Can I get your email for a receipt?"):
        score = build_score(line, VoicePrint.default(), profile)
        print(line)
        print("  ", describe_score(score))
        print("   phones:", " ".join(
            "%s%s" % (slot["sym"], slot["stress"] if slot["stress"] else "")
            for slot in score["phones"]))
        print("   words:", json.dumps(score["words"]))
