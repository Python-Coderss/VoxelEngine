import glob, os
for f in sorted(glob.glob('src/main/resources/shaders/*')):
    try:
        print(f, sum(1 for _ in open(f, errors='replace')))
    except Exception as e:
        print(f, 'ERR', e)
