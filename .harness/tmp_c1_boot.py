"""c1 boot test v2: auto-enter Tutorial World via VOXEL_AUTO_TUTORIAL=1, collect in-world GPU perf."""
import subprocess, time, sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
LOG = r'.harness\tmp_c1_run.log'
if os.path.exists(LOG):
    os.remove(LOG)

env = dict(os.environ)
env['MAVEN_OPTS'] = '-Xms512m -Xmx3g'
env['VOXEL_AUTO_TUTORIAL'] = '1'

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
    # menu->tutorial entry at t+3s, world init + chunk stream needs time before
    # steady-state GPU numbers mean anything. Collect 110s.
    print('collecting 110s of in-world runtime...')
    time.sleep(110)

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
print(f'=== PROBE / BOOT ===')
for l in lines:
    if ('[c1 PROBE]' in l or l.startswith('[BOOT]') or 'Welcome back' in l
            or 'Exception' in l or 'ERROR' in l):
        print(l.strip())
print(f'=== GPU PERF LINES ({len(gpu)}) ===')
for g in gpu:
    print(g)
if not gpu:
    print('(no [GPU/ms] lines)')
