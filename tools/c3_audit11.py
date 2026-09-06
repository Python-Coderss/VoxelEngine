import re
print('== game.log tail ==')
try:
    print(open('game.log', errors='replace').read()[-800:])
except Exception as e:
    print('ERR', e)
for f in ['src/main/java/com/voxel/Main.java']:
    lines = open(f, errors='replace').readlines()
    rx = re.compile(r'\[GPU|\[FPS|PERF_NAMES|perfEnabled|togglePerf|F3|GL_TIME_ELAPSED')
    print('==', f)
    for i,l in enumerate(lines,1):
        if rx.search(l):
            print(i, l.rstrip()[:150])
