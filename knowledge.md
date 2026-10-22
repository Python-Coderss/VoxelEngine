# VoxelEngine Knowledge

A Minecraft-style voxel engine built in Java with OpenGL 4.3+ compute shaders.

## Build & Run

- **Build system:** Maven (`./mvnw compile`); `./mvnw -o test` is canonical and must stay green
- **Fast loop:** `tools/devtest.sh <TestClass>` — incremental javac (only stale sources, tracked by per-source stamp files under `target/devloop/stamps`) + direct JUnit, ~2.2 s warm / ~2.5 s with an edit (measured on `Main.java`, the 9k-line worst case). `javac` runs with `-J-XX:TieredStopAtLevel=1 -J-Xshare:auto` because C2 warmup doubles the cost of a short-lived compile. `--full` drops all classes and stamps; `--all` runs every `*Test` (729 tests in ~50 s, versus several minutes through Maven). Maven's `useIncrementalCompilation` never takes effect in this tree, so `mvn test` recompiles everything on every invocation; keep using devtest for iteration and Maven for the final check. Never put an absolute `/c/...` path in a javac argfile and always use `;` as the Windows classpath separator — both fail silently (missing-symbol errors / classpath entry glued onto the last jar).
- **GLFW window:** 1280×720, titled "Voxel Engine"
- **Entry point:** `com.voxel.Main` — single-class orchestrator (god-object, ~2100 lines)
- **Launch script for the ai terminal coding agent that was used to make most of this project:** `launch_freebuff.bat`

## Modding API

- **Java plugins, no patching:** jars in `mods/` are extracted to `mods/.cache/` and loaded with
  `ServiceLoader` (`META-INF/services/com.voxel.api.VoxelMod`).
- **Stable surface:** `com.voxel.api` (`VoxelMod`, `ModContext`, `ModBlock`, `ModItem`) and
  `com.voxel.api.events` (`ModEvents`, `BlockEvent`, `TickEvent`, `WorldLoadEvent`).
- **Blocks:** `ModContext.registerBlock(ModBlock...)` allocates ids from **3000-4095** (built-ins
  stop below 1100). Textures come from `assets/<modid>/textures/blocks`, models from
  `assets/<modid>/models/block`. Mod textures join the single `TextureManager.loadTextures` pass
  (`ModLoader.discover` runs before it), so they share the atlas/layer offsets.
- **Items:** block items are queued by `registerBlockItem` and flushed right after
  `ItemDefinitions.setup` (`ModLoader.flushItems`), so a mod can never shadow a vanilla item id.
- **Events:** break/place fire from `BlockInteraction` after the voxel change; tick fires once per
  20 Hz logic tick in `Main.tick`; world load fires when `initializeWorldPhase` clears
  `ctx.initializing`. Listener exceptions are logged per mod and skipped.
- **Failure isolation:** a jar that fails discovery or `onLoad` is recorded on
  `ModLoader.LoadedMod.error`; the rest still load.
- **Docs + example:** `docs/MODDING.md` and `mods/example-lantern/` (build.bat / build.sh).

## Authored Worlds

- **Tutorial** (`TutorialWorldAuthor`): 26 zones; `buildApproach()` adds the spawn forecourt,
  a five-wide stone-brick avenue to z=38, alternating lantern posts, notice boards, benches and
  twin oak rows. Re-export with `TutorialWorldExporter` after editing.
- **Point & Click** (`PointClickWorldAuthor`): plaza fountain + working stations, four districts,
  plus the north-east watchtower (beacon cap at G+9, inside `AREA_H`) and the south-east ruins
  garden (arch, reflecting pool, chest), both on stone-brick spurs. A detail pass fills the gaps:
  NW hamlet (3 cottages, well, vegetable plot, laundry, cart), NW pond (jetty, rowboat, stilted
  fishing hut), SW orchard + traveller's camp, farmstead barn/paddock/windmill, quarry head-frame
  + spoil heap + site office, watchtower upper storey, ruins colonnade/statue/mosaic, and
  benches/banners/planters/signposts on the plaza, market and avenues, plus a NE standing-stone circle
  and a SE walled graveyard/mausoleum. `PointClickWorldDetailTest`
  pins every new landmark; the runtime rebuild means no template export is needed (the bundled
  `pointclick_world` directory is only a fallback).
- **Showcase** (`ShowcaseWorldAuthor`): `buildLandmarks()` adds a gallery colonnade, twin gateway
  arches, a redstone clock tower, a workshop water mill, a farm windmill/orchard and four plaza
  obelisks without changing zone bounds. `buildDetailPass()` then dresses each district (plaza
  statue/planters/banners/hedges, gateway wool runners + relics + map table, gallery mosaic +
  placards + iron aisle posts, workshop pegboard/awning/cart, machine-hall shaft run + control
  desk + gear sculpture, farm paddock/shed/jetty/scarecrow/compost, walkway benches and pots) and
  scatters deterministic copses, boulders and flower patches outside the zone rectangles.
  `ShowcaseWorldDetailTest` pins the new pieces.
- Templates live in `src/main/resources/{tutorial,pointclick}_world`; the showcase builds its
  level.dat at runtime (`ShowcaseWorldExporter`). Tests pin zone metadata, coordinates and the
  exported template's content.

## Default World: pack.png

- New worlds default to Minecraft@Home's cracked **pack.png seed `3257840388504953787`** (`com.voxel.world.PackPng`).
- Camera column `x=49, z=0` looking at the hill (`x=116, z=-31`); the player spawns there and faces it (`SPAWN_YAW`).
- The Overworld generator is a faithful **Beta 1.8.1** port, so this is the same seed/region, not a pixel-identical copy of the Alpha 1.2.2a icon.
- Bundled world templates and the three prebuilt exporters carry the same seed in `level.dat`.

## Boot Timing

