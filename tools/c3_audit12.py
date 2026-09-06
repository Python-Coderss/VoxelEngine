import re
for f in ['src/main/java/com/voxel/Main.java']:
    lines = open(f, errors='replace').readlines()
    rx = re.compile(r'exposureProbeTex|exposurePbo|currentExposure|LOC_EXPOSURE')
    print('==', f)
    for i,l in enumerate(lines,1):
        if rx.search(l):
            print(i, l.rstrip()[:140])
