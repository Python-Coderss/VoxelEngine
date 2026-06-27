#!/usr/bin/env python3
"""
tools/migrate_ui_fixup.py
=========================
Targeted post-fix for HudUI.java after migrate_ui_inline.py + migrate_ui_shrink.sh.

Fixes:
1. Add `import com.voxel.game.CraftingManager;` to HudUI.java imports.
   The inlined body from Main.updateInventoryUi references
   `CraftingManager.CraftingRecipe match = ctx.craftingManager.matchRecipe(...)`
   which needs the type imported.

2. Substitute bare `height` -> `main.height` and `width` -> `main.width`
   WITHIN the inlined-body region only (between the marker comment
   `// Slot/UI updates (inlined from Main.updateInventoryUi)` and the next
   method-closing `    }` at column 0). HudUI.setup() takes `width`/`height`
   as parameters, so we must not touch the parameters — scope the rewrite.

We also substitute any remaining bare Main-only fields/leaky identifiers with
main. prefix:
   - lastMeasuredFps (used in HudUI.updateWindowTitle — already used as ctx.lastMeasuredFps)
   - Any other field that the inlined body needed but missed
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUD = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'voxel', 'ui', 'HudUI.java')


def read(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        return f.write(content)


def main():
    hud = read(HUD)

    # --- Fix 1: ensure CraftingManager import is present ---
    if 'import com.voxel.game.CraftingManager;' not in hud:
        # Insert after `import com.voxel.game.FurnaceManager;` for tidiness.
        hud = hud.replace(
            'import com.voxel.game.FurnaceManager;',
            'import com.voxel.game.FurnaceManager;\nimport com.voxel.game.CraftingManager;'
        )
        print('Added: import com.voxel.game.CraftingManager;')
    else:
        print('Skipped (already present): import com.voxel.game.CraftingManager;')

    # --- Fix 2: scope `height`/`width` substitutions to inlined-body region ---
    marker = '// Slot/UI updates (inlined from Main.updateInventoryUi)'
    pos = hud.find(marker)
    if pos < 0:
        print('WARNING: inlined-body marker not found; skipping inlined-body scope fix.')
        print('   (maybe the inlined body changed shape; verify HudUI source manually.)')
        write(HUD, hud)
        return

    # Find the end of UpdateInventoryUi method: next 4-space-indented `}` after pos.
    m = re.search(r'^    \}\s*(\n|$)', hud[pos:], re.MULTILINE)
    if not m:
        print('ERROR: could not find end of updateInventoryUi after marker.')
        sys.exit(1)
    end = pos + m.start()
    pre  = hud[:pos]
    sect = hud[pos:end]
    post = hud[end:]

    print(f'Inlined-body region: pos={pos}..end={end} ({end - pos} chars)')

    # Substitute bare identifiers in this region only.
    # Use word-boundary lookarounds.
    # Order: do main-only fields first, then anything else.
    section_subs = [
        # Field names living on Main, not on HudUI
        ('height', 'main.height'),
        ('width', 'main.width'),
    ]
    for pat, repl in section_subs:
        sect = re.sub(r'(?<![\w.])' + pat + r'(?![\w])', repl, sect)
        print(f'  applied: {pat} -> {repl}')

    hud = pre + sect + post

    write(HUD, hud)
    print('HudUI.java written.')


if __name__ == '__main__':
    main()
