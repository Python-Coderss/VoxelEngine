import subprocess, time, os
def sh(*args):
    r = subprocess.run(['git'] + list(args), capture_output=True, text=True,
                       cwd=r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine')
    return r.stdout + r.stderr

# mtime of Main.java = how recent was the last edit
st = os.stat(r'src/main/java/com/voxel/Main.java')
print('Main.java last modified %.1f seconds ago' % (time.time() - st.st_mtime))
st2 = os.stat(r'src/main/resources/shaders/raytracer.comp')
print('raytracer.comp last modified %.1f seconds ago' % (time.time() - st2.st_mtime))
# current DRS section
lines = open(r'src/main/java/com/voxel/Main.java', errors='replace').readlines()
start = None
for i,l in enumerate(lines):
    if 'private void updateDynamicResolution' in l or ('Dynamic resolution' in l and 'Auto mode' in l):
        start = i
for i in range(start, min(start+75, len(lines))):
    print(i+1, lines[i].rstrip())
