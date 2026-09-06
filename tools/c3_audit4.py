import re
lines = open('src/main/java/com/voxel/Main.java', errors='replace').readlines()
rx = re.compile(r'updateVariableExposure\(\)|EXPOSURE_PROBE_INTERVAL\s*=|EXPOSURE_PROBE_SIZE\s*=|dispatchCompute|raytrace|glDispatchCompute|renderFrame|void loop\(|pollEvents|glfwSwapBuffers|frameCounter\+\+')
for i,l in enumerate(lines,1):
    if rx.search(l):
        print(i, l.rstrip()[:150])
