"""Articulatory phone inventory for a source-filter synthesiser.

Every phone carries the vocal-tract *targets* the filter is driven to (formant
frequencies/bandwidths/amplitudes), how it is voiced, and how it is released.
Vowel values are Received-Pronunciation male references at
``tract.length_scale = 1.0``; the voiceprint scales the whole space, and
:mod:`analyze` can replace individual targets with LPC measurements.

Symbols are ARPAbet-style ASCII so they survive JSON, logs and filenames.
"""

import math

VOWEL = "vowel"
DIPHTHONG = "diphthong"
NASAL = "nasal"
STOP = "stop"
FRICATIVE = "fricative"
AFFRICATE = "affricate"
APPROXIMANT = "approximant"
GLIDE = "glide"
SILENCE = "silence"
VOCALISATION = "vocalisation"

# F4/F5 are near-constant for one speaker; per-phone values refine them slightly.
F4 = 3400.0
F5 = 4300.0
BW = (60.0, 90.0, 150.0, 200.0, 260.0)
BW_NARROW = (50.0, 80.0, 130.0, 190.0, 250.0)
BW_WIDE = (90.0, 140.0, 220.0, 280.0, 340.0)


def _phone(manner, voiced, dur, amp, formants, bw=BW, **extra):
    entry = {"manner": manner, "voiced": voiced, "dur": dur, "amp": amp,
             "f": formants, "bw": bw}
    entry.update(extra)
    return entry


# --- vowels: (F1, F2, F3) triples; F4/F5 filled in below ---------------------
_VOWEL_SPACE = {
    "IY": (270.0, 2290.0, 3010.0),   # fleece
    "IH": (400.0, 1990.0, 2570.0),   # kit
    "EH": (530.0, 1840.0, 2480.0),   # dress
    "AE": (660.0, 1720.0, 2410.0),   # trap
    "AA": (720.0, 1240.0, 2490.0),   # bath/palm
    "AO": (570.0, 840.0, 2440.0),    # thought (long)
    "OQ": (600.0, 900.0, 2450.0),    # lot (short)
    "UH": (430.0, 1050.0, 2400.0),   # foot
    "UW": (300.0, 870.0, 2340.0),    # goose
    "AH": (640.0, 1190.0, 2390.0),   # strut
    "IX": (430.0, 1700.0, 2300.0),   # weak KIT ("villager" -er, "looking" -ing)
    "ER": (490.0, 1350.0, 1690.0),   # nurse / r-coloured
    "AX": (520.0, 1450.0, 2480.0),   # schwa /@/
}

_LONG = {"IY", "AA", "AO", "UW", "ER"}
_DURATIONS = {"IY": 0.185, "IH": 0.105, "EH": 0.115, "AE": 0.135, "AA": 0.200,
              "AO": 0.195, "OQ": 0.105, "UH": 0.100, "UW": 0.190, "AH": 0.115,
              "IX": 0.070, "ER": 0.190, "AX": 0.080}

# Lip rounding pulls F4/F5 down a little.
_ROUNDED = {"AO", "OQ", "UH", "UW"}


def _formants(symbol, triple):
    f4 = F4 - (180.0 if symbol in _ROUNDED else 0.0)
    f5 = F5 - (260.0 if symbol in _ROUNDED else 0.0)
    return [triple[0], triple[1], triple[2], f4, f5]


PHONES = {}
for _sym, _triple in _VOWEL_SPACE.items():
    PHONES[_sym] = _phone(
        VOWEL, True, _DURATIONS[_sym], 1.0, _formants(_sym, _triple),
        long=_sym in _LONG,
        tense=_sym in {"IY", "EH", "AA", "AO", "UW", "ER"},
        stressable=True,
    )

# Reduced vowels are quieter and shorter; the prosody layer picks AX/IX.
PHONES["AX"]["amp"] = 0.82
PHONES["IX"]["amp"] = 0.72

# --- diphthongs: start and end targets -------------------------------------
_DIPHTHONGS = {
    "AY": (("AA", 0.55), ("IH", 0.45)),   # price
    "EY": (("EH", 0.60), ("IH", 0.40)),   # face
    "OY": (("OQ", 0.55), ("IH", 0.45)),   # choice
    "AW": (("AA", 0.55), ("UH", 0.45)),   # mouth
    "OW": (("AX", 0.45), ("UH", 0.55)),   # goat (RP əʊ)
    "IA": (("IH", 0.55), ("AX", 0.45)),   # near
    "EA": (("EH", 0.55), ("AX", 0.45)),   # square
    "UA": (("UH", 0.55), ("AX", 0.45)),   # cure
}
_DIPH_DUR = {"AY": 0.205, "EY": 0.195, "OY": 0.200, "AW": 0.205, "OW": 0.190,
             "IA": 0.180, "EA": 0.180, "UA": 0.180}
