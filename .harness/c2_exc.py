import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
t = open(r'.harness\c2_run1.log', encoding='utf-8', errors='replace').read()
# print everything between the last DRS line and the maven [ERROR] block
a = t.rfind('[DRS]')
b = t.find('[ERROR] Failed to execute goal', a)
print(repr(t[a:b]))
