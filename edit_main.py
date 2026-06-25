import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))
path = 'src/main/java/com/voxel/Main.java'

with open(path, 'rb') as f:
    content = f.read()

changes = 0

# 1. Update toggleCameraMode to cycle through all CameraMode values
old_toggle = b'\r\n    private void toggleCameraMode() {\r\n        cameraMode = cameraMode == CameraMode.FIRST_PERSON ? CameraMode.THIRD_PERSON_FOLLOW : CameraMode.FIRST_PERSON;\r\n        ctx.cameraMode = cameraMode;\r\n        setStatus("Camera: " + (cameraMode == CameraMode.FIRST_PERSON ? "first person" : "third person"));\r\n    }'
new_toggle = b'\r\n    private void toggleCameraMode() {\r\n        CameraMode[] modes = CameraMode.values();\r\n        int currentOrdinal = cameraMode.ordinal();\r\n        int nextOrdinal = (currentOrdinal + 1) % modes.length;\r\n        cameraMode = modes[nextOrdinal];\r\n        ctx.cameraMode = cameraMode;\r\n        setStatus("Camera: " + cameraMode.name().toLowerCase().replace(\'_\', \' \'));\r\n    }'

if old_toggle in content:
    content = content.replace(old_toggle, new_toggle)
    print('OK: toggleCameraMode updated')
    changes += 1
else:
    print('FAIL: toggleCameraMode not found')
    # Debug: find nearby content
    idx = content.find(b'toggleCameraMode')
    if idx >= 0:
        print(repr(content[idx-10:idx+300]))

# 2. Add cutsceneManager.update() before the chunkManager.update call at END of tick()
# Find the LAST occurrence (the one at the end of tick(), not in init())
old_tick = b'\r\n        chunkManager.update(player.getPosition(), yaw);\r\n    }\r\n\r\n    private void handleInput'
new_tick = b'\r\n        // Update cutscene manager for cinematic camera shots\r\n        Vector3f pPosCM = player.getPosition();\r\n        Vector3f pLookTargetCM = new Vector3f(pPosCM).add(getLookDirection().mul(10.0f));\r\n        ctx.cutsceneManager.update(dt, pPosCM, pLookTargetCM, combatMode);\r\n\r\n        chunkManager.update(player.getPosition(), yaw);\r\n    }\r\n\r\n    private void handleInput'

if old_tick in content:
    content = content.replace(old_tick, new_tick)
    print('OK: cutsceneManager.update() added in tick()')
    changes += 1
else:
    print('FAIL: tick() chunkManager.update not found')
    idx = content.find(b'chunkManager.update(player.getPosition(), yaw)')
    if idx >= 0:
        print(f'Found at offset {idx}')
        print(repr(content[idx:idx+150]))

# 3. Update getActiveCameraPosition to check cutsceneManager
old_cam = b'\r\n        if (cameraMode == CameraMode.FIRST_PERSON) {\r\n            return eye;\r\n        }\r\n\r\n        // Story Mode Style: Over-the-shoulder with slight offset'
new_cam = b'\r\n        // Cutscene manager active: use its computed position\r\n        if (ctx.cutsceneManager.isActive()) {\r\n            return new Vector3f(ctx.cutsceneManager.getCameraPosition());\r\n        }\r\n\r\n        if (cameraMode == CameraMode.FIRST_PERSON) {\r\n            return eye;\r\n        }\r\n\r\n        // Story Mode Style: Over-the-shoulder with slight offset'

if old_cam in content:
    content = content.replace(old_cam, new_cam)
    print('OK: getActiveCameraPosition cutscene check added')
    changes += 1
else:
    print('FAIL: getActiveCameraPosition not found')
    idx = content.find(b'if (cameraMode == CameraMode.FIRST_PERSON)')
    if idx >= 0:
        print(f'Found at offset {idx}')
        print(repr(content[idx:idx+400]))

with open(path, 'wb') as f:
    f.write(content)

print(f'\nTotal changes: {changes}')
