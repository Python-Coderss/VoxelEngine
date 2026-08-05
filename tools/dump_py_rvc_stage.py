import sys, os, types, tempfile, numpy as np, soundfile as sf, torch
sys.path.insert(0, os.path.abspath('../villager_voice/python'))
import villager_line
villager_line._patch_rvc()
from rvc_python.infer import RVCInference
from pathlib import Path
MDIR = Path('../villager_voice/models')
rvc = RVCInference(models_dir=str(MDIR), device='cpu:0', model_path=str(MDIR/'Villager-ElementAnimation.pth'), index_path=str(MDIR/'Villager-ElementAnimation.index'), version='v2')
model_info = rvc.models[rvc.current_model]
rvc.protect = 0.5
out = Path('dev/voice-comparison/python-rvc'); out.mkdir(exist_ok=True)
for p in sorted(Path('dev/voice-comparison/python-base').glob('*.wav')):
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as t:
        pass
    with torch.inference_mode():
            res = rvc.vc.vc_single(sid=0, input_audio_path=str(p), f0_up_key=3, f0_method='rmvpe', file_index=model_info.get('index',''), index_rate=0.5, filter_radius=rvc.filter_radius, resample_sr=rvc.resample_sr, rms_mix_rate=rvc.rms_mix_rate, protect=0.5, f0_file='', file_index2='')
    wav = res
    while isinstance(wav, tuple):
        cand = [e for e in wav if isinstance(e, np.ndarray) and e.ndim == 1 and e.size > 100]
        wav = cand[0] if cand else wav[0]
    wav = np.asarray(wav, np.float32)
    np.array(wav, dtype=np.float32).astype('>f4').tofile(out / (p.stem + '.f32'))
    print(p.stem, 'len', len(wav), 'peak_int16', np.abs(wav).max(), 'peak_f', np.abs(wav).max()/32768)
