#!/usr/bin/env python3
"""
Tools/migrate_ui_inline.py
==========================
BATCH 1 (A+B): Inline Main.updateInventoryUi + Main.handleFurnaceSlotClick +
Main.handleChestSlotClick into HudUI.java, applying field substitutions.

After this script:
- HudUI.updateInventoryUi contains the full UI update body (frame-by-frame).
- HudUI.handleFurnaceSlotClick / HudUI.handleChestSlotClick contain the actual
  pick-up / place / stack logic instead of delegating to main.X.
- Main.updateInventoryUi / Main.handleFurnaceSlotClick / Main.handleChestSlotClick
  remain as 1-line delegates (VoxelMethodBody -r shrinks them in the next step).

Field substitution rules (when inlining Main method body into HudUI class):
- HudUI already has private fields for: ctx, main, camera, playerInventory,
  textureManager, itemDefinitions, biomeManager. So bare X access resolves
  to HudUI's own field. No substitution needed for these.
- HudUI also has its own duplicate UI element fields (slotBackgrounds[i],
  furnacePanelBg, chestSlotBackgrounds[i], craftingSlotBackgrounds[i],
  crafting3x3SlotBackgrounds[i], playerHearts[i], heartBases[i], crosshairElement,
  inventoryPanelElement, hotbarActiveElement, carriedItemElement, itemNameElement,
  uiTextureSize, fontTextureId, uvHeartFull/Half/Empty/Base). These resolve to
  HudUI's populated copies (NOT Main's dead stubs).

Bare-identifier substitutions applied to Main body:
- inventoryOpen          -> main.inventoryOpen
- commandMode            -> main.commandMode
- commandBuffer          -> main.commandBuffer
- lastMouseX             -> main.lastMouseX
- lastMouseY             -> main.lastMouseY
- statusMessage          -> main.statusMessage
- statusUntil            -> main.statusUntil
- statusLineOffset       -> main.statusLineOffset
- player\.(?!.*health=deadloop) -> main.player.  [caret-anchored: only Main.player]
  - But wait: Main has `Player player` field, HudUI does NOT have a player field.
    Without substitution, `player` would be unresolved -> compile error.
    So: `player.` -> `main.player.` (everywhere)
- ctx\.(...) already routes to HudUI.ctx (HudUI has private final GameContext ctx). No change.

Static-constant substitutions (Main has public static finals, HudUI uses them
qualified as Main.HOTBAR_X etc.):
- HOTBAR_X              -> Main.HOTBAR_X
- HOTBAR_Y              -> Main.HOTBAR_Y
- HOTBAR_SIZE           -> Main.HOTBAR_SIZE
- INVENTORY_SIZE        -> Main.INVENTORY_SIZE
- SLOT_H                -> Main.SLOT_H
- SLOT_W                -> Main.SLOT_W
- CRAFTING_SLOTS        -> Main.CRAFTING_SLOTS
- CRAFTING_RESULT_SLOT  -> Main.CRAFTING_RESULT_SLOT

Bare `hud.X` accesses (which appear in Main.updateInventoryUi where `hud` is a
field on Main) resolve to HudUI's own fields when inlined, so:
- hud\. -> (empty)

But we must NOT clobber `hud.X` references in HudUI's source code (which we are
editing) where `hud` is undefined. So we only do this substitution inside the
inlined Main body, not in the receiving HudUI source.

Walk strategy (avoids edit-outs):
1. Read Main.java fully.
2. Extract body of Main.updateInventoryUi / handleFurnaceSlotClick /
   handleChestSlotClick with a brace-aware walker (depth counter tracking
   balanced `{`/`}`).
3. Apply substitutions to each extracted body.
4. Read HudUI.java fully.
5. Find HudUI.updateInventoryUi body — replace the tail-call line
   `main.updateInventoryUi();` with the substituted Main body.
6. Find HudUI.handleFurnaceSlotClick body — replace the body's contents with
   the substituted Main body (HudUI's version does `main.handleFurnaceSlotClick(slot);`
   followed by `inventoryUiDirty = true;` — we replace all internal lines).
7. Find HudUI.handleChestSlotClick similarly.
8. Write HudUI.java back.

The body extraction is "between the opening `{` and its matching closing `}`",
excluding both brace lines themselves (the result is the lines INSIDE the method).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'voxel', 'Main.java')
HUD = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'voxel', 'ui', 'HudUI.java')


def read(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def find_method_range(src, signature_re):
    """Return (start_idx_of_opening_brace_CHAR, end_idx_of_closing_brace_CHAR)
    for the FIRST method matching `signature_re`.

    Convention: signature_re MUST end with `{` so the pattern's last char IS
    the opening brace of the method. After re.search, that opening brace is at
    position `m.end() - 1`. We use it directly as `opening_idx`, then walk
    forward from `m.end()` to find the matching `}` at depth=0.

    Brace-aware: skips comments and string/char literals so a `{` inside them
    doesn't confuse the depth counter."""
    m = re.search(signature_re, src)
    if not m:
        return None
    opening_idx = m.end() - 1  # the `{` is the last char of the matched string
    i = m.end()
    depth = 1  # we are INSIDE the just-found opening brace
    in_line_comment = False
    in_block_comment = False
    in_string = False
    in_char = False
    while i < len(src):
        c = src[i]
        nxt = src[i+1] if i+1 < len(src) else ''
        if in_line_comment:
            if c == '\n':
                in_line_comment = False
        elif in_block_comment:
            if c == '*' and nxt == '/':
                in_block_comment = False
                i += 1
        elif in_string:
            if c == '\\' and nxt:
                i += 1
            elif c == '"':
                in_string = False
        elif in_char:
            if c == '\\' and nxt:
                i += 1
            elif c == "'":
                in_char = False
        else:
            if c == '/' and nxt == '/':
                in_line_comment = True
                i += 1
            elif c == '/' and nxt == '*':
                in_block_comment = True
                i += 1
            elif c == '"':
                in_string = True
            elif c == "'":
                in_char = True
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return (opening_idx, i)
        i += 1
    raise RuntimeError(f"No matching close brace for sig at {m.start()}")


