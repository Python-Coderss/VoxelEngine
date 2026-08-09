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
- `/dimension error502` opens a separate saved Beta dimension (`dev/world/error502`) using the current coordinate-aware `BetaNumericProfile.DEFAULT` preset, without changing Overworld terrain. The normal Overworld uses `BetaNumericProfile.STANDARD_BETA` instead.
- `BetaNumericProfile.STANDARD_BETA` is used by the normal Overworld: `shortBits=10`, X/Z `intBits=20`, Y `intBits=15`, standard float `8/23`, and standard double `11/52` (exponent/mantissa). Only the integer widths are nonstandard, preserving the normal integer-driven Beta Far Lands behavior.
- The editable `BetaNumericProfile.DEFAULT` preset remains reserved for `error502`: `shortBits=10`, X/Z `intBits=20`, Y `intBits=15`, X/Z float `8/14` and Y float `8/11` (exponent/mantissa), X/Z double `11/26` and Y double `11/11` (exponent/mantissa). Its coordinate-aware floating stages intentionally produce the experimental degradation bands.
- Derived float values are also quantized with `floatValueAtDistance(...)`: the local angle, sine/cosine, radius, and interpolation value is quantized using the signed dominant world coordinate as its ULP context. For coordinate-specific tuning, edit the X/Y/Z switch functions in `BetaPrecisionTuning.java` (for example `xFloatMantissaBits(x)`, `yIntBits(y)`, or `zDoubleMantissaBits(z)`) and change the return value for the desired coordinate band. Absolute coordinates continue to use `floatValue(...)`; this avoids double-counting the world offset. The same distance-aware routing is used by Perlin/simplex noise, cave shape evolution, and ore-vein geometry.
- The independent 15-bit Y lattice control targets the observed vertical Far Lands threshold near **188 blocks**. Y float/double precision is independently configurable so higher-coordinate degradation can be tuned without changing X/Z terrain.
- With the 20-bit X/Z integer precision and X/Z float `8/14` preset, the intended X/Z float degradation/Far Lands range is approximately **8x farther** than the previous ~3,060-block boundary (roughly 24,500 blocks; verify empirically because octave scaling and sampler offsets affect the exact edge).
- This is an observed terrain/noise boundary, not the legacy `12,550,821` constant; the effective threshold depends on the configured numeric widths and sampler offsets.
- Beta section generation uses cached bulk section population and does not require a per-voxel `getHeight()` query.
- **Far-lands Y ceiling (y≈2000):** degraded coordinates previously packed columns solid to the buffer top (y=2047), starving the chunk pool and leaving the far lands with no sky. `BetaChunkProvider.func_4061_a` (the single density source for the initial column fill and on-demand sections) now forces density negative above y≈2000 with a quadratic fade from y=1890, so the far lands fade back to air by y=2000 in every Beta dimension (overworld + error502). `evaluateDensity` shares the same guard. Nether/End are bounded near their column heights; the Aether's 128-block repeating sky islands are by design.
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
| 274 | villager_tv | - | - |

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

## Important Patterns

- **Globals on Main:** `window`, `renderTexture`, all SSBO handles, `ctx` reference
- **Thread safety:** Render thread owns GL; LogicThread ticks game state; `volatile` flags for cross-thread sync
- **SSBO uploads:** Dirty-slots set caps at 48/frame; light pool uploaded via `lightsNeedUpload` flag
- **World save:** `dev/world/` folder, `WorldSaveManager` handles chunk + crafting/furnace/chest persistence
- **Dimension switch:** Saves UI state, drops old-dimension items, scales coordinates, re-scans spawn surface
- **Error handling:** OpenGL errors via `GLUtil.checkError()`, runtime exceptions for shader/setup failures
