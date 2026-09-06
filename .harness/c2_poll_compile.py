import subprocess, time, sys, os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
env = dict(os.environ)

for attempt in range(12):
    r = subprocess.run(['cmd', '/c', 'mvnw.cmd', '-q', 'compile'],
                       capture_output=True, env=env)
    out = (r.stdout + b'\n' + r.stderr).decode('utf-8', errors='replace')
    if 'writtenCount is already defined' in out:
        print(f'attempt {attempt}: still broken (EntityManager writtenCount)')
    elif r.returncode == 0:
        print(f'attempt {attempt}: COMPILE OK')
        sys.exit(0)
    else:
        print(f'attempt {attempt}: other failure rc={r.returncode}')
        print(out[-1200:])
        sys.exit(2)
    time.sleep(25)
print('gave up waiting for compile fix')
sys.exit(3)
