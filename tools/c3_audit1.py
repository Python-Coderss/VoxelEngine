import re
def grep(label, path, pat):
    print('==', label)
    try:
        rx = re.compile(pat)
        for i, line in enumerate(open(path, errors='replace'), 1):
            if rx.search(line):
                print(i, line.rstrip()[:160])
    except Exception as e:
        print('ERR', e)

grep('Main updateVariableExposure', 'src/main/java/com/voxel/Main.java', r'updateVariableExposure')
grep('EntityManager upload', 'src/main/java/com/voxel/entity/EntityManager.java', r'uploadToGPU|class EntityManager|glNamedBuffer|glMap|fence|Fence|sync|Sync')
grep('UIManager begin/end', 'src/main/java/com/voxel/ui/UIManager.java', r'void begin\(|void end\(|getUITexture|rebuild|dirty')
