# VoxelEngine — Multi-Session Task Notes

> **START HERE (any future session).** The user only sends “Continue with the task”.
> Read this file top to bottom first, then `git log --oneline -15` and `git status`,
> then run `./mvnw -o test` to confirm the tree is green, then pick up the
> **NEXT ACTION** below. Update this file before you stop.

## 0. NEXT ACTION (keep this current)

**CURRENT PRIORITY (user): cutscenes + point-and-click first; then Sift; then
MCSM/Legends/Dungeons. Combat is explicitly deferred (§11).**
Cutscene/P&C passes #1–#3 are DONE (see §4b and §10).
**Sift slice 2 is DONE** (sifting mechanic + tide + rift travel — see §5).
Next: **the melody rift (§6)**, then MCSM/Legends/Dungeons (§7), then the
existing-generator overhauls (§4c).

1. Cutscene/P&C pass #1 **committed**: timed dialogue cues in scenes
   (`CinematicScript` + `CinematicSystem.publishScript/playScriptedScene/
   playStructureReveal`), a structure-reveal cutscene wired when an ancient-
   builder site is first stamped, ancient-builder billboard markers + a
   caption-only builder reply on interact, and an expanded Point & Click demo
   plaza (TV, enchanting table, anvil, beacon, builder pedestal + builder).
2. **Next**: more cutscene scenes (boss/dungeon/Nether/Sift reveals with
   dialogue), and P&C pass #2 (multi-action markers, TV marker dispatch, click
   ranges). Then **THE SIFT** (§5).
3. Keep `knowledge.md` + `CHANGELOG.md` updated per phase; commit at each stable
   point (local commit survives a cutoff; push if asked).

### Already done / on disk
- Ancient builders (§4) — **committed**.
- Cutscenes + Point & Click pass #1 (§4b) — **committed** (full suite green:
  429 tests). Confirm with `./mvnw -o test`.
- Overhauls of existing generators (§4c) — NOT started.
- THE SIFT (§5), melody rift (§6), MCSM/Legends/Dungeons (§7) — NOT started.

## 1. The user’s requests (verbatim intent, accumulate across sessions)

1. “Overhaul every implemented structure, and add more. This game is set before the
   extinction of the ancient builders, so add them with smart AI, but no audio, but
   they should show up in live captions though.”
2. “Also implement THE SIFT (new feature announced for Minecraft that has major lore
   implications).”
3. “The Sift can also be accessed via the ancient city portal by playing the right
   melody. Manage tasks and todos smartly, taking notes so you don’t get lost. Add
   all MCSM, Legends, Dungeons 1 & 2 content. Take as long as you would like.”
4. “This will be split across multiple sessions. But you will be cut off abruptly. So
   plan ahead for that. The later sessions will only get the command
   ‘Continue with the task’ with no additional context.”
5. “The combat perspective and mechanics need improvement too, but that’s for later.”
   → **DEFERRED.** Do not start until cutscenes/P&C, Sift, and MCSM/Legends/
   Dungeons are done. Track it as §11 below so it is never lost.

## 2. Hard rules / conventions

- **Builders make NO audio.** No clip, no OpenAL, no `VillagerAudioManager`. They speak
  only through `com.voxel.ai.speech.CaptionSpeech` → `LiveCaptions` (already rendered
  by `HudUI.updateSpeechCaptions`).
- Repo root: `C:/Users/raman/eclipse-workspace/fastpbrjava/VoxelEngine`.
  Remote `https://github.com/Python-Coderss/VoxelEngine.git`, branch `main`.
- Build/test: `./mvnw -o test` (offline, Maven). Compile-only:
  `./mvnw -o -q -DskipTests compile`. Preserve exit status when piping:
  `... 2>&1 | tail -30; echo "EXIT=${PIPESTATUS[0]}"`.
- Terminal is **bash on Windows**. Use POSIX syntax (`ls`, `rm`, `mv`), never
  `dir`/`del`. CRLF warnings on `git add/status` are harmless.
- Commit trailer is mandatory:
  `🤖 Generated with Codebuff` / `Co-Authored-By: Codebuff <noreply@codebuff.com>`.
