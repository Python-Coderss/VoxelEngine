"""Villager voice: analysis-synthesis engine (no neural models, no network).

The villagers' voice is *recreated*, not cloned. Two classic DSP halves:

1. ``analyze`` measures a **voiceprint** from reference recordings -- F0
   statistics, LPC formant positions/bandwidths, breathiness (HNR), jitter,
   shimmer, spectral tilt -- and writes it as JSON.
2. ``synth`` renders speech from a voiceprint plus a performance score, using a
   source-filter model (Rosenberg/LF-style glottal source -> cascade formant
   resonators -> radiation). ``transform`` re-voices existing recordings with
   PSOLA pitch/time scaling and LPC envelope remoulding.

Everything is driven by JSON. The voice changer takes a JSON file where a
conventional voice changer would take a microphone: the score *is* the
performance signal.

Pure standard library -- numpy is used opportunistically by :mod:`analyze` when
it happens to be importable, but is never required.
"""

from .voiceprint import VoicePrint, VOICEPRINT_FORMAT, VOICEPRINT_VERSION
from .wavio import read_wav, write_wav

__all__ = [
    "VoicePrint",
    "VOICEPRINT_FORMAT",
    "VOICEPRINT_VERSION",
    "read_wav",
    "write_wav",
]

FORMAT_VERSION = 1
