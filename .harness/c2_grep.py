import sys, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def scan(p, pat):
    try:
        lines = open(p, encoding='utf-8', errors='replace').read().splitlines()
    except OSError:
        return
    for i, l in enumerate(lines):
        if pat in l:
            print(p.replace('\\', '/'), i + 1, l.strip()[:170])

pat = sys.argv[1]
targets = sys.argv[2:] or ['src/main/java/com/voxel']
for f in targets:
    if os.path.isdir(f):
        for root, _, names in os.walk(f):
            for n in names:
                if n.endswith('.java'):
                    scan(os.path.join(root, n), pat)
    else:
        scan(f, pat)
