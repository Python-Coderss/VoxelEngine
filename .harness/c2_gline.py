import io, re, sys
p, names = sys.argv[1], sys.argv[2]
maxlen = int(sys.argv[3]) if len(sys.argv) > 3 else 120
pat = re.compile(r'^\s*(?:void|float|vec[234]|bool|int|uint|mat[34])\s+(?:' + '|'.join(n.strip() for n in names.split(',')) + r')\s*\(')
for i, l in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
    if pat.search(l):
        print(i, l.rstrip()[:maxlen])
