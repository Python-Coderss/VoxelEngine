import subprocess
def sh(*args):
    r = subprocess.run(['git'] + list(args), capture_output=True, text=True,
                       cwd=r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine')
    return (r.stdout + r.stderr).strip()
print('== log =='); print(sh('log', '--oneline', '-5'))
print('== status =='); print(sh('status', '--short'))
print('== comp diff stat vs HEAD =='); print(sh('diff', 'HEAD', '--stat', '--', 'src/main/resources/shaders/raytracer.comp') or '(no diff)')