def get_inner_body(src, open_idx, close_idx):
    """Returns the substring that is INSIDE the braces (excluding the braces
    themselves), and captures the leading indentation of the FIRST inner line
    so callers can preserve it."""
    inner_start = open_idx + 1
    inner_end = close_idx
    inner = src[inner_start:inner_end]
    # Strip trailing newline-before-closing-brace so we can dedent cleanly.
    # We'll keep raw and let caller dedent.
    return inner


def dedent_body(inner, new_indent='        '):
    """Dedent `inner` (which was originally indented by `orig_indent`) so each
    line begins at `new_indent`."""
    # Find minim"""Indent each line by `indent`. But ignore empty lines."""
    lines = inner.splitlines(keepends=True)
    out_lines = []
    for ln in lines:
        if ln.strip() == '':
            out_lines.append(ln)
        else:
            out_lines.append(ln)
    return ''.join(out_lines)


def get_leading_indent(line):
    return len(line) - len(line.lstrip())


def reindent(inner_text, target_indent='        '):
    """Re-indent the body to `target_indent` per line of content, respecting
    the existing relative structure (deeper inner blocks keep their delta)."""
    lines = inner_text.splitlines(keepends=True)
    if not lines:
        return inner_text
    # Determine the smallest leading whitespace of a non-empty line; that's the
    # method body's outer indent (e.g., '        ' for 8 spaces).
    outer_indents = []
    for ln in lines:
        if ln.strip():
            outer_indents.append(get_leading_indent(ln))
    if not outer_indents:
        return inner_text
    min_outer = min(outer_indents)
    if min_outer == len(target_indent):
        # Already at target indent.
        return inner_text
    new_lines = []
    for ln in lines:
        if not ln.strip():
            new_lines.append(ln)
            continue
        cur = get_leading_indent(ln)
        delta = cur - min_outer
        new_indent = target_indent + ' ' * delta
        new_lines.append(new_indent + ln.lstrip())
    return ''.join(new_lines)