for _sym, ((_a, _ka), (_b, _kb)) in _DIPHTHONGS.items():
    _fa = _formants(_a, _VOWEL_SPACE[_a])
    _fb = _formants(_b, _VOWEL_SPACE[_b])
    PHONES[_sym] = _phone(
        DIPHTHONG, True, _DIPH_DUR.get(_sym, 0.19), 1.0,
        [[_fa[0], _fa[1], _fa[2], _fa[3], _fa[4]],
         [_fb[0], _fb[1], _fb[2], _fb[3], _fb[4]]],
        bw=[list(BW), list(BW)], long=True, stressable=True,
        kernel=[_ka, _kb], glide_from=_a, glide_to=_b,
    )

# --- nasals: low-frequency murmur, tract closed at the lips/tongue ---------
PHONES["M"] = _phone(NASAL, True, 0.075, 0.34, [250.0, 1050.0, 2200.0, 3100.0, 4000.0],
                     bw=list(BW_NARROW), nasal=1.0, place="bilabial")
PHONES["N"] = _phone(NASAL, True, 0.070, 0.34, [270.0, 1700.0, 2600.0, 3300.0, 4200.0],
                     bw=list(BW_NARROW), nasal=1.0, place="alveolar")
PHONES["NG"] = _phone(NASAL, True, 0.080, 0.32, [280.0, 2100.0, 2900.0, 3500.0, 4400.0],
                      bw=list(BW_NARROW), nasal=1.0, place="velar")

# --- approximants and glides ----------------------------------------------
PHONES["L"] = _phone(APPROXIMANT, True, 0.070, 0.58,
                     [350.0, 1100.0, 2650.0, 3400.0, 4300.0], bw=list(BW_NARROW),
                     lateral=True, place="alveolar")
PHONES["R"] = _phone(APPROXIMANT, True, 0.060, 0.52,
                     [330.0, 1150.0, 1600.0, 3300.0, 4200.0], bw=list(BW_NARROW),
                     rhotic=True, place="postalveolar")
PHONES["W"] = _phone(GLIDE, True, 0.055, 0.50,
                     [300.0, 720.0, 2350.0, 3300.0, 4200.0], bw=list(BW_NARROW),
                     rounded=True, place="labial")
PHONES["Y"] = _phone(GLIDE, True, 0.050, 0.48,
                     [280.0, 2200.0, 3000.0, 3500.0, 4400.0], bw=list(BW_NARROW),
                     place="palatal")
PHONES["HH"] = _phone(GLIDE, False, 0.050, 0.30,
                      [500.0, 1500.0, 2500.0, 3400.0, 4300.0], bw=list(BW_WIDE),
                      aspiration=True, place="glottal",
                      noise={"center": 1500.0, "q": 0.7, "amp": 0.30, "tilt": -4.0})

# --- stops: closure + burst + aspiration ----------------------------------
_STOPS = {
    "P": dict(place="bilabial", burst={"center": 900.0, "q": 1.1, "amp": 0.24,
                                       "tilt": -3.0}, dur=0.075, voiced=False),
    "B": dict(place="bilabial", burst={"center": 800.0, "q": 1.0, "amp": 0.20,
                                       "tilt": -3.0}, dur=0.070, voiced=True),
    "T": dict(place="alveolar", burst={"center": 4200.0, "q": 1.4, "amp": 0.30,
                                       "tilt": -1.0}, dur=0.065, voiced=False),
    "D": dict(place="alveolar", burst={"center": 3800.0, "q": 1.3, "amp": 0.24,
                                       "tilt": -1.0}, dur=0.060, voiced=True),
    "K": dict(place="velar", burst={"center": 2100.0, "q": 1.2, "amp": 0.28,
                                    "tilt": -2.0}, dur=0.080, voiced=False),
    "G": dict(place="velar", burst={"center": 1900.0, "q": 1.1, "amp": 0.22,
                                    "tilt": -2.0}, dur=0.075, voiced=True),
}
for _sym, _spec in _STOPS.items():
    _locus = {"bilabial": 800.0, "alveolar": 1750.0, "velar": 2050.0}[_spec["place"]]
    PHONES[_sym] = _phone(
        STOP, _spec["voiced"], _spec["dur"], 0.0,
        [300.0, _locus, 2500.0, 3400.0, 4300.0], bw=list(BW_NARROW),
        place=_spec["place"], burst=_spec["burst"],
        closure=_spec["dur"], aspiration=0.045 if not _spec["voiced"] else 0.008,
        voicing_during_closure=0.20 if _spec["voiced"] else 0.0,
    )

