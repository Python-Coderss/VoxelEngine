import re
lines = open('src/main/java/com/voxel/entity/EntityManager.java', errors='replace').readlines()
print('total lines:', len(lines))
rx = re.compile(r'entityStage|partStage|partScratch|memAlloc|memFree|writtenCount|partUploadCount')
for i,l in enumerate(lines,1):
    if rx.search(l): print(i, l.rstrip()[:150])
