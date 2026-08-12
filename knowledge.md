# VoxelEngine Knowledge

A Minecraft-style voxel engine built in Java with OpenGL 4.3+ compute shaders.

## Build & Run

- **Build system:** Maven (`./mvnw compile`)
- **GLFW window:** 1280×720, titled "Voxel Engine"
- **Entry point:** `com.voxel.Main` — single-class orchestrator (god-object, ~2100 lines)
- **Launch script for the ai terminal coding agent that was used to make most of this project:** `launch_freebuff.bat`

## Architecture

```
Main.java (god-object) sorry I meant god.
├── Render Thread (OpenGL, input polling, render loop)
│   ├── loop() — frame loop: uniforms → dispatch compute → quad blit → swap
│   ├── tick() — game logic on a separate LogicThread
│   └── takeScreenshot() — reads renderTexture back via glGetTextureImage, saves PNG
├── GameContext (shared mutable state bag)
│   ├── World, ChunkManager, DimensionManager
│   ├── EntityManager, PlayerEntity, Player
│   ├── BlockDataManager, BlockRegistry, ShaderBlockRegistry
│   ├── ItemDefinitions, PlayerInventory, CraftingManager
│   ├── FurnaceManager, ChestManager, CraftingTableManager
│   ├── DroppedItemManager — world-dropped items with hover + auto-pickup
│   └── RedstoneManager
├── CommandProcessor — slash commands (handled from GameContext)
├── BlockInteraction — block place/break logic
├── PortalSystem — dimension portal teleport
├── LightingEngine (in lighting/ package)
│   ├── EnumSkyBlock — SKY(15) vs BLOCK(0) channels
│   └── LIGHTENGINE:
│       ├── generateSkyLight() — top-down column sweep from world ceiling
│       ├── propagateBlockLight() — per-type BFS flood-fill for emissive blocks
│       ├── onBlockChanged() — 3×3×3 section rebuild for non-emissive changes
│       └── floodFillScalar() — primitive LongQueue BFS for scalar intensity
├── Camera — CameraController, multiple modes (first, follow, orbit, fixed)
└── HUD / UI — HudUI, UIManager, UILayer
```

## Key Data Structures

### World (sliding-window voxel buffer)
- **Buffer size:** 2048×2048×2048 (REGION_SIZE=128 chunks of CHUNK_SIZE=16)
- **Indirection Table:** `int[128×128×128]` maps (cx,cy,cz) → pool slot (EMPTY=0xFFFFFFFF)
- **Chunk Pool:** flat int array, each slot = 4096 ints (16³), bit 31 = solid flag
- **Bitmask Pool:** 128 ints per slot (1 bit per voxel solidity)
- **Light Pool:** 4096 ints per slot, packed format:
  - bits 0-7 = sky light (0-255)
  - bits 8-15 = block R, 16-23 = block G, 24-31 = block B
- **Occlusion Pool:** 4096 shorts per slot (14-bit directional sky visibility)
- **Directional SDF Pool:** 8 bytes per slot (6 directional distances, 2 padding)
- **Temp Light Pool:** byte[] for per-type BFS scalar intensity during propagation
- **Lighting scale:** internal 0-15, stored as ×17 (0-255), ÷17 when reading back

Recenters do happen to the buffer. in all axises.

### ChunkManager
- Manages chunk loading/unloading around player
- Cubic spiral sort: by Chebyshev distance `max(|dx|, |dz|)`, then angle from forward
- Dirty-slots set for GPU upload, capped at 48 per frame
- Lighting engine runs on gen thread; `lightsNeedUpload` flag set after BFS completion
- Terrain bounds tracked (min/max X,Y,Z, topSolidY) for SDF sky early-out

### Block System
- **BlockDataManager:** GPU texture-buffer with 3 ivec4s per block:
  - d0: 6 face texture indices (4 ints covering all 6 faces)
  - d1: emissive, opacity, transparency, tint mask, material effects packed into w
  - d2: RGB block color + animation info packed into w
