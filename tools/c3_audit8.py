lines = open('src/main/java/com/voxel/entity/EntityManager.java', errors='replace').readlines()
for i in range(145, 200):
    print(i+1, lines[i].rstrip()[:160])