def apply_substitutions_for_hud(body):
    """Apply the field substitutions needed to convert a Main method body
    (which references Main's instance fields and constants) into code that
    compiles inside HudUI's class.

    Order matters: do constant substitutions BEFORE field-name substitutions
    so we don't accidentally rewrite constant NAME occurrences into a method
    name. We use word-boundary regex everywhere.
    """
    # Constants (static finals on Main) -> Main.X
    static_constants = [
        'HOTBAR_X', 'HOTBAR_Y', 'HOTBAR_SIZE',
        'INVENTORY_SIZE', 'SLOT_H', 'SLOT_W',
        'CRAFTING_SLOTS', 'CRAFTING_RESULT_SLOT',
    ]
    for c in static_constants:
        body = re.sub(r'(?<!\w)' + c + r'(?!\w)', 'Main.' + c, body)

    # Main instance fields (only on Main, not on HudUI) -> main.X
    # We do them in this order so we don't get partial overlap.
    main_only_fields = [
        'inventoryOpen',
        'commandMode',
        'commandBuffer',
        'lastMouseX',
        'lastMouseY',
        'statusMessage',
        'statusUntil',
        'statusLineOffset',
    ]
    # In the body of Main.updateInventoryUi there are also reads like:
    #     if (hud.itemNameElement...) -> in HudUI context: `itemNameElement` resolves
    #     if (hud.itemNameDisplayUntil...) -> HudUI's own field.
    # So `hud.X` -> X (clear the prefix) but make sure we don't end up with
    # superimposing onto short names.
    # HudUI owns these fields directly, so a bare `hud.X` reference inside the
    # inlined body must become `X`.
    for f in ['itemNameElement', 'itemNameDisplayUntil', 'fontTextureId',
              'uiTextureSize']:
        body = re.sub(r'(?<!\w)hud\.' + f + r'(?!\w)', f, body)

    # Player: Main has a `Player player` field; HudUI doesn't have one.
    # We want `player.X` -> `main.player.X` (Player is referenced via
    # `main.player.getHealth()` etc.). ONLY at the start of a . chain or as
    # standalone -> main.player.
    # Use `player.` and `player` (word-boundary, not followed by alpha).
    body = re.sub(r'(?<!\w)player\b', 'main.player', body)

    # Now the bare Main-instance fields with simple substitution.
    # IMPORTANT: we run them only on IDENTIFIERS we've not yet substituted.
    # Using word-boundary lookahead/lookbehind ensures no overlap.
    for f in main_only_fields:
        body = re.sub(r'(?<!\w)' + f + r'(?!\w)', 'main.' + f, body)

    # Also fix references like `Main.<X>` if they accidentally appear.
    # No-op, leave alone.

    return body


def replace_method_body(src, signature_re, new_body_text):
    """Replace the entire body BETWEEN the opening `{` and matching `}`
    of the first method matching `signature_re`. The new body is `new_body_text`,
    which already starts at the right indent (caller's responsibility).

    Both opening `{` and closing `}` are kept; only the inner content is
    replaced."""
    (open_idx, close_idx) = find_method_range(src, signature_re)
    # Preserve leading whitespace before opening brace (it's on the SAME line).
    # open_idx points at `{`. Find the line start.
    line_start = src.rfind('\n', 0, open_idx) + 1
    opener_line = src[line_start:open_idx + 1]  # e.g., "    public void foo() {"
    # After opener_line includes the brace, the next char is the newline
    # (or directly content). Preserve that.
    # close_idx points at `}`. close_line_start:
    close_line_start = src.rfind('\n', 0, close_idx) + 1
    trailing = src[close_line_start:close_idx + 1]  # e.g., "    }"
    # The closing brace line is preceded by "\n" or belongs to trailing
    # whitespace trail.

    # In src: src = head + opener_line + src[open_idx+1:close_idx] + src[close_idx:end]
    head = src[:line_start]
    tail = src[close_idx + 1:]

    # new_body_text should NOT have its own trailing newline before `}`.
    # It SHOULD end with a single '\n' so the `}` lives on its own line.
    if not new_body_text.endswith('\n'):
        new_body_text = new_body_text + '\n'
    # Use 4-space indent for the closing brace (consistent with project).
    # Determine target indent from opener_line.
    return head + opener_line + '\n' + new_body_text + '    }\n' + tail


# ---------------------------------------------------------------------------
# Main migration
# ---------------------------------------------------------------------------

