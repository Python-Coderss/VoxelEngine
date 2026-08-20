# VoxelEngine — Recent Changes

This document summarizes the work ported from `../miners` and other
Mojang-parity features added to VoxelEngine over a multi-day session.
All entries below have been merged into `main` and pushed to GitHub.

---

## End Portal end-to-end

The full Mojang-style End Portal arc is now functional:

- **Eye of Ender** item (auto-registered via the existing
  `assets/minecraft/models/item/ender_eye.json` resource). Throwing one
  spawns an `EntityEnderEye` projectile that arcs toward the cached
  stronghold XZ and expires on contact.
- **End Portal Frame** block with eye-tracking via the chunk-pool's
  flag byte (low nibble = facing, high bit = filled). Right-click an
  empty frame with an eye of ender to flip the bit; once all 12 frame
  bits are set the 3×3 interior lights up with the `end_portal` block.
- **Procedural Stronghold** placed on the Mojang ring at
  1260..1650 chunks from origin. `StrongholdPlacement.resolve(seed)`
  picks the first slot deterministically per save seed. Save loads
  average the procedural chunk with the saved column so the portal
  stays within walking distance of a returning player.
- **Stronghold structure** baked by `MapGenStronghold` at the chosen
  chunk: a 4×5×4 stone-brick portal room with 12 frame blocks, a
  14×9×15 library, and a 4×15×4 stairwell down to a lava pool.
- **Dimension switch** when the player walks into an active
  `end_portal` block: cooldowned by `ctx.endPortalCooldownTicks` to
  suppress bouncing, spawns the player at (100.5, 65.0, 0.5) on
  the End obsidian pillar.
- **Ender Dragon** skeleton: 3-head orbit at radius 38 / period ~35s,
  perch phase every 45s, fireballs every 4s, drops a `dragon_egg` on
  death via `Main.tick` polling `dragon.isDead()`.
- **End Crystals** (3) spawn around the obsidian pillar with
  `EndCrystalEntity.heal()` adding 0.02 HP per tick to the dragon.
  Punching one destroys it.
- **End Gateway** at (102, 48, 2) on top of the platform, used to
  return to the Overworld.
- **Per-dimension atmosphere** via `u_DimensionID` / `u_FogColor` /
  `u_SkyTint` uniforms in `lighting.comp`:
  - Overworld: bright daylight, neutral blue-grey fog
  - Nether: deep red haze (0.42, 0.05, 0.05)
  - End: near-black void (0.05, 0.02, 0.07) + magenta horizon
- New block assets added with auto-loader coverage:
  - `end_portal.json` (blockstate + model + 16×16 PNG texture)
  - `end_gateway.json` (blockstate + model + 16×16 PNG texture)
  - `purpur_block.json`, `purpur_pillar.json`, `end_crystal_base.json`

---

## Wither boss

- **`WitherEntity`** mirrors the Ender Dragon's lifecycle but tuned for
  Overworld play:
  - **Charge phase** (11 s): invulnerable, body pulses, health fills 0 → 300
  - **Combat phase**: fires `WitherSkullEntity` every 2.5 s, 15
    punches kill
  - **Death**: drops a `nether_star` via `DroppedItemManager.spawn`
- **`WitherSkullEntity`** projectile: blue-wither-skull texture,
  homing behaviour, expires on contact; the projectile cleanup loop
  in `Main.tick` deals 5 damage to the player on contact and prints
  "Hit by a wither skull!" to the status bar.
- `/spawn wither` now creates a real `WitherEntity` instead of a
  generic mob, so the boss AI is live from the debug spawn.

---

## Beacon activation

- **`BeaconLogic`** scans 1..4 pyramid tiers (3×3 → 5×5 → 7×7 → 9×9)
  of iron / gold / diamond / emerald blocks below a beacon, returning
  the active pyramid level (0..4).
- **`Player.setBeaconBuffs(float jump, float speed, boolean regen)`**
  multiplies the player's `JUMP_VELOCITY` and horizontal acceleration
  by the beacon's tier-scaled buff:
  - Tier 1: +30% speed
  - Tier 4: +120% speed, +80% jump, regen flag
- **`ChunkManager.setVoxel`** auto-tracks every placed beacon so the
  per-tick scan picks them up without a chunk scan.
- The buff radius scales 20→50 blocks across tiers 1..4.

---

## Magma Cube

- **`MagmaCubeEntity`** extends `EnemyEntity` and shrinks size by 1 per
  player hit (size-4 → 3 → 2 → 1 → dead). `/spawn magma_cube` now
  creates a real MagmaCubeEntity (size 4) instead of a generic mob.

---

## Nether fortress blaze spawner

- **`MapGenFortress.generateTower`** drops a blaze spawner (id 258) at
  roughly 1-in-8 towers, replacing the lava pool on the parapet.
- **`MobSpawnerLogic`** tracks every registered spawner; each logic
  tick increments its timer and emits a fresh `BlazeEntity` on the
  12-second spawn cycle (Mojang: 10..30 s).

