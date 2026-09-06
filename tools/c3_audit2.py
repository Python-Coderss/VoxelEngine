import re
def grep(label, path, pat):
    print('==', label)
    rx = re.compile(pat)
    try:
        for i, line in enumerate(open(path, errors='replace'), 1):
            if rx.search(line):
                print(i, line.rstrip()[:160])
    except Exception as e:
        print('ERR', e)

grep('Main sync GL calls', 'src/main/java/com/voxel/Main.java', r'glGetTexImage\(|glReadPixels|glFinish\(|glClientWaitSync|glfwSwapBuffers|glGetError|glGetQueryObject')
grep('HudUI per-frame entry', 'src/main/java/com/voxel/ui/HudUI.java', r'void updateInventoryUi|uiManager\.dirty|needsRebuild|rebuild')
grep('EntityManager fields', 'src/main/java/com/voxel/entity/EntityManager.java', r'ENTITY_STRIDE|PART_STRIDE|MAX_ENTITIES|MAX_PARTS|private final List|entities\s*=')
