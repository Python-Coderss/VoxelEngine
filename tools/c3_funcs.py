txt = open('src/main/resources/shaders/raytracer.comp', errors='replace').read().splitlines()
import re
for i, line in enumerate(txt, 1):
    if re.search(r'^\w[\w\s\*]*\s+\w+\s*\(|^void main|^\s{0,4}(bool|void|float|int|uint|vec[234]|ivec[234]|uvec[234])\s+\w+\s*\(', line):
        s = line.strip()
        if s.endswith('{') or '(' in s:
            print(i, s[:110])
