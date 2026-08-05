import sys, os, types, numpy as np, torch
sys.path.insert(0, os.path.abspath('../villager_voice/python'))
import villager_line
villager_line._patch_rvc()
import soundfile as sf
from pathlib import Path
import librosa
from scipy import signal as sps
from rvc_python.modules.vc import utils
# replicate pipeline steps manually for hello
x, sr = sf.read('dev/voice-comparison/python-base/hello.wav', dtype='float32')
x16 = librosa.resample(x, orig_sr=sr, target_sr=16000)
am = np.abs(x16).max() / 0.95
if am > 1: x16 = x16 / am
bh = np.array([ 0.9699606451838447,-4.849803225919223,9.699606451838447,-9.699606451838447,4.849803225919223,-0.9699606451838447])
ah = np.array([1.0,-4.939001819168364,9.757863526739543,-9.639544849413458,4.761506797356209,-0.9408236532054606])
x16 = sps.filtfilt(bh, ah, x16)
audio_pad = np.pad(x16, (16000, 16000), mode='reflect')
# load hubert
config = types.SimpleNamespace(device='cpu')
hubert = utils.load_hubert(config, None)
feats = torch.from_numpy(audio_pad).float().view(1, -1)
padmask = torch.BoolTensor(feats.shape).fill_(False)
with torch.no_grad():
    logits = hubert.extract_features(feats, padmask, output_layer=12)
    feats = logits[0]  # [1, frames, 768]
print('hubert out shape', tuple(feats.shape))
# index blend k=8
import faiss
idx = faiss.read_index('../villager_voice/models/Villager-ElementAnimation.index')
big = idx.reconstruct_n(0, idx.ntotal)
npy = feats[0].numpy().astype('float32')
score, ix = idx.search(npy, k=8)
w = np.square(1/score); w /= w.sum(axis=1, keepdims=True)
npy2 = np.sum(big[ix] * np.expand_dims(w, axis=2), axis=1)
feats = torch.from_numpy(npy2).unsqueeze(0) * 0.5 + (1-0.5)*feats
np.array(feats[0].numpy(), dtype='>f4').tofile('dev/voice-comparison/py_feats_blended.f32')
print('blended feats', feats.shape, 'saved')