- Git identity is set locally (`Python-Coderss` / `gurtaj.sran@outlook.com`).
- Untracked junk from a stray installer — leave alone:
  `Checksum Downloading Extracting Fetching Installing Latest Verifying install.cmd
  "Villager News 1.0 Add-On (addon).zip"`.
- Tests: JUnit 4. Headless tests subclass `com.voxel.World` with `super(8)` and override
  `int getVoxel(int,int,int)`. Never construct `EntityManager` in a test (it needs a GL
  context) — guard population code on `entityManager == null` and test the null path.
- `AncientBuilderEntity`/`VillagerEntity` constructors work with `TextureManager == null`.
- Test baseline before this work: **390 tests, 0 failures**. Keep it green.

## 3. Key existing architecture (map)

- World/structure generation: `com.voxel.world.*` +
  `com.voxel.world.structure.{MapGenStructure, MapGenVillage, MapGenMineshaft,
  MapGenFortress, MapGenStronghold}`. `MapGenStructure(long seed).generateStructures(...)`
  dispatches village + mineshaft; constructed in `BetaWorldGenerator` (~116) and
  `DimensionWorldGenerator` (~92, hard-coded seed 42), called ~237 / ~778.
- Lazy site stamping (the model to copy): `McsmStructures.ensure(...)`,
  `AncientBuilderModern.ensure(World, ChunkManager, px, pz)` →
  `VoxelSink`/`RecordingSink`, `surfaceY`, `variantFor`, `resetForTest`, `isBuilt`.
- Dimensions: `com.voxel.world.DimensionType` (enum: OVERWORLD, NETHER, END, AETHER,
  ERROR502, PORTAL_HALL), `DimensionManager.createDimension` picks a `WorldGenerator`
  per type. `/dimension <name>` in `com.voxel.game.CommandProcessor.handleDimension`
  (~line 84).
- Generators: `WorldGenerator` base; `AetherGenerator` is the best template for a
  hand-rolled dimension (noise density + `decorate` + `getBlockType`).
- Blocks: registered in `Main` (~line 5671–6990) via
  `blockRegistry.register(name, id)` + `shaderBlockRegistry.register(id, id)` +
  `blockDataManager.registerBlock(id, name, textureManager, mcModels[, cfg...])`.
  `mcModels = "src/main/resources/assets/minecraft/models/block"`. A block needs a
  texture PNG `textures/blocks/<name>.png` **and** a model JSON
  `models/block/<name>.json` (`{"parent":"block/cube_all","textures":{"all":"<name>"}}`)
  or `registerBlock` throws “Texture for block … could not be resolved”.
  4-arg extra forms: `(id,name,tm,dir,transparency,reflectivity,diffuse)` and
  `(...,emissive)`; office_glass used `(150,50,255)`, ceiling_light `(0,0,255,255)`.
- Texture/model generator precedent: `tools/gen_modern_block_textures.py` (Python3 +
  Pillow is installed; deterministic seeded pixel art). Modern block IDs 920–926 with
  textures already exist.
- Behaviour/entities: `MobBrain` (perceive/update/onStimulus), `Senses.scan(...)`,
  `StimulusBus.GLOBAL`, `PathFinder.findPath(VoxelView, x,y,z, gx,gy,gz)`,
  `Emote` (NOD, HEAD_SHAKE, WAVE_FRANTIC, JUMP_CHEER, COWER, HAMMER, POINT, TUG),
  `EmotePlayer`, `GazeController`. `Brains` gates brain installation.
- Villagers/professions: `VillagerEntity`, `VillagerBrain`, `VillagerVillageManager`,
  `VillagerProfessions/Trades/Trading`, `VillagerSpeech` (clip-backed — builders must
  NOT use it). `LiveCaptions` is the shared caption overlay (audio-independent).