- An MC-styled instant title frame is presented before OpenAL, the raytracer compile and content registration (`BootScreen`).
- Linked program binaries are cached under `target/shader-cache` (SHA-256 of sources + GL renderer/version); a warm boot loads `raytracer.comp` in ~27 ms instead of ~6.7 s. Disable with `-Dvoxel.shader.cache=off`.
- Typical cold start on this machine: GLFW 64 ms, window 118 ms, GL context 243 ms, title frame ~0.70 s; the remaining seconds are texture/content registration.

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
├── PortalSystem — dimension portal teleport; orientation-aware lighting:
│   X-spanning frames place the *_ns portal block (nether 19 / aether 106),
│   Z-spanning frames the *_ew twin (128 / 127) — matches the vanilla-Aether
│   blockstate (axis=x → ns model, axis=z → ew model). checkTeleport accepts
│   both variants of each portal type.
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
- **Brightness balance (rebalanced):** the emissive byte is a brightness FRACTION — `LightEngine.seedLevel` maps it to the grid level (byte×15/255), so sub-maximum emitters spread less (torch 238→14, lit furnace 220→13, portals 180→11) instead of everything flooding at 15. In raytracer.comp a full lamp is **0.25 suns**: `sceneN = max(skyNorm, blockNorm)` picks the dominant source and the lamp branch paints its own tinted colour (`clamp(sunLum×0.25, 0.20, 0.95)`) over a 0.045 cave floor — lamps are distinguishable outdoors and plainly visible in caves.
- **Sky light model:** straight-down sweep only — opaque opacity subtracts, leaves/foliage transmit ×0.8 per block (horizontal cost −3), the old "8 clear blocks = sun" clause is gone. Sunlight **branches sideways every 4 blocks of descent** (`BRANCH_EVERY`): branch nodes flood 6-directionally at −1/step so openings form soft cones; photons at unloaded sections still park/resume.
- **Variable exposure:** every 8 frames a 64×64 centre crop of the output is mip-pooled to 1×1 for average luminance; `currentExposure` chases photographic-grey (key 0.18, clamped 0.65–2.4) exponentially and multiplies the scene before ACES (`u_Exposure`, location 6). UI pixels bypass it.

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

## Portals

- **Orientation-aware lighting (`PortalSystem`):** lighting a frame scans both axes; a frame spanning **X** fills with the `*_ns` portal block (faces point north/south — vanilla blockstate `axis=x`), a frame spanning **Z** with the `*_ew` twin (`axis=z`). Nether = flint & steel → 19 (ns) / 128 (ew, model `nether_portal_ew.json`); Aether = water bucket → 106 (ns) / 127 (ew). `checkTeleport` accepts either variant of each type, and the interaction billboards name/scan all four IDs. Both portal blocks use transparency 60 + emissive 180 (the mod lights the Aether portal at lightLevel(11)) with the blue `setLightColor` tint.
- **Animated strips & mcmeta frametime:** vertical-strip textures (h = n·16) get one atlas layer per frame; `TextureManager.detectFrameTicks` now parses the sibling `.png.mcmeta` `animation.frametime` (default 1). `BlockDataManager` packs it into block data word 3 bits 24-31 and the raytracer steps frames at `20 / frametime` fps in BOTH animated paths, so the Aether portal strip ("frametime": 2) plays at MC's 10 fps instead of double speed. Water/lava/fire (`{}` mcmeta) are unaffected.

## Railways & Minecarts

- **Rails:** straight IDs 391 (`rail_ns`, along Z) and 392 (`rail_ew`, along X, horizontal `rail_normal_ew.png` texture) + curved corners 450-453 (`rail_curve_se/sw/nw/ne`, `rail_normal_turned.png` L-texture with flip UVs); all are 1/16-tall slab models. Placement requires a full block below; `BlockInteraction.chooseRailShape` picks straights from the look direction or rail neighbours, and exactly one N-S + one E-W neighbour auto-creates a curve; `refreshRailShapes` converts neighbours after place/break (Beta RailLogic behaviour). Item `rail` (6 iron ingots → 16). All six IDs drop the rail item.
- **Minecart:** item `minecart` (5 iron ingots) spawns a `MinecartEntity` on the target rail (queued via `GameContext.minecartSpawnQueue` from the GL thread, drained on the logic thread). Entity model `models/entity/minecart.json` (6 cuboid-atlas parts, ported from the 1.12.2 `ModelMinecart` **including the render Y-flip** Minecraft applies via `scale(-1,-1,1)` — deck 20×16×2 (base `from [-10,-7,-1]`, rot X90°Y180°) + 4 walls 16×8×2 + a fillable dirt slab). Because our `cuboid_atlas` shader maps +Z/−Z box faces swapped vs `ModelBox`, the wall rotations are chosen so each wall's OUTER face lands on local −Z: north Y0°, south Y180°, west Y90°, east Y270°. Net result matches vanilla exactly: every wall's OUTER face samples the light-grey region (uv x 20-36) and the inner faces the dark body (x 2-18), the deck sits at the BOTTOM (y 0–0.125) with walls rising to y 0.625, and the deck top samples the dark-grey floor panel (x 2-22, y 12-28). Uses the **vanilla** 64×32 `textures/entity/minecart.png` (uploaded at native size into the top half of the 64×64 entity array — `TextureManager` special-cases it, no 2× vertical stretch — so model UVs are vanilla pixel coords).
- **Fill level:** `MinecartEntity.fillLevel` (0 empty → 1 full) moves the `dirt` part's Y offset (`offset.y = 1 + fill*8` in 1/16 units) so the dirt slab — whose top face samples the legacy dirt region (uv 33,12 → x 45-61, y 12-24) — rises from just below the interior floor (hidden when empty) to the wall tops when full. Right-click the cart with dirt in hand fills it one notch (8 dirt = full); shift-right-click empties it.
- **Physics (`MinecartEntity.updateCart`):** follows the rail under its center along the rail's axis, arcing through curve corners on a quarter-circle centred on the cell corner (entry edge tangent to the straight, radius 0.5); accelerates from rider W/S input, coasts with friction, parks at track ends, clamps fall steps to 0.5 blocks so thin floors can't be tunnelled. Off rails it falls with gravity until something is below. Position is snapshotted each tick (`snapshotPrev()`) so the render interpolation is smooth.
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
| ERROR502 | 4 | 1.0× | Isolated Beta terrain using the same faithful Beta 1.8.1 generator as the Overworld |

## Key Recent Changes

