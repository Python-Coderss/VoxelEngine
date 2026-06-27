#!/usr/bin/env python3
"""
tools/migrate_dead_ui_fields.py
================================
After Batch 1 (UI consolidation), HudUI owns the actual UI element fields and
array slots. Main.java's duplicate declarations are now write-only / unread.

This script deletes the dead declarations:
- crosshairElement, inventoryPanelElement, hotbarActiveElement, carriedItemElement
- slotBackgrounds[], slotItemElements[], slotCountBars[],
  slotCountDigit1[], slotCountDigit2[]  (size INVENTORY_SIZE)
- craftingSlotBackgrounds[], craftingSlotItems[]  (size CRAFTING_SLOTS)
- crafting3x3SlotBackgrounds[], crafting3x3SlotItems[]  (size 9)
- craftingTableBg
- furnacePanelBg, furnaceInputBg/FuelBg/OutputBg,
  furnaceInputItem/FuelItem/OutputItem, furnaceProgressBar, furnaceFuelBar, furnaceFuelText
- chestPanelBg, chestSlotBackgrounds/Items/CountBars/CountDigit1/CountDigit2 (size 20)
- playerHearts[10], heartBases[10]
- commandTextElement, statusTextElement
- uvHeartFull, uvHeartHalf, uvHeartEmpty, uvHeartBase
- inventoryUiDirty, prevInventoryOpenForUi, prevCommandModeForUi,
  prevSelectedSlot, prevHealth

It also removes their comments and blank-line trims.
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'voxel', 'Main.java')


def read(p):
    with open(p, 'r', encoding='utf-8') as f:
        return f.read()


def write(p, c):
    with open(p, 'w', encoding='utf-8') as f:
        f.write(c)


# Lines (regex patterns) to delete. Pattern matches the leading 4-space indent.
DEAD_PATTERNS = [
    r'^    public UILayer\.UIElement crosshairElement;\s*$',
    r'^    public UILayer\.UIElement hotbarActiveElement;\s*$',
    r'^    public UILayer\.UIElement inventoryPanelElement;\s*$',
    r'^    public UILayer\.UIElement carriedItemElement;\s*$',
    r'^    public final UILayer\.UIElement\[\] slotBackgrounds = new UILayer\.UIElement\[INVENTORY_SIZE\];\s*$',
    r'^    public final UILayer\.UIElement\[\] slotItemElements\s+= new UILayer\.UIElement\[INVENTORY_SIZE\];\s*$',
    r'^    public final UILayer\.UIElement\[\] slotCountBars\s+= new UILayer\.UIElement\[INVENTORY_SIZE\];\s*$',
    r'^    public final UILayer\.UIElement\[\] slotCountDigit1\s+= new UILayer\.UIElement\[INVENTORY_SIZE\];\s*$',
    r'^    public final UILayer\.UIElement\[\] slotCountDigit2\s+= new UILayer\.UIElement\[INVENTORY_SIZE\];\s*$',
    r'^    public final UILayer\.UIElement\[\] craftingSlotBackgrounds = new UILayer\.UIElement\[CRAFTING_SLOTS\];\s*$',
    r'^    public final UILayer\.UIElement\[\] craftingSlotItems\s+= new UILayer\.UIElement\[CRAFTING_SLOTS\];\s*$',
    r'^    public final UILayer\.UIElement\[\] crafting3x3SlotBackgrounds = new UILayer\.UIElement\[9\];\s*$',
    r'^    public final UILayer\.UIElement\[\] crafting3x3SlotItems\s+= new UILayer\.UIElement\[9\];\s*$',
    r'^    public UILayer\.UIElement craftingTableBg;\s*$',
    r'^    public UILayer\.UIElement furnacePanelBg;\s*$',
    r'^    public UILayer\.UIElement furnaceInputBg, furnaceFuelBg, furnaceOutputBg;\s*$',
    r'^    public UILayer\.UIElement furnaceInputItem, furnaceFuelItem, furnaceOutputItem;\s*$',
    r'^    public UILayer\.UIElement furnaceProgressBar, furnaceFuelBar;\s*$',
    r'^    public UILayer\.UITextElement furnaceFuelText;\s*$',
    r'^    public UILayer\.UIElement chestPanelBg;\s*$',
    r'^    public final UILayer\.UIElement\[\] chestSlotBackgrounds = new UILayer\.UIElement\[20\];\s*$',
    r'^    public final UILayer\.UIElement\[\] chestSlotItems\s+= new UILayer\.UIElement\[20\];\s*$',
    r'^    public final UILayer\.UIElement\[\] chestCountBars\s+= new UILayer\.UIElement\[20\];\s*$',
    r'^    public final UILayer\.UIElement\[\] chestCountDigit1\s+= new UILayer\.UIElement\[20\];\s*$',
    r'^    public final UILayer\.UIElement\[\] chestCountDigit2\s+= new UILayer\.UIElement\[20\];\s*$',
    r'^    public final UILayer\.UIElement\[\] playerHearts = new UILayer\.UIElement\[10\];\s*$',
    r'^    public final UILayer\.UIElement\[\] heartBases\s+= new UILayer\.UIElement\[10\];\s*$',
    r'^    public UILayer\.UITextElement commandTextElement;\s*$',
    r'^    public UILayer\.UITextElement statusTextElement;\s*$',
    r'^    public boolean inventoryUiDirty = true; // Skip updateInventoryUi when nothing changed\s*$',
    r'^    public boolean prevInventoryOpenForUi = false;\s*$',
    r'^    public boolean prevCommandModeForUi = false;\s*$',
    r'^    public int prevSelectedSlot = -1;\s*$',
    r'^    public float prevHealth = -1;\s*$',
    r'^    public Vector4f uvHeartFull = new Vector4f\(99, 2, 7, 7\);\s*$',
    r'^    public Vector4f uvHeartHalf = new Vector4f\(108, 2, 7, 7\);\s*$',
    r'^    public Vector4f uvHeartEmpty = new Vector4f\(90, 2, 7, 7\);\s*$',
    r'^    public Vector4f uvHeartBase = new Vector4f\(62, 1, 9, 9\);\s*$',
]

# Comment-only lines or section markers to also remove:
SECTION_COMMENTS = [
    r'^    // --- Furnace UI elements ---\s*$',
    r'^    // --- Chest UI elements ---\s*$',
    r'^    // State tracking for skipping redundant UI updates\s*$',
    r'^    // Crafting grid \(4 input slots in 2x2 \+ 1 result slot\)\s*$',
    r'^    // MCSM-style 3x3 crafting table grid \(10 slots: 9 input \+ 1 result\)\s*$',
    # (hud.X moved to HudUI) marker comments are already lines removal list
    # above. But sometimes they appear as comments OUTSIDE the field block:
    r'^    // \(hud\.itemNameElement moved to HudUI\)\s*$',
    r'^    // \(hud\.itemNameDisplayUntil moved to HudUI\)\s*$',
    r'^    // \(hud\.lastTitleUpdate moved to HudUI\)\s*$',
    # Also the marker for UI fields that raw-commented out:
    r'^    // \(hud\.uiTextureId, hud\.uiTextureSize, hud\.fontTextureId, hud\.fontTextureSize moved to HudUI\)\s*$',
]

ALL_PATTERNS = DEAD_PATTERNS + SECTION_COMMENTS
all_re = re.compile('|'.join(ALL_PATTERNS))


def main():
    src = read(MAIN)
    lines = src.split('\n')
    out_lines = []
    deleted = 0
    for ln in lines:
        if all_re.match(ln):
            deleted += 1
            continue
        out_lines.append(ln)
    # Squeeze 3+ consecutive blank lines in the field block area down to 1.
    new_src = '\n'.join(out_lines)
    new_src = re.sub(r'\n\n\n+', '\n\n', new_src)
    write(MAIN, new_src)
    print(f'Deleted {deleted} lines from Main.java')
    # Report new line count
    with open(MAIN) as f:
        print(f'New Main.java line count: {sum(1 for _ in f)}')


if __name__ == '__main__':
    main()