- **BlockRegistry:** string name → numeric ID
- **ShaderBlockRegistry:** ID → shader state ID, directional variants, on/off pairs
- **MaterialEffect:** PORTAL(1), LIQUID(2), WIRE(3)
- **Mining tiers:** HAND(0)→WOOD(1)→STONE(2)→IRON(3)→DIAMOND(4)
- Non-full blocks use model-defined AABBs (from JSON block models)

### Item System
- **ItemDefinitions:** registry of items + blocks with tool tiers
- **PlayerInventory:** hotbar (5 slots) + inventory (20 slots)
- **CraftingManager:** 2×2 and 3×3 recipe matching
- **CraftingTableManager:** per-position 3×3 persistent grids
- **FurnaceManager:** smelting with fuel/timer tracking
- **ChestManager:** per-position persistent storage

where is the canonical registry mentioned.

## Shader Pipeline

### raytracer.comp (binding=0 imgOutput)
- Full path tracer: 2 bounces, DDA traversal through voxel world
- **SDF sky early-out:** plane SDF tests skip DDA when ray won't hit loaded terrain
- **Chunk-level directional SDF:** sphere-trace acceleration for empty chunks
- **Ambient Occlusion:** vertex-based, checks 8 blocks one step along face normal, bilinear interpolation
- **Lighting:** sun + moon directional with shadow DDA, Minecraft lightmap sampling, sky contribution
- **Entities:** OBB intersection with rotated parts, health bars, tint support
- **Crafting/dropped items:** scaled block AABBs with optional spin rotation
- **Block break overlay:** destroy_stage textures alpha-blended on targeted block
- **Post-processing:** ACES filmic tone mapping, gamma 2.2
- **UI compositing:** reads u_UITexture, alpha-blends over rendered scene

### lighting.comp (binding=0 imgOutput)
- GBuffer-based lighting (position, normal, albedo, material)
- Shadow DDA + atmosphere + lightmap sampling
- (Legacy raster path — raytracer.comp is the primary renderer)

### Supporting shaders
- **quad.vert/.frag:** Full-screen quad blit (compute output → screen)
- **chunk.vert/.frag:** Rasterized chunk rendering (legacy)
- **ui.vert/.frag:** UI element rendering with rotation, texture arrays
- **test.vert/.frag:** Testing shaders

## Railways & Minecarts

- **Rails:** block IDs 391 (`rail_ns`, runs along Z) and 392 (`rail_ew`, runs along X); 1/16-tall slab models (`rail_ns.json`/`rail_ew.json`, `rail_normal.png` texture). Placement requires a full block below and picks the axis from rail neighbours (cross/free-standing falls back to the player's look direction). Item `rail` (6 iron ingots → 16). Drops map both IDs to the rail item.
- **Minecart:** item `minecart` (5 iron ingots) spawns a `MinecartEntity` on the target rail (queued via `GameContext.minecartSpawnQueue` from the GL thread, drained on the logic thread). Entity model `models/entity/minecart.json` (5 cuboid-atlas parts) + generated 64×32 `textures/entity/minecart.png` (regenerate with `tools/gen_minecart_texture.py`).
- **Physics (`MinecartEntity.updateCart`):** follows the rail under its center along the rail's axis (accelerates from rider W/S input, coasts with friction, parks at track ends, clamps fall steps to 0.5 blocks so thin floors can't be tunnelled). Off rails it falls with gravity until something is below.
- **Riding:** right-click a cart to mount (W/S move, E or right-click dismounts). `Player.ridingCart` early-returns in `update()` to lock the player onto the cart; `GameContext.ridingMinecart` (volatile) + `ctx.dismountMinecart` runnable handle the GL→logic thread handoff. Auto-dismount on death or dimension change.

## Slash Commands

| Command | Description |
|---------|-------------|
| `/help` | Show all commands |
| `/gamemode <survival\|creative>` | Change game mode |
| `/give <item> [amount]` | Give item to player |
| `/slotclear [slot]` | Clear inventory slot |
| `/spawn` | Teleport to spawn |
| `/dimension <overworld\|nether\|end\|aether>` | Switch dimension | also short form is /dim
| `/list <items\|blocks\|commands>` | List registered items/blocks |
| `/camera <follow\|orbit\|fixed>` | Change camera mode |
| `/setuv <full\|half\|empty> <x> <y> [w] [h]` | Adjust heart UVs | that existed?
| `/screenshot` | Save current frame as PNG to `screenshots/` |