def migrate():
    main_src = read(MAIN)
    hud_src = read(HUD)

    # ===== 1. Extract Main.updateInventoryUi body =====
    print('=== A) Extracting Main.updateInventoryUi body ===')
    (open_idx, close_idx) = find_method_range(
        main_src, re.escape('public void updateInventoryUi() {'))
    inner_main_inv = get_inner_body(main_src, open_idx, close_idx)
    print(f'    inner body length (chars): {len(inner_main_inv)}')
    # Re-indent for HudUI's class (was inside class Main, also 4-space class
    # indent, so the body content is at 8 spaces in raw — but we want it at
    # 8 spaces in HudUI too — same indent). So actually no reindent needed.
    # But the inner-most blocks are at 12, 16, etc. Differentials preserved.
    inner_main_inv_indented = reindent(inner_main_inv, target_indent='        ')
    inner_main_inv_subst = apply_substitutions_for_hud(inner_main_inv_indented)

    # ===== 2. Extract Main.handleFurnaceSlotClick body =====
    print('=== B) Extracting Main.handleFurnaceSlotClick body ===')
    (open2, close2) = find_method_range(
        main_src, re.escape('public void handleFurnaceSlotClick(int slot) {'))
    inner_main_furn = get_inner_body(main_src, open2, close2)
    print(f'    inner body length (chars): {len(inner_main_furn)}')
    inner_main_furn_indented = reindent(inner_main_furn, target_indent='        ')
    inner_main_furn_subst = apply_substitutions_for_hud(inner_main_furn_indented)

    # ===== 3. Extract Main.handleChestSlotClick body =====
    print('=== C) Extracting Main.handleChestSlotClick body ===')
    (open3, close3) = find_method_range(
        main_src, re.escape('public void handleChestSlotClick(int slot) {'))
    inner_main_chest = get_inner_body(main_src, open3, close3)
    print(f'    inner body length (chars): {len(inner_main_chest)}')
    inner_main_chest_indented = reindent(inner_main_chest, target_indent='        ')
    inner_main_chest_subst = apply_substitutions_for_hud(inner_main_chest_indented)

    # ===== 4. Replace HudUI.updateInventoryUi tail-call with body =====
    print('=== D) Replacing HudUI.updateInventoryUi tail-call ===')
    # The HudUI version currently ends with:
    #         // Slot/UI updates delegate to Main.updateInventoryUi (...).
    #         main.updateInventoryUi();
    #     }
    # We want to keep the comment (optional), then put the inlined body
    # content in its place. We're replacing the whole method body so that
    # the prefix-set code (carriedItemElement.visible, commandTextElement, etc.)
    # remains AT THE TOP of the method (HudUI's existing prefix code), and
    # the long body (furnace/chest/crafting/inventory slots/hearts/status)
    # lands at the BOTTOM (after the early-out check).
    #
    # Strategy: keep HudUI's prefix code (the part above the comment), drop
    # the tail-call line + comment, then append the inlined Main body.
    #
    # But this is hard to do in-place without overlap. Cleaner approach:
    # append the inlined body to the END of HudUI.updateInventoryUi, but
    # REMOVE the duplicate prefix code that comes from Main's body (which
    # we don't need anymore).
    #
    # Since Main.updateInventoryUi has its OWN prefix-set code (carriedItem,
    # command text, early-out, panel visibility, hotbar position), and HudUI
    # ALSO has its own prefix-set code, we end up with duplicates. We need
    # to dedupe. The cleanest way: APPEND the inlined Main body to HudUI's
    # CURRENT method body. HudUI already does:
    #     - carriedItemElement logic
    #     - commandText logic
    #     - early-out check
    #     - panel visibility
    #     - hotbar position
    #     - use3x3 / useFurnace / useChest booleans
    #     - then DELEGATES TO main.updateInventoryUi();
    # If we keep HudUI's prefix and append Main's body (which starts with
    # the same prefix logic), we'll have duplicate `carriedItemElement.visible=`
    # assignments. To avoid this, we strip Main body's prefix portion.
    #
    # Trimming Main body's prefix: remove Main.updateInventoryUi's first ~80
    # lines that overlap with what HudUI already does. We do this by
    # matching: find the comment that marks the divergence.
    #
    # Looking at HudUI's current tail:
    #     boolean use3x3 = ctx.craftingTableOpen && ctx.activeUI == ...;
    #     boolean useFurnace = ctx.furnaceOpen && ctx.activeUI == ...;
    #     boolean useChest = ctx.chestOpen && ctx.activeUI == ...;
    #
    #     // Slot/UI updates delegate to Main.updateInventoryUi (...).
    #     main.updateInventoryUi();
    #
    # So we just need to drop the tail-call and the comment, and insert the
    # Main body AFTER the useChest/useFurnace/use3x3 booleans.
    #
    # In the HUD source, the tail-call block is:
    #     \n        // Slot/UI updates delegate to Main.updateInventoryUi (still owns the long body).\n        main.updateInventoryUi();\n    \}
    # We'll find that and remove it, then insert the Main body at that point,
    # stripping Main body's redundant prefix lines that match what HudUI
    # already does.

    # Trimming Main body: find the location in Main body where the Furnace UI
    # starts (the "// --- Furnace UI ---" comment) and KEEP from there.
    # That way we strip Main's carriedItemElement, commandText, early-out,
    # and panel/hotbar position code (which HudUI already does).
    trim_marker = '// --- Furnace UI ---'
    if trim_marker in inner_main_inv_subst:
        trim_pos = inner_main_inv_subst.index(trim_marker)
        trimmed_main_inv = inner_main_inv_subst[trim_pos:]
        print(f'    trimmed Main body to start at: {trim_marker}')
        print(f'    trimmed body length (chars): {len(trimmed_main_inv)}')
    else:
        print('    WARNING: trim marker not found; using full Main body')
        trimmed_main_inv = inner_main_inv_subst

    # Now splice the trimmed Main body into HudUI.updateInventoryUi body.
    # We use a SIMPLE string-replace (no regex) so it's deterministic.
    # The HudUI source has the literal substring:
    #     "        // Slot/UI updates delegate to Main.updateInventoryUi (still owns the long body).\n        main.updateInventoryUi();"
    # We replace it with the inlined body.
    tail_call_lit = '        // Slot/UI updates delegate to Main.updateInventoryUi (still owns the long body).\n        main.updateInventoryUi();'
    if tail_call_lit not in hud_src:
        raise RuntimeError(
            'Could not find HudUI.updateInventoryUi tail-call literal. '
            'Inspect HudUI source for changes since this script was written.'
        )
    splice_block = '        // Slot/UI updates (inlined from Main.updateInventoryUi)\n' + trimmed_main_inv.rstrip('\n')
    new_hud_src = hud_src.replace(tail_call_lit, splice_block)
    print('    spliced HudUI.updateInventoryUi -> 1 match')
    # Reset `new_hud_src` to begin the next phase.
    hud_src = new_hud_src

    # ===== 5. Replace HudUI.handleFurnaceSlotClick body =====
    print('=== E) Replacing HudUI.handleFurnaceSlotClick body ===')
    # Current HudUI version (from our source dump):
    #     public void handleFurnaceSlotClick(int slot) {
    #         FurnaceManager.FurnaceState state = ctx.furnaceManager.getState(...);
    #         ItemStack carried = playerInventory.getCarriedStack();
    #         // ... delegated via main.handleFurnaceSlotClick (semantics unchanged)
    #         main.handleFurnaceSlotClick(slot);
    #         inventoryUiDirty = true;
    #     }
    # We'll replace the inner body with the inlined Main body.
    # Note: Main.handleFurnaceSlotClick starts with the SAME `state`, `carried`
    # declarations, so we WANT those included. We can use the FULL inner body.
    hud_src_after_furn = replace_method_body(
        new_hud_src,
        re.escape('public void handleFurnaceSlotClick(int slot) {'),
        inner_main_furn_subst,
    )
    new_hud_src = hud_src_after_furn
    print('    replaced HudUI.handleFurnaceSlotClick body')

    # ===== 6. Replace HudUI.handleChestSlotClick body =====
    print('=== F) Replacing HudUI.handleChestSlotClick body ===')
    hud_src_after_chest = replace_method_body(
        new_hud_src,
        re.escape('public void handleChestSlotClick(int slot) {'),
        inner_main_chest_subst,
    )
    new_hud_src = hud_src_after_chest
    print('    replaced HudUI.handleChestSlotClick body')

    # ===== 7. Write HudUI.java back =====
    write(HUD, new_hud_src)
    print('=== G) HudUI.java written ===')

    print()
    print('Final HudUI line count:')
    print(f'    {sum(1 for _ in open(HUD))} lines')

    print()
    print('Done. Next:')
    print('  - Run tools/migrate_ui_shrink.sh which uses VMB -r to shrink')
    print('    Main.updateInventoryUi/handleFurnaceSlotClick/handleChestSlotClick')
    print('    to 1-line delegates.')
    print('  - Then mvn compile gate.')


if __name__ == '__main__':
    migrate()
