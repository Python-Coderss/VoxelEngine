"""Mine the villager clip index into a measured prosody/vocabulary profile.

``src/main/resources/voice/clips_index.json`` carries 2252 clips (Villager News
add-on + TEAVSRP corpus) with transcripts, durations and timed subtitle cues.
The *audio* is external and gitignored, but the timing data is not: from it we
can measure how fast the villagers talk, how they pause, which words they use
and how often they ask questions. Those measurements seed the prosody model so
synthesised lines move like recorded ones instead of like a metronome.

Nothing here guesses acoustics -- only timing and vocabulary. Acoustics come
from :mod:`analyze` when recordings are available.
"""

import json
import math
import os
import re
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_INDEX = os.path.join(ROOT, "src", "main", "resources", "voice", "clips_index.json")

WORD_RE = re.compile(r"[A-Za-z']+")
VOWEL_RUN_RE = re.compile(r"[aeiouy]+")

# Words whose naive vowel-run count mis-syllabifies; small and targeted.
SILENT_E = re.compile(r"(?:[^aeiouy])e$")


def syllable_count(word):
    """Approximate English syllable count (vowel-run heuristic + silent-e)."""
    word = word.lower().strip("'")
    if not word:
        return 0
    runs = VOWEL_RUN_RE.findall(word)
    count = len(runs)
    if count == 0:
        # Consonant syllables ("hmm", "shh") still take a beat.
        return 1
    if count > 1 and SILENT_E.search(word) and not word.endswith(("le", "les")):
        count -= 1
    return max(1, count)


def percentile(sorted_values, fraction):
    if not sorted_values:
        return 0.0
    index = int(round(fraction * (len(sorted_values) - 1)))
    return sorted_values[max(0, min(len(sorted_values) - 1, index))]


def mean(values):
    return sum(values) / len(values) if values else 0.0


def stdev(values):
    if len(values) < 2:
        return 0.0
    average = mean(values)
    return math.sqrt(sum((value - average) ** 2 for value in values) / (len(values) - 1))


class CorpusProfile(object):
    """Timing/vocabulary statistics measured from the clip index."""

    def __init__(self, **data):
        self.__dict__.update(data)

    def to_dict(self):
        return dict(self.__dict__)

    @classmethod
    def from_dict(cls, data):
        return cls(**dict(data))

    def describe(self):
        return (
            "clips=%d (%s)  audio=%.2fh  words=%d/%d distinct\n"
            "rate: %.2f words/s  %.2f syllables/s (p10 %.2f p90 %.2f)\n"
            "clip length: median %.2fs  mean %.2fs  max %.2fs\n"
            "questions: %.1f%%  cues/clip %.2f\n"
            "word-slot duration: median %.3fs (p10 %.3f p90 %.3f)\n"
            "cue->cue span: median %.3fs"
            % (
                self.clip_count,
                ", ".join("%s=%d" % item for item in sorted(self.kinds.items())),
                self.total_audio_seconds / 3600.0,
                self.word_tokens,
                self.word_types,
                self.words_per_sec,
                self.syllables_per_sec,
                self.syllables_per_sec_p10,
                self.syllables_per_sec_p90,
                self.clip_duration_median,
                self.clip_duration_mean,
                self.clip_duration_max,
                100.0 * self.question_rate,
                self.cues_per_clip,
                self.word_slot_median,
                self.word_slot_p10,
                self.word_slot_p90,
                self.cue_span_median,
            )
        )


