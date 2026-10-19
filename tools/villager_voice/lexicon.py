"""Grapheme-to-phoneme for the villager vocabulary (British RP, rule based).

Two layers:

1. A curated dictionary of the words that actually dominate the corpus -- 72
   words cover half of all 16,146 tokens, so a few hundred entries carry most
   of the dialogue. Contractions and the villager vocalisations ("oh", "ooh",
   "ahh", "hmmm") live here too, because rules cannot guess lexical stress.
2. A rule-based fallback: syllabify the spelling, choose tense/lax vowels from
   the grapheme context (silent-e, r-colouring, digraphs), map consonants with
   the usual English context rules, then place stress by syllable weight and
   suffix class.

Stress is written as a digit on each vowel: ``V IH1 L AH0 JH AX0``.
``AX`` is schwa /@/ and ``IX`` is the weakened KIT vowel ("looking" -ing).

The tense/lax split is lexical in English and no rule set gets it every time,
so the policy is deliberately conservative: a single vowel letter is lax unless
it sits in a word-final open syllable, a silent-e (VCe) frame, or an inherently
long digraph. That keeps the frequent words right and the long tail merely
approximate, which is what a formant synthesiser can absorb.

No dictionary download, no model, no network -- rules and the corpus word list.
"""

import re

from .phones import PHONES

