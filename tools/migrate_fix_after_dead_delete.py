#!/usr/bin/env python3
"""
tools/migrate_fix_after_dead_delete.py
======================================
After migrate_dead_ui_fields.py deletes Main's duplicate UI fields, leftover
writes and reads in Main that referenced them must be re-routed through
hud. This script rewrites the bare references to use the hud instance which
now owns those fields.

Targets:
- inventoryUiDirty (5+ write sites, 0 read sites after VMB shrink)
- uvHeartFull/Half/Empty/Base (1 reference in Main.loop)
- equipState flags also possibly referenced (defensive: only fix what's
  actually present in Main.java source after the dead-field deletion).
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'voxel', 'Main.java')


def main():
    src = open(MAIN, encoding='utf-8').read()

    # 1. Rewrite ALL bare writes of `inventoryUiDirty = true;` to `hud.inventoryUiDirty = true;`.
    #    These appear in Main.toggleInventory/openCommandMode/cancelCommandMode/handleMouseButton/setInventoryOpen.
    #    The semicolon terminates the write so we capture exactly the assignment.
    new_src, n = re.subn(
        r'(?<![\w.])inventoryUiDirty\s*=\s*true;',
        'hud.inventoryUiDirty = true;',
        src
    )
    print(f'Rewrote inventoryUiDirty = true -> hud.inventoryUiDirty = true: {n} site(s)')

    # 2. Rewrite bare uvHeartFull/Half/Empty/Base references in Main to hud.uvHeartXxx.
    #    (Main.uVHeart... fields were deleted; log only `hud.*`)
    for fld in ['uvHeartFull', 'uvHeartHalf', 'uvHeartEmpty', 'uvHeartBase']:
        pat = r'(?<![\w.])' + fld + r'(?![\w])'
        new_src, m = re.subn(pat, 'hud.' + fld, new_src)
        print(f'Rewrote {fld} -> hud.{fld}: {m} site(s)')

    if new_src != src:
        with open(MAIN, 'w', encoding='utf-8') as f:
            f.write(new_src)
        print('Main.java written.')


if __name__ == '__main__':
    main()