def analyze_index(clips):
    """Compute a :class:`CorpusProfile` from raw clip records."""
    kinds = Counter()
    durations = []
    word_rates = []
    syllable_rates = []
    word_slots = []
    cue_spans = []
    cue_counts = []
    words = Counter()
    questions = 0
    total_audio = 0.0

    for clip in clips:
        kinds[clip.get("kind", "?")] += 1
        duration = float(clip.get("duration") or 0.0)
        text = clip.get("text")
        if not isinstance(text, str):
            continue
        durations.append(duration)
        total_audio += duration
        tokens = WORD_RE.findall(text)
        for token in tokens:
            words[token.lower()] += 1
        if text.strip().endswith("?"):
            questions += 1
        if duration > 0.3 and tokens:
            word_rates.append(len(tokens) / duration)
            syllable_rates.append(sum(syllable_count(token) for token in tokens) / duration)

        cues = sorted(
            cue for cue in clip.get("cues") or []
            if isinstance(cue, (list, tuple)) and len(cue) >= 2 and isinstance(cue[1], str)
        )
        cue_counts.append(len(cues))
        for position, (_at, cue_text) in enumerate(cues):
            at = float(_at or 0.0)
            ends_at = float(cues[position + 1][0]) if position + 1 < len(cues) else duration
            span = ends_at - at
            if span <= 0 or span > 3.0:
                continue
            cue_spans.append(span)
            if len(WORD_RE.findall(cue_text)) == 1 and 0.05 <= span < 1.5:
                word_slots.append(span)

    durations.sort()
    word_slots.sort()
    cue_spans.sort()
    syllable_rates.sort()
    word_rates.sort()

    return CorpusProfile(
        clip_count=len(clips),
        kinds=dict(kinds),
        total_audio_seconds=round(total_audio, 3),
        word_types=len(words),
        word_tokens=sum(words.values()),
        question_rate=round(questions / len(clips), 4) if clips else 0.0,
        cues_per_clip=round(mean(cue_counts), 3),
        words_per_sec=round(mean(word_rates), 3),
        words_per_sec_median=round(percentile(word_rates, 0.5), 3),
        syllables_per_sec=round(mean(syllable_rates), 3),
        syllables_per_sec_median=round(percentile(syllable_rates, 0.5), 3),
        syllables_per_sec_p10=round(percentile(syllable_rates, 0.10), 3),
        syllables_per_sec_p90=round(percentile(syllable_rates, 0.90), 3),
        syllables_per_sec_stdev=round(stdev(syllable_rates), 3),
        clip_duration_median=round(percentile(durations, 0.5), 3),
        clip_duration_mean=round(mean(durations), 3),
        clip_duration_max=round(max(durations), 3) if durations else 0.0,
        clip_duration_min=round(min(durations), 3) if durations else 0.0,
        word_slot_median=round(percentile(word_slots, 0.5), 4),
        word_slot_p10=round(percentile(word_slots, 0.10), 4),
        word_slot_p90=round(percentile(word_slots, 0.90), 4),
        word_slot_mean=round(mean(word_slots), 4),
        word_slot_count=len(word_slots),
        cue_span_median=round(percentile(cue_spans, 0.5), 4),
        cue_span_mean=round(mean(cue_spans), 4),
        vocabulary=words.most_common(400),
    )


def load_profile(index_path=None):
    """Load the clip index and measure it, or return a documented fallback."""
    path = index_path or DEFAULT_INDEX
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
        clips = payload.get("clips") if isinstance(payload, dict) else payload
        profile = analyze_index(clips or [])
        profile.source_index = os.path.relpath(path, ROOT)
        return profile
    # Hard-coded fallbacks are the measured values from the committed index, so
    # the engine behaves identically if the JSON is missing. selftest checks
    # these against a fresh measurement of clips_index.json; if the index is
    # ever re-generated, re-run `python3 -m villager_voice.selftest` and update
    # the numbers below rather than letting them drift.
    return CorpusProfile(
        clip_count=2252, kinds={"addon": 2235, "teavrsp": 17},
        total_audio_seconds=6317.7, word_types=2509, word_tokens=16146,
        question_rate=0.1745, cues_per_clip=1.669, words_per_sec=2.56,
        words_per_sec_median=2.57, syllables_per_sec=3.312,
        syllables_per_sec_median=3.35, syllables_per_sec_p10=2.083,
        syllables_per_sec_p90=4.472, syllables_per_sec_stdev=1.1,
        clip_duration_median=2.45, clip_duration_mean=2.81,
        clip_duration_max=13.21, clip_duration_min=0.4,
        word_slot_median=0.77, word_slot_p10=0.42, word_slot_p90=1.16,
        word_slot_mean=0.786, word_slot_count=239, cue_span_median=1.61,
        cue_span_mean=1.631, vocabulary=[], source_index="(fallback constants)",
    )


if __name__ == "__main__":
    print(load_profile().describe())