# ---------------------------------------------------------------------------
# Curated RP pronunciations. Keys are lowercase with punctuation removed.
# ---------------------------------------------------------------------------
DICT = {
    # --- function words -----------------------------------------------------
    "i": "AY1", "a": "AX0", "an": "AX0 N", "the": "DH AX0", "and": "AX0 N D",
    "to": "T AX0", "of": "AX0 V", "in": "IH0 N", "is": "IH1 Z", "it": "IH1 T",
    "its": "IH1 T S", "be": "B IY1", "am": "AE1 M", "are": "AA1",
    "was": "W OQ1 Z", "were": "W ER1", "been": "B IY1 N", "being": "B IY1 IH0 NG",
    "do": "D UW1", "does": "D AH1 Z", "did": "D IH1 D", "done": "D AH1 N",
    "have": "HH AE1 V", "has": "HH AE1 Z", "had": "HH AE1 D",
    "you": "Y UW1", "your": "Y AO1", "yours": "Y AO1 Z", "youre": "Y UH1 AX0",
    "my": "M AY1", "me": "M IY1", "we": "W IY1", "us": "AH1 S",
    "they": "DH EY1", "them": "DH EH1 M", "their": "DH EH1 AX0",
    "he": "HH IY1", "him": "HH IH1 M", "his": "HH IH1 Z", "she": "SH IY1",
    "her": "HH ER1", "hers": "HH ER1 Z",
    "that": "DH AE1 T", "thats": "DH AE1 T S", "this": "DH IH1 S",
    "these": "DH IY1 Z", "those": "DH OW1 Z", "there": "DH EH1 AX0",
    "theres": "DH EH1 AX0 Z", "here": "HH IH1 AX0", "heres": "HH IH1 AX0 Z",
    "where": "W EH1 AX0", "when": "W EH1 N", "what": "W OQ1 T",
    "whats": "W OQ1 T S", "why": "W AY1", "how": "HH AW1", "who": "HH UW1",
    "whos": "HH UW1 Z", "whom": "HH UW1 M", "whose": "HH UW1 Z",
    "which": "W IH1 CH", "while": "W AY1 L",
    "no": "N OW1", "not": "N OQ1 T", "yes": "Y EH1 S",
    "for": "F AO1", "with": "W IH1 DH", "without": "W IH0 DH AW1 T",
    "at": "AE1 T", "on": "OQ1 N", "off": "OQ1 F", "up": "AH1 P",
    "out": "AW1 T", "from": "F R OQ1 M", "by": "B AY1", "about": "AX0 B AW1 T",
    "into": "IH1 N T UW0", "onto": "OQ1 N T UW0", "over": "OW1 V AX0",
    "under": "AH1 N D AX0", "after": "AA1 F T AX0", "before": "B IH0 F AO1",
    "if": "IH1 F", "or": "AO1", "but": "B AH1 T", "so": "S OW1",
    "as": "AE1 Z", "than": "DH AE1 N", "then": "DH EH1 N",
    "as": "AE1 Z", "of": "AX0 V", "too": "T UW1", "also": "AO1 L S OW0",
    "even": "IY1 V AH0 N", "open": "OW1 P AH0 N", "often": "OQ1 F AH0 N",
    "only": "OW1 N L IY0", "other": "AH1 DH AX0", "another": "AX0 N AH1 DH AX0",
    "every": "EH1 V R IY0", "each": "IY1 CH", "both": "B OW1 TH",
    "much": "M AH1 CH", "many": "M EH1 N IY0", "few": "F Y UW1",
    "such": "S AH1 CH", "own": "OW1 N", "same": "S EY1 M",

    # --- modal / auxiliary --------------------------------------------------
    "can": "K AE1 N", "cant": "K AA1 N T", "cannot": "K AE1 N OQ2 T",
    "could": "K UH1 D", "couldnt": "K UH1 D AH0 N T", "would": "W UH1 D",
    "wouldnt": "W UH1 D AH0 N T", "should": "SH UH1 D", "will": "W IH1 L",
    "wont": "W OW1 N T", "shall": "SH AE1 L", "may": "M EY1",
    "might": "M AY1 T", "must": "M AH1 S T",
    "dont": "D OW1 N T", "didnt": "D IH1 D AH0 N T", "isnt": "IH1 Z AH0 N T",
    "arent": "AA1 R AH0 N T", "wasnt": "W OQ1 Z AH0 N T",
    "im": "AY1 M", "ive": "AY1 V", "ill": "AY1 L", "id": "AY1 D",
    "youll": "Y UW1 L", "youd": "Y UW1 D", "youve": "Y UW1 V",
    "lets": "L EH1 T S",
    # Contraction tails (produced by normalize_word splitting "don't" -> do+nt).
    "nt": "AH0 N T", "re": "AX0", "ve": "V", "ll": "L", "m": "M", "d": "D",
    "s": "Z",

    # --- vocalisations ------------------------------------------------------
    "oh": "OW1", "ooh": "UW1", "ah": "AA1", "ahh": "AA1", "ow": "AW1",
    "hmm": "HMM", "hmmm": "HMM", "hmmmm": "HMM", "mmm": "HMM", "mm": "HMM",
    "uh": "AX0", "um": "AX0 M", "er": "ER0", "hey": "HH EY1", "hi": "HH AY1",
    "hello": "HH EH0 L OW1", "goodbye": "G UH2 D B AY1", "gosh": "G OQ1 SH",
    "wow": "W AW1", "whoa": "W OW1", "yay": "Y EY1", "ouch": "AW1 CH",
    "oops": "UW1 P S", "aye": "AY1", "nah": "N AA1", "yep": "Y EH1 P",
    "nope": "N OW1 P", "ok": "OW2 K EY1", "okay": "OW1 K EY1",

    # --- high-frequency verbs ----------------------------------------------
    "need": "N IY1 D", "needs": "N IY1 D Z", "want": "W OQ1 N T",
    "wants": "W OQ1 N T S", "like": "L AY1 K", "likes": "L AY1 K S",
    "get": "G EH1 T", "gets": "G EH1 T S", "got": "G OQ1 T",
    "go": "G OW1", "goes": "G OW1 Z", "going": "G OW1 IH0 NG",
    "gone": "G OQ1 N", "come": "K AH1 M", "comes": "K AH1 M Z",
    "see": "S IY1", "sees": "S IY1 Z", "saw": "S AO1", "seen": "S IY1 N",
    "look": "L UH1 K", "looks": "L UH1 K S", "looking": "L UH1 K IH0 NG",
    "wait": "W EY1 T", "know": "N OW1", "knows": "N OW1 Z",
    "think": "TH IH1 NG K", "thought": "TH AO1 T", "say": "S EY1",
    "says": "S EH1 Z", "said": "S EH1 D", "tell": "T EH1 L",
    "told": "T OW1 L D", "make": "M EY1 K", "makes": "M EY1 K S",
    "made": "M EY1 D", "take": "T EY1 K", "takes": "T EY1 K S",
    "put": "P UH1 T", "give": "G IH1 V", "gives": "G IH1 V Z",
    "gave": "G EY1 V", "find": "F AY1 N D", "found": "F AW1 N D",
    "love": "L AH1 V", "loves": "L AH1 V Z", "lovely": "L AH1 V L IY0",
    "thank": "TH AE1 NG K", "thanks": "TH AE1 NG K S",
    "please": "P L IY1 Z", "sorry": "S OQ1 R IY0",
    "work": "W ER1 K", "works": "W ER1 K S", "working": "W ER1 K IH0 NG",
    "trade": "T R EY1 D", "trades": "T R EY1 D Z",
    "trading": "T R EY1 D IH0 NG", "sell": "S EH1 L", "buy": "B AY1",
    "pay": "P EY1", "help": "HH EH1 L P", "want": "W OQ1 N T",
    "haggle": "HH AE1 G AH0 L", "haggling": "HH AE1 G L IH0 NG",
    "lookit": "L UH1 K IH0 T", "testificate": "T EH1 S T IH0 F AH0 K EY2 T",

    # --- nouns / adjectives -------------------------------------------------
    "one": "W AH1 N", "two": "T UW1", "three": "TH R IY1", "four": "F AO1",
    "five": "F AY1 V", "ten": "T EH1 N", "first": "F ER1 S T",
    "all": "AO1 L", "some": "S AH1 M", "any": "EH1 N IY0",
    "more": "M AO1", "most": "M OW1 S T", "less": "L EH1 S",
    "very": "V EH1 R IY0", "really": "R IY1 L IY0", "just": "JH AH1 S T",
    "now": "N AW1", "today": "T AX0 D EY1", "tonight": "T AX0 N AY1 T",
    "yesterday": "Y EH1 S T AX0 D EY2", "tomorrow": "T AX0 M OQ1 R OW0",
    "again": "AX0 G EH1 N", "back": "B AE1 K", "away": "AX0 W EY1",
    "right": "R AY1 T", "left": "L EH1 F T", "well": "W EH1 L",
    "good": "G UH1 D", "bad": "B AE1 D", "great": "G R EY1 T",
    "new": "N Y UW1", "old": "OW1 L D", "big": "B IH1 G",
    "small": "S M AO1 L", "little": "L IH1 T AH0 L",
    "man": "M AE1 N", "men": "M EH1 N", "woman": "W UH1 M AX0 N",
    "baby": "B EY1 B IY0", "boy": "B OY1", "girl": "G ER1 L",
    "friend": "F R EH1 N D", "friends": "F R EH1 N D Z",
    "villager": "V IH1 L AH0 JH AX0", "villagers": "V IH1 L AH0 JH AX0 Z",
    "village": "V IH1 L AH0 JH", "citizen": "S IH1 T AH0 Z AX0 N",
    "citizens": "S IH1 T AH0 Z AX0 N Z", "zombie": "Z AO1 M B IY0",
    "zombies": "Z AO1 M B IY0 Z", "emerald": "EH1 M AX0 L D",
    "emeralds": "EH1 M AX0 L D Z", "nose": "N OW1 Z", "sheep": "SH IY1 P",
    "thing": "TH IH1 NG", "things": "TH IH1 NG Z",
    "something": "S AH1 M TH IH0 NG", "anything": "EH1 N IY0 TH IH0 NG",
    "nothing": "N AH1 TH IH0 NG", "everything": "EH1 V R IY0 TH IH0 NG",
    "fellow": "F EH1 L OW0", "people": "P IY1 P AH0 L",
    "time": "T AY1 M", "day": "D EY1", "days": "D EY1 Z",
    "night": "N AY1 T", "morning": "M AO1 N IH0 NG",
    "house": "HH AW1 S", "home": "HH OW1 M", "shop": "SH OQ1 P",
    "money": "M AH1 N IY0", "price": "P R AY1 S", "prices": "P R AY1 S IH0 Z",
    "deal": "D IY1 L", "news": "N Y UW1 Z", "report": "R IH0 P AO1 T",
    "breaking": "B R EY1 K IH0 NG", "story": "S T AO1 R IY0",
    "book": "B UH1 K", "books": "B UH1 K S", "pork": "P AO1 K",
    "farm": "F AA1 M", "food": "F UW1 D", "water": "W AO1 T AX0",
    "world": "W ER1 L D", "life": "L AY1 F", "hand": "HH AE1 N D",
    "head": "HH EH1 D", "eye": "AY1", "eyes": "AY1 Z", "face": "F EY1 S",
    "place": "P L EY1 S", "way": "W EY1", "lot": "L OQ1 T",
    "kind": "K AY1 N D", "sort": "S AO1 T", "bit": "B IH1 T",
    "nice": "N AY1 S", "fine": "F AY1 N", "sure": "SH UH1 AX0",
    "never": "N EH1 V AX0", "always": "AO1 L W EY2 Z", "still": "S T IH1 L",
    "gentle": "JH EH1 N T AH0 L", "sparkly": "S P AA1 K L IY0",
    "magnificent": "M AE2 G N IH1 F AH0 S AH0 N T",
    "beautiful": "B Y UW1 T AH0 F AX0 L", "wonderful": "W AH1 N D AX0 F AX0 L",
    "receipt": "R IH0 S IY1 T", "email": "IY1 M EY2 L",
    "perfect": "P ER1 F AH0 K T", "odd": "OQ1 D", "thrown": "TH R OW1 N",
    "enjoy": "IH0 N JH OY1", "being": "B IY1 IH0 NG",
    "look": "L UH1 K", "looks": "L UH1 K S",
    # Irregular spellings that no rule set can guess consistently.
    "through": "TH R UW1", "though": "DH OW1", "thought": "TH AO1 T",
    "enough": "IH0 N AH1 F", "cough": "K OQ1 F", "rough": "R AH1 F",
    "tough": "T AH1 F", "bough": "B AW1", "knowledge": "N OQ1 L AH0 JH",
    "known": "N OW1 N", "knowing": "N OW1 IH0 NG", "gnome": "N OW1 M",
    "sign": "S AY1 N", "island": "AY1 L AX0 N D", "listen": "L IH1 S AH0 N",
    "often": "OQ1 F AH0 N", "nation": "N EY1 SH AX0 N", "station": "S T EY1 SH AX0 N",
    "delicious": "D IH0 L IH1 SH AX0 S", "special": "S P EH1 SH AX0 L",
    "magical": "M AE1 JH IH0 K AX0 L", "expensive": "IH0 K S P EH1 N S IH0 V",
    "better": "B EH1 T AX0", "ready": "R EH1 D IY0", "pretty": "P R IH1 T IY0",
    "heavy": "HH EH1 V IY0", "body": "B OQ1 D IY0", "city": "S IH1 T IY0",
    "running": "R AH1 N IH0 NG", "stopped": "S T OQ1 P T",
    "theend": "DH IY1 EH1 N D",
}

