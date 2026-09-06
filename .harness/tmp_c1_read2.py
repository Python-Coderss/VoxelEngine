import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
for f in ['.agents/state.md', '.agents/tasks.md', '.agents/changes.md', '.agents/decisions.md', '.agents/blockers.md', '.agents/coordination.md']:
    print('=====', f, '=====')
    try:
        txt = open(f, encoding='utf-8', errors='replace').read()
        print(txt[-3500:])
    except Exception as e:
        print('ERR', e)
