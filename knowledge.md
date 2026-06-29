# VoxelEngine Knowledge

A Minecraft-style voxel engine built in Java with OpenGL 4.3+ compute shaders.

## Build & Run

- **Build system:** Maven (`./mvnw compile`)
- **GLFW window:** 1280×720, titled "Voxel Engine"
- **Entry point:** `com.voxel.Main` — single-class orchestrator (god-object, ~2100 lines)
- **Launch script:** `launch_freebuff.bat`

## Architecture

```
Main.java (god-object)
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
| `/dimension <overworld\|nether\|end\|aether>` | Switch dimension |
| `/list <items\|blocks\|commands>` | List registered items/blocks |
| `/camera <follow\|orbit\|fixed>` | Change camera mode |
| `/setuv <full\|half\|empty> <x> <y> [w] [h]` | Adjust heart UVs |
| `/screenshot` | Save current frame as PNG to `screenshots/` |

## Dimensions

| Dimension | ID | Scale factor | Notes |
|-----------|----|-------------|-------|
| Overworld | 0 | 1.0× | Day/night cycle, surface terrain |
| Nether | 1 | 0.125× | 8 blocks overworld = 1 nether, cave spawn |
| End | 2 | — | Fixed spawn island |
| Aether | 3 | 8.0× | 1 block overworld = 8 aether, island spawn, parachutes |

## Key Recent Changes

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

## Important Patterns

- **Globals on Main:** `window`, `renderTexture`, all SSBO handles, `ctx` reference
- **Thread safety:** Render thread owns GL; LogicThread ticks game state; `volatile` flags for cross-thread sync
- **SSBO uploads:** Dirty-slots set caps at 48/frame; light pool uploaded via `lightsNeedUpload` flag
- **World save:** `dev/world/` folder, `WorldSaveManager` handles chunk + crafting/furnace/chest persistence
- **Dimension switch:** Saves UI state, drops old-dimension items, scales coordinates, re-scans spawn surface
- **Error handling:** OpenGL errors via `GLUtil.checkError()`, runtime exceptions for shader/setup failures