# Words where "g" before e/i/y stays hard.
HARD_G = {"get", "gets", "getting", "give", "gives", "gave", "given", "girl",
          "girls", "gift", "gifts", "giggle", "begin", "begins", "began",
          "together", "forget", "target", "finger", "bigger", "tiger",
          "gear", "geese", "goose", "good", "go", "goes", "gone", "going",
          "got", "gosh", "great", "ground", "group", "grow", "green"}

# Suffixes that pull stress onto the syllable right before them.
PRE_STRESS_SUFFIXES = ("tion", "sion", "tian", "cial", "tial", "ic", "ical",
                       "ity", "ify", "ular", "ient", "ious", "ial", "ivity",
                       "ician", "ious", "eous")
# Suffixes that carry the stress themselves.
SELF_STRESS_SUFFIXES = ("ee", "eer", "ese", "esque", "ette", "een")

CONTRACTIONS = (("'re", "re"), ("'ve", "ve"), ("'ll", "ll"), ("'nt", "nt"),
                ("'m", "m"), ("'d", "d"), ("'s", "s"))

_SUFFIX_PRON = {
    "tion": "SH AX0 N", "sion": "ZH AX0 N", "tian": "SH AX0 N",
    "cial": "SH AX0 L", "tial": "SH AX0 L", "ic": "IH0 K",
    "ical": "IH0 K AX0 L", "ity": "IH0 T IY0", "ify": "IH0 F AY1",
    "ular": "Y AX0 L AX0", "ient": "IY0 AH0 N T", "ious": "IY0 AX0 S",
    "ial": "IY0 AX0 L", "ivity": "IH1 V AH0 T IY0", "ee": "IY1",
    "eer": "IH1 AX0", "ese": "IY1 Z", "esque": "EH1 S K", "ette": "EH1 T",
    "een": "IY1 N", "ician": "IH1 SH AX0 N", "eous": "IY0 AX0 S",
}