- Block IDs handy (see `Main` for full list): stone_brick 131, mossy_cobblestone 132,
  bookshelf 136, iron/gold/emerald block 137/138/140, copper 143, zinc 145,
  stone_brick_stairs 202, slab 208, torch 211, spawner 258, cobblestone 71, chest 118,
  crafting_table 115, anvil 456, beacon 457, enchanting_table 458, hopper 461,
  cauldron 462, brewing_stand 463, end_bricks 464, end_rod 465, prismarine 467,
  sea_lantern 470, iron_bars 471, rail_ns 391, rail_ew 392, purpur 900, end_glass 904,
  void_steel 905, farmland_wet 907, Create ports: shaft 291, cogwheel 294,
  large_cogwheel 295, steam_engine 396, blaze_burner 394, press 407, deployer 412,
  belt 413, andesite_casing 262. Modern: concrete 920, concrete_dark 921, steel_beam 922,
  ceiling_light 923 (emissive), tile_block 924, office_glass 925, marble 926.
- Ores: coal 61, iron 81, gold 82, diamond 83, emerald 84, lapis 85, copper 142, zinc 144,
  redstone 26.

## 4. DONE so far (this session) — coded, compiles, NOT yet tested/committed

New files:
- `src/main/java/com/voxel/ai/speech/CaptionSpeech.java`
  Caption-only, audio-free, rate-limited (7 s/speaker). API: `say(id,speaker,line)`,
  `say(id,speaker,line,durationSeconds)`, `isRateLimited(id)`, `resetForTest()`.
- `src/main/java/com/voxel/ai/speech/AncientBuilderLines.java`
  `enum Topic {GREETING, WARNING, WORK, STUDY, PATROL, ALARM, RETREAT, OBSERVE}`,
  `pick(Topic, Random)`, `pool(Topic)`.
- `src/main/java/com/voxel/entity/AncientBuilderEntity.java`
  `extends Entity`, loads `models/entity/player.json`, `enum Role {ARCHITECT, SCHOLAR,
  ENGINEER, SENTINEL, CURATOR}` with `title()`; `setWorld/setRole/setHomeSite`, ai* API
  (`aiMoveToward/aiStandAnim/aiFaceYaw/aiAimHead/aiSpeak/aiPlayEmote/aiRole/aiHomeSite/
  aiHasHomeSite/aiDisplayName`), static `setEntityManager`. `aiSpeak` → `CaptionSpeech`.
- `src/main/java/com/voxel/ai/brain/AncientBuilderBrain.java`
  `implements MobBrain, StimulusBus.Listener`. `enum Action {PATROL, WORK, STUDY, GREET,
  ESCORT, WARN, FLEE, IDLE}`; pure `static chooseAction(threat,hostile,playerNear,
  workDue,hasSite,sentinel)`; publishes `THREAT_SEEN` (`announceThreat`); flees toward
  home; greets/escorts/warns the player; work/study/patrol beats; all speech caption-only.
- `src/main/java/com/voxel/world/AncientBuilderSites.java`
  6 sites (each 2 layout variants), `VoxelSink`/`RecordingSink`, `ensure/build/
  variantFor/surfaceY/resetForTest/isBuilt`, blocks palette constants.
  Sites: GRAND_ARCHIVE(240,240), OBSERVATORY(-240,40), ANCIENT_QUARRY(40,-240),
  ENGINE_WORKS(-48,264), WATCH_SPIRE(264,-64), MONUMENT_GARDEN(-264,-240).
  `ensure` also calls `AncientBuilderPopulation.queue(...)`.
- `src/main/java/com/voxel/game/AncientBuilderPopulation.java`
  static queue/tick/groundLoaded/findSurface/roleFor/rolesFor; `SPAWN_RADIUS=180`,
  `MAX_PER_SITE=6`; spawns 3–6 builders on real ground, role by site.

Modified:
- `com.voxel.ai.brain.Brains` — added `newBuilderBrain(AncientBuilderEntity)`.
- `com.voxel.Main` — `AncientBuilderEntity.setEntityManager(entityManager)` after
  VillagerEntity’s; `AncientBuilderSites.ensure(...)` after `AncientBuilderModern.ensure`;
  `AncientBuilderPopulation.tick(...)` after the village-manager tick (both under
  `player != null && ctx.chunkManager != null`).

## 5. THE SIFT — design (SLICE 1 DONE: dimension + terrain + tides)

