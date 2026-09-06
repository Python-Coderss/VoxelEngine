import os, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
for f in ['knowledge.md', 'collaboration.txt']:
    if os.path.exists(f):
        print('=====', f, '=====')
        txt = open(f, encoding='utf-8', errors='replace').read()
        print(txt[:2500])
    else:
        print('MISSING', f)
