import io, re, sys
p = sys.argv[1] if len(sys.argv) > 1 else r'src\main\resources\shaders\raytracer.comp'
s = io.open(p, encoding='utf-8', errors='replace').read()
print('lines:', s.count('\n') + 1)
funcs = re.findall(r'^\s*(?:void|float|vec[234]|bool|int|uint|mat[34])\s+(\w+)\s*\(', s, re.M)
print('functions:', len(funcs))
print(', '.join(funcs))