# --- fricatives ------------------------------------------------------------
_FRICATIVES = {
    "F":  dict(center=3200.0, q=0.9, amp=0.22, tilt=-3.0, dur=0.105, voiced=False,
               place="labiodental"),
    "V":  dict(center=2900.0, q=0.9, amp=0.19, tilt=-3.0, dur=0.095, voiced=True,
               place="labiodental"),
    "TH": dict(center=4600.0, q=0.8, amp=0.17, tilt=-2.0, dur=0.100, voiced=False,
               place="dental"),
    "DH": dict(center=4200.0, q=0.8, amp=0.15, tilt=-2.0, dur=0.085, voiced=True,
               place="dental"),
    "S":  dict(center=6200.0, q=1.5, amp=0.34, tilt=0.0, dur=0.125, voiced=False,
               place="alveolar"),
    "Z":  dict(center=6000.0, q=1.5, amp=0.28, tilt=0.0, dur=0.110, voiced=True,
               place="alveolar"),
    "SH": dict(center=2700.0, q=1.3, amp=0.32, tilt=-1.0, dur=0.130, voiced=False,
               place="postalveolar"),
    "ZH": dict(center=2600.0, q=1.3, amp=0.26, tilt=-1.0, dur=0.110, voiced=True,
               place="postalveolar"),
}
for _sym, _spec in _FRICATIVES.items():
    _f2 = {"labiodental": 1400.0, "dental": 1600.0,
           "alveolar": 1800.0, "postalveolar": 1900.0}[_spec["place"]]
    PHONES[_sym] = _phone(
        FRICATIVE, _spec["voiced"], _spec["dur"], _spec["amp"] * 0.55,
        [400.0, _f2, 2600.0, 3400.0, 4300.0], bw=list(BW_WIDE),
        place=_spec["place"],
        noise={"center": _spec["center"], "q": _spec["q"], "amp": _spec["amp"],
               "tilt": _spec["tilt"]},
        sibilant=_spec["center"] > 2400.0,
    )

# --- affricates ------------------------------------------------------------
PHONES["CH"] = _phone(AFFRICATE, False, 0.135, 0.10,
                      [400.0, 1900.0, 2600.0, 3400.0, 4300.0], bw=list(BW_WIDE),
                      place="postalveolar", closure=0.055,
                      burst={"center": 2600.0, "q": 1.3, "amp": 0.26, "tilt": -1.0},
                      noise={"center": 2700.0, "q": 1.3, "amp": 0.30, "tilt": -1.0},
                      aspiration=0.020, sibilant=True)
PHONES["JH"] = _phone(AFFRICATE, True, 0.125, 0.10,
                      [400.0, 1900.0, 2600.0, 3400.0, 4300.0], bw=list(BW_WIDE),
                      place="postalveolar", closure=0.050,
                      burst={"center": 2500.0, "q": 1.3, "amp": 0.20, "tilt": -1.0},
                      noise={"center": 2600.0, "q": 1.3, "amp": 0.24, "tilt": -1.0},
                      aspiration=0.010, sibilant=True)

# --- silence and the villager vocalisation --------------------------------
PHONES["SIL"] = _phone(SILENCE, False, 0.120, 0.0,
                       [400.0, 1500.0, 2500.0, 3400.0, 4300.0], bw=list(BW_WIDE))
PHONES["PAU"] = _phone(SILENCE, False, 0.250, 0.0,
                       [400.0, 1500.0, 2500.0, 3400.0, 4300.0], bw=list(BW_WIDE))
# "HMM": the canonical villager murmur -- voiced, nasal, no consonant release.
PHONES["HMM"] = _phone(VOCALISATION, True, 0.420, 0.55,
                       [420.0, 1050.0, 2350.0, 3200.0, 4100.0], bw=list(BW_NARROW),
                       nasal=0.85, long=True, stressable=True,
                       contour="hmm")

VOICED_MANNERS = {VOWEL, DIPHTHONG, NASAL, APPROXIMANT, GLIDE, VOCALISATION}


def is_voiced(symbol):
    phone = PHONES.get(symbol)
    if phone is None:
        return False
    return bool(phone.get("voiced")) and phone["manner"] != SILENCE


def is_syllabic(symbol):
    phone = PHONES.get(symbol)
    return phone is not None and phone["manner"] in (VOWEL, DIPHTHONG, VOCALISATION)


def is_consonant(symbol):
    phone = PHONES.get(symbol)
    return phone is not None and phone["manner"] in (
        STOP, FRICATIVE, AFFRICATE, NASAL, APPROXIMANT, GLIDE)


def manner(symbol):
    phone = PHONES.get(symbol)
    return phone["manner"] if phone else SILENCE


def intrinsic_duration(symbol):
    phone = PHONES.get(symbol)
    return phone["dur"] if phone else 0.0


def amplitude(symbol):
    phone = PHONES.get(symbol)
    return phone["amp"] if phone else 0.0


def formant_targets(symbol, index=0):
    """Return one formant target set. Diphthongs take ``index`` 0 or 1."""
    phone = PHONES.get(symbol)
    if phone is None:
        return list(_formants("IX", _VOWEL_SPACE["IX"])), list(BW_WIDE)
    target = phone["f"]
    bw = phone["bw"]
    if isinstance(target[0], list):
        target = target[max(0, min(index, len(target) - 1))]
        bw = bw[max(0, min(index, len(bw) - 1))]
    return list(target), list(bw)


def phone_names():
    return sorted(PHONES)


def inventory_report():
    by_manner = {}
    for name, phone in PHONES.items():
        by_manner.setdefault(phone["manner"], []).append(name)
    return {key: sorted(value) for key, value in sorted(by_manner.items())}


def hz_to_mel(hz):
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel):
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


if __name__ == "__main__":
    report = inventory_report()
    total = sum(len(v) for v in report.values())
    print("phones: %d" % total)
    for key, value in report.items():
        print("  %-14s %2d  %s" % (key, len(value), " ".join(value)))
