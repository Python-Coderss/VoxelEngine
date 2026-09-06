import json

p = r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine\src\main\resources\assets\minecraft\models\entity\villager.json'
d = json.load(open(p))
parts = {q['name']: q for q in d['parts']}

def box(name):
    q = parts[name]
    f, s, o = q['from'], q['size'], q.get('absolute_offset', [0, 0, 0])
    return (f[0], f[1], f[2], s[0], s[1], s[2], o)

h = box('head')
n = box('nose')
b = box('body')

# head: min y=24, max y=34
assert abs(h[1] - 24.0) < 1e-6, 'head min.y must be 24 (attached at neck), got %s' % h[1]
assert abs(h[4] - 10.0) < 1e-6
# nose: vanilla-relative to pivot (0,24,0): addBox(-1,-1,-6,2,4,2) -> min y = 24-2-(-1)?? see derivation:
# MC nose spans y in [-3,+1] around pivot(0,24,0) -> engine min.y = 23; z front face +6 from center -> z=+4
assert abs(n[1] - 23.0) < 1e-6 and abs(n[4] - 4.0) < 1e-6, 'nose must stay vanilla-correct'
assert abs(n[2] - 4.0) < 1e-6, 'nose must protrude on +Z (front)'
assert abs(n[6][1] - 24.0) < 1e-6, 'nose pivot must be neck (0,24,0)'
assert abs(h[6][1] - 24.0) < 1e-6, 'head pivot must be neck (0,24,0)'
# body top must touch head bottom
body_top = b[1] + b[4]
head_bottom = h[1]
assert abs(body_top - 24.0) < 1e-6, 'body top should meet the neck at y=24'
assert abs(head_bottom - body_top) < 1e-6, 'HEAD DETACHED: gap between body top and head bottom'

# arms/connector share the crossed-arm pivot and sit flush under the shoulders
for a in ('left_arm', 'right_arm', 'arm_connector'):
    q = parts[a]
    assert abs(q['absolute_offset'][1] - 21.0) < 1e-6, a + ' pivot y should stay 21'
    assert abs(q['from'][1] - 18.0) < 1e-6, a + ' from.y should be 18 (vanilla -2 rel pivot 20->21 with +z shift)'

print('villager model OK: head attached (y %.0f-%.0f), nose front +Z, pivots consistent'
      % (h[1], h[1] + h[4]))
