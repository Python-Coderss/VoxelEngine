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

- **WorldSize enum:** TINY(16b)/SMALL(18b)/MEDIUM(20b)/LARGE(24b)/HUGE(28b) — legacy from the precision layer; no longer affects generation (the 1.8.1 port is vanilla precision). `WorldSize.fromString(name)` still parses case-insensitively.
- **WorldBorderManager:** Hard world border at `(1L << (intBits-1)) - 16` blocks. `clamp(Player)` pushes player back on X/Z and displays a 3s "You have reached the world border!" message. `getBorderMessage()` returns transient messages for HUD display.
- **World border:** `WorldBorderManager` clamps the player at `(1L << (intBits-1)) - 16` with the default 20-bit width (the `WorldSize`/`/worldsize` machinery from the old precision layer is retained but `setWorldSize` is a no-op, so the border no longer moves).

### Point-and-Click Function Pass (2026-08-21)

**Input split in BlockInteraction:** `tryUseTarget(hit, hitBlock)` = shared use-dispatch (eye insert/throw, command editor, TV/furnace cutscenes, blaze burner, copper tank, crank, bearing, vault/chest, repeater/comparator, crafting table); `finishPlaceAttempt` = item tail + placement; `attemptClickInteract()` = PAC left-click uses the hovered target (Main.handleMouseButton consumes click so it doesn't also mine). Holding still mines; FPS mode unchanged.

**Raycast:** `raycastBlock(maxDist, includeFluids)` exact DDA traversal; fluids transparent unless includeFluids (bucket scoop). Per-entity pick boxes via Entity.getPickWidth/Height (dragon 4x5, wither 3x3.5, magma scales); melee reach 6.0 matches hover affordance. updatePointAndClick deactivates during cutscenes/cinematics.

**Cutscenes:** ESC skips cinematic scenes (+HUD "ESC to skip"); level-up queue no longer swallowed; nightfall guards UI/death/cutscenes; directed cameras lift out of terrain; crafting/furnace walk targets validate walkable sides; TV camera sits player-side and aims at the screen.

**Chest model + hinge animation (in progress):** chest.json elements (body+lid+latch), isFullBlock=false forced for chest, hasHinge bit = d3.w bit 7, shader rotates lid ray around back-hinge by CPU-ticked uniform angle.
