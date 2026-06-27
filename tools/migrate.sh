#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."

echo "=== A) Compile VoxelMethodBody ==="
(cd tools && javac VoxelMethodBody.java) && echo "OK compile"
ls -la tools/VoxelMethodBody.class
echo

echo "=== B) Apply VMB: Main.buildInventoryUi ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java 'public void buildInventoryUi\(UILayer layer\)' '        hud.buildInventoryUi(layer)'
echo

echo "=== C) Apply VMB: Main.showSelectedItemName ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java 'private void showSelectedItemName\(\)' '        hud.showSelectedItemName()'
echo

echo "=== D) Retarget Main.loop render ==="
java -cp tools VoxelRefactor -l src/main/java/com/voxel/Main.java '        for (UILayer layer : uiLayers) layer.render(uiManager);' '        for (UILayer layer : hud.uiLayers) layer.render(hud.uiManager);'
echo

echo "=== E) Retarget Main.uiManager.begin/end ==="
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java 'uiManager.begin()' 'hud.uiManager.begin()'
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java 'uiManager.end()' 'hud.uiManager.end()'
echo

echo "=== F) Retarget Main.handleMouseButton iteration ==="
java -cp tools VoxelRefactor -l src/main/java/com/voxel/Main.java '        for (int i = uiLayers.size() - 1; i >= 0; i--) {' '        for (int i = hud.uiLayers.size() - 1; i >= 0; i--) {'
java -cp tools VoxelRefactor -la src/main/java/com/voxel/Main.java 'uiLayers.get(i).handleMouseClick' 'hud.uiLayers.get(i).handleMouseClick'
echo

echo "=== G) Delete dead allocations/fields in Main ==="
sed -i '/^    public UIManager uiManager;$/d' src/main/java/com/voxel/Main.java && echo "del uiManager field"
sed -i '/^    public final List<UILayer> uiLayers = new ArrayList<>();$/d' src/main/java/com/voxel/Main.java && echo "del uiLayers field"
sed -i '/^        uiManager = new UIManager(width, height);$/d' src/main/java/com/voxel/Main.java && echo "del init allocation"
echo

echo "=== H) Fix HudUI.java run-together-method bug ==="
java -cp tools VoxelRefactor -l src/main/java/com/voxel/ui/HudUI.java '}        public boolean handleClickForLayer(float mouseX, float mouseY) {' '}
    public boolean handleClickForLayer(float mouseX, float mouseY) {'
echo

echo "=== I) Sanity: leftover bare references in Main ==="
echo "Bare uiLayers. count:"; grep -cE '\buiLayers\.' src/main/java/com/voxel/Main.java || true
echo "Bare uiManager. begin/end count:"; grep -cE '\buiManager\.begin\(|\buiManager\.end\(' src/main/java/com/voxel/Main.java || true
echo "hud.uiLayers count:"; grep -cE '\bhud\.uiLayers' src/main/java/com/voxel/Main.java || true
echo "hud.uiManager count:"; grep -cE '\bhud\.uiManager' src/main/java/com/voxel/Main.java || true
echo

echo "=== J) Verify body sizes ==="
echo "buildInventoryUi body (should be ~3 lines):"
awk '/public void buildInventoryUi\(UILayer layer\)/{f=1} f{print; if (/^    }$/) exit}' src/main/java/com/voxel/Main.java | wc -l
echo "showSelectedItemName body (should be ~3 lines):"
awk '/private void showSelectedItemName\(\)/{f=1} f{print; if (/^    }$/) exit}' src/main/java/com/voxel/Main.java | wc -l
echo

echo "=== K) mvn compile ==="
./mvnw -q compile 2>&1 | tail -25
RC=$?
echo "mvn exit code: $RC"
echo

echo "=== L) Final line counts ==="
wc -l src/main/java/com/voxel/Main.java src/main/java/com/voxel/ui/HudUI.java tools/VoxelMethodBody.java