_VOWELS = "aeiou"
# Single-character membership sets. Never test ``rest[:1] in _VOWELS``: an empty
# slice is a substring of every string, so it silently matches.
_VOWEL_SET = frozenset("aeiou")
_VOWEL_SET_Y = frozenset("aeiouy")
_LONG_VOWEL_PHONES = {"AY", "EY", "OY", "AW", "OW", "IY", "UW", "AA", "AO",
                      "ER", "IA", "EA", "UA"}

# Suffixes whose vowel is lax even inside a silent-e frame ("-ive", "-ine").
LAX_SUFFIXES = ("ive", "ine", "ite", "ice", "ise", "ize", "ile", "age",
                "ance", "ence", "able", "ible", "ment", "ness")


def normalize_word(word):
    """Lowercase, strip punctuation, split contractions into dictionary keys."""
    if not word:
        return []
    value = word.lower().replace("\u2019", "'").strip()
    value = re.sub(r"[^a-z']+", "", value)
    if not value:
        return []
    if "'" in value:
        parts = [value]
        for suffix, tail in CONTRACTIONS:
            expanded = []
            for part in parts:
                if part.endswith(suffix) and len(part) > len(suffix):
                    expanded.append(part[: -len(suffix)])
                    expanded.append(tail)
                else:
                    expanded.append(part)
            parts = expanded
        return [part.replace("'", "") for part in parts if part.replace("'", "")]
    return [value]


# ---------------------------------------------------------------------------
# Syllabification
# ---------------------------------------------------------------------------
# Vowel graphemes, longest first. "y" is only a vowel when it is not followed
# by another vowel letter ("yes"/"yesterday" take Y as a consonant onset).
_VOWEL_GRAPHEMES = [
    "aigh", "eigh", "ough", "igh", "eau", "ai", "ay", "au", "aw", "ea", "ee",
    "ei", "ey", "ie", "oa", "oi", "oy", "oo", "ou", "ow", "ue", "ui", "uy",
    "ew", "eu", "a", "e", "i", "o", "u", "y",
]
# (tense/long phone, lax phone)
_VOWEL_PHONES = {
    "aigh": ("EY", "EY"), "eigh": ("EY", "EY"), "ough": ("OW", "AW"),
    "igh": ("AY", "AY"), "eau": ("Y UW", "Y UW"),
    "ai": ("EY", "EY"), "ay": ("EY", "EY"), "au": ("AO", "AO"),
    "aw": ("AO", "AO"), "ea": ("IY", "EH"), "ee": ("IY", "IY"),
    "ei": ("EY", "IY"), "ey": ("IY", "EY"), "ie": ("IY", "AY"),
    "oa": ("OW", "OW"), "oi": ("OY", "OY"), "oy": ("OY", "OY"),
    "oo": ("UW", "UH"), "ou": ("AW", "AH"), "ow": ("OW", "AW"),
    "ue": ("UW", "UW"), "ui": ("UW", "IH"), "uy": ("AY", "IH"),
    "ew": ("Y UW", "UW"), "eu": ("Y UW", "UW"),
    "a": ("EY", "AE"), "e": ("IY", "EH"), "i": ("AY", "IH"),
    "o": ("OW", "OQ"), "u": ("Y UW", "AH"), "y": ("AY", "IH"),
}

