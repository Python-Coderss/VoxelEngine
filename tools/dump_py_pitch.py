import sys, os, types, numpy as np
sys.path.insert(0, os.path.abspath('../villager_voice/python'))
import villager_line
villager_line._patch_rvc()
import soundfile as sf
from pathlib import Path
from rvc_python.lib.rmvpe import RMVPE
rmvpe = RMVPE('../villager_voice/python/.venv/Lib/site-packages/rvc_python/base_model/rmvpe.pt', False, device='cpu')
x, sr = sf.read('dev/voice-comparison/python-base/hello.wav', dtype='float32')
import librosa
x16 = librosa.resample(x, orig_sr=sr, target_sr=16000)
from scipy import signal as sps
x16 = x16 / np.abs(x16).max() * 0.95 if np.abs(x16).max() > 0.95 else x16
# replicate pipeline: high-pass via bh/ah, reflect pad t_pad=16000
bh = np.array([ 0.9699606451838447,-4.849803225919223,9.699606451838447,-9.699606451838447,4.849803225919223,-0.9699606451838447])
ah = np.array([1.0,-4.939001819168364,9.757863526739543,-9.639544849413458,4.761506797356209,-0.9408236532054606])
x16 = sps.filtfilt(bh, ah, x16)
audio_pad = np.pad(x16, (16000, 16000), mode='reflect')
p_len = audio_pad.shape[0] // 160
f0 = rmvpe.infer_from_audio(audio_pad, thred=0.03)
f0 = f0[:p_len]
f0 = f0 * pow(2, 3/12)
# pitch = f0_coarse
f0_mel_min = 1127*np.log(1+50/700); f0_mel_max = 1127*np.log(1+1100/700)
mel = 1127*np.log(1+f0/700)
pitch = ((mel - f0_mel_min)*254/(f0_mel_max-f0_mel_min)+1).astype(int)
pitch[pitch<1]=1; pitch[pitch>255]=255; pitch[f0<=0]=1
print('py pitchf frames=', len(f0), 'voiced=', int((f0>0).sum()))
np.array(f0, dtype='>f4').tofile('dev/voice-comparison/py_pitchf.f32')
np.array(pitch, dtype='>i4').tofile('dev/voice-comparison/py_pitch.i32')