## Dimensions

| Dimension | ID | Scale factor | Notes |
|-----------|----|-------------|-------|
| Overworld | 0 | 1.0× | Day/night cycle, surface terrain |
| Nether | 1 | 0.125× | 8 blocks overworld = 1 nether, cave spawn |
| End | 2 | — | Fixed spawn island |
| Aether | 3 | 8.0× | 1 block overworld = 8 aether, island spawn, parachutes |
| ERROR502 | 4 | 1.0× | Isolated Beta terrain using `BetaNumericProfile.DEFAULT` and the current coordinate-aware precision switches |

## Key Recent Changes

### Beta Numeric Precision and Far Lands
- Beta world generation uses independent configurable short, int, float, and double controls. Edit `src/main/java/com/voxel/world/beta/BetaPrecisionTuning.java`; `BetaNumericProfile` is the compatibility/API wrapper consumed by generators.
- `/dimension error502` opens a separate saved Beta dimension (`dev/world/error502`) using the current coordinate-aware `BetaNumericProfile.DEFAULT` preset, without changing Overworld terrain. The normal Overworld uses the coordinate-tuned `BetaNumericProfile.OVERWORLD` preset instead.
- **Per-dimension policies (names match dimensions):** `BetaNumericProfile.OVERWORLD` is backed by `OverworldBetaPrecision` (X/Z/Y float precision and doubles stay full through the chunk-aligned 4,000 boundary; X/Z and Y then follow the tuned degradation bands, with X/Z doubles using the historical fixed 26-bit mask; tuned mantissas are 23→20→12→6→4→2→1); `BetaNumericProfile.DEFAULT` (the `error502` preset) is backed by `Error502BetaPrecision` (aggressive float: 23→16→11→6→4→2→1, X/Z + Y double 52→40→30→18→11→6→1). Edit the switch bodies in those two files to tune.
- `BetaNumericProfile.STANDARD_BETA` is the fixed-width reference profile (tests): `shortBits=10`, X/Z `intBits=20`, Y `intBits=15`, standard float `8/23`, and standard double `11/52` (exponent/mantissa). Only the integer widths are nonstandard, preserving the normal integer-driven Beta Far Lands behavior. It is **not** used by the Overworld anymore — the Overworld uses the coordinate-tuned `OVERWORLD` preset.
- The editable `BetaNumericProfile.DEFAULT` preset remains reserved for `error502`: `shortBits=10`, X/Z `intBits=20`, Y `intBits=15`, X/Z float `8/14` and Y float `8/11` (exponent/mantissa), X/Z double `11/26` and Y double `11/11` (exponent/mantissa). Its coordinate-aware floating stages intentionally produce the experimental degradation bands.
- Derived float values are also quantized with `floatValueAtDistance(...)`: the local angle, sine/cosine, radius, and interpolation value is quantized using the signed dominant world coordinate as its ULP context. For coordinate-specific tuning, edit the X/Y/Z switch functions in `BetaPrecisionTuning.java` (for example `xFloatMantissaBits(x)`, `yIntBits(y)`, or `zDoubleMantissaBits(z)`) and change the return value for the desired coordinate band. Absolute coordinates continue to use `floatValue(...)`; this avoids double-counting the world offset. The same distance-aware routing is used by Perlin/simplex noise, cave shape evolution, and ore-vein geometry.
- The independent 17-bit Y lattice control now pushes the signed Y integer wrap from ±16,384 to ±65,536 blocks. In the Overworld precision policy, Y float/double precision remains full through the chunk-aligned 4,000-block boundary so it cannot contaminate near-spawn X/Z behavior; after that, Y follows the same tuned bands as X/Z, allowing three-axis Far Lands. X/Z doubles then use the historical fixed 26-bit mask. X/Z integer widths remain unchanged.
- With the 20-bit X/Z integer precision and X/Z float `8/14` preset, the intended X/Z float degradation/Far Lands range is approximately **8x farther** than the previous ~3,060-block boundary (roughly 24,500 blocks; verify empirically because octave scaling and sampler offsets affect the exact edge).
- This is an observed terrain/noise boundary, not the legacy `12,550,821` constant; the effective threshold depends on the configured numeric widths and sampler offsets.
- Beta section generation uses cached bulk section population and does not require a per-voxel `getHeight()` query.
- **Far-lands Y cap removed:** `BetaChunkProvider.func_4061_a` (the single density source for the initial column fill and on-demand sections) no longer forces degraded terrain to fade out at Y=2000. Far Lands may continue through the separate bounded resident-buffer range, up to the 2048-block buffer edge; no new Y=2000 terrain cap is applied. Nether/End are bounded near their column heights; the Aether's 128-block repeating sky islands are by design.
- **Per-section cubic generation (no more full-column batching):** `BetaChunkProvider` now generates only the requested 16³ section. `ensureSection(cx, cy, cz)` is the single entry point for `populateSection`/`prepareSection`/`getBetaBlock`/`getHeight`; `loadColumnContext(cx, cz)` runs once per column (noise offsets, biome/temp arrays, rand seed). Sections 0..7 (the classic Beta band) generate as one unit (one `generateSectionRange(0,8)` call + a probe band 8..11 + `replaceBlocksForBiome` gated on `!hasSolidAboveSection(7)` + caves); sections 8+ generate one at a time (~75 noise evals) with `applyHighSectionSurfacePass` (per-(x,z) gate `hasSolidAboveColumn` skips underground mass; relies on ChunkManager's top-down ordering). `generateColumn` survives only for `BetaTreeDensityTest`; `BetaWorldGenerator.ensureColumn` deleted. `generateColumnCopy` (decoration neighbor LRU) is band-only (`generateSectionRange(0,9)` covers y 0..143, enough for all tree writes: ground≤127 + height≤15). Known cosmetic tradeoffs: rand-consumption order shifts surface dressing, and a topmost section generated before its above-neighbor may get a buried grass layer.
- **Decoration stage map:** `ChunkManager` tracks live per-column stages (`TERRAIN_PENDING`, `TERRAIN_READY`, `DECORATION_PENDING`, `DECORATED`) separately from the transient task queue. Beta decoration is deferred during bootstrap, then retried in bounded batches of 16 ordered by Chebyshev distance to the player. A column advances to `DECORATED` only after population and structures succeed; missing `cy=4`, queue pruning, and same-process unload/reload preserve retryable state. Stage metadata is intentionally live-session state; the existing chunk save format does not persist it.

### Sky Light Fix (2 parts)
1. `generateSkyLight` now starts from world ceiling instead of `topY` — air above terrain gets sky=15
2. `onBlockChanged` regenerates sky light for 9 affected chunk columns after clearing (was leaving it at 0)

### AO Fix
Vertex-based with normal-step offset: checks 8 blocks one step along face normal, bilinear interpolation

### Chunk Cubic Spiral
Sorted by `max(|dx|, |dz|)` then angle from forward, replacing old stretched-ring sort

### Screenshot Command
Reads `renderTexture` back via `glGetTextureImage`, Y-flips, saves timestamped PNG

## Block IDs Quick Reference

| ID | Name | ID | Name |
|----|------|----|------|
| 1 | grass_block | 2 | stone |
| 3 | glass | 4 | oak_leaves |
| 5 | oak_log | 13 | dirt |
| 14 | sand | 15 | water |
| 16 | obsidian | 17 | glowstone |
| 18 | end_stone | 19 | nether_portal |
| 20-24 | nether blocks | 25-30 | redstone blocks |
| 31-33 | pistons | 34-91 | biome decoration |
| 100-114 | aether blocks | 115-118 | functional blocks |
| 119-126 | vegetation/decorative | 127 | aether_portal_ew |
| 130-141 | staple blocks | 200-205 | stairs |
| 206-210 | slabs | 211 | torch |
| 259 | sticky piston head | 260-261 | horizontal oak logs |
| 262 | andesite_casing | 263 | encased_fan |
| 274 | villager_tv | 291-296 | kinetic blocks (see below) |
| 297-328 | colored redstone lamps (16 × off/on) | 329-336 | repeaters (4 dirs × off/on) |
| 337-352 | comparators (4 dirs × off/on × compare/subtract) | 353-356 | clutch, clutch_on, gearshift, gearshift_on |
| 357-390 | dye items + nether quartz (item/drop models) | - | - |
| 394 | blaze_burner (unlit) | 395 | blaze_burner_lit |
| 396 | steam_engine (cold) | 397 | steam_engine_active |
| 398-403 | copper_tank (levels 0-5) | 404-409 | tank level variants |

## Kinetic Network (KineticManager)

- **Sources:** a `water_wheel` adjacent to water (4 horizontal neighbors or below) generates rotation. **Propagation:** any kinetic block (shafts 291-293, cogwheel 294, large_cogwheel 295, water_wheel 296, clutch/gearshift 353-356) connects to kinetic neighbors in all 6 directions.
- **Clutch (353/354):** redstone-powered → disengages (blocks propagation). **Gearshift (355/356):** redstone-powered → reverses the spin direction of everything downstream.
- Every voxel in an active network gets flags in packed-voxel bits 24-25 (bit 24 = spinning, bit 25 = reversed), written via `ChunkManager.setVoxelWithFlags` (marks the chunk dirty; no relight). The raytracer threads these out of `traceWorld`/`traceAll` and gates/reverses the cog/wheel texture animation — so a lone cog is static, a wheel-fed network spins, and a powered gearshift spins it backwards. Dropped/crafting-grid items still always animate.
- `KineticManager.tick()` runs after `redstoneManager.tickLamps()` (clutch power reads the fresh power map); swaps are applied on the GL thread via `applySwaps()` next to `applyLampChanges()`. Positions are tracked on place/break (BlockInteraction) plus an incremental per-column rescan (covers dimension switches / world reloads). Note: kinetic flags are not persisted in the chunk save format (recomputed live).

## Redstone Expansion

- **Colored lamps (297-328):** 16 colors (white…black, MC dye order), off = 297+2c, on = off+1. Toggle via `id ^ 1` in `RedstoneManager.evaluateLampStates`; lit variants are emissive. Textures are the vanilla lamp art tinted per color (`tools/generate_redstone_assets.py`). Crafted from `redstone_lamp` + matching dye.
- **Repeaters (329-336):** off = 329-332 (north/south/west/east), on = 333-336 (`id ^ 4`). Delay 1-4 redstone ticks stored in voxel extra bits 0-3 (1 tick = 6 logic ticks at 60 TPS); right-click cycles it. Input = back face (wire power or strong source), output = strong 15 at the front cell, seeded into `rebuildNetwork` Phase 1b (repeat delay via `repeaterTimers` map).
- **Comparators (337-352):** direction + mode are baked into the ID — off 337-340, on 341-344, subtract-off 345-348, subtract-on 349-352 (toggle mode via `id ^ 8`, on/off via `id ^ 4`). Back input = chest (118) or furnace (116/117) fill strength, else wire/strong power behind; compares against the max of the two side inputs (compare = `input>=side ? input : 0`, subtract = `max(0, input-side)`). Output strength seeded at the front cell. `RedstoneManager.setContainerManagers(...)` attaches chest/furnace for fill reads.
- **Wires** connect visually and logically to lamps/repeaters/comparators (they're redstone components). `applyLampChanges` now preserves the voxel extra byte on every swap so repeater delays survive.

### Kinetic Blocks (Create-inspired, IDs 291-296)

| ID | Name | Notes |
|----|------|-------|
| 291 | shaft | Vertical (Y axis) 4px column; `axis` side + `axis_top` caps |
| 292 | shaft_x | Horizontal along X (placed from clicked face, drops `shaft`) |
| 293 | shaft_z | Horizontal along Z (placed from clicked face, drops `shaft`) |
| 294 | cogwheel | 6px horizontal disc, animated spinning cogwheel texture |
| 295 | large_cogwheel | 8px horizontal disc, animated spinning texture |
| 296 | water_wheel | 14px vertical disc facing N/S, `wheel` + `axis` hub, animated |

- Textures come from the old Create mod repo (mc1.16 branch): `axis`, `axis_top`, `cogwheel`, `large_cogwheel`, `wheel`. The cogs/wheel textures are 8-frame 16×128 vertical strips (generated by `tools/generate_kinetic_animations.py`) — TextureManager auto-detects them and the raytracer cycles frames at ~20 fps, so placed blocks visibly spin.
- These are AABB blocks (`setFullBlock(false)`): model JSON `elements` define the shape, texture alpha punches holes (gear gaps), and light passes through (`getOpacity` = 0).
- Dropped items render the block's own AABB model spinning (OBB path), so a dropped cog is a spinning mini-gear.
- Recipes (CraftingManager): 2× andesite_casing → 4 shaft (shapeless); stick ring + casing → 8 cogwheel; stick corners + planks + casing → 2 large_cogwheel; oak_slab ring + large_cogwheel → 1 water_wheel.

## Nether Mobs (Create-inspired)

- **BlazeEntity.java** — hostile flying mob (3 health bars, 4-block hover height). AI: approaches within 8 blocks, retreats when closer than 3, strafes left/right. Fires `FireballEntity` projectiles every 1.5s when lined up. Drops 0-1 blaze rods on death. Model: `models/entity/blaze.json` (smoke rings at 3 heights + core/head rotation). Natural spawns in nether (2 BlazeEntities on dimension entry if < 2 nether entities exist). Texture: generated 64×64 `textures/entity/blaze.png`.
- **ZombiePigmanEntity.java** — neutral mob (pre-1.16 gold-sword model with zombie animations). 2 health bars. Passive until attacked; then horde-aggros ALL pigmen within 40 blocks (sets `aggroTarget` to attacker, sprint + group-swarm). Restores 20 HP every 5s when out of combat. Drops 0-1 rotten flesh, 0-1 gold nugget on death. Spawns 5 on nether entry. Model: `models/entity/zombie_pigman.json` (head/body/legs/arms + gold sword), texture: `textures/entity/zombie_pigman.png`.
- **FireballEntity.java** — projectile entity with 4s lifetime. Moves 12 blocks/s in aim direction. `world == null` → falls back to `GameContext.activeWorld` for voxel collision. Deals 4 damage on contact + knockback, expires immediately. Cleaned up in Main's logic tick via `EntityManager.pruneExpired()` (non-Fireball entities ignored). Texture: `textures/entity/fireball.png`.

## Blaze Burner & Steam Engine (Create-inspired)

- **BlazeBurnerManager.java** — per-block fuel tracking. Blaze burner (ID 394) lit → swaps to `blaze_burner_lit` (395; emissive). Accepts coal (+30s), blaze rod (+60s), blaze powder (+20s) via right-click. Lit state propagates to adjacent steam engines. Uses `Set<Long>` for active positions; drain-swaps applied on GL thread alongside KineticManager swaps.
- **Steam engine (IDs 396/397):** cold = 396, active = 397 (emissive). When adjacent to a lit blaze burner, swaps to active and becomes a **kinetic source** (KineticManager BFS seeds rotation from each active engine). Right-click shows fuel timer in HUD.
- **CopperTankManager.java** — fluid storage per-block. Right-click with empty bucket fills bucket (water), right-click with water bucket drains into tank. 6 visible levels (0-5, IDs 398-403), each with a progressively higher fluid window. Tank_5 is full; tank_0 is empty (window dark). Level swaps applied via drain-swaps on the GL thread.

## Nether Fortress (MapGenFortress)

- **MapGenFortress.java** — corridor+bridge nether structure generator. Seedable per-column (`(cx*0x5DEECE66DL+cz)^0x5DEECE66DL`). Generates at cy=2 (y 32-47) in NETHER dimension during `DimensionWorldGenerator.decorate()`. Places `nether_brick` blocks for walls/floors (3-block-wide corridors, 4-block ceiling), with occasional cross-junctions and pillar supports. Uses `World.setVoxelInPool()` to write into the indirection-table-managed chunk pool.

## Entity Management

- **EntityManager.pruneExpired()** — removes dead/expired entities from the list. FireballEntity sets `isDead=true` and adds itself to `expired` set on collision/timeout. Called every tick in Main.java's logic thread after fireball cleanup loop.

## New Items

| Item ID | Name | Drop Block ID |
|---------|------|--------------|
| 231 | gunpowder | 250 |
| 232 | blaze_rod | 251 |
| 233 | blaze_powder | 252 |
| 234 | fire_charge | 253 |

## New Recipes (CraftingManager)

| Recipe | Grid | Result |
|--------|------|--------|
| blaze_powder | 2×2 shapeless: 1 blaze_rod | 2 blaze_powder |
| fire_charge | 2×2 shapeless: 1 blaze_powder + 1 gunpowder + 1 coal | 3 fire_charge |
| blaze_burner | 3×3 shaped: iron ring + furnace center + blaze_rod above | 1 blaze_burner |
| steam_engine | 3×3 shaped: copper ring + blaze_burner center + iron ingot above | 1 steam_engine |
| copper_tank | 3×3 shaped: copper ring + empty center + copper_ingot above | 1 copper_tank |

## Villager System

- **VillagerEntity.java** – peaceful NPC with own model (`villager.json`: big nose, robe, hat)
- AI states: IDLE → WANDERING → BUILDING → FORTIFYING → FLEEING → WATCHING_TV
- Block place/break via `BuildTask` queue, builds houses (planks + cobblestone + glass + roof)
- Village walls fortification, walks within village radius, crosses arms when idle
- **VillagerVillageManager.java** – tracks villages, assigns building projects, manages TV gatherings
- **Java villager voice (Java 8, no Python):** right-clicking a villager picks a profession/time-aware line for the HUD and synthesizes natural English speech in the Element Animation villager timbre: Coqui VCTK VITS base (`coqui-vctk-vits.onnx`) -> ContentVec (`vec-768-layer-12.onnx`) -> RVC v2 timbre model (`rvc-villager.onnx`, exported from the Dan Lloyd / Element Animation RVC checkpoint). All inference runs in Java via ONNX Runtime with a pure-Java cmudict frontend; no Python, eSpeak, subprocess, or network at runtime. The transcript-renamed TEAVSRP corpus in `voice/corpus/` is replayable with the CLI `--mode reference`.
- **VillagerTVSystem.java** – 4 channels:
  - 0: Static/Off-Air, 1: Villager Shopping Network, 2: Weather & Time, 3: VNN Villager News
- TV block (ID 274): right-click = zoom cutscene, LEFT/RIGHT = cycle channels, ESC = exit
- `/locate village` – find nearest village; `/tv <0-3>` – change TV channel
- **MapGenVillage improved**: log corners, glass windows, roof overhangs, cobblestone plaza, gravel paths, glowstone light posts, perimeter walls, workshops with crafting tables

### 2×2 Shaped Recipes on the 3×3 Crafting Table
- Stick (and other 2×2 shaped) recipes previously only matched on the 2×2 surface-crafting grid: `matchRecipe3x3` fell back to 2×2 **shapeless** recipes only. Now shaped 2×2 patterns also match on the table — `matchesPatternRotation` takes a top-left offset and rotates within the pattern's own dimensions, and `matchesPattern2x2On3x3` tries all 4 placements × 4 rotations. Sticks (2 planks in a column, 7 plank variants) and 2×2 compacting recipes (packed ice, etc.) now craft on the table.

### Biome Registry Init Race Fix (boot NPE)
- **Root cause:** `BiomeRegistry.init()` set the `initialized` flag to true *before* registering the ~110 biomes. The world-gen thread's first `getBiome()` (via `BiomeManager.fillBiomeDataForChunk`) could observe `initialized == true` while the map was still half-populated (`DimensionWorldGenerator` also calls `init()` on the main thread when other dimensions are created) → `biomeById.get(id)` returned null → NPE at `BiomeManager.getBiomeTempHumidity`.
- **Fix:** `init()` is now double-checked-locked on the class with a `volatile initialized` flag published only *after* `populate()` completes, plus a re-entrancy guard (`initializing`) so a biome constructor can't recurse into `populate()`. `getBiome(int)`/`getBiome(String)` fall back to PLAINS instead of null; `BiomeManager.getBiomeTempHumidity` null-checks the provider and biome (temperate fallback 0.7/0.5); the Beta anonymous provider bounds-checks `betaId` against `BETA_TO_VE_BIOME`.

### Crafting Table Camera Fix (detached camera bug)
- **Root cause:** `loop()`'s camera decomposition used the first-person player eye whenever `cameraMode == FIRST_PERSON`, even while the crafting cutscene / crafting table / furnace cutscene were active. `getActiveCameraPosition()` correctly returned the cutscene/target camera, but the shader ray origin was the player eye — so the rendered crafting view never matched `raycastCraftingCell` (which casts from the cutscene camera). Grid clicks landed on the wrong cells (or missed entirely), ingredients went to wrong slots, and the CRAFT button appeared not to register — the misalignment varied with the cutscene yaw, matching the "certain angles" report.
- **Fix:** `loop()` now routes those states through the cameraPos-based decomposition (`detachedCamera = ctx.craftingCutsceneActive || ctx.craftingTableOpen || ctx.furnaceCutsceneActive`). `Player.getInterpolatedPosition` and the fixed-point `pxTP` read the same longs, so the else-branch math resolves exactly to `cameraPos` even mid-walk.
- **Also fixed:** `handleMouseButton` returns early during cutscenes (clicks could otherwise place/break blocks or re-trigger the cutscene); `updateCursorMode` resyncs `lastMouseX/Y` from `glfwGetCursorPos` when releasing the cursor (GLFW virtual cursor drifts under `GLFW_CURSOR_DISABLED`, so the first post-cutscene click could register at a stale coordinate).

## Texture Generation

- `tools/gen_nether_create_textures.py` — generates all nether/Create textures: blaze entity, zombie pigman skin, fireball, blaze burner (lit/unlit), steam engine (cold/active), copper tank (6 frames + full), plus item textures (gunpowder, blaze_rod, blaze_powder, fire_charge). Use Pillow (`pip install Pillow`). Rerun after editing the generator to refresh all textures.

## Important Patterns

- **Globals on Main:** `window`, `renderTexture`, all SSBO handles, `ctx` reference
- **Thread safety:** Render thread owns GL; LogicThread ticks game state; `volatile` flags for cross-thread sync
- **SSBO uploads:** Dirty-slots set caps at 48/frame; light pool uploaded via `lightsNeedUpload` flag
- **World save:** `dev/world/` folder, `WorldSaveManager` handles chunk + crafting/furnace/chest persistence
- **Dimension switch:** Saves UI state, drops old-dimension items, scales coordinates, re-scans spawn surface
- **Error handling:** OpenGL errors via `GLUtil.checkError()`, runtime exceptions for shader/setup failures

## World Size & Border

- **WorldSize enum:** TINY(16b)/SMALL(18b)/MEDIUM(20b)/LARGE(24b)/HUGE(28b) — controls X/Z int bit width. Higher bits push the Far Lands further out and expand the hard world border. `WorldSize.fromString(name)` parses case-insensitively.
- **WorldBorderManager:** Hard world border at `(1L << (intBits-1)) - 16` blocks. `clamp(Player)` pushes player back on X/Z and displays a 3s "You have reached the world border!" message. `getBorderMessage()` returns transient messages for HUD display.
- **BetaPrecisionTuning.xzIntBits:** Configurable field (default 20). `setWorldSize(WorldSize)` on `BetaWorldGenerator` (delegates to `BetaNumericProfile.setXzIntBits()`) pushes the int bit width to the active precision policy. Both `OverworldBetaPrecision` and `Error502BetaPrecision` read from this field instead of hardcoding 20.
- **Startup menu:** `GameContext.worldSizeMenu = true` at launch shows a world-size selection screen (UP/DOWN/ENTER) using the existing spawn-loading overlay. `worldSizeSelection` is the index, `worldSizeConfirmed` gates world creation. `tick()` defers `initializeWorldPhase()` until confirmed.
- **`/worldsize <tiny|small|medium|large|huge>` command:** Changes world size at runtime. Pushes new int bits into the active Beta generator and recomputes the border. Existing chunks retain old precision; new chunks use the new size.
