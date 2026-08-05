import sys, os, numpy as np
sys.path.insert(0, os.path.abspath('../villager_voice/python'))
import villager_line
villager_line._patch_rvc()
import torch, librosa
from pathlib import Path
from rvc_python.lib.rmvpe import RMVPE
rmvpe = RMVPE('../villager_voice/python/.venv/Lib/site-packages/rvc_python/base_model/rmvpe.pt', False, device='cpu')
out = Path('dev/voice-comparison/f0-py'); out.mkdir(exist_ok=True)
for p in sorted(Path('dev/voice-comparison/python-base').glob('*.wav')):
    import soundfile as sf
    x, sr = sf.read(str(p), dtype='float32')
    x16 = librosa.resample(x, orig_sr=sr, target_sr=16000)
    f0 = rmvpe.infer_from_audio(x16, thred=0.03)
    np.array(f0, dtype=np.float32).astype('>f4').tofile(out / (p.stem + '.f32'))
    print(p.stem, 'frames', len(f0))