# Consonant graphemes, longest first.
_CONSONANT_GRAPHEMES = [
    "tch", "dge", "sch", "cks", "ths", "th", "sh", "ch", "ph", "gh", "wh",
    "ck", "qu", "kn", "gn", "wr", "ng", "nk", "mb",
    "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s",
    "t", "v", "w", "x", "z",
]
_CONSONANT_PHONES = {
    "tch": "CH", "dge": "JH", "sch": "S K", "cks": "K S", "ths": "DH Z",
    "th": "TH", "sh": "SH", "ch": "CH", "ph": "F", "gh": "", "wh": "W",
    "ck": "K", "qu": "K W", "kn": "N", "gn": "N", "wr": "R", "ng": "NG",
    "nk": "NG K", "mb": "M",
    "b": "B", "c": "K", "d": "D", "f": "F", "g": "G", "h": "HH", "j": "JH",
    "k": "K", "l": "L", "m": "M", "n": "N", "p": "P", "r": "R", "s": "S",
    "t": "T", "v": "V", "w": "W", "x": "K S", "z": "Z",
}

_FUNCTION_TH = {"the", "this", "that", "these", "those", "there", "then",
                "than", "them", "they", "their", "though", "thus", "thee",
                "thy", "thine", "therefore", "brother", "mother", "father",
                "other", "another", "bother", "gather", "rather", "together",
                "weather", "whether", "smooth", "breathe", "clothe"}

_SILENT_H = {"hour", "honest", "honour", "heir", "herb", "vehicle"}
_SILENT_L = {"walk", "talk", "half", "calf", "could", "would", "should",
             "folk", "yolk", "calm", "palm", "salmon", "almond"}


class Syllable(object):
    __slots__ = ("onset", "onset_pos", "nucleus", "coda", "coda_pos",
                 "vowel", "long", "weight")

    def __init__(self, onset, onset_pos, nucleus, coda, coda_pos, vowel,
                 long_flag):
        self.onset = onset
        self.onset_pos = onset_pos
        self.nucleus = nucleus
        self.coda = coda
        self.coda_pos = coda_pos
        self.vowel = vowel
        self.long = long_flag
        self.weight = 1 if ((long_flag and vowel) or len(coda) >= 2) else 0

    def __repr__(self):
        return "<%s|%s|%s>" % (self.onset, self.nucleus, self.coda)


def _is_y_vowel(word, index):
    """'y' is a consonant when a vowel letter follows it, a vowel otherwise."""
    if index < 0 or index >= len(word) or word[index] != "y":
        return True
    following = word[index + 1:index + 2]
    if following and following in _VOWEL_SET:
        return False
    if index == 0:
        # Word-initial y before a consonant ("yttrium") is still a consonant.
        return len(word) > 1 and word[1:2] not in _VOWEL_SET
    return True


def _scan(word):
    """Split a spelling into alternating consonant-cluster / vowel groups.

    Each group records its absolute start index in ``word`` so consonant
    context rules can look at the *whole* remaining word, not just the cluster
    they happen to sit in ("magical": the g is soft because of the following i).
    """
    groups = []
    index = 0
    silent_e = False
    core = word
    # Silent-e: final 'e' after a consonant with an earlier vowel in the word.
    if (len(core) > 2 and core.endswith("e")
            and not core.endswith(("ee", "ie", "ye", "oe", "ue"))
            and any(ch in _VOWEL_SET for ch in core[:-1])
            and core[-2] not in _VOWEL_SET):
        silent_e = True
        core = core[:-1]

    while index < len(core):
        matched = False
        for grapheme in _VOWEL_GRAPHEMES:
            if core.startswith(grapheme, index) and _is_y_vowel(core, index):
                groups.append(("V", grapheme, index))
                index += len(grapheme)
                matched = True
                break
        if matched:
            continue
        for grapheme in _CONSONANT_GRAPHEMES:
            if core.startswith(grapheme, index):
                groups.append(("C", grapheme, index))
                index += len(grapheme)
                matched = True
                break
        if not matched:
            groups.append(("C", core[index], index))
            index += 1

    # Merge adjacent consonants only. Two vowel graphemes side by side ("played"
    # -> "ay"+"e") are separate nuclei; merging them would invent a vowel that
    # matches no rule and swallow the silent "-ed".
    merged = []
    for kind, text, start in groups:
        if kind == "C" and merged and merged[-1][0] == "C":
            merged[-1] = (kind, merged[-1][1] + text, merged[-1][2])
        else:
            merged.append((kind, text, start))
    return merged, silent_e


