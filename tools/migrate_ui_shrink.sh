#!/usr/bin/env bash
# Shrink Main.updateInventoryUi / handleFurnaceSlotClick / handleChestSlotClick
# to 1-line delegates routing through `hud`. After this script:
#   Main.updateInventoryUi()          -> { hud.updateInventoryUi(); }
#   Main.handleFurnaceSlotClick(slot) -> { hud.handleFurnaceSlotClick(slot); }
#   Main.handleChestSlotClick(slot)   -> { hud.handleChestSlotClick(slot); }
# The bodies previously in Main's copies are now live inside HudUI (inlined via
# tools/migrate_ui_inline.py earlier in this migration). This completes the UI
# consolidation.
set -uo pipefail
cd "$(dirname "$0")/.."

echo "=== A) Compile VoxelMethodBody (already done but cheap to recheck) ==="
(cd tools && javac VoxelMethodBody.java)

echo
echo "=== B) VMB -r Main.updateInventoryUi  -> { hud.updateInventoryUi(); } ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java \
    'public void updateInventoryUi\(\)' 'hud.updateInventoryUi()'

echo
echo "=== C) VMB -r Main.handleFurnaceSlotClick -> { hud.handleFurnaceSlotClick(...); } ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java \
    'public void handleFurnaceSlotClick\(int slot\)' 'hud.handleFurnaceSlotClick(slot)'

echo
echo "=== D) VMB -r Main.handleChestSlotClick -> { hud.handleChestSlotClick(...); } ==="
java -cp tools VoxelMethodBody -r src/main/java/com/voxel/Main.java \
    'public void handleChestSlotClick\(int slot\)' 'hud.handleChestSlotClick(slot)'

echo
echo "=== E) Final line counts ==="
wc -l src/main/java/com/voxel/Main.java src/main/java/com/voxel/ui/HudUI.java

echo
echo "=== F) mvn compile gate ==="
./mvnw -q compile 2>&1 | tail -30
