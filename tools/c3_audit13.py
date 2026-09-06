import re
lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
rx = re.compile(r'world pools ready|autoLoad|autoJoin|MenuScreen\.|loadWorld|continueWorld|hasSave|autosave', re.I)
for i,l in enumerate(lines,1):
    s = l.rstrip()
    if rx.search(s):
        print(i, s[:150])
