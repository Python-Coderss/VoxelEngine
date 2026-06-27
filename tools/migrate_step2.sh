#!/usr/bin/env bash
# Step 2 of the HudUI migration: shrink duplicated Main method bodies down to
# 1-line delegates via VoxelMethodBody. This delivers the visible line-count
# shrink the user asked for. Two methods targeted:
#   - Main.buildInventoryUi (~280-line body)  ->  hud.buildInventoryUi(layer);
#   - Main.showSelectedItemName (~17-line body) ->  hud.showSelectedItemName();
# SAFE to leave Main.updateInventoryUi (~480-line body) untouched this turn.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== A) Shrink Main.buildInventoryUi body (~280 lines) ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java \
    'public void buildInventoryUi\(UILayer layer\)' \
    '        hud.buildInventoryUi(layer)' \
    || echo '  (no-op)'
echo

echo "=== B) Shrink Main.showSelectedItemName body (~17 lines) ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java \
    'private void showSelectedItemName\(\)' \
    '        hud.showSelectedItemName()' \
    || echo '  (no-op)'
echo

echo "=== C) Sanity: shrunk methods should now be ~3 lines ==="
echo "buildInventoryUi body lines:"
awk '/public void buildInventoryUi\(UILayer layer\)/{f=1} f{print; if (/^    }$/) exit}' src/main/java/com/voxel/Main.java | wc -l
echo "showSelectedItemName body lines:"
awk '/private void showSelectedItemName\(\)/{f=1} f{print; if (/^    }$/) exit}' src/main/java/com/voxel/Main.java | wc -l
echo

echo "=== D) mvn compile (GATE) ==="
./mvnw -q compile 2>&1 | tail -15
RC=$?
if [ "$RC" -ne 0 ]; then echo "BUILD FAILED (exit $RC)"; exit "$RC"; fi
echo "BUILD OK"
echo

echo "=== E) Final line counts ==="
echo "Before this turn Main.java was ~3087 lines, HudUI.java was 597 lines"
wc -l src/main/java/com/voxel/Main.java src/main/java/com/voxel/ui/HudUI.java
