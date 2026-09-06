s = open(r'src\main\java\com\voxel\Main.java', encoding='utf-8', errors='replace').read().splitlines()
keys = ['renderScaleMode = ', 'SCALE_MODE_VALUES', 'DRS_TARGET_MS', 'DRS_SCALE_STEPS =']
for i, l in enumerate(s):
    if any(k in l for k in keys):
        print(i + 1, l.strip())
