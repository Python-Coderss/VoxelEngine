"""t6 verification harness: compile -> boot -> kill -> parse [BOOT]/[DRS]/[GPU/ms].
Usage: python c3_verify.py [seconds] [mode]
  mode 'ground' (default): auto-enter Tutorial World (VOXEL_AUTO_TUTORIAL=1)
  mode 'sky': same + pinned upward gaze (VOXEL_PROBE_SKY=1)
  mode 'menu': no env vars (main-menu panorama only)"""
import subprocess, time, re, statistics, sys, os

DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 75
MODE = sys.argv[2] if len(sys.argv) > 2 else 'ground'
ROOT = r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine'
LOG = ROOT + r'\tools\c3_final_boot.txt'
OUT = ROOT + r'\tools\c3_perf_summary.txt'

env = os.environ.copy()
env.pop('VOXEL_AUTO_TUTORIAL', None)
env.pop('VOXEL_PROBE_SKY', None)
if MODE == 'ground':
    env['VOXEL_AUTO_TUTORIAL'] = '1'
elif MODE == 'sky':
    env['VOXEL_AUTO_TUTORIAL'] = '1'
    env['VOXEL_PROBE_SKY'] = '1'

rep = []
def say(s):
    print(s)
    rep.append(s)

# 1) compile
say('== compile ==')
r = subprocess.run('mvnw.cmd -q compile', cwd=ROOT, shell=True,
                   capture_output=True, text=True, timeout=600)
say('compile exit=%d' % r.returncode)
if r.returncode != 0:
    say(r.stdout[-3000:]); say(r.stderr[-3000:])
    open(OUT, 'w').write('\n'.join(rep)); sys.exit(1)

# 2) boot
say('== boot %ds mode=%s ==' % (DURATION, MODE))
logf = open(LOG, 'w')
p = subprocess.Popen(['cmd', '/c', 'mvnw.cmd', '-q', 'exec:java'],
                     cwd=ROOT, stdout=logf, stderr=subprocess.STDOUT,
                     env=env)
time.sleep(DURATION)
subprocess.run(['taskkill', '/T', '/F', '/PID', str(p.pid)],
               capture_output=True)
logf.close()
time.sleep(1)

# 3) parse
say('== parse %s ==' % LOG)
txt = open(LOG, errors='replace').read()
for line in txt.splitlines():
    if line.startswith('[BOOT]'):
        say(line.strip())

gpu = {'pools': [], 'raytrace': [], 'present': []}
drs = []
rx_gpu = re.compile(r'\[GPU/ms\] pools=([\d.]+)\((\d+)\) raytrace=([\d.]+)\((\d+)\) present=([\d.]+)\((\d+)\)')
rx_drs = re.compile(r'\[DRS\] scale=(\d+)% \(\d+x\d+\) ema=([\d.]+)ms fps=(\d+) (up|down)')
for line in txt.splitlines():
    m = rx_gpu.search(line)
    if m:
        gpu['pools'].append(float(m.group(1)))
        gpu['raytrace'].append(float(m.group(3)))
        gpu['present'].append(float(m.group(5)))
        continue
    m = rx_drs.search(line)
    if m:
        drs.append((int(m.group(1)), float(m.group(2)), int(m.group(3)), m.group(4)))

def stats(v):
    if not v:
        return 'n/a'
    v2 = sorted(v)
    return ('n=%d min=%.2f med=%.2f p90=%.2f max=%.2f' %
            (len(v), v2[0], statistics.median(v2),
             v2[int(len(v2)*0.9)-1] if len(v2) > 1 else v2[0], v2[-1]))

for k in ('pools', 'raytrace', 'present'):
    say('[GPU/ms] %-8s %s' % (k, stats(gpu[k])))

if drs:
    scales = sorted(set(s for s, _, _, _ in drs))
    fps_all = [f for _, _, f, _ in drs]
    say('[DRS] events=%d scales_seen=%s fps(min/med/max)=%d/%d/%d' %
        (len(drs), scales, min(fps_all), statistics.median(fps_all), max(fps_all)))
    last10 = drs[-10:]
    say('[DRS] tail: ' + ' | '.join('%d%% ema=%.1f fps=%d %s' % e for e in last10))

sky_like = [v for v in gpu['raytrace'] if v < 12]
gnd_like = [v for v in gpu['raytrace'] if v >= 12]
say('raytrace split: sky-like(<12ms) n=%d med=%.2f | ground-like(>=12ms) n=%d med=%.2f' %
    (len(sky_like), statistics.median(sky_like) if sky_like else -1,
     len(gnd_like), statistics.median(gnd_like) if gnd_like else -1))

open(OUT, 'w').write('\n'.join(rep))
say('summary written to tools/c3_perf_summary.txt')
