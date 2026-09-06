import json

p = r'C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine\src\main\resources\assets\minecraft\models\entity\zombie_villager.json'
d = json.load(open(p))
for q in d['parts']:
    if q['name'] in ('head', 'nose', 'body', 'torso'):
        print(q['name'], q['from'], q.get('absolute_offset'))
