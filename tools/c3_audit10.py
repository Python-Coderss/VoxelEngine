import os, re
# pom exec config
pom = open('pom.xml', errors='replace').read()
m = re.search(r'<mainClass>(.*?)</mainClass>', pom)
print('mainClass:', m.group(1) if m else 'none')
print('has exec plugin:', 'exec-maven-plugin' in pom)
print('has shade:', 'shade' in pom)
print('has application plugin:', 'application' in pom.lower())
# logs present
for f in sorted(os.listdir('.')):
    if f.endswith('.log') or f.endswith('.txt'):
        print('file:', f, os.path.getsize(f))
