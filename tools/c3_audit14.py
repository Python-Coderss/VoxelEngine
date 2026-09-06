import re
lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
inside = False
depth = 0
for i, l in enumerate(lines, 1):
    if 'c1 TEMP PERF PROBE' in l and 'Automated world entry' in l:
        inside = True
    if inside:
        print(i, l.rstrip()[:160])
        depth += l.count('{') - l.count('}')
        if depth <= 0 and i > 2530:
            break
