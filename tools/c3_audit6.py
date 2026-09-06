import re
lines = open('src/main/java/com/voxel/entity/EntityManager.java', errors='replace').readlines()
rx = re.compile(r'void (cleanup|destroy|dispose|free)|getUploadedEntityCount')
for i,l in enumerate(lines,1):
    if rx.search(l): print(i, l.rstrip()[:140])