### Beta 1.8.1 Generator Port
- The Overworld (and ERROR502) now use a **faithful port of the Beta 1.8.1 (Adventure Update) generator** — the first biome-driven "continental" terrain with large oceans/landmasses — ported from the decompiled b1.8.1 source (`../beta 1.8/minecraft/src`). The old 1.7.3 temperature/humidity generator and the entire coordinate-aware precision layer (Far Lands tuning) were deleted. All math is vanilla double precision; obfuscated field/method names in `com.voxel.world.beta` match the source for line-by-line verification.
- **Terrain:** `BetaChunkProvider.func_4061_a` is the 1.8.1 continental density field — 5×5 biome height/variation kernel (`field_35388_l` weights), 16-octave `field_921_b` continental noise at 200.0 scale `/8000`, 10-octave `field_922_a` at 1.121, `684.412` base octaves, the `dist*4` below-base ramp and top 4-slice fade. `replaceBlocksForBiome` reproduces the vanilla surface dressing incl. `rand.nextInt(5)` bedrock and the sand→sandstone underfill. Sea level 63.
- **Biomes:** `BetaGenLayer.func_35497_a` builds the full 1.8.1 layer chain (Island → ZoomFuzzy/Zoom → RiverInit/River/Smooth → VillageLandscape → Temperature/Downfall mixes → Voronoi). `BetaBiomeGenBase` carries the vanilla 1.8.1 set (0–9) **plus the six legacy 1.7.3 biomes (10–15: rainforest, seasonal forest, savanna, shrubland, ice desert, tundra)**, which `BetaGenLayerVillageLandscape` appends to its 12-biome assignment table. `BetaWorldGenerator.BETA_TO_VE_BIOME` maps them to the engine `BiomeRegistry`.
- **Caves/ravines:** `BetaMapGenCaves`/`BetaMapGenRavine` are the 1.8.1 ports (MathHelper SIN_TABLE quantization, 8-chunk range); `BetaMathHelper` provides the 16-bit sin/cos table.
- **Decoration:** `BetaBiomeDecorator` + the `BetaWorldGen*` classes are faithful ports (ore/dirt/gravel veins, trees by `treeType` selector: oak/big/forest/taiga/swamp, flowers, mushrooms, reeds, cactus, tall grass, dead bush, pumpkin, clay, sand/gravel patches, lakes, liquids, 8 dungeons/chunk). Blocks write through `BetaGenContext` (provider implements it) into both the world and the internal column cache; `BetaBlocks` holds the engine block ids.
- **Cubic chunks kept:** terrain exists at y 0..127 (classic 1.8.1 band, generated once per column), density-evaluated deep stone below 0, air above. `populateColumn` is called once per column from `BetaWorldGenerator.decorate` (cy == 4).
- `BetaChunkProvider(seed, BetaBlocks)` is the only public constructor — no numeric profiles. `setWorldSize` on `BetaWorldGenerator` is a no-op kept for old callers; the world border stays at the fixed default.
- Seed derivation unchanged: `worldSeed ^ ((ordinal+1) * 0x9E3779B97F4A7C15L)` per dimension (`DimensionManager.seedFor`).

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

Orientable logs: every log type (oak 5, birch 46, spruce 47, jungle 49, acacia 51,
dark_oak 52, aether skyroot 103) has X/Z variants (260/261, 438-449) chosen from
the clicked face at placement (piston-style per-face texture models); all variants
drop their base log item.
| 14 | sand | 15 | water |
| 16 | obsidian | 17 | glowstone |
| 18 | end_stone | 19 | nether_portal |
| 20-24 | nether blocks | 25-30 | redstone blocks |
| 31-33 | pistons | 34-91 | biome decoration |
| 100-114 | aether blocks | 115-118 | functional blocks |
| 119-126 | vegetation/decorative | 127 | aether_portal_ew |
| 128 | nether_portal_ew | 130-141 | staple blocks |
| 200-205 | stairs | 206-210 | slabs |
| 211 | torch | 259 | sticky piston head |
| 260-261 | horizontal oak logs | 262-263 | andesite_casing, encased_fan |
| 274 | villager_tv | 291-296 | kinetic blocks (see below) |
| 297-328 | colored redstone lamps (16 × off/on) | 329-336 | repeaters (4 dirs × off/on) |
| 337-352 | comparators (4 dirs × off/on × compare/subtract) | 353-356 | clutch, clutch_on, gearshift, gearshift_on |
| 357-390 | dye items + nether quartz (item/drop models) | - | - |
| 394 | blaze_burner (unlit) | 395 | blaze_burner_lit |
| 396 | steam_engine (cold) | 397 | steam_engine_active |
| 398-403 | copper_tank (levels 0-5) | 404-409 | tank level variants |
| 916-917 | formidi_bomb, white_pumpkin | 920-926 | ancient-builder modern blocks (concrete 920, concrete_dark 921, steel_beam 922, ceiling_light 923, tile_block 924, office_glass 925, marble 926) |

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

