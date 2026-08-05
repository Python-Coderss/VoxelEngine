import os, subprocess, json, sys
os.environ.setdefault('COQUI_TOS_AGREED', '1')
from TTS.api import TTS

texts = [
 "Please measure the tiny village for a large juicy mango",
 "merchant emerald trade hmm traveler chest furnace bread wheat diamond",
 "It's a nice day, we're having, isn't it?",
 "I'm afraid I can't do that.",
 "Hello, friend! Welcome to our village.",
 "What?! No way!",
 "The quick brown fox jumps over the lazy dog",
 "How much for this lovely apple and that big chest over there",
 "Good day traveler what brings you to our village today",
 "Aha that seems like a deal that will work for both of us",
 "village villager journey treasure ancient magic powerful secret",
 "Through the window the rain fell softly on the old wooden house",
 "Courage courage what is courage when you are afraid",
 "the sound of the sea was soothing",
 "Perhaps we should build a wall around the village",
 "emerald diamonds gold iron and lapis lazuli",
 "one two three four five six seven eight nine ten",
 "Thirty-three thirsty thieves thought they thrilled the throne",
 "Rural juror, curious European, furious university",
 "the first girl had a purple purse",
 "a noisy oil boiler boils oil",
 "Zebra zigzag zany zoo",
 "Measure for pleasure treasure",
 "I went to the zoo and saw a yak and a zebra",
 "You have made a wise choice, friend.",
 "Hmm. Let me think about it.",
 "The weather is lovely today, is it not?",
 "Would you like to trade some wheat for bread?",
 "I cannot believe my eyes, what a fine day.",
 "All the villagers gathered around the square",
 "don't won't can't it's I'm we're you'll they're",
 "Hmm. What do you want, traveler?",
 "Hello there friend what a lovely day for trading",
 "Please make yourself at home",
]
open('dev/voice-comparison/token-probe.txt', 'w', encoding='utf-8').write('\n'.join(texts))

CP = 'target/classes;' + open('target/cp.txt').read().replace('\r','')
subprocess.run(['java', '-cp', 'target/probe-classes;' + CP, 'TokBatch', 'dev/voice-comparison/token-probe.txt', 'dev/voice-comparison/token-probe-java.txt'], check=True)
java = open('dev/voice-comparison/token-probe-java.txt', encoding='utf-8').read().split('\n')

print('loading TTS...', flush=True)
tts = TTS('tts_models/en/vctk/vits', gpu=False)
model = tts.synthesizer.tts_model

bad = 0
for i, t in enumerate(texts):
    jtoks = java[i].split(',') if java[i] and not java[i].startswith('ERR') else None
    ptoks = model.tokenizer.text_to_ids(t, language=None)
    if jtoks is None:
        print(f'MISMATCH[{i}] {t!r} -> JAVA ERROR: {java[i]}')
        bad += 1
        continue
    jl = [int(x) for x in jtoks]
    if jl != ptoks:
        bad += 1
        print(f'MISMATCH[{i}] {t!r}')
        print('  java :', jl)
        print('  coqui:', ptoks)
print('total texts:', len(texts), 'mismatches:', bad)