Researched facts (Sept 26 2026 Minecraft LIVE / Xbox Wire / logicservers recap):
Minecraft’s **first new dimension in 14 years**, coming to Java+Bedrock in **2027**
(debuts in Minecraft Dungeons II, 29 Sep 2026). Bright, colourful, **teeming with
souls**; **souls fly through the Overworld** as an omen. Biomes named so far:
**Meadow** and **Carapace**. Mechanics: **shifting tides** that affect the player and
those around them, **hazardous new blocks**, and a **sifting block** that strains
buried material from the dimension’s unique soil types. Entered via **rifts**.
Music track names hint at content: “Humus-Sifting”, “Steppe”, “Routers”, “Deep Dark
Portal”, “Rift”. (In this game’s lore: the builders foresaw extinction and built the
first rifts; the Sift is where their souls went.)

DONE (slice 1):
- Blocks **927–936** registered in `Main` after marble: `sift_stone`, `meadow_grass`,
  `sift_soil`, `carapace`, `carapace_lamp` (emissive), `sift_bed` (the sifting
  block), `rift_frame`, `sift_portal` (portal effect), `soul_lamp` (emissive),
  `soul_slate`. Assets generated by `tools/gen_sift_block_textures.py` (textures
  under `textures/blocks/`, cube_all models under `models/block/`).
- `DimensionType.SIFT(6, "sift", 72)`.
- `com.voxel.world.SiftGenerator extends WorldGenerator` — `biomeAt` (Meadow 0 /
  Carapace 1), `columnHeight`, `getBlockType`, `decorate` (sift beds, soul lamps,
  carapace spires, and a rift gate at (6,·,0)). Wired in
  `DimensionManager.createDimension`.
- `com.voxel.world.SiftTides` — phases LOW/RISING/HIGH/EBBING, `strength()`,
  `hazardous()`, `siftYieldMultiplier()` (1..3), `describe()`. Pure.
- `/dimension sift` (and `thesift`) added to `CommandProcessor`.
- Tests: `SiftGeneratorTest` (5), `SiftTidesTest` (5).

DONE (slice 2):
- `com.voxel.game.SiftSieve` — tide-scaled loot table (6 common / 6 rare) +
  per-bed cooldown. Wired into `BlockInteraction.tryUseTarget` (block 932):
  right-click rolls `SiftSieve.roll(ctx.siftTides, rng)`, adds to the inventory
  via `addItem`, shows a status + a `LiveCaptions` line, and respects the
  cooldown. `SiftSieveTest` (6).
- `GameContext.siftTides` field; `Main` ticks it each logic tick while in
  `DimensionType.SIFT`.
- `sift_portal` (934) is now a working rift: `PortalSystem` treats it like the
  Nether/Aether portals (walk-in and click-to-enter), and entering the Sift
  plays a `CinematicSystem.playDungeonArrival` scene. `Main.performBillboardAction`
  routes id 934 to `enterPortal`.
- `InteractionBillboardSystem` classifies `sift_bed` ("Sift Bed"/"Sift") and
  `sift_portal` ("Sift Rift"/"Enter").

TODO (slice 3+):
- Soul wisps: ambient drifting entity with caption-only whispers (no audio).
- Carapace hazards (harmful blocks) + richer decorations.
- Surface the tide `describe()`/phase in the HUD (currently only status/loot).

## 6. MELODY RIFT (NOT STARTED)

“The Sift can also be accessed via the ancient city portal by playing the right melody.”
- Need/locate an **Ancient City** structure (Deep Dark). Check whether one exists in
  `com.voxel.world` (grep “ancient”). If not, add an `AncientCity` generator/site with a
  portal frame + note blocks.
- `com.voxel.game.MelodyPortal`: a fixed tune `int[] MELODY` (note-block pitch ids),
  `static boolean playNote(int noteId)` accumulates a rolling window and validates;
  correct melody → open `sift_rift` / switch to `DimensionType.SIFT`; wrong note → reset
  (+ caption). Wire note-block right-click to `MelodyPortal.playNote`.
- Captions: “the stones remember the tune” style lines, caption-only.

## 7. MCSM / LEGENDS / DUNGEONS 1 & 2 CONTENT (PARTIAL / NOT STARTED)

