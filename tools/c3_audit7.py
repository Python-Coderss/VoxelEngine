lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
start = None
for i,l in enumerate(lines):
    if 'private void updateVariableExposure' in l:
        start = i
        break
for i in range(start, min(start+90, len(lines))):
    print(i+1, lines[i].rstrip()[:160])
