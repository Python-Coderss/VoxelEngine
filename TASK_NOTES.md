# VoxelEngine — Multi-Session Task Notes

> **START HERE (any future session).** The user only sends “Continue with the task”.
> Read this file top to bottom first, then `git log --oneline -15` and `git status`,
> then run `./mvnw -o test` to confirm the tree is green, then pick up the
> **NEXT ACTION** below. Update this file before you stop.

## 0. NEXT ACTION (keep this current)

1. Write tests for the ancient-builder work already coded (see §4 “Done”):
   `CaptionSpeechTest`, `AncientBuilderEntityTest`, `AncientBuilderBrainTest`,
   `AncientBuilderSitesTest`, `AncientBuilderPopulationTest`.
2. Run `./mvnw -o test` (must be green), then **commit + push** so progress persists.
3. Then start **THE SIFT** (§5) — see the detailed design there.
4. Then the **melody rift** in the ancient city portal (§6).
5. Then the **MCSM / Legends / Dungeons 1 & 2** content expansion (§7).
6. Keep `knowledge.md` + `CHANGELOG.md` updated per phase.

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

## 5. THE SIFT — design (NOT STARTED)

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

Planned implementation:
- `DimensionType.SIFT(6, "sift", 96)` (append to the enum; `fromId` handles it).
- New blocks IDs **927–936** (+ textures via a new
  `tools/gen_sift_block_textures.py` and model JSONs):
  927 `sift_stone`, 928 `meadow_grass`, 929 `sift_soil`, 930 `carapace`,
  931 `carapace_lamp` (emissive), 932 `sift_bed` (**the sifting block**),
  933 `rift_frame`, 934 `sift_rift` (PORTAL effect), 935 `soul_lamp` (emissive),
  936 `soul_slate`. Register all in `Main` (blockRegistry + shaderBlockRegistry +
  blockDataManager.registerBlock + setHardness), like the modern set.
- `com.voxel.world.SiftGenerator extends WorldGenerator` (model on `AetherGenerator`):
  noise terrain; **Meadow** = rolling meadow_grass/sift_soil, soul pools, `sift_bed`
  clusters, gem-trees; **Carapace** = spiky ridges of `carapace`/`carapace_lamp`,
  hazardous. `getBiome(x,z)` from noise (no BiomeProvider needed; null is fine, cf.
  Aether). `decorate(...)` places lamps/beds/spires. No entity spawning during gen.
- `com.voxel.world.SiftTides`: phases `CALM/RISING/HIGH/EBBING/LOW`; `strength()` 0..1;
  `tick(dt)`; drives hazard + sift loot quality + a `ctx`/HUD status line.
- `com.voxel.game.SiftSieve`: `sift(int tier, Random)` → loot (existing item ids:
  “wheat_seeds”, “coal”, “copper_ingot”, “iron_ingot”, “gold_ingot”, “diamond”,
  “emerald”, “quartz”, “glowstone”, “soul_slate”…). Hook right-click on `sift_bed` in
  `BlockInteraction`.
- Rift travel: either a `sift_rift` block that teleports (extend/replicate the existing
  portal teleport path) or a command-block console (`dimension sift`) as in
  `AncientBuilderFacility`. Add `case "sift"` to `CommandProcessor.handleDimension` and
  update its usage/help strings.
- `SiftSoul`/soul wisps: ambient “souls fly through the world” — a lightweight drifting
  entity, caption-only whispers (no audio). Optional; only if time allows.

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

## 10. Progress log (append newest at the bottom)

- 2026-10-05: Ancient-builder AI + caption speech + 6 new sites + population manager
  coded and compiling; Main wired. Not yet tested or committed. NEXT: tests → green →
  commit → start THE SIFT.