Existing: `com.voxel.world.McsmStructures` (EnderCon Arena (128,0), Soren’s Building
Site (-128,96), Pumpkin Hideout (0,-128)), `com.voxel.game.McsmStory` (episode beats,
boss summon), `com.voxel.game.FormidiBomb` (916), white_pumpkin 917.
TODO content to add (structures + mobs + story beats), using the lazy-stamp + `VoxelSink`
pattern and existing block palette:
- **MCSM:** Episodes 2–8 sites — Redstonia, Boom Town, Sky City, Crown Mesa, Jack’s
  Lair (have Pumpkin Hideout), the Wither Storm arena, the Order’s Temple, Beacontown,
  Fred’s Keep, the Portal Network. Story beats in `McsmStory`.
- **Minecraft Legends:** Well of Fate, the Great Hog / Horde, Legendary hero
  structures, villages to defend, the Overworld/Nether campaign outposts.
- **Dungeons 1:** Creeper Woods, Pumpkin Pastures, Soggy Swamp, Cacti Canyon, Desert
  Temple, Highblock Halls, Fiery Forge, Obsidian Pinnacle, Island Realms, the
  Arch-Illager / Heart of Ender.
- **Dungeons 2:** the Sift-linked campaign, rift stops, the illagers’ plot, new mobs.
- Keep every new site deterministic + idempotent; add a `McsmContentTest` /
  `DungeonsContentTest` per group.

## 8. Immediate test list to write (before more features)

- `src/test/java/com/voxel/ai/speech/CaptionSpeechTest.java` — `say` publishes to
  `LiveCaptions.current()`; empty line ignored; rate limit; `resetForTest`; no audio
  dependency (classpath-safe).
- `src/test/java/com/voxel/entity/AncientBuilderEntityTest.java` — construct with null TM;
  `aiSpeak` shows a caption; `getDisplayName`; role/home site; movement on a stub World.
- `src/test/java/com/voxel/ai/brain/AncientBuilderBrainTest.java` — pure `chooseAction`
  ordering; `AncientBuilderLines.pool` non-empty; brain attaches without GL.
- `src/test/java/com/voxel/world/AncientBuilderSitesTest.java` — every site × variant
  places blocks, stays inside a sane footprint, deterministic variants, idempotent.
- `src/test/java/com/voxel/game/AncientBuilderPopulationTest.java` — `groundLoaded`
  true/false cases; queue survives `tick(..., null, null)`; `roleFor` mapping.

## 9. How to resume (future “Continue with the task” session)

1. Read this file (you already did).
2. `git log --oneline -15`; `git status --short`; `./mvnw -o test` → confirm green.
3. If tests are red, fix before adding features.
4. Do the **NEXT ACTION** at the top; update this file as you go (mark DONE, set a new
   NEXT ACTION); commit+push at every stable point so a cutoff cannot lose work.

## 4b. DONE — cutscenes + point-and-click pass #1 (committed)

Cutscenes:
- `com.voxel.cinematic.CinematicScript` — pure timed dialogue (`at(sec,speaker,
  text)`, `due(prev,now)`, `of(...)`). Time-0 cue fires on the first tick, then
  never again; consecutive windows `(t1,t2]` fire each line exactly once.
- `CinematicSystem` — scenes now carry a script; `publishScript` pushes due lines
  to `LiveCaptions` (caption-only, no audio). New `playScriptedScene(title,sub,
  shots,script)` and `playStructureReveal(siteName, subject, speaker, line)`
  (wide orbit → close-up, title card, builder line).
- `AncientBuilderSites.Built` + `consumeNewlyBuilt()` + `surfaceYAt(...)`; `Main`
  calls `playStructureReveal` for each newly stamped site (speaker “Ancient
  Builder”, line from `AncientBuilderLines`).
- `CinematicScriptTest` (5 tests), `CameraShotTest` (5 tests).
- `CinematicSystem` scene recipes: `playScriptedScene`, `playStructureReveal`,
  `playBossIntro(name,taunt,subject)`, `playBossDefeat(name,line,subject)`,
  `playDungeonArrival(name,line,subject)`, plus `canDirectScene()` gate.