---

## Procedural Stronghold placement

- **`StrongholdPlacement.resolve(worldSeed)`** picks the first
  stronghold slot on the Mojang-style ring:
  - θ ∈ [0, 2π)
  - r ∈ [0.9·1400, 1.1·1500] chunks
  - strongholdChunkX = round(cos θ · r)
  - strongholdChunkZ = round(sin θ · r)
- The same world seed always yields the same chunk, so save loads
  remain deterministic. Zero seed is hashed into a non-zero constant.

---

## Player melee combat

- **`BlockInteraction.updateMining`** now raycasts entities within 5.5
  blocks before raycasting blocks; left-click on a mob drains HP and
  dispatches the boss-specific `onPunch()` hook (Ender Dragon, Wither,
  Magma Cube).
- **Tool damage multipliers** from the held item id:
  - bare hand / pickaxe → 1.0 damage
  - axe (any tier) → 2.0 damage
  - sword (any tier) → 4.0 damage
  - Knockback scales with the multiplier too.
- Boss `onPunch` hooks fire exactly once per click so the kill-arc
  stays consistent regardless of held tool.

---

## XP / experience

- **`Player.addExperience(int)`** implements the canonical Mojang XP
  curve:
  - level 0..15 → 2L + 7
  - level 16..30 → 5L - 38
  - level 30+ → 9L - 158
  - Tracks `totalExperience` cumulatively across the save.
- **`Player.LevelUpListener`** fires when the player crosses a level
  boundary. Main wires a single listener that flashes "Level up! You
  are now level N" in the status bar.
- **`ExperienceOrbEntity`** floats toward the nearest player at
  4 blocks/sec when they're within 2.5 blocks; on contact grants XP
  and expires.
- Mob XP drops (Mojang parity):
  - generic EnemyEntity → 5 XP
  - EnderDragonEntity → 12000 XP
  - WitherEntity → 50 XP
  - MagmaCubeEntity → 3..17 XP by size tier

---

## Per-dimension atmosphere

Per-dimension sky + fog uniforms in `lighting.comp`, uploaded each
frame in `Main.render`:
- Overworld: bright daylight, neutral blue-grey fog
- Nether: deep red haze (0.42, 0.05, 0.05)
- End: near-black void (0.05, 0.02, 0.07) + magenta horizon

The `litColor *= mix(vec3(1.0), u_SkyTint, fog)` line darkens distant
surfaces so the End feels appropriately depressing.

---

## Bug-fix follow-ups

- `EndPortalLogic.countEyesAround` now scans only the 12 unique
  portal-room blocks (was 14 by double-counting corners).
- `StrongholdLocator.reset()` clears the stronghold chunk coords.
- `StrongholdLocator.setCenter()` respects `debugOverride`.
- `World.getVoxelFlags(x, y, z)` / `getVoxelExtra(x, y, z)` are the
  canonical read API for the high and mid bits of the chunk-pool
  entry.

---

## Test coverage

- **217 tests pass** (was 172 entering the session; +45 new tests)
- New test files:
  - `StrongholdLocatorTest` (6 cases) — seed-fallback, setCenter,
    debug override, reset lifecycle
  - `EndPortalLogicTest` (6 cases) — flag helpers, frame-count scan,
    12-eye threshold, portal-fill trigger
  - `EnderDragonEntityTest` (4 cases) — health mechanics, heal clamp
  - `WitherEntityTest` (4 cases) — charge cycle, damage, dead flag
  - `BeaconLogicTest` (8 cases) — pyramid detection across tiers
  - `MagmaCubeEntityTest` (4 cases) — size-shrinking combat
  - `StrongholdPlacementTest` (5 cases) — Mojang ring math
  - `MobSpawnerLogicTest` (3 cases) — track/untrack lifecycle
  - `ExperienceOrbEntityTest` (5 cases) — pickup, XP curve, listener

---

## File-level summary

