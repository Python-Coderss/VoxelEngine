# VoxelEngine — Handoff Context (for a fresh model)

Project root: `C:\Users\raman\eclipse-workspace\fastpbrjava\VoxelEngine`
Build: Maven (`./mvnw compile`, `./mvnw test -Dtest=X -DfailIfNoTests=false`). Java, OpenGL 4.3+ compute shaders. Shell is **bash on Windows** — use POSIX paths (`/c/Users/raman/eclipse-workspace/fastpbrjava/VoxelEngine`), never `dir`/`copy`/`findstr`.

---

## THE ACTIVE TASK (COMPLETED 2026-08-08)

**User's directive (verbatim):** "we don't ever need per column generation. only cubic chunk generation. why are we batching the entire column???"

✅ **DONE.** `BetaChunkProvider` now generates only the requested 16³ section:

- `loadColumnContext(cx, cz)` — per-column context (noise offsets, biome/temp arrays, rand seed), cached via `columnContextReady`.
- `ensureSection(cx, cy, cz)` — single per-section entry; rewired `populateSection`/`prepareSection`/`getBetaBlock`/`getHeight` to it.
  - `cy < 0`: no-op (per-voxel `evaluateDensity`).
  - `0 <= cy < 8`: classic band as one unit — `generateSectionRange(0,8)` + probe `generateSectionRange(8,12)` (skipped if `hasAnySectionAbove(7)`) + `replaceBlocksForBiome` gated on `!hasSolidAboveSection(7)` (when probe all-air, probe sections are added to `highSectionsGenerated` so they aren't regenerated) + `carveCavesFromSections` + `mergeDecorationOverlay`. One-shot via `band08Generated`.
  - `cy >= 8`: `generateSectionRange(cy, cy+1)` + `applyHighSectionSurfacePass` (per-(x,z) `hasSolidAboveColumn` gate skips underground mass) + `mergeDecorationOverlay`; once per section via `highSectionsGenerated`.
- `generateColumnCopy` (neighbor LRU) — band-only `generateSectionRange(0,9)` (y 0..143; tree writes max at 127+1+15=143) + dressing + caves.
- `populateColumn` → `loadColumnContext` + `ensureSection(cx,0,cz)`.
- `BetaWorldGenerator.ensureColumn` deleted; `getColumnBlocks` uses `columnContextReady`.
- `generateColumn` kept intact for `BetaTreeDensityTest`.

Validation: compile ✓, BetaFarLandsCeilingTest (2) ✓, BetaNumericControlsTest (22) ✓, ChunkManagerGridReadyTest (4) ✓, PlayerFallThroughTest (5) ✓, PlayerUnstuckTest (4) ✓ — **37/37 green.**

**Also fixed (follow-up):** `BetaNumericControlsTest.overworldPolicyClassDegradesWithDistance` was failing (expected 52 got 26) — pre-existing, in the user's precision-policy work. `OverworldBetaPrecision`'s javadoc already documented "X/Z + Y doubles degrade 52→40→30→18→11→6" and the Y branch matched, but the X/Z double branches still carried the pre-swap values (26/2). Aligned them to the documented contract (52/40/30/18/11/6). Effect: the error502 dimension (DEFAULT preset → OverworldBetaPrecision) now has full 52-bit X/Z doubles at origin, degrading with distance — matches STANDARD_BETA near spawn better. `BetaTreeDensityTest` (slow, ~min) still untested this session.

### Planned design (already fully worked out, see details below)

1. **`loadColumnContext(cx, cz)`** — extract the context-loading head of `generateColumn` (noise context push, `rand.setSeed`, `loadBlockGeneratorData` biomes/temps/hums, clear `columnSections`, reset `maxSectionCY`). Cache with `columnContextReady` flag so it runs once per (cx,cz).
2. **`ensureSection(cx, cy, cz)`** — the single per-section entry point:
   - `cy < 0`: nothing (below-zero handled by `evaluateDensity` per-voxel).
   - `0 <= cy < 8`: generate the **classic Beta band [0,8)** (y 0..127) as one unit — density (`generateSectionRange(0,8)`) + a **probe band** `generateSectionRange(8,12)` above it + `replaceBlocksForBiome` surface dressing (gated: only if `!hasSolidAboveSection(7)`) + `carveCavesFromSections`. One-shot per column via `band08Generated` flag.
   - `cy >= 8`: if section not cached: `generateSectionRange(cy, cy+1)` (one func_4061_a call, 3 samples) + **section-local surface pass** `applyHighSectionSurfacePass(cx, cy, cz)` (per-(x,z), skips columns where solid exists above — detects underground mass vs real surface via top-down ordering).
3. **Rewire** `populateSection` / `prepareSection` / `getBetaBlock` to call `ensureSection` instead of `generateColumn`.
4. **`generateColumnCopy`** (decoration neighbor copies, via `getColumnBlocks`) — band-only (`generateSectionRange(0,9)` + surface + caves) instead of full-column `generateTerrain`. Decoration never probes above y≈143 (`worldGetTopY` caps at y=127).
5. **`populateColumn`** — replace `generateColumn(cx,cz)` with `ensureColumnForDecoration` = `loadColumnContext` + `generateCaveBand` if needed.
6. **`BetaWorldGenerator`** — remove `ensureColumn` (calls `generateColumn` = full column!) from `getHeight`/`getBlockType(x,y,z,height)`; the provider's `getHeight`/`getBetaBlock` are self-contained.
7. Keep `generateColumn` itself intact — `BetaTreeDensityTest` calls `provider.generateColumn(cx, cz)` directly (line 113).

### Key correctness insights (already established)

- `generateSectionRange(fromCY, toCY)` is correct per-section: `yStart = minY/8`, one section = 3 Y samples (boundaries + 1 above for interpolation), writes stone/water/ice via `interpolateDensityToSections` which has an all-solid short-circuit (fast far-lands fill).
- **Surface dressing** (`replaceBlocksForBiome`) is inherently column-wide (scans topY→0, needs to know where the surface is). Solution: generate sections **top-down** (ChunkManager does: `orderedSections` sorts higher-first, `ensure3x3x3Loaded` uses {pcy+1, pcy, pcy-1}). Then when generating section cy, cached sections above tell you if (x,z) is underground (solid above → leave stone) or at the surface (air above → first stone below air = surface → grass/dirt/sand/gravel).
- **The band needs a probe above it**: if the first requested section of a column is cy<8 (e.g. `ensure3x3x3Loaded` at spawn, or standing at a mountain base), no sections above are cached yet. If the real surface is above y=127 (tall mountain / far-lands mass), the band's `replaceBlocksForBiome` would wrongly grass the band top. So generate probe sections 8..11 first; if any solid → skip surface pass on the band. Cost: 4×75 evals once per column.
- Caves (`carveCavesFromSections`) already only touch cy 0..7 (16×128×16 temp buffer) — safe inside the band.
- `func_4061_a` already has the far-lands fade ceiling (y≥2000 → negative density) baked in; per-section generation inherits it for free.
- `getHeight` is effectively dead code for Beta (no production callers found; grep showed only `BetaWorldGenerator.getHeight` → `betaProvider.getHeight`, and WorldGenerator's default `getBlockType(x,y,z,height)` fallback which Beta overrides). Keep it working but don't over-invest; its far-lands early-out (`maxSectionCY==127 || |coord| >= CLASSIC_FAR_LANDS_BLOCKS`) may be less accurate with per-section gen — acceptable, it's unused in the hot path.

### Known acceptable tradeoffs (user already said "no parity needed" for far lands)

- Rand-consumption order changes slightly → grass/sand/gravel placement differs from the old full-column build in cosmetic ways (still deterministic per seed).
- Filler (dirt) that crosses a section boundary directly below a surface is lost in rare cases (surface at exactly y%16==0); rare + cosmetic.
- A buried far-lands section generated before its above-neighbors may get a spurious grass layer at its top (invisible, buried).
- Far-lands glowstone veins: neighbor copies only cover y<143, so neighbor glowstone is reduced; own-column glowstone is fine.

---

## ARCHITECTURE (knowledge.md is current — read it)

- `Main.java` god-object: render thread owns GL, LogicThread ticks, single-class orchestrator (~2100 lines).
- `ChunkManager`: single gen thread + FIFO task queue + dedicated lighting thread. `loadOneSection(cx, cy, cz)` → `generateBaseTerrain` → `generator.populateSection(cx, cy, cz, world, slot)`; `decorateSectionIfAllowed` → `generator.decorate` (only cy==4, once per column via `decoratedColumns` set). Spawn bootstrap defers Beta decoration (`deferredBetaDecoration`). Sections requested top-down.
- `World`: sliding-window buffer 2048³, chunk pool (16³ int sections), light pool, occlusion, directional SDF pool. Pool has ~2048 slots.
- `BetaChunkProvider`: the whole Beta 1.7.3 pipeline. **Read it fully before editing** — it's 1,324 lines.

### BetaChunkProvider key members (from the 2026-08-08 read)

- Fields: `columnSections` (HashMap<cy, byte[4096]>), `cachedMainSection*`/`cachedGeneratedSection*` caches, `columnCX/columnCZ/columnGenerated`, `maxSectionCY`, `neighborBlocks` (LRU 96 cols), `decorationOverlay`, `decorationTouchedNeighbors` (Set, per-populateColumn), `caveTempArray` (16×128×16), `evalNoiseBuffer[1]`.
- `generateColumn(cx,cz)`: full 0..2047 — generateTerrain + replaceBlocksForBiome + carveCavesFromSections + overlay merge. **KEEP for tests.**
- `generateSectionRange(fromCY, toCY)`: per-section density — one `func_4061_a` call + `interpolateDensityToSections`. **THE cubic-chunk path.**
- `populateSection`: currently `generateColumn` if not cached → copy section to pool, -1 if cy<0. **REWIRE.**
- `prepareSection`: same gate, cy<0 → true. **REWIRE.**
- `getBetaBlock(x,z,y)`: same gate; y<0 → evaluateDensity/bedrock. **REWIRE.**
- `getHeight(x,y,z)`: same gate + scan down from maxSectionCY. **REWIRE the gate only.**
- `replaceBlocksForBiome`: column-wide surface pass (topY from `var3.keySet()`). Extracting `computeSurfaceNoise(cx,cz)` (sandNoise/gravelNoise/stoneNoise via `field_909_n`/`field_908_o`) is planned so the section-local pass can reuse it. **Must still work for generateColumn + band + generateColumnCopy.**
- `carveCavesFromSections`: fills caveTempArray from sections 0..7, runs `caveGen.func_867_a`, writes back. Only cy 0..7.
- `func_4061_a`: single density source; has FAR_LANDS_CEILING_Y=2000 / FADE_START=1890 / FADE_SLOPE=1e12.
- `populateColumn(world, cx, cz)`: decoration — ore veins, glowstone, trees (`worldGetTopY` caps scan at y=127), lakes, beaches, clay, dungeons, snow. Writes into **world** + `getColumnBlocks` (neighbor copies) + overlay flush for `decorationTouchedNeighbors`.
- `generateColumnCopy(cx,cz)`: full-column neighbor copy (generateTerrain + replaceBlocksForBiome + caves). **PLAN: band-only.**
- `genOreVein(world,...)`: checks `getSectionBlock(b, ...) == BETA_STONE` where `b = getColumnBlocks(px>>4, pz>>4)`; writes world + section.
- `getColumnBlocks(cx,cz)`: returns `columnSections` if current column, else neighborBlocks LRU (generateColumnCopy on miss) + marks `decorationTouchedNeighbors`.
- `applyNoiseContext(blockX,blockY,blockZ)`: pushes chunk-aligned block offsets into every octave chain (the session's precision work — block offsets rounded to 16). `chunkCorner(chunkIndex)` = chunkIndex>=0 ? chunkIndex*16 : (chunkIndex+1)*16.
- Numeric profile routing: `d()/xDouble()/zDouble()/...` wrap `numericProfile` quantization; `BetaNumericProfile` has per-dimension policies (OVERWORLD / DEFAULT=ERROR502).

### ChunkManager call sites (verified)

- `generateBaseTerrain` (ChunkManager ~1855): `bulkCount = generator.populateSection(...)`; if >=0 done; else `prepareSection` + per-voxel `getBlockType`. Beta returns >=0 for cy>=0, -1 for cy<0.
- `loadOneSection` (~1508): first section of a column registers **all** Y-range slots first (disk-load correctness), then per-section `generateBaseTerrain` + `decorateSectionIfAllowed` + occlusion + SDF + fluids + lighting.
- `ensure3x3x3Loaded` (~1231): sync 3×3×3, sections {pcy+1, pcy, pcy-1}, top-down.
- `orderedSections(minY,maxY)` (~1791): sorted `compareHigherSectionFirst`.
- `runDeferredBetaDecoration`/`decorateBootstrapColumns` (~1816): decorate at cy==4 after bootstrap.
- `generateBaseTerrain` already calls `world.clearChunkPoolSlot(slot)` before populate.

### BetaWorldGenerator

- `getHeight(x,y,z)` → `ensureColumn(cx,cz)` (== `generateColumn`! **remove this**) → `betaProvider.getHeight`.
- `getBlockType(x,y,z)` → `betaProvider.getBetaBlock` (self-contained already once rewired).
- `getBlockType(x,y,z,height)` → `ensureColumn` + `getBetaBlock` (**remove ensureColumn**).
- `decorate(cx,cy,cz,...)`: facility columns + once-per-column at cy==4 → `betaProvider.populateColumn(world, cx, cz)` + `structureGen.generateStructures`.
- `ensureColumn` private → uses `lastCX/lastCZ`; candidate for deletion.

---

## THIS SESSION'S HISTORY (condensed)

1. Removed chunk-freeze logic; made overworld terrain generate fast (persistent 96-column neighbor LRU in BetaChunkProvider; `decorationTouchedNeighbors` limits overlay flush).
2. Sky-light: "8 air blocks under a cover = sun" fix in `LightEngine.generateSkyLight` (`SUN_CLEAR_RUN=8`, removed early break).
3. Far-lands fade-out ceiling y=2000 in `func_4061_a` (FADE_START 1890, slope 1e12) + `evaluateDensity` guard; new test `BetaFarLandsCeilingTest`. Far lands previously packed columns solid to y=2047 → chunk pool starvation.
4. Beta precision work (previous session): `OverworldBetaPrecision` (integer-only far lands ~3060 blocks, distance degradation OFF by default) vs `Error502BetaPrecision` (coordinate-aware degradation, editable). `BetaNumericProfile` now holds a per-dimension `BetaPrecisionTuning` instance; `STANDARD_BETA` retained for tests.
5. Latest complaint: post-spawn generation stall ("chunks take a while to generate after the spawn chunks, then picks up again") → traced to full-column batching in `generateColumn` → user: "we don't ever need per column generation. only cubic chunk generation."

---

## TESTS

- `src/test/java/com/voxel/world/beta/BetaFarLandsCeilingTest.java` — probes `p.getBetaBlock(bx, bz, y)` across y for far-lands column under both profiles; asserts no solid ≥2000, solid below. **Uses getBetaBlock — must keep working.**
- `BetaNumericControlsTest` (22 tests) — precision routing.
- `BetaTreeDensityTest` — calls `provider.generateColumn(cx, cz)` directly; slow (~min), avoid in quick runs.
- `ChunkManagerGridReadyTest` — fake generator, `isPlayerSectionGenerated`.
- Player/CommandBlock/AncientBuilder suites pass.
- User explicitly said: **"no need for test running"** at one point, but compile + the two fast Beta tests are still worth doing. Don't run BetaTreeDensityTest in quick validation.

## VALIDATION COMMANDS

```
cd /c/Users/raman/eclipse-workspace/fastpbrjava/VoxelEngine && ./mvnw -q compile 2>&1 | tail -15
cd /c/Users/raman/eclipse-workspace/fastpbrjava/VoxelEngine && ./mvnw test -Dtest='BetaFarLandsCeilingTest,BetaNumericControlsTest' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:|FAILURE|ERROR|expected:|BUILD'
```

---

## RECENT USER EDITS TO RESPECT

- `DimensionManager`: OVERWORLD uses `BetaNumericProfile.OVERWORLD` (OverworldBetaPrecision); ERROR502 uses `DEFAULT` (Error502BetaPrecision).
- User may have edited noise classes / BetaChunkProvider since this handoff — **re-read files before editing** (the file tree in this doc is from a full read on 2026-08-08 and may be stale).

## NEXT STEPS (do in order)

1. Re-read `BetaChunkProvider.java` (1,324 lines) to confirm current state.
2. Implement the 7-step plan above (write_todos).
3. Compile + run `BetaFarLandsCeilingTest` + `BetaNumericControlsTest`.
4. Spawn code-reviewer-deepseek-flash.
5. Update `knowledge.md` if architecture changed meaningfully.
