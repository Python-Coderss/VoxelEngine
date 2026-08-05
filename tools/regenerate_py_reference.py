import os, json, sys, re
os.environ.setdefault('COQUI_TOS_AGREED', '1')
sys.stdout.reconfigure(encoding='utf-8')
from TTS.api import TTS

tts = TTS('tts_models/en/vctk/vits', gpu=False)
tok = tts.synthesizer.tts_model.tokenizer

def load_keys(path):
    with open(path, encoding='utf-8') as f:
        return list(json.load(f).keys())

all_keys = []
for p in ['dev/voice-comparison/py_tokens.json', 'dev/voice-comparison/py_game_tokens.json']:
    all_keys += load_keys(p)

out = {}
for t in all_keys:
    cleaned = tok.text_cleaner(t)
    ph = tok.phonemizer.phonemize(cleaned, separator='')
    ids = tok.text_to_ids(t, language=None)
    out[t] = {'cleaned': cleaned, 'phonemes': ph, 'ids': ids}

with open('dev/voice-comparison/py_live_full.json', 'w', encoding='utf-8') as f:
    json.dump(out, f, indent=1, ensure_ascii=False)

print('total lines:', len(out))
bad = 0
for t, v in out.items():
    if '??' in v['phonemes'] or 'unknown' in v['phonemes'].lower():
        bad += 1
        print('SUSPECT:', repr(t), repr(v['phonemes']))
print('suspect lines:', bad)
# sanity: ids match existing references?
old = load_keys('dev/voice-comparison/py_tokens.json')
old_ids = json.load(open('dev/voice-comparison/py_tokens.json', encoding='utf-8'))
drift = 0
for t in old:
    if t in out and out[t]['ids'] != old_ids[t]:
        drift += 1
        print('DRIFT vs old ref:', repr(t), out[t]['ids'][:20], 'vs', old_ids[t][:20])
print('drift lines vs old ref:', drift)
