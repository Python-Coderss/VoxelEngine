# Voxel Engine modding

Voxel Engine loads **Java mods from `mods/*.jar`** at startup. A mod is an
ordinary jar compiled against the game's classes; it can register blocks and
items, and listen for gameplay events. No patching or forking is needed.

```
mods/
  example-lantern.jar      ← loaded automatically
  .cache/                  ← extracted jar contents (generated, ignored)
```

## Quick start

1. Copy `mods/example-lantern/` somewhere and look at `LanternMod.java`.
2. Compile against the game classes:

   ```bat
   javac -cp "..\..\target\classes" -d build\classes ^
         src\com\example\lantern\LanternMod.java
   ```

   ```sh
   javac -cp ../../target/classes -d build/classes \
         src/com/example/lantern/LanternMod.java
   ```

3. Pack the classes, `META-INF/services` and `assets/` into a jar and drop it in
   `mods/`:

   ```bat
   build.bat
   ```

   ```sh
   ./build.sh
   ```

4. Launch the game. The log prints
   `[Mods] loaded Example Lantern 1.0 (example), 1 block(s), 1 item(s)`.

## Entry point

```java
package com.example.lantern;

import com.voxel.api.*;

public final class LanternMod implements VoxelMod {
    @Override public String id() { return "example"; }       // [a-z0-9_-], max 32
    @Override public String name() { return "Example Lantern"; }
    @Override public String version() { return "1.0"; }      // optional

    @Override public void onLoad(ModContext ctx) {
        ctx.registerBlock(ModBlock.of("glow_lantern")
                .emissive(220)
                .light(255, 214, 140)
                .hardness(0.6f)
                .build());
        ctx.registerBlockItem("glow_lantern", "Glow Lantern");

        ctx.events().onBlockBreak(event ->
                ctx.log("broke " + event.blockName() + " at " + event.x + "," + event.y));
    }
}
```

Declare it with the standard `ServiceLoader` descriptor:

```
META-INF/services/com.voxel.api.VoxelMod
    com.example.lantern.LanternMod
```

(One class name per line.)

## Assets

Assets live inside the mod jar using vanilla paths. The loader extracts the jar
to `mods/.cache/<jar>/` and registers the mod's texture folder with the global
texture array, so mod blocks share the engine's atlas.

```
assets/example/models/block/glow_lantern.json
assets/example/textures/blocks/glow_lantern.png
```

`glow_lantern.json` is a normal block model:

```json
{ "parent": "block/cube_all", "textures": { "all": "glow_lantern" } }
```

Textures must be 16x16 (or a vertical animation strip); names are file names
without the extension. The model file defaults to the block name; override it
with `ModBlock.of("x").model("other_model")`.

## Blocks

`ModBlock.of(name)` returns a builder:

| method | meaning |
| --- | --- |
| `.model(String)` | model JSON to use instead of the block name |
| `.translucent(0-255)` | 0 = opaque, 150 = glass-like, 255 = invisible-ish |
| `.reflective(0-255)` | specular weight |
| `.diffuse(0-255)` | diffuse weight (default 255) |
| `.emissive(0-255)` | self-illumination in the raytracer |
| `.light(r,g,b)` | coloured light emission (0-255 per channel) |
| `.nonFullBlock()` | use the model's AABB instead of a cube |
| `.hardness(float)` | mining time; negative keeps the engine default |
| `.miningTier(0-4)` | tool tier required to drop the block |
| `.build()` | finish |

`ctx.registerBlock(...)` returns the allocated numeric id. Ids come from the
reserved range **3000-4095**; built-in blocks stop below 1100, so a mod can
never collide with vanilla.

## Items

```java
ctx.registerBlockItem("glow_lantern", "Glow Lantern"); // placeable, 3D model
ctx.registerItem(ModItem.of("lantern_oil", "Lantern Oil", "lantern_oil_icon"));
```

Block items reuse the block's top texture as their icon. Standalone items look
for `<iconTexture>.png` anywhere under `assets/<modid>/textures/`.

## Events

| hook | when |
| --- | --- |
| `onBlockBreak` | after a block has been removed |
| `onBlockPlace` | after a block has been placed |
| `onTick` | every logic tick (20 Hz) while a world runs |
| `onWorldLoad` | once the world has finished initialising |

All listeners run on the logic thread. Listeners may edit the world through
`event.game` / `event.world`, but must never call OpenGL. A listener that throws
is logged (`[Mods] <id> failed in tick listener: ...`) and skipped; other
listeners still run.

## Loading rules

- Jars are loaded in file-name order from `mods/`.
- Duplicate mod ids and ids outside `[a-z0-9_-]{1,32}` are rejected.
- If `onLoad` throws, that mod is disabled and the rest keep loading.
- The mod's jar is extracted fresh on every launch; `mods/.cache/` is safe to
  delete.
- Mods run after all built-in blocks are registered and before the block
  texture buffer is uploaded, so their textures are part of the same atlas.

## API packages

| package | contents |
| --- | --- |
| `com.voxel.api` | `VoxelMod`, `ModContext`, `ModBlock`, `ModItem` |
| `com.voxel.api.events` | `ModEvents`, `BlockEvent`, `TickEvent`, `WorldLoadEvent` |

The rest of `com.voxel.*` is engine internals; it is available because mods are
plain Java, but only the API package is kept stable.
