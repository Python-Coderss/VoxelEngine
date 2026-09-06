import os, sys
files = []
for r, d, f in os.walk('src/main/java'):
    for x in f:
        if x.endswith('.java'):
            p = os.path.join(r, x)
            files.append((os.path.getsize(p), p))
files.sort(reverse=True)
for s, p in files[:14]:
    print(s, p)
