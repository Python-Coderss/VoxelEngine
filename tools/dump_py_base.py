import os, sys, json, re
import numpy as np, soundfile as sf
os.environ.setdefault("COQUI_TOS_AGREED", "1")
from TTS.api import TTS
tts = TTS("tts_models/en/vctk/vits", gpu=False)
sr = getattr(tts.synthesizer, "output_sample_rate", 22050)
outdir = os.path.join("dev", "voice-comparison", "python-base")
os.makedirs(outdir, exist_ok=True)
lines = [("hello", "Hello"), ("i_am_haggling_you", "I am haggling you"), ("no", "No!!!"), ("what_are_you_doing", "What are you doing")]
# Dump tokens for Hello via the Coqui phonemizer
for fid, text in lines:
    wav = np.asarray(tts.tts(text=text, speaker="p226"), dtype=np.float32)
    sf.write(os.path.join(outdir, fid + ".wav"), wav, sr)
    print(fid, len(wav), "samples")
# Phonemize Hello through Coqui's exact frontend
from TTS.tts.utils.text.phonemizers.espeak_wrapper import ESpeak
ph = ESpeak(language="en-us")
print("phonemize(Hello) =", repr(ph.phonemize("Hello", separator="")))
