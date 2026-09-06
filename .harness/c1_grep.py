import io, sys
p = r'src/main/java/com/voxel/Main.java'
pat = sys.argv[1] if len(sys.argv) > 1 else 'loop|dispatchCompute|renderScale|RenderScale|glViewport'
for i, l in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
    if any(s in l for s in pat.split('|')):
        print(i, l.rstrip())
