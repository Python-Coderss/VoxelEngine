import re, sys
pat = re.compile(sys.argv[1])
path = sys.argv[2]
for i, line in enumerate(open(path, errors='replace'), 1):
    if pat.search(line):
        print(f"{i}: {line.rstrip()[:200]}")
