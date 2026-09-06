import subprocess
def sh(*args):
    r = subprocess.run(['git'] + list(args), capture_output=True, text=True,
                       cwd=r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine')
    return r.stdout + r.stderr

print('=== raytracer.comp diff ===')
print(sh('diff', 'HEAD', '--', 'src/main/resources/shaders/raytracer.comp'))
print('=== Main.java diff (exposure hunks only should be mine; flag others) ===')
d = sh('diff', 'HEAD', '--', 'src/main/java/com/voxel/Main.java')
import re
for hunk in d.split('@@'):
    if hunk.strip() and not hunk.startswith(('---','+++')):
        head = hunk[:400].replace('\n', ' | ')
        print('HUNK:', head[:380])
        print('---')
