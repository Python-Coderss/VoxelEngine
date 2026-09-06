import re, sys
p = r'src/main/resources/shaders/raytracer.comp'
for i, line in enumerate(open(p, encoding='utf-8', errors='replace'), 1):
    if re.match(r'^(void|bool|float|vec[234]|int|uint|ivec[234]|mat[34]) [A-Za-z_]+\(', line):
        print(i, line.rstrip())