def _syllabify(word):
    """Split a spelling into :class:`Syllable` objects."""
    merged, silent_e = _scan(word)

    # "-le" final syllable after a consonant ("little", "gentle").
    syllabic_le = bool(
        word.endswith("le") and len(word) > 3 and word[-3] not in _VOWEL_SET
        and any(ch in _VOWEL_SET for ch in word[:-2]))

    syllables = []
    onset = ""
    onset_pos = -1
    for kind, text, start in merged:
        if kind == "C":
            if not onset:
                onset_pos = start
            onset += text
        else:
            syllables.append([onset, onset_pos, text, start, "", -1])
            onset = ""
            onset_pos = -1
    if onset:
        if syllables:
            syllables[-1][4] = onset
            syllables[-1][5] = onset_pos
        else:
            # No vowel at all ("hmm", "shh"): one syllabic consonant.
            return [Syllable(onset, onset_pos, "", "", -1, "HMM", True)]
    if not syllables:
        return [Syllable("", 0, "", "", -1, "HMM", True)]

    # Move intervocalic consonant clusters to the following onset (max onset),
    # keeping a legal coda behind.
    for i in range(len(syllables) - 1):
        coda = syllables[i][4]
        if len(coda) > 1:
            keep = coda[0] if coda[:2] in ("st", "sp", "sk", "sc", "ft", "mp",
                                           "nd", "nk", "nt", "lt", "rt") else coda[:1]
            syllables[i][4] = keep
            moved_pos = syllables[i][5] + len(keep)
            syllables[i + 1][0] = coda[len(keep):] + syllables[i + 1][0]
            syllables[i + 1][1] = moved_pos

    count = len(syllables)
    result = []
    for position, (onc, onc_pos, nucleus, _nuc_pos, coda, coda_pos) in enumerate(syllables):
        long_flag, vowel = _choose_vowel(nucleus, coda, word, position, count,
                                         silent_e, syllabic_le,
                                         position == count - 1)
        result.append(Syllable(onc, onc_pos, nucleus, coda, coda_pos, vowel,
                               long_flag))
    return result


def _choose_vowel(grapheme, coda, word, position, count, silent_e,
                  syllabic_le, is_last):
    """Pick the vowel phone and whether it is long/tense.

    Returns ``(long_flag, phone)``; ``phone`` may be the empty string when the
    nucleus contributes no vowel of its own (final "-ed").
    """
    tense, lax = _VOWEL_PHONES.get(grapheme, ("IH", "IH"))
    following = coda

    # Final "-ed": the vowel is silent unless the stem ends in t or d, and even
    # then the consonant rule emits /IH D/ as a unit -- so blank the nucleus.
    if (grapheme == "e" and coda == "d" and is_last and len(word) > 2
            and word.endswith("ed")):
        return False, ""

    # r-colouring: RP keeps the vowel length and drops the r itself.
    if following.startswith("r"):
        if grapheme == "a":
            return True, "AA"
        if grapheme == "o":
            return True, "AO"
        if grapheme in ("e", "i", "u", "y"):
            return True, "ER"

    # Lengthening before -ld/-ll/-lk/-lm/-st ("bold", "roll", "folk", "calm").
    if grapheme == "a" and following[:2] in ("ld", "ll", "lk", "lm"):
        return True, "AO"
    if grapheme == "o" and following[:2] in ("ld", "ll", "st", "ne"):
        return True, "OW"

    # Word-final -y after a consonant is the happy vowel.
    if grapheme == "y" and is_last and count > 1:
        return False, "IY"
    # Unstressed final -er is a schwa in RP ("better", "water", "never").
    if grapheme == "e" and following == "r" and is_last and count > 1:
        return False, "AX"
    # "-ive"/"-ine" and friends stay lax even inside a silent-e frame.
    if len(grapheme) == 1 and silent_e and is_last:
        if any(word.endswith(suffix) for suffix in LAX_SUFFIXES):
            return False, lax
    # Word-final single vowel in an open syllable is long ("go", "so", "we").
    if is_last and not coda and len(grapheme) == 1:
        return True, tense
    # Silent-e frame makes the preceding single vowel long.
    if silent_e and len(grapheme) == 1 and position == count - 1:
        return True, tense
    # Digraphs are inherently long.
    if len(grapheme) > 1:
        return True, tense
    # "-le" syllabic ending.
    if syllabic_le and is_last:
        return False, "AX"
    return False, lax


def _consonant_phones(cluster, word, cluster_pos=0):
    """Map a consonant cluster to phones using whole-word context."""
    result = []
    if not cluster:
        return result
    index = 0
    while index < len(cluster):
        grapheme = None
        for candidate in _CONSONANT_GRAPHEMES:
            if cluster.startswith(candidate, index):
                grapheme = candidate
                break
        if grapheme is None:
            grapheme = cluster[index]
        mapping = _CONSONANT_PHONES.get(grapheme, "")
        # Absolute position just after this grapheme, in the whole word.
        absolute = (cluster_pos if cluster_pos >= 0 else 0) + index + len(grapheme)
        index += len(grapheme)
        if not mapping:
            continue
        for phone in mapping.split():
            adjusted = _adjust_consonant(phone, grapheme, word, absolute)
            if adjusted:
                result.extend(adjusted.split())
    return result