- **MapGenFortress.java** — corridor+bridge nether structure generator. Seedable per-column (`(cx*0x5DEECE66DL+cz)^0x5DEECE66DL`). Generates at cy=2 (y 32-47) in NETHER dimension during `DimensionWorldGenerator.decorate()`. Places `nether_brick` blocks for walls/floors (3-block-wide corridors, 4-block ceiling), with occasional cross-junctions and pillar supports. Uses `World.setVoxelInPool()` to write into the indirection-table-managed chunk pool. Detail: towers carry a chest on a brick plinth, soul-sand corners, wall sconces, slab benches, two braziers, an iron grate over the lava basin and a slab crenellated parapet; corridors get brick cross-beams with glowstone lamps, sconces and lit supply niches.
- **MapGenMineshaft.java** — slab-timber roofs on alternate steps, cobble post footings, lit supply alcoves, a parked minecart, gravel spill and a furnished treasure room (work table, anvils, crates, ceiling lamps, ore samples in the walls).

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
- AI states: IDLE → WANDERING → BUILDING → FORTIFYING → FLEEING → WATCHING_TV (legacy FSM; `VillagerBrain` claims ticks by default and adds the dumb-human comedy layer: distractions, forgetfulness, bravado-then-panic, arguments, slapstick building — shared with mobs via `ComedyMind`, escalated by the village-wide `Chaos` meter)
- Block place/break via `BuildTask` queue, builds houses (planks + cobblestone + glass + roof)
- Village walls fortification, walks within village radius, crosses arms when idle
- **VillagerVillageManager.java** – tracks villages, assigns building projects, manages TV gatherings, and spawns village populations: the structure generator only *queues* a village (`queueVillageSpawn`), and `tick()` materialises it once `groundLoaded()` confirms the centre + sample ring have real ground and the player is within 160 blocks. Villagers are placed on their true surface (never inside terrain or water) with unique ids and are added to the village's villager list. Spawning during chunk generation is gone — it dropped villagers into columns that did not exist yet, which is why generated villages used to stand empty.
- **Villager professions (`VillagerProfessions`, `VillagerTrades`, `VillagerTrading`):** each trade owns a job site built from blocks villages already place (FARMER → farmland/wheat, BUILDER → crafting_table 115, SHOPKEEPER → chest 118, NEWS_ANCHOR → villager_tv 274). `assignJobSiteIfAny()` is called at spawn: the nearest job site sets the profession, so a village staffs the buildings it actually contains instead of rolling random trades (the V key and `/spawn villager` do the same). Job XP (tend 2, plant 3, harvest 5, build 4, broadcast 3, trade 6) promotes through five levels of titles (`Novice Farmer` … `Master Farmer`; a promotion is announced as a spoken line). `VillagerBrain` has a `WORK` action in the utility order (panic > shelter > build > work > socialize > wander): the villager walks to its work site and works it — farmers harvest ripe wheat (loot drops through `VillagerEntity.droppedItemManager`) and plant bare farmland, anchors broadcast at the desk, traders tend the stall, builders keep at the workbench between builds; a broken workbench sends them looking for a new one. Shift-right-click a villager with a trade to open the barter panel (offers filtered by career level, `1`-`3` to buy, `ESC` to close; trades pay job XP).
- **MapGenVillage**: villages queue their population instead of spawning entities during chunk generation; farms are real farmland + planted wheat and every house holds a chest, so the new job sites exist to be found.
- **AncientBuilderModern.java** – the ancient builders' final (modern) era before they were wiped out: glass tower, metro hall, research lab, and builder plaza stamped near spawn like MCSM sites (lazy, idempotent), each with deterministic layout variants and its own 920-926 block set (concrete/steel/tile/glass/marble/light panels)
- **Villager voice (clips first, dynamic TTS for the rest):** right-clicking a villager picks a profession/time-aware line and plays the best-matching recorded clip: Villager News addon voice (`.ogg` via stb_vorbis) + transcript-renamed TEAVSRP corpus in `voice/corpus/`, indexed by `src/main/resources/voice/clips_index.json` (rebuild: `tools/build_voice_clip_index.py`). A line a clip shares words with (`ClipIndex.bestWordMatch`, exact transcript first) keeps the recording, captioning that clip's own transcript. A line no clip is on topic for is spoken by **`CoquiVitsTts`**: a fixed-voice (non-zero-shot) Coqui VITS model trained on VCTK with 108 voices, run in-process on ONNX Runtime. Each villager is assigned one of those speakers deterministically from its entity id (`p225`…`p376`, sorted, so the same villager always sounds like itself), and the authored text is captioned with the audio rendered from it. This replaces the old unmatched-line behaviour (a random unrelated vocalization with no caption). Model files are `dev/voice-models/coqui-vctk-{vits.onnx,vocab.json,speaker_ids.json}` + `coqui-vctk-config.json`, never committed; the G2P dictionaries come from the Kokoro bundle. Verify or install the bundle with `python tools/fetch_villager_voice.py` (`--check` verifies only, `--url` + `--sha256` downloads the graph, and it writes `villager-voice.manifest.json`); it checks the ONNX input signature (`input`/`input_lengths`/`scales`/`sid`), the `<BLNK>` vocab and the contiguous `pNNN` speaker list, so a mismatched export is caught before the game silently drops back to clips. `-Dvoxel.voice.villager.dynamic=false` restores clip-only. `voxel.voice.mode`: `clip` (default) or `reference`. Live captions (`LiveCaptions`, HUD bottom-center) show every line word-by-word even without audio. Caption sync is playback-driven: `showForAudio` holds the reveal until `VillagerAudioManager.update()` actually starts the OpenAL source, so decode/queue latency can no longer run the text ahead of the voice. Word times come from `voice/clip_word_times.json` when present (`ClipWordTiming`, written by `python tools/align_voice_clips.py`, local faster-whisper word timestamps, stretched to the transcript when the recogniser mishears), otherwise from `CaptionTiming`, which finds voiced runs in the rendered PCM (20 ms RMS frames, hangover, short-gap bridging) and spreads the words by syllable weight. Cue/clip times are scaled by `ClipVoice.playbackFactor` (speed × emotion pitch), not speed alone. A 2.5 s watchdog reveals a held caption when no audio arrives.
- **Voice artifact audit (`SpectralAudit`):** frequency-domain gate for neural output — gap hiss (mean 4–10 kHz energy of quiet frames vs peak frame), tonal whine (strongest 1–8 kHz narrowband peak over its local median), spectral bursts (frame-to-frame flux spikes), clicks in quiet regions, DC offset and clipping. `CoquiVitsTts` conditions output (DC removal, silence trim with margin, edge fade, peak normalize) and `CoquiVitsHeadlessTest` runs the audit on real renders of villager lines; `SpectralAuditTest` proves a clean signal passes and a steady whine / clicks / DC offset fail. `Report.toString()` prints every metric, so a regression names the artifact instead of just failing.
- **Character voices for authored dialogue (`CharacterVoice`, `VoiceProfile`, `KokoroTts`, `MisakiEnG2P`):** ancient builders, the player ("You"), the narrator and bosses speak their written lines through the neural **Kokoro-82M** model, in-process on ONNX Runtime. The pure-Java misaki en-GB G2P produces stress-marked British phonemes; each role has its own style vector and rate from the existing profile (`PLAYER` is Male Jesse `am_puck`; `-Dvoxel.voice.jesse=female` switches to `af_heart`). Install the model once with `python tools/fetch_kokoro.py` (≈350 MB: graph, 54 style vectors, config, gb_gold/gb_silver; SHA-256 manifest, `--check`); it lands in `dev/voice-models/kokoro` and is never committed. Renders are cached as WAVs under `dev/voice-cache/tts/characters` keyed by `kokoro:<voice>`, played through `VillagerAudioManager.playRendered`, while the caption shows the exact authored text. If the model/runtime is missing (or `-Dvoxel.voice.engine=sapi|winrt`, or `-Dvoxel.voice.tts=off`), the bridge falls back to WinRT/SAPI OS voices, then the recorded clip corpus, then caption-only. Villager labels stay on their add-on clips. `CharacterVoice.speak*` rate-limits conversations (7 s), never rate-limits cutscene beats or monologue, and suppresses an identical line from the same speaker inside 4 s.
- **MCSM decision row (`CinematicChoice`, `HudUI.updateChoiceOverlay`):** timed decisions render as Minecraft: Story Mode's four-card row — one translucent card per option across the bottom (`HudUI.choiceCards`, `choiceCardAccent`, `choiceCardText`), a wide prompt banner above, and a countdown line under it that shifts gold → amber → red. Cards carry the 1-4 key hint, hover brightens the card, and clicking the card picks it (`choiceRowAt` hit-tests the drawn rectangles). `StoryBeats.tutorialFork` and `pointClickFork` both fill the row with four choices; picks remain `choice.<id>` story flags.
- **MCSM cast:** the story characters are cast from Kokoro's trained speakers — Jesse male `am_puck`, Jesse female `af_heart`, Petra `af_nova`, Lukas `am_michael`, Axel `am_adam`, Olivia `af_bella`, Ivor `bm_fable`, Soren `bm_george`, Gabriel `am_onyx`, Magnus `am_fenrir`, Ellegaard `af_jessica`, Harper `af_nicole`. `VoiceProfile.byCharacterName` resolves names, `forBuilder(id)` casts ancient builders as the Order (so a site is not one voice), `forMob(id)` voices mobs. Cutscene inner voice: `CinematicScript.monologue(at, text)` (speaker "Jesse") is routed by `CinematicSystem` through `CharacterVoice.speakMonologue`. Every synthesizing profile keeps a David SAPI fallback.
- **Villager neural planning (`VillagerPolicy`, `SpatialMind`):** the planning beat is a tiny MLP loaded from `/ai/villager_policy.json` (8 features in `FEATURES` order: build_pending, social_open, has_job_site, chaos, friend_near, player_near, threat_far, daytime; 12 tanh hidden units; 5 outputs: GO_BUILD, SOCIALIZE, WORK, WANDER, IDLE). Retrain with `python tools/train_villager_policy.py` (numpy only, writes the resource); the loader rejects a file whose `features`/`actions` order disagrees with the Java constants and the brain then uses `VillagerBrain.chooseAction`. `VillagerBrain.decide` keeps safety non-neural: swimming, threat/panic and night short-circuit the net, `jobSiteReady()` still redirects idle/wander into `WORK`, and `ComedyMind`/`Chaos` can override low-stakes plans. `SpatialMind` is the non-learning spatial layer: `isSafeStand`/`isCliff`/`openness`, `escapeTarget` (threat distance + openness − height penalty, refuses cliffs), `coverCell` (breaks line of sight via `Raycaster`), `flankCell`, `separation` (crowd push, clamped to unit length) and `sideFor`; panic pathing uses `escapeTarget` before its geometric fallback. Tests: `VillagerPolicyTest` (load/validation/malformed files, full 80-combination grid vs the trainer's labels), `SpatialMindTest`.
- **Look-at lore (`ExamineSystem`, key **H**):** `Main.examineLookTarget` raycasts the crosshair, `describe(blockName)` returns `{title, body, monologue}`; the status line shows the lore and Jesse speaks the monologue. Eleven named entries (command block, rift frame, Sift rift, Villager News desk, enchanting table, anvil, beacon, end/nether portals, door, bed) plus twelve keyword categories cover most blocks; matching is token-aware (stone ≠ redstone) and unknown blocks get a written fallback.
- Audioless behaviour is pinned by `KokoroPhonemesTest` (dictionary/G2P/vocab, no ONNX), `KokoroTtsHeadlessTest` (renders + writes a 24 kHz WAV with no OpenAL device), `AudiolessVoiceTest` (captions + cache), `DecorationModelsHeadlessTest` (models bind textures on disk) and `CharacterVoiceTest` (fallback order). Full suite runs with `ALSOFT_DRIVERS=null`; 729 tests pass.
- **VillagerTVSystem.java** – 8 channels, driven by the written schedule in `src/main/resources/voice/tv_guide.json` (`TvGuide` parses it; `TvMusicCue` holds the sung cues):
  - 0 Static/Off-Air, 1 VSN Shopping, 2 WX Weather & Time, 3 VNN Villager News, 4 VMC Music, 5 NAT Geographic, 6 KITCH Hrmm Kitchen, 7 SPORT Sports. Channels 0-3 keep their old numbers, so `/tv` and saved worlds still mean what they meant.
  - The station broadcasts from **one** collection only: the `kind="addon"` Villager News 1.0 Add-On takes (`VillagerAudioManager.TV_CLIP_KIND`, `ClipIndex.restrictedToKind`). Every scripted line is a real take and is pinned to it by `tools/bind_tv_clips.py` (`--check` verifies); captions are never paraphrases.
- TV block (ID 274): right-click = zoom cutscene, LEFT/RIGHT = cycle channels, ESC = exit
- `/locate village` – find nearest village; `/tv <0-7>` – change TV channel
- **MapGenVillage improved**: log corners, glass windows, roof overhangs, cobblestone plaza, gravel paths, glowstone light posts, perimeter walls, workshops with crafting tables. Detail pass: furnished interiors (bed, workbench, furnace, shelf, plant, lamp, chimney), stair-and-slab table settings and a wool rug in large houses, plaza benches/planters/banners/notice board, two stocked market stalls, farm scarecrow + gate gap + bench, compass-pillar lanterns on the wall, and studio chairs/camera/banners at the news desk. `VillageStructureDetailTest` pins the furnishings.

### 2×2 Shaped Recipes on the 3×3 Crafting Table
- Stick (and other 2×2 shaped) recipes previously only matched on the 2×2 surface-crafting grid: `matchRecipe3x3` fell back to 2×2 **shapeless** recipes only. Now shaped 2×2 patterns also match on the table — `matchesPatternRotation` takes a top-left offset and rotates within the pattern's own dimensions, and `matchesPattern2x2On3x3` tries all 4 placements × 4 rotations. Sticks (2 planks in a column, 7 plank variants) and 2×2 compacting recipes (packed ice, etc.) now craft on the table.

### Biome Registry Init Race Fix (boot NPE)
- **Root cause:** `BiomeRegistry.init()` set the `initialized` flag to true *before* registering the ~110 biomes. The world-gen thread's first `getBiome()` (via `BiomeManager.fillBiomeDataForChunk`) could observe `initialized == true` while the map was still half-populated (`DimensionWorldGenerator` also calls `init()` on the main thread when other dimensions are created) → `biomeById.get(id)` returned null → NPE at `BiomeManager.getBiomeTempHumidity`.
- **Fix:** `init()` is now double-checked-locked on the class with a `volatile initialized` flag published only *after* `populate()` completes, plus a re-entrancy guard (`initializing`) so a biome constructor can't recurse into `populate()`. `getBiome(int)`/`getBiome(String)` fall back to PLAINS instead of null; `BiomeManager.getBiomeTempHumidity` null-checks the provider and biome (temperate fallback 0.7/0.5); the Beta anonymous provider bounds-checks `betaId` against `BETA_TO_VE_BIOME`.

### Crafting Table Camera Fix (detached camera bug)
- **Root cause:** `loop()`'s camera decomposition used the first-person player eye whenever `cameraMode == FIRST_PERSON`, even while the crafting cutscene / crafting table / furnace cutscene were active. `getActiveCameraPosition()` correctly returned the cutscene/target camera, but the shader ray origin was the player eye — so the rendered crafting view never matched `raycastCraftingCell` (which casts from the cutscene camera). Grid clicks landed on the wrong cells (or missed entirely), ingredients went to wrong slots, and the CRAFT button appeared not to register — the misalignment varied with the cutscene yaw, matching the "certain angles" report.
- **Fix:** `loop()` now routes those states through the cameraPos-based decomposition (`detachedCamera = ctx.craftingCutsceneActive || ctx.craftingTableOpen || ctx.furnaceCutsceneActive`). `Player.getInterpolatedPosition` and the fixed-point `pxTP` read the same longs, so the else-branch math resolves exactly to `cameraPos` even mid-walk.
- **Also fixed:** `handleMouseButton` returns early during cutscenes (clicks could otherwise place/break blocks or re-trigger the cutscene); `updateCursorMode` resyncs `lastMouseX/Y` from `glfwGetCursorPos` when releasing the cursor (GLFW virtual cursor drifts under `GLFW_CURSOR_DISABLED`, so the first post-cutscene click could register at a stale coordinate).

## Game Feel (camera, jump, feedback)

- **`camera/CameraFeel`** is the single source of gameplay camera "juice" and holds no GL state:
  - **Trauma shake.** Callers push an impulse with `addTrauma(0..1)`; amplitude is `trauma²`
    and trauma decays at 1.5/s, so a hit snaps then fades instead of stopping dead. The noise is a
    sum of incommensurate sines, not `Math.random()` — the old per-frame random camera-fraction
    jitter is gone, so the shake is smooth, deterministic and frame-rate independent.
  - **Head bob** drives off the player's horizontal speed (walk 1.8 step-cycles/s, sprint 2.6) and is
    cancelled in the air / whenever `setBobEnabled(false)` (third person, cutscenes, detached cameras).
  - **Speed FOV kick** up to +6° while sprinting, eased with an exponential; `CameraController.getFovDegrees()`
    adds it and every picking path reads `getTanHalfFov()` from the same value, so aim stays exact.
- **Main.loop()** samples locomotion once per frame, calls `feel.update(dt)`, pushes the FOV offset into
  the controller, and applies shake as a **render-basis rotation** (yaw/pitch/roll) plus a ≤0.035-block
  positional wobble. Gameplay `yaw`/`pitch` are untouched, so a hit never moves the crosshair.
- **`Player` jump feel:** `COYOTE_TIME_SECONDS` (0.10 s) lets a press land just after walking off a
  ledge; `JUMP_BUFFER_SECONDS` (0.12 s) holds a press made just before touchdown and fires it on the
  next grounded tick. `consumeLandingImpact()` reports the downward speed of the last landing once, so
  the camera can scale its kick with fall height. A held Space still re-arms the buffer each frame,
  preserving vanilla auto-bounce, and an airborne press never becomes a double jump.
- **Impulse plumbing:** `Main.cameraShake` / `GameContext.cameraShake` remain the public "kick the camera"
  API; `Main.tick` drains them into `CameraFeel` trauma instead of decaying them inline. Combat hits,
  fall landings, damage taken (with `ctx.hurtFlash`) and block break/place feed it.
- **Damage flash:** `GameContext.hurtFlash` (volatile) is raised on any health loss and decayed by
  `HudUI.updateHurtOverlay(dt)`, which draws a full-screen red quad (`damageQuad`, first in the static
  layer so the hotbar/hearts/crosshair stay crisp) at `0.38 * flash²` alpha over ~0.35 s.

## Texture Generation

- `tools/gen_nether_create_textures.py` — generates all nether/Create textures: blaze entity, zombie pigman skin, fireball, blaze burner (lit/unlit), steam engine (cold/active), copper tank (6 frames + full), plus item textures (gunpowder, blaze_rod, blaze_powder, fire_charge). Use Pillow (`pip install Pillow`). Rerun after editing the generator to refresh all textures.

## Music Library & Soundtrack Pools

- **MusicLibrary** discovers every bundled track in one pass: the MCSM albums
  under `src/resources/music` (episodes 01-08, `main` only), the Aether
  soundtrack under `src/main/resources/assets/aether/sounds/music`, and any
  `minecraft/` or `fanmade/` folder added under `src/main/resources/music`.
  It skips `unused/`, files under 700 KB (stings/risers), and classified
  junk. The shipped install resolves 257 tracks.
- **Pools:** menu, calm, night, danger, combat, boss, cave, nether, end,
  aether, sift, tutorial, village, story. Classification is keyword/path based
  (`MusicLibrary.classify`), with explicit mood folders overriding keywords.
  Empty pools fall back mood-preservingly (danger -> cave -> night -> story).
- **MusicDirector.contextForScene(...)** picks the pool from dimension,
  underground-ness, hostiles, night, boss proximity, tutorial/showcase and
  village proximity. Legacy `contextFor(near, close, night)` is retained.
- **MusicPlayer** plays MP3 and OGG, decodes on a daemon worker (never on the
  render thread), crossfades context changes, and exposes `/music`
  (`auto|off|on|next|list|<pool>`).

## Guided Tutorial

- **TutorialProgress** tracks six goals (mine, craft, smelt, forge iron gear,
  portal, dungeon loot) from real action hooks in BlockInteraction,
  PlayerInventory, HudUI's furnace output and PortalSystem. The HUD draws a
  persistent top-left tracker; Main consumes completion events one at a time
  and shows a step-complete card.
- The bundled `src/main/resources/tutorial_world` template is regenerated by
  `TutorialWorldExporter.exportTo` (also a runtime fallback when the template
  is missing). `TutorialWorldTemplateTest` guards doors, zone builds, spawn and
  the starter kit against staleness.

## Multiblocks

- **Doors** are a registered two-cell multiblock. `DecorBlocks` owns the id
  layout (`lower, upper, lower_ew, upper_ew` per wood) plus `doorPartner`,
  `doorToggle` and `doorLowerForWall`; `DoorColumn` performs the actual voxel
  transitions (place the upper half, toggle both halves with the
  `DOOR_OPEN_BIT` in extra data, remove the partner on break). `BlockInteraction`
  only adds the player/entity checks and status text. The `_ew` pair is the
  same panel rotated 90°, so "open" is an id swap and needs no extra models.
- **Beds** (`BedPair`, `DecorBlocks.Bed*`): a foot cell plus a head cell one
  step along the facing. `bedFootForFacing` / `bedHeadForFoot` / `bedPartner`
  plus `bedPartnerDx/Dz` give the pair math; `BedPair.place` stamps the head and
  `BedPair.removePartner` clears the opposite cell only when it is exactly the
  expected partner id. Models for all four facings come from
  `tools/gen_bed_banner_models.py` (oak frame, white mattress, red blanket).
- **Standing banners** (`BannerColumn`, `DecorBlocks.Banner*`): a lower plus an
  upper cell, with two cloth planes (NS and EW) sharing geometry. Placement
  needs solid ground and a free cell above; breaking either half removes the
  other; every half drops the one `banner` item.
- Every two-cell family routes writes through `ChunkManager` and uses the same
  own-half/partner checks as doors: never delete a mismatched neighbour, and
  let only the clicked cell drop the item.

## Important Patterns

- **Globals on Main:** `window`, `renderTexture`, all SSBO handles, `ctx` reference
- **Thread safety:** Render thread owns GL; LogicThread ticks game state; `volatile` flags for cross-thread sync
- **SSBO uploads:** Dirty-slots set caps at 48/frame; light pool uploaded via `lightsNeedUpload` flag
- **World save:** `dev/world/` folder, `WorldSaveManager` handles chunk + crafting/furnace/chest persistence
- **Dimension switch:** Saves UI state, drops old-dimension items, scales coordinates, re-scans spawn surface
- **Error handling:** OpenGL errors via `GLUtil.checkError()`, runtime exceptions for shader/setup failures

## World Size & Border

- **WorldSize enum:** TINY(16b)/SMALL(18b)/MEDIUM(20b)/LARGE(24b)/HUGE(28b) — legacy from the precision layer; no longer affects generation (the 1.8.1 port is vanilla precision). `WorldSize.fromString(name)` still parses case-insensitively.
- **WorldBorderManager:** Hard world border at `(1L << (intBits-1)) - 16` blocks. `clamp(Player)` pushes player back on X/Z and displays a 3s "You have reached the world border!" message. `getBorderMessage()` returns transient messages for HUD display.
- **World border:** `WorldBorderManager` clamps the player at `(1L << (intBits-1)) - 16` with the default 20-bit width (the `WorldSize`/`/worldsize` machinery from the old precision layer is retained but `setWorldSize` is a no-op, so the border no longer moves).

### Point-and-Click Function Pass (2026-08-21)

**Input split in BlockInteraction:** `tryUseTarget(hit, hitBlock)` = shared use-dispatch (eye insert/throw, command editor, TV/furnace cutscenes, blaze burner, copper tank, crank, bearing, vault/chest, repeater/comparator, crafting table); `finishPlaceAttempt` = item tail + placement; `attemptClickInteract()` = PAC left-click uses the hovered target (Main.handleMouseButton consumes click so it doesn't also mine). Holding still mines; FPS mode unchanged.

**Raycast:** `raycastBlock(maxDist, includeFluids)` exact DDA traversal; fluids transparent unless includeFluids (bucket scoop). Per-entity pick boxes via Entity.getPickWidth/Height (dragon 4x5, wither 3x3.5, magma scales); melee reach 6.0 matches hover affordance. updatePointAndClick deactivates during cutscenes/cinematics.

**Cutscenes:** ESC skips cinematic scenes (+HUD "ESC to skip"); level-up queue no longer swallowed; nightfall guards UI/death/cutscenes; directed cameras lift out of terrain; crafting/furnace walk targets validate walkable sides; TV camera sits player-side and aims at the screen.

**Title wordmark (2026-10-21, resolved):** `assets/minecraft/textures/gui/title/mcsm_title.png` is re-cut from the port's `menu-spritesheet.png` whole — the art occupies y 48–109 and x 2–213 (the last letters' side faces run under the sheet's blue shortcut bar), which the old 196x64 crop sliced through — that is why the top of MINECRAFT, the bottom of STORY MODE and the right edge of both lines were missing. The extraction is 208x61. The extraction drops only the sheet's translucent white shine and the neighbouring PSP button glyphs; nothing of the logo itself is cropped. `MCGui` still reads the PNG's own opaque bounds at load time (`MCSM_TITLE_RECT`), so the title draws complete at any window size.

**Lighting demo world (2026-10-21):** `LightingDemoWorldAuthor` + `LightingDemoWorldGenerator` (menu row 4, save `lighting`, level.dat only via `LightingDemoWorldExporter`) build two halls that differ only in sky access: an open sandstone hall with a glass skylight bay in the west and a solid-roofed stone hall in the east (torches, one glowstone lamp, a glass-covered lava channel, a soul-lamp recess, and one deliberately unlit alcove), joined by a corridor whose roof has a skylight. Both halls carry the same chest/bench/pool so a frame diff is lighting only. `VOXEL_AUTO_WORLD=lighting` enters it unattended.

**Lighting data path, three fixes (2026-10-21):** (1) *Ambient is a hemisphere, not the zenith* — `AtmosphereRenderer.overworld` used 0.4x the sky colour, giving noon ambient (0.04, 0.14, 0.30) = 1 : 3.5 : 7.5, so tan sandstone rendered cyan; it now mixes in the warm ground bounce (`out[6..8]`) for about 0.7 : 0.8 : 1.0. (2) *Block light screen-blends* — `LightEngine.applyTintToMain` clamped every overlapping source into the same 255, so a lit room stored one flat maximum with no falloff; contributions now screen-blend (`a + b - a*b/255`) and keep their gradient. (3) *Debug views* — `VOXEL_DEBUG_SURF=surf` paints (sky, block, point) light into RGB and `=terms` paints (point, god rays, final), the same sentinels the panorama probe uses, so "is this room sky-lit or lamp-lit?" is a pixel read.

**UI palette (2026-10-21):** all HUD/menu chrome draws from `com.voxel.ui.UiPalette` — warm sepia panels, cream text, one amber accent (MCSM 2015 / pre-2020 vanilla, no blue-grey). Panels print on `assets/minecraft/textures/gui/mcsm_parchment.png`, a seamless 16px grain generated by `tools/gen_mcsm_ui.py`; `MCGui.mcsmPanel(element)` tiles it and the element's vertex colour tints it. New chrome should use the palette factories (`panel()`, `panelDeep()`, `cream()`, `muted()`, `amber()`, `gold()`, `ink()`) instead of new colour literals: the constants are **display sRGB**, but the factories return `display^2.2` because the HUD canvas is composited through `linearToSrgb` (a raw 0.19 reached the screen as 0.47). Use `UiPalette.srgb()` for one-off tints and `UiPalette.unencoded()` only for the boot screen, whose quad presents the canvas without that encode.

**Prompt culling + focus (2026-10-20):** `InteractionBillboardSystem.segmentOccluded(cam→anchor, BlockOccluder)` is an Amanatides & Woo walk that hides a prompt when a full block intervenes (full-block flag only, so glass/leaves/fluids stay clickable through). Projection also drops off-screen anchors instead of letting the HUD clamp their cards onto a screen edge, and publishes `getCulledOccludedCount`/`getCulledOffscreenCount` for capture diagnostics. HUD: only the hovered prompt lists verbs, idle prompts dim to `MARKER_IDLE_ALPHA` (0.58), the leader picks the roomier half of the screen and shortens its stem so the card cannot pin to an edge, and a reticle pill (`InteractionBillboardSystem.promptLine`) states what a click does. Probes: `VOXEL_PAC_PROBE` (fixed prompt layout), `VOXEL_PAC_NO_CULL` (A/B), `VOXEL_PROBE_POS`/`VOXEL_PROBE_LOOK` (camera pin), `VOXEL_UI_PROBE` prints marker/card rects.

**Chest model + hinge animation (in progress):** chest.json elements (body+lid+latch), isFullBlock=false forced for chest, hasHinge bit = d3.w bit 7, shader rotates lid ray around back-hinge by CPU-ticked uniform angle.

## The Sift, the Melody Rift & the Story Map (2026-10-05)

- **The Sift** (`DimensionType.SIFT`, base height 72) is `com.voxel.world.SiftGenerator`
  (Meadow / Carapace biomes) with `SiftTides` (240s low→high→low swell). `GameContext.siftTides`
  ticks only while the player is in the dimension.
- **Sifting:** `com.voxel.game.SiftSieve` is a pure loot table + per-block cooldown.
  `BlockInteraction.siftBed` right-clicks block 932, rolls `SiftSieve.roll(tides, rng)`, adds the
  item and shows a `LiveCaptions` line. Rare chance 8% (low tide) → 55% (high tide).
- **Rift travel:** `PortalSystem` treats `sift_portal` (934) like the Nether/Aether portals;
  entering the Sift plays `CinematicSystem.playDungeonArrival`. `Main.performBillboardAction`
  routes id 934 to `enterPortal`.
- **Melody rift:** block `note_block` (937). `com.voxel.game.MelodyPortal` is a pure static
  melody lock (`MELODY = {0,2,4,6,4,2}`, `NOTES = 7`); `pitchAt(x,y,z)` maps a stone's offset
  from the registered origin to a pitch. `com.voxel.world.AncientCity` (-384, 384) stamps a
  soul-slate plaza, a dormant `rift_frame` gate and seven tuning stones; `AncientCity.open`
  fills the gate with `sift_portal` once the tune is played. `BlockInteraction.playNoteBlock`
  drives it.
- **Story map:** `com.voxel.world.SiteShapes` is a shared shape library (plaza, town, arena,
  temple, keep, spire, canyon, swampHut, forge, outpost, riftHall, pumpkinField, forestCamp,
  skyIsland) with a `RecordingSink` for headless tests. `McsmStructures` now also stamps
  Episodes 2-8; `LegendsStructures` and `DungeonsStructures` add the Legends campaign and both
  Dungeons journeys. Every site is lazy, deterministic and idempotent, stamped from `Main`'s
  Overworld logic tick.
- **Note:** the new story sites have structures only — their mobs and story beats are still TODO.
