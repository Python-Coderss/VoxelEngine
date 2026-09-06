import subprocess, os
r = subprocess.run(['tasklist'], capture_output=True, text=True)
lines = [l for l in r.stdout.splitlines() if 'java' in l.lower()]
print('\n'.join(lines) if lines else 'no java processes')
print('---- boot log size:', os.path.getsize(r'tools\c3_final_boot.txt'))
