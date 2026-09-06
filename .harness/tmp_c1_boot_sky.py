"""c1 sky-view boot test: auto-enter tutorial + pitch-lock, capture in-world GPU perf."""
import subprocess, time, sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
LOG = r'.harness\tmp_c1_run_sky.log'
if os.path.exists(LOG):
    os.remove(LOG)

env = dict(os.environ)
env['MAVEN_OPTS'] = '-Xms512m -Xmx3g'
env['VOXEL_AUTO_TUTORIAL'] = '1'
env['VOXEL_PROBE_SKY'] = '1'

proc = subprocess.Popen(['cmd', '/c', 'mvnw.cmd', '-q', 'compile', 'exec:java'],
                        stdout=open(LOG, 'w', encoding='utf-8', errors='replace'),
                        stderr=subprocess.STDOUT,
                        env=env, cwd=os.getcwd())

def tail(n=20000):
    try:
        with open(LOG, 'r', encoding='utf-8', errors='replace') as f:
            return f.read()[-n:]
    except OSError:
        return ''

booted = False
t0 = time.time()
while time.time() - t0 < 180:
    time.sleep(3)
    t = tail()
    if 'shaders ready' in t:
        print(f'[BOOT] shaders ready after {time.time()-t0:.0f}s')
        booted = True
        break
    if proc.poll() is not None:
        print(f'PROCESS EXITED early rc={proc.returncode}')
        break

if booted:
    print('collecting 100s of in-world SKY-VIEW runtime...')
    time.sleep(100)

print('--- killing tree ---')
try:
    subprocess.run(['taskkill', '/T', '/F', '/PID', str(proc.pid)],
                   capture_output=True, timeout=30)
except Exception as e:
    print('kill failed:', e)

time.sleep(2)
raw = open(LOG, 'r', encoding='utf-8', errors='replace').read()
lines = raw.splitlines()
gpu = [l.strip() for l in lines if '[GPU/ms]' in l]
vals = []
for g in gpu:
    try:
        vals.append(float(g.split('raytrace=')[1].split('(')[0]))
    except Exception:
        pass
vals_ss = vals[5:]  # drop first seconds (world-gen churn)
vals_ss.sort()
n = len(vals_ss)
med = vals_ss[n // 2] if n else -1
p90 = vals_ss[int(n * 0.9)] if n else -1
print(f'=== PROBE / BOOT ===')
for l in lines:
    if ('[c1 PROBE]' in l or 'Welcome back' in l or 'Exception' in l or 'ERROR' in l):
        print(l.strip())
print(f'=== GPU PERF ({len(gpu)} lines) raytrace ms ===')
print(f'first5={vals[:5]}')
print(f'steady: n={n} min={vals_ss[0] if n else -1:.2f} med={med:.2f} p90={p90:.2f} max={vals_ss[-1] if n else -1:.2f}')