### New files (18)
```
src/main/java/com/voxel/entity/EnderDragonEntity.java
src/main/java/com/voxel/entity/EntityEnderEye.java
src/main/java/com/voxel/entity/EndCrystalEntity.java
src/main/java/com/voxel/entity/WitherEntity.java
src/main/java/com/voxel/entity/WitherSkullEntity.java
src/main/java/com/voxel/entity/MagmaCubeEntity.java
src/main/java/com/voxel/entity/ExperienceOrbEntity.java
src/main/java/com/voxel/world/EndPortalLogic.java
src/main/java/com/voxel/world/StrongholdLocator.java
src/main/java/com/voxel/world/StrongholdPlacement.java
src/main/java/com/voxel/world/MobSpawnerLogic.java
src/main/java/com/voxel/world/BeaconLogic.java
src/main/java/com/voxel/world/structure/MapGenStronghold.java

src/main/resources/assets/minecraft/blockstates/end_portal.json
src/main/resources/assets/minecraft/blockstates/end_gateway.json
src/main/resources/assets/minecraft/blockstates/purpur_block.json
src/main/resources/assets/minecraft/blockstates/purpur_pillar.json
src/main/resources/assets/minecraft/blockstates/end_crystal_base.json
src/main/resources/assets/minecraft/models/block/{end_portal,end_gateway,purpur_block,purpur_pillar,end_crystal_base}.json
src/main/resources/assets/minecraft/models/entity/{end_crystal,experience_orb}.json
src/main/resources/assets/minecraft/textures/blocks/{end_portal,end_gateway,purpur_block,purpur_pillar,end_crystal_base}.png
src/main/resources/assets/minecraft/textures/items/{skull_wither,experience_orb}.png

src/test/java/com/voxel/world/StrongholdLocatorTest.java
src/test/java/com/voxel/world/EndPortalLogicTest.java
src/test/java/com/voxel/world/StrongholdPlacementTest.java
src/test/java/com/voxel/world/MobSpawnerLogicTest.java
src/test/java/com/voxel/world/BeaconLogicTest.java
src/test/java/com/voxel/entity/EnderDragonEntityTest.java
src/test/java/com/voxel/entity/WitherEntityTest.java
src/test/java/com/voxel/entity/MagmaCubeEntityTest.java
src/test/java/com/voxel/entity/ExperienceOrbEntityTest.java
```

### Modified files (8)
```
src/main/java/com/voxel/Main.java              (dimension switch, dragon/wither spawn,
                                                XP drops, beacon buffs, mob spawner tick,
                                                melee attack bridge, level-up listener,
                                                per-dim atmosphere uniforms)
src/main/java/com/voxel/World.java              (getVoxelFlags/getVoxelExtra helpers)
src/main/java/com/voxel/Player.java             (XP/level system, beacon buff fields,
                                                setBeaconBuffs, level-up listener)
src/main/java/com/voxel/entity/EnemyEntity.java (xpDropValue, markedXpDropped,
                                                markXpDropped)
src/main/java/com/voxel/entity/MagmaCubeEntity.java   (size shrinking)
src/main/java/com/voxel/game/BlockInteraction.java     (eye-of-ender insert/throw, melee,
                                                        tool multipliers)
src/main/java/com/voxel/game/GameContext.java           (endPortalCooldownTicks,
                                                        endReturnX/Y/Z, enderDragonSpawned,
                                                        enderDragonEntityId)
src/main/java/com/voxel/world/ChunkManager.java        (auto-track beacons + spawners)
src/main/java/com/voxel/world/DimensionWorldGenerator.java (MapGenStronghold hook,
                                                             end_gateway + end_crystal_base
                                                             placement on End platform)
src/main/java/com/voxel/world/structure/MapGenFortress.java  (1-in-8 blaze spawner placement)
src/main/resources/shaders/lighting.comp               (u_DimensionID, u_FogColor,
                                                        u_SkyTint, litColor *= mix)
```

### Deleted files: 0

---

## Mojang coverage map

| Mojang feature                       | Status |
| ------------------------------------ | ------ |
| Eye of Ender throws → Stronghold     | ✅ |
| Stronghold procedural ring placement | ✅ |
| End Portal Frame + 12-eye ritual     | ✅ |
| End Portal block (active mesh)       | ✅ (cube placeholder, magic visual TBD) |
| End dimension transition              | ✅ |
| Ender Dragon orbit AI                | ✅ |
| Ender Crystal regen anchors           | ✅ |
| Dragon egg drop                      | ✅ |
| End Gateway return portal             | ✅ |
| End dimension unique atmosphere      | ✅ |
| Wither skeleton skull spawn         | ✅ (manual via /spawn) |
| Wither charge + combat phases       | ✅ |
| Wither skull projectile             | ✅ |
| Nether Star drop                    | ✅ |
| Beacon pyramid detection            | ✅ |
| Beacon tier buffs (speed/jump)      | ✅ |
| Magma Cube (size shrinking)         | ✅ |
| Nether fortress blaze spawner       | ✅ |
| Procedural Stronghold ring          | ✅ |
| Player melee attack on mobs         | ✅ |
| Tool damage multipliers (sword/axe) | ✅ |
| XP drops from mobs                  | ✅ |
| XP curve + level-up listener        | ✅ |
| Nether fortresses (MapGenFortress)  | ✅ (existing) |
| Blaze mob                           | ✅ (existing) |
| Enderman / Endermite / Silverfish   | ✅ (existing) |

### Not yet implemented (deferred)
- End Cities (post-Dragon exploration content)
- Aether dimension polish (floating islands, Moa, skyroot)
- Custom portal render (currently a cube)
- Beacon visible beam
- XP HUD bar overlay
- Anvil, Enchanting Table, Brewing Stand logic
- Lodestone + Compass
- Bees, Fox, Sweet Berry Bush
- Conduit / Heart of the Sea
- Wandering Trader + Llama
- Archaeology (Suspicious Sand / Brush)