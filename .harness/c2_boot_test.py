import subprocess, time, sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
LOG = r'.harness\c2_run1.log'
if os.path.exists(LOG):
    os.remove(LOG)

env = dict(os.environ)
env['MAVEN_OPTS'] = '-Xms512m -Xmx3g'

proc = subprocess.Popen(['cmd', '/c', 'mvnw.cmd', '-q', 'compile', 'exec:java'],
                        stdout=open(LOG, 'w', encoding='utf-8', errors='replace'),
                        stderr=subprocess.STDOUT,
                        env=env, cwd=os.getcwd())

def tail(n=8000):
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
    if 'shaders ready' in t or '[DRS]' in t:
        print(f'BOOT/RUN markers after {time.time()-t0:.0f}s')
        booted = True
        break
    if proc.poll() is not None:
        print(f'PROCESS EXITED early rc={proc.returncode}')
        break

if booted:
    print('collecting 50s of runtime...')
    time.sleep(50)

print('--- killing tree ---')
try:
    subprocess.run(['taskkill', '/T', '/F', '/PID', str(proc.pid)],
                   capture_output=True, timeout=30)
except Exception as e:
    print('kill failed:', e)

time.sleep(2)
t = tail(40000)
keep = []
for line in t.splitlines():
    if any(k in line for k in ('[BOOT]', '[DRS]', '[GPU/ms]', '[FPS]', 'Exception',
                               'ERROR', 'Render Scale', 'shaders ready')):
        keep.append(line.strip())
print('=== MARKERS (%d) ===' % len(keep))
print('\n'.join(keep[-70:]) if keep else '(no markers found)')
print('=== LAST 12 RAW ===')
print('\n'.join(t.splitlines()[-12:]))