- `Main` wires boss scenes: White Pumpkin intro (spawnWhitePumpkinBoss),
  White Pumpkin/Wither/Ender Dragon defeat scenes.

Point & Click:
- `InteractionBillboardSystem` now also marks **villager TV (274)** and
  **AncientBuilderEntity** (“Talk”); `isInteractableBlock`/`blockName`/
  `blockAction` made package-private for testing.
- `BlockInteraction.interactWithEntity` handles `AncientBuilderEntity`: sets
  status + `aiSpeak` (caption-only) + the VILLAGER reaction beat.
- `PointClickWorldAuthor` plaza expanded: villager TV, enchanting table +
  bookshelves, anvil on a plinth, beacon on a gold plinth, a mirrored
  ancient-builder pedestal. `Main` spawns a demo ancient builder at (2.5, G+1,
  4.5). Tests: `InteractionBillboardClassificationTest` (4),
  `PointClickWorldGeneratorTest.expandedPlazaStationsExist`.

## 10. Progress log (append newest at the bottom)

- 2026-10-05: Ancient-builder AI + caption speech + 6 new sites + population
  manager; Main wired; 29 new tests green; committed.
- 2026-10-05: Cutscenes + Point & Click pass #1 (dialogue cues, structure-reveal
  scene, builder/TV billboards, expanded demo plaza). Full suite green (429
  tests); committed.
- 2026-10-05: **THE SIFT slice 1** — dimension + 10 blocks (+ generated assets)
  + `SiftGenerator` (Meadow/Carapace) + `SiftTides` + `/dimension sift`; 10 new
  tests; full suite green (444); committed.
- 2026-10-05: Cutscene pass #2 — boss intro/defeat + dungeon-arrival scene
  recipes, wired to the White Pumpkin/Wither/Ender Dragon; `CameraShotTest`.
  Full suite green (434 tests); committed. NEXT: deepen cutscenes/P&C (or
  begin THE SIFT, §5).
- 2026-10-05: Cutscene/P&C pass #3 — `CameraShot` twoShot/crane/pushIn;
  `CinematicScript` exchange/conversation; multi-line reveal + boss scenes;
  `playBuilderDialogue` (wired to builder interaction); billboards now list
  villager Talk/Trade and classify hand crank/windmill bearing/item vault/
  lever/button. 6 new tests. Full suite green (450 tests); committed.
- 2026-10-05: User deferred **combat perspective + mechanics** to later (§11).
- 2026-10-05: **THE SIFT slice 2** — `SiftSieve` wired into `BlockInteraction`
  (sift_bed loot + cooldown + caption), `GameContext.siftTides` ticked in `Main`
  inside the Sift, `sift_portal` rift travel via `PortalSystem` with a dungeon
  arrival scene, billboard classification for 932/934. `SiftSieveTest` (+6);
  full suite green (456); committed.

## 11. DEFERRED — combat perspective + mechanics (user: “for later”)

User asked (2026-10-05) to improve the **combat perspective and mechanics**.
Explicitly deferred until after the current priority chain (cutscenes/P&C →
Sift → melody rift → MCSM/Legends/Dungeons → structure overhauls).

Ideas to evaluate when picked up (NOT started): third-person combat framing /
over-the-shoulder aim; target lock / soft-lock on the block-or-entity under the
cursor; attack wind-up + recovery timing; directional hit reactions and hit-stop
tuning; a parry/block; weapon reach vs. pick-box consistency; and combat beats
in `CinematicSystem` (already has `Beat.BOSS_SPAWN/BOSS_DEFEAT`). `Main` owns the
combat loop; `BlockInteraction.raycastEntity` and `Player` health/attack live in
`com.voxel`. Add tests where the logic is pure.

### Pass #3 (current, cutscenes + P&C)
- `CameraShot`: `twoShot`, `crane`, `pushIn` presets (pure, tested).
- `CinematicScript`: `exchange`, `conversation` helpers (pure, tested).
- `CinematicSystem`: richer multi-line reveal/boss scenes; `playBuilderDialogue`.
- `InteractionBillboardSystem`: multi-action markers (villager Talk/Trade) and
  more classified interactables (hand crank, windmill bearing, item vault,
  lever/button).
