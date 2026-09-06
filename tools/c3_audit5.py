import re
lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
print('--- imports')
for i,l in enumerate(lines[:120],1):
    if 'import' in l: print(i, l.rstrip())
print('--- exposure fields')
for i,l in enumerate(lines[190:215],191):
    print(i, l.rstrip())
