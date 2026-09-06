import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
t = open(r'.harness\c2_run1.log', encoding='utf-8', errors='replace').read()
print('len', len(t))
print(t[:2500])
print('=== TAIL ===')
print(t[-1500:])
