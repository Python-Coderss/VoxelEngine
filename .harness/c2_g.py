import io, re, sys
p, pat = sys.argv[1], sys.argv[2]
maxlen = int(sys.argv[3]) if len(sys.argv) > 3 else 130
rx = re.compile(pat)
for i, l in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
    if rx.search(l):
        print(i, l.rstrip()[:maxlen])