def _adjust_consonant(phone, grapheme, word, after_pos):
    """Apply soft-c/soft-g, -tion/-cious, voicing, non-rhotic r, silent h/l."""
    rest = word[after_pos:] if after_pos <= len(word) else ""
    before = word[after_pos - len(grapheme) - 1:after_pos - len(grapheme)] \
        if after_pos - len(grapheme) > 0 else ""
    next_vowel = rest[:1]

    if grapheme == "c" and phone == "K":
        if next_vowel in ("e", "i", "y"):
            # "ci" + vowel is /S/ ("special", "delicious" via c+i).
            if next_vowel == "i" and rest[1:2] and rest[1:2] in _VOWEL_SET:
                return "SH"
            return "S"
    if grapheme == "g" and phone == "G":
        if next_vowel in ("e", "i", "y") and word not in HARD_G:
            return "JH"
    if grapheme == "th" and phone == "TH" and word in _FUNCTION_TH:
        return "DH"
    if grapheme == "s" and phone == "S":
        if rest[:3] in ("ion", "ure") or rest[:4] == "ianc":
            return "SH"
        if next_vowel and next_vowel in _VOWEL_SET and before and before in _VOWEL_SET:
            return "Z"  # intervocalic s ("nose", "these", "easy")
    if grapheme == "t" and phone == "T":
        if rest[:3] == "ion" or rest[:2] == "ia":
            return "SH"
        if word.endswith(("sten", "stle")) or word in (
                "often", "listen", "whistle", "castle", "hasten"):
            return ""
    if grapheme == "h" and phone == "HH":
        if word in _SILENT_H or word.startswith("rh"):
            return ""
    if grapheme == "l" and phone == "L":
        if word in _SILENT_L:
            return ""
    if grapheme == "r" and phone == "R":
        # Non-rhotic RP: /r/ surfaces only before a vowel.
        if not next_vowel or next_vowel not in _VOWEL_SET_Y:
            return ""
    if grapheme == "n" and phone == "N" and next_vowel in ("k", "g"):
        return "NG"
    if grapheme == "d" and phone == "D" and word.endswith("ed") \
            and after_pos == len(word):
        # "-ed" is /t/ after a voiceless consonant, /Id/ after t or d, else /d/.
        preceding = word[-3:-2]
        if preceding in ("t", "d"):
            return "IH D"
        if preceding in ("p", "k", "f", "s", "h"):
            return "T"
        if word.endswith(("shed", "ched", "sed", "zed")):
            return "D"
        return "D"
    return phone


def _assign_stress(syllables, word):
    """Stress digits per syllable (1 primary, 2 secondary, 0 unstressed)."""
    count = len(syllables)
    if count == 0:
        return []
    if count == 1:
        return [1]

    # Inflectional/derivational suffixes that never move stem stress.
    for suffix in ("ingly", "edly", "ing", "ed", "es", "ly", "ness", "ful",
                   "less", "ment", "able", "ible", "er", "est", "en", "s"):
        if word.endswith(suffix) and len(word) - len(suffix) >= 3:
            stem = word[:len(word) - len(suffix)]
            stem_syllables = _syllabify(stem)
            stem_count = len(stem_syllables)
            if 0 < stem_count < count:
                stem_stress = _assign_stress_core(stem_syllables, stem)
                stresses = [0] * count
                for i, value in enumerate(stem_stress[:stem_count]):
                    stresses[i] = value
                if 1 not in stresses:
                    stresses[0] = 1
                return _fill_secondary(stresses, syllables)
            break
    return _assign_stress_core(syllables, word)


def _assign_stress_core(syllables, word):
    """Weight-based stress for a stem that carries no neutral suffix."""
    count = len(syllables)
    if count == 0:
        return []
    if count == 1:
        return [1]

    for suffix in PRE_STRESS_SUFFIXES:
        if word.endswith(suffix) and len(word) - len(suffix) >= 2:
            stresses = [0] * count
            stresses[max(0, count - 2)] = 1
            return _fill_secondary(stresses, syllables)
    for suffix in SELF_STRESS_SUFFIXES:
        if word.endswith(suffix) and len(word) - len(suffix) >= 2:
            stresses = [0] * count
            stresses[count - 1] = 1
            return _fill_secondary(stresses, syllables)

    stresses = [0] * count
    if count == 2:
        stresses[1 if syllables[1].weight else 0] = 1
    else:
        # Heavy penult takes the stress; otherwise the antepenult.
        target = count - 2 if syllables[count - 2].weight else count - 3
        stresses[max(0, target)] = 1
    return _fill_secondary(stresses, syllables)


def _fill_secondary(stresses, syllables):
    """Add secondary stress to other heavy syllables, keeping one primary."""
    primary = stresses.index(1) if 1 in stresses else 0
    for i, syllable in enumerate(syllables):
        if i == primary or stresses[i]:
            continue
        if syllable.weight and syllable.vowel in _LONG_VOWEL_PHONES:
            stresses[i] = 2
    if 1 not in stresses:
        stresses[0] = 1
    return stresses


