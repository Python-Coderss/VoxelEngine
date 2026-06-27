#!/usr/bin/env bash
# SAFE subset of HudUI migration. Regex-tolerant: uses VR -ra with capture
# groups to preserve leading whitespace for nested lines (12-space indented
# `for (UILayer layer : uiLayers) layer.render(uiManager);` inside the while,
# and 16-space indented for-loop inside if-blocks).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== A) Compile VoxelMethodBody ==="
(cd tools && javac VoxelMethodBody.java)
echo

echo "=== B) Retarget Main.loop render (capture leading whitespace) ==="
java -cp tools VoxelRefactor -ra src/main/java/com/voxel/Main.java \
    '^([ \t]*)for \(UILayer layer : uiLayers\) layer\.render\(uiManager\);' \
    '${1}for (UILayer layer : hud.uiLayers) layer.render(hud.uiManager);' \
    || echo '  (no-op - already retargeted)'
echo

echo "=== C) Retarget Main.uiManager.begin/end/getUITexture ==="
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java \
    'uiManager.begin()' 'hud.uiManager.begin()' \
    || echo '  (no-op)'
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java \
    'uiManager.end()' 'hud.uiManager.end()' \
    || echo '  (no-op)'
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java \
    'uiManager.getUITexture()' 'hud.uiManager.getUITexture()' \
    || echo '  (no-op)'
echo

echo "=== D) Retarget Main.handleMouseButton iteration (capture ws) ==="
java -cp tools VoxelRefactor -ra src/main/java/com/voxel/Main.java \
    '^([ \t]*)for \(int i = uiLayers\.size\(\) - 1; i >= 0; i--\) \{' \
    '${1}for (int i = hud.uiLayers.size() - 1; i >= 0; i--) {' \
    || echo '  (no-op - already retargeted)'
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java \
    'uiLayers.get(i).handleMouseClick' 'hud.uiLayers.get(i).handleMouseClick' \
    || echo '  (no-op)'
echo

echo "=== E) Delete 3 dead allocations (loose anchor) ==="
sed -i '/public UIManager uiManager;/d' src/main/java/com/voxel/Main.java
sed -i '/public final List<UILayer> uiLayers = /d' src/main/java/com/voxel/Main.java
sed -i '/uiManager = new UIManager(width, height);/d' src/main/java/com/voxel/Main.java
echo "deleted"
echo

echo "=== F) Fix HudUI.java run-together-method formatting ==="
NL=$'\n'
java -cp tools VoxelRefactor -ra src/main/java/com/voxel/ui/HudUI.java \
    '\}\s*public boolean handleClickForLayer\(float mouseX, float mouseY\) \{' \
    "}${NL}    public boolean handleClickForLayer(float mouseX, float mouseY) {" \
    || echo '  (no-op - already formatted)'
echo

echo "=== G) Sanity: leftover bare references in Main ==="
echo "Bare uiLayers. count:"; grep -cE '\buiLayers\.' src/main/java/com/voxel/Main.java || echo 0
echo "Bare uiManager count:"; grep -cE '\buiManager\.begin\(|\buiManager\.end\(|\buiManager\.getUITexture\(|\buiManager = new' src/main/java/com/voxel/Main.java || echo 0
echo "hud.* usage count (uiLayers/uiManager/uiTextureId/fontTextureId/itemName):"
grep -cE '\bhud\.uiLayers|\bhud\.uiManager|\bhud\.uiTextureId|\bhud\.fontTextureId|\bhud\.itemName' src/main/java/com/voxel/Main.java || echo 0
echo

echo "=== H) mvn compile (GATE: must succeed) ==="
./mvnw -q compile 2>&1 | tail -25
RC=$?
if [ "$RC" -ne 0 ]; then
    echo "BUILD FAILED (exit $RC). Stopping."
    exit "$RC"
fi
echo "BUILD OK"
echo

echo "=== I) Final line counts ==="
wc -l src/main/java/com/voxel/Main.java src/main/java/com/voxel/ui/HudUI.java
