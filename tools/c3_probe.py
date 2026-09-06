import re, sys
print('lines:', sum(1 for _ in open('src/main/java/com/voxel/Main.java', errors='replace')))
txt = open('src/main/java/com/voxel/Main.java', errors='replace').read()
print('shadow count:', txt.count('shadow'))
print('shadowMapRes count:', txt.count('shadowMapRes'))
for i, line in enumerate(txt.splitlines(), 1):
    if 'shadowMapRes' in line:
        print(i, line.strip()[:150])