def spell_to_phones(word):
    """Rule-based G2P: returns a list of ``(symbol, stress)`` tuples."""
    text = re.sub(r"[^a-z]", "", word.lower())
    if not text:
        return []
    if text in DICT:
        return parse_pronunciation(DICT[text])

    syllables = _syllabify(text)
    stresses = _assign_stress(syllables, text)

    result = []
    for position, syllable in enumerate(syllables):
        onset_pos = syllable.onset_pos if syllable.onset_pos is not None else 0
        coda_pos = syllable.coda_pos if syllable.coda_pos is not None else -1
        for phone in _consonant_phones(syllable.onset, text, onset_pos):
            result.append((phone, 0))
        stress = stresses[position] if position < len(stresses) else 0
        parts = syllable.vowel.split()  # empty for a silent final "-ed"
        for i, phone in enumerate(parts):
            # Stress sits on the last phone of a multi-phone vowel ("Y UW").
            result.append((phone, stress if i == len(parts) - 1 else 0))
        for phone in _consonant_phones(syllable.coda, text, coda_pos):
            result.append((phone, 0))
    return _cleanup(result)


def _cleanup(phones):
    """Drop unknown symbols and collapse accidental repeats."""
    out = []
    for symbol, stress in phones:
        if symbol not in PHONES:
            continue
        if out and out[-1][0] == symbol and symbol != "HMM":
            out[-1] = (symbol, max(out[-1][1], stress))
            continue
        out.append((symbol, stress))
    # A stray R between two non-syllabic phones is a rule artefact.
    from .phones import is_syllabic

    cleaned = []
    for i, (symbol, stress) in enumerate(out):
        if symbol == "R" and 0 < i < len(out) - 1:
            if not is_syllabic(out[i - 1][0]) and not is_syllabic(out[i + 1][0]):
                continue
        cleaned.append((symbol, stress))
    return cleaned


def parse_pronunciation(text):
    """Parse ``V IH1 L AH0 JH AX0`` into ``[(symbol, stress), ...]``."""
    result = []
    for token in str(text).split():
        token = token.strip()
        if not token:
            continue
        if token[-1].isdigit():
            symbol = token[:-1].upper()
            stress = int(token[-1])
        else:
            symbol = token.upper()
            stress = 0
        for part in symbol.split():
            if part in PHONES:
                result.append((part, stress))
    return result


def pronounce(word):
    """Public entry point: dictionary first, rules second."""
    keys = normalize_word(word)
    if not keys:
        return []
    result = []
    for key in keys:
        if key in DICT:
            result.extend(parse_pronunciation(DICT[key]))
            continue
        stem, suffix = _match_suffix(key)
        if suffix and stem in DICT and suffix in _SUFFIX_PRON:
            phones = parse_pronunciation(DICT[stem])
            phones.extend(parse_pronunciation(_SUFFIX_PRON[suffix]))
            result.extend(_cleanup(phones))
            continue
        result.extend(spell_to_phones(key))
    return _cleanup(result)


def _match_suffix(word):
    for suffix in sorted(PRE_STRESS_SUFFIXES + SELF_STRESS_SUFFIXES,
                         key=len, reverse=True):
        if word.endswith(suffix) and len(word) - len(suffix) >= 2:
            return word[: -len(suffix)], suffix
    return word, ""


def text_to_words(text):
    """Split a line into typed tokens, keeping punctuation as structure."""
    tokens = re.findall(r"[A-Za-z']+|[.,!?;:\-]+|\s+", text or "")
    result = []
    for token in tokens:
        if re.match(r"^[A-Za-z']+$", token):
            result.append(("word", token))
        elif token in (",", ";", ":"):
            result.append(("comma", token))
        elif token in (".", "!", "?"):
            result.append(("boundary", token))
        elif token == "-":
            result.append(("dash", token))
        elif token.strip() == "":
            result.append(("space", token))
    return result


def phones_for_line(text):
    """Whole-line phone plan with word and punctuation structure."""
    plan = []
    for kind, token in text_to_words(text):
        if kind == "word":
            plan.append({"kind": "word", "text": token,
                         "phones": pronounce(token)})
        else:
            plan.append({"kind": kind, "text": token})
    return plan


def coverage_report(words):
    """How much of a vocabulary the curated dictionary handles directly."""
    inside = sum(1 for word in words if normalize_word(word)[0:1] and
                 normalize_word(word)[0] in DICT)
    return inside, len(words)


if __name__ == "__main__":
    samples = ["villager", "emeralds", "testificate", "haggling", "about",
               "hello", "citizen", "looking", "nose", "trading", "magnificent",
               "sparkly", "books", "gentle", "receipt", "pork", "don't", "I'm",
               "beautiful", "hmmm", "wonderful", "yesterday", "never", "even",
               "open", "money", "happy", "little", "people", "water", "great",
               "enjoy", "thrown", "perfect", "magical", "expensive", "delicious"]
    for word in samples:
        phones = pronounce(word)
        print("%-14s %s" % (word, " ".join(
            "%s%s" % (s, d if d else "") for s, d in phones)))
