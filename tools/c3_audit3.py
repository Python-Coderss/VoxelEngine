import re
def grep(label, path, pat, ctx=0):
    print('==', label)
    lines = open(path, errors='replace').readlines()
    rx = re.compile(pat)
    for i, line in enumerate(lines, 1):
        if rx.search(line):
            print(i, line.rstrip()[:160])
            for j in range(1, ctx+1):
                if i < len(lines): print('+', lines[i].rstrip()[:160])

grep('uploadToGPU callers', 'src/main/java/com/voxel/Main.java', r'entityManager\.uploadToGPU')
print()
# print updateVariableExposure body
lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
inside = False
brace = 0
for i, l in enumerate(lines, 1):
    if 'updateVariableExposure' in l and ('void' in l or 'private' in l):
        inside = True
        brace = 0
    if inside:
        print(i, l.rstrip()[:170])
        brace += l.count('{') - l.count('}')
        if brace <= 0 and '{' in ''.join(lines[max(0,i-30):i]) and len(l.strip().endswith('}')) :
            pass
        if '}' == l.strip() and brace <= 0:
            inside = False
        if i > 5000: break
