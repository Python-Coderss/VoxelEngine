# CHANGELOG

## Ancient-Builder Modern Sites (Oct 4, 2026)

### The final age before the wipeout
- New `AncientBuilderModern`: the ancient builders' modern era, preserved
  intact on the eve of their disappearance — a glass office tower, a tiled
  metro hall, a marble research lab, and a civic plaza with a steel monument.
  Unlike their stone-brick strongholds and Far Lands facilities, these sites
  are precast concrete, office glass, and idle command-tech consoles.
- Each site has 2-3 **layout variants** chosen deterministically from its name
  (tower heights 10/14/18 floors with a setback crown; island vs side-platform
  metro; lab with or without an archive annex; statue with raised arm vs
  light-crowned obelisk), so sites are families of designs, not fixed copies.
- Sites stamp lazily near the player at fixed map-discoverable coordinates
  (same runtime approach as the MCSM sites, idempotent on reload); builders
  write through a `VoxelSink` so layouts are unit-testable in memory.

### New blocks (920-926)
- `concrete`, `concrete_dark`, `steel_beam`, `ceiling_light` (emissive),
  `tile_block`, `office_glass` (translucent), `marble` — with generated 16x16
  textures (`tools/gen_modern_block_textures.py`) and cube models.

### Tests
- Full suite: 379 tests, 0 failures.

## Dumb-Human AI, Clip Voice, Live Captions (Oct 4, 2026)

### Comedic "dumb human" AI (villagers and mobs)
- New shared psyche layer `ComedyMind` with a per-entity personality (boldness,
  distractibility, drama, incompetence) derived from the entity id. Villagers
  and hostile mobs run on the same mind, so a hunter and a farmer make the
  same kinds of mistakes.
- **Short attention span**: villagers abandon tasks to investigate shiny
  things (`DISTRACTED`), forget what they were doing mid-task (`FORGOT`,
  "Wait. What was I doing?"), and stand admiring nothing (`ADMIRE`). Mobs
  break off chases because something else got interesting (`DISTRACT`).
- **Confident then cowardly**: villagers strut toward danger acting tough
  (`STRUT`, "I am not scared of you!") and scream the moment it crosses their
  boldness-based coward distance ("Okay I am a LITTLE scared of you!"). Bold
  hunters swagger in at walking speed taunting (`MOB_TAUNT`); timid ones cut
  and run at the first hit (`MOB_COWARD`) regardless of health.
- **Herd panic & contagion**: fleeing villagers follow whoever is ahead
  instead of choosing an escape direction (conga lines), fear adopted from
  neighbors amplifies with the chaos level, and hunters trail the nearest
  packmate mid-chase instead of flanking.
- **Job incompetence**: building is slapstick — proud "Nailed it." on
  success, "Ow! My thumb!" on failure, and forgetting the job entirely,
  scaled by the incompetence trait. Failures feed the chaos meter.
- **Petty social drama**: pointless arguments (`ARGUE`), waves at the wrong
  person (`WRONG_WAVE`), loitering after the player (`FOLLOW_PLAYER`), gossip
  lines, and tripping over nothing while wandering.
- **Escalating chaos** (`Chaos`, ticked from EntityManager): screams, crowds,
  arguments, and blunders raise a village-wide meter that decays slowly. High
  chaos makes comedy more frequent, panic stickier, and herds dumber.
- All lines flow through the speech pipeline with delivery emotions and show
  up in the live captions.

### Voice: synthesis removed, recorded clips only
- The ONNX synthesis stack is deleted (Coqui VITS, RVC v2, Kokoro, Misaki,
  Rmvpe, model bundle/assembler, eval tools) along with the onnxruntime
  dependency. Nothing synthesizes speech anymore.
- **Purge completed end to end.** The 928 MB of tracked ONNX model parts
  (`models/java/`) and their manifest/README are gone, the synthesis-only tools
  (`export_coqui_vctk_onnx`, `dump_py_*`, `align_neural_to_reference`,
  `compare_voice_pipeline`, `split_model_assets`, `prepare_java_*_assets`, the
  comparison/sample renderers, …) are deleted, and `PYTHON_VOICE.md` (the
  record of the Python TTS/RVC recipe) is removed with the pipeline it
  documented.
- **Synthesis-era Java is gone, not just unused.** `VillagerVoice` (the model
  loading/synthesis wrapper), `TestWavCompare`, the vocoder comb-whine removal
  (with its FFT/median/notch helpers), the RVC source-energy mask and unvoiced
  source-boost mixers are deleted; `VillagerSynthesizer` is renamed
  `VillagerVoiceRenderer` and no longer takes a model directory. The
  `modelDirectory`/`--models` plumbing is removed from the game bridge, the CLI,
  and all three editors.
- **Profile fields a recording cannot honor are gone.** The RVC retrieval
  weight (`naturalSourceMix`/index rate), the singing expression, the neural
  register lift, and `getEffectivePitchSemitones` are removed from
  `SpeechOptions`; old dialogue catalogs and presets that still carry
  `naturalSourceMix`/`singing` keys load fine (the keys are ignored), and the
  metadata cache key is bumped to v4. The editors' "Timbre strength" and
  "Singing" controls went with them.
- Villager voice now replays **recorded clips**: the Villager News addon voice
  (2,229 transcribed Element Animation clips, `.ogg` decoded via stb_vorbis)
  and the TEAVSRP corpus (17 transcript-named wavs). `ClipIndex` picks the
  best transcript match for a line; unmatched lines fall back to vocalizations
  instead of silence.
- `tools/build_voice_clip_index.py` rebuilds
  `src/main/resources/voice/clips_index.json` from the addon zip (sound
  definitions + `ebi.js` subtitle cues + `en_US.lang`).
- `VoiceMode` is now `clip` (default) or `reference`; `neural` is rejected
  with a clear error. `voxel.voice.addon` points at the addon zip or an
  extracted RP directory; `voxel.voice.corpus` at the TEAVSRP wav folder.

### Live captions
- New `LiveCaptions` queue: speaker name + word-by-word reveal timed to the
  line, then hold and fade. Captions publish even when no audio device exists
  or a clip fails to load, so the voice stack can no longer hide what villagers
  say. The HUD renders the caption block bottom-center (outlined), and the
  reveal re-syncs to the clip's real duration when playback starts.

### Fixes along the way
- Brain-driven emotes never reached the model (the brain and the entity each
  owned a separate `EmotePlayer`); brains now drive the entity's layer.
- Brain-driven building never completed a task (the queue only drained in the
  legacy FSM); jobs now finish via `aiCompleteBuildTarget()` — or comically
  fail.

### Tests
- Full suite: 359 tests, 0 failures.

## Hunter AI Rollout & Villager Gossip (Sep 5, 2026)

### Pack-hunter brain rollout
- The hunter brain now installs automatically on every hostile mob that keeps
  the legacy `updateAI` contract (Cockatrice, Creeper, Endermite, GenericMob,
  MagmaCube, Silverfish, Valkyrie, Zombie). Subclasses with custom FSMs
  (Blaze, Skeleton, Spider, Enderman, Sentry, Mimic, Swet, bosses, ...) keep
  their behavior; the reflection gate walks the hierarchy below EnemyEntity.
- Installation moved to the EnemyEntity base constructor via
  `Brains.newHunterBrainIfLegacy`; no per-entity wiring needed.
- **Brain-driven melee**: a claimed tick previously could not land hits (the
  legacy attack path was skipped). Hunters now telegraph and strike through
  the overridable `performAttack`, so creeper explosions, cockatrice pecks,
  and valkyrie strikes all keep their subclass damage while under brain
  control. Villager prey cannot be damaged (no damage API), so chases against
  them stay atmospheric.

### Villager gossip
- Panic screams now publish `SPEECH_HEARD`; villagers in earshot adopt mild
  fear (`DialogueDirector.onGossip`) and alert-look toward the danger without
  ever seeing it. Stronger gossip also seeds panic. Fear spreads through a
  crowd hop by hop until the shouters calm down.

### Tests
- Full suite: 317 tests, 0 failures (was 311).

## Dialogue Director & Pack-Hunter Mob AI (Sep 5, 2026)

### Improved dialogue system (`com.voxel.audio.DialogueDirector`)
- **Temperament**: every villager now has a stable personality (chattiness,
  cheerfulness) derived from their entity id, so the same villager always
  sounds like the same person.
- **Mood**: fear and wariness spike when hurt or when threats are seen and
  decay over time. Mood colors delivery through the voice pipeline — scared
  lines get louder, faster, higher-pitched delivery with `scared` emotion —
  and biases line selection toward panicked or calm text.
- **Memory**: recently spoken lines are excluded for 6 picks, so villagers
  stop repeating themselves in back-to-back chats; repeated interaction adds
  wariness that fades with time.
- **Merged pools**: the editable `DialogueCatalog` and the built-in
  profession/time table are one pool; `VillagerDialogue.builtinLinesFor`
  exposes built-ins with the same `builtin_...` id scheme for tooling.
- `VillagerAudioManager.requestVillagerDialogue` now routes through the
  director; catalog entries still win over built-ins.
- VillagerBrain publishes threat sightings into the dialogue mood; 12 new
  unit tests (`DialogueDirectorTest`).

### Pack-hunter mob AI (`com.voxel.ai.brain.HunterBrain`)
- **Sight memory**: prey position refreshes only on real line of sight and
  decays over 8 s; losing sight switches the hunter to circling the last-seen
  spot (SEARCH) and it gives up after 6 s instead of homing omnisciently.
- **Pack coordination**: spotting prey publishes a new `HUNT_CALL` stimulus;
  hunters within 24 blocks converge on the shared point with restored hunt
  interest. Independent per-hunter pathing produces natural flanking.
- **Retaliation without omniscience**: damage taken re-aims the hunt at the
  attacker's position even without line of sight.
- **Self-preservation**: below 25% health the hunter disengages and retreats
  from the last known prey position.
- **Path-backed chase**: throttled A* repaths (0.45 s) shared with the
  villager brain, slowing to a walk inside melee range so the attack
  telegraph connects. LURK defers to the legacy FSM so idle behavior is kept.
- Installed on `ZombieEntity` when brains are enabled
  (`-Dvoxel.ai.brains.off=true` restores pure legacy FSM); 6 new decision
  matrix tests (`HunterBrainTest`).

### Voice model bundle
- All four ONNX models (Coqui VCTK VITS, ContentVec, RVC villager, Kokoro)
  re-verified bit-for-bit against `model-parts.manifest`: 23 part files, all
  SHA-256 checksums match. A fresh clone downloads the complete voice setup
  and `ModelAssembler` rebuilds the runtime models on first launch.

### Tests
- Full suite: 311 tests, 0 failures (was 293).

## Base Voice Overhaul — Numbers, Acronyms, Prosody (Aug 25, 2026)

### Base TTS frontend (`CoquiFrontend`)
- **Numbers are spoken, not dropped**: digit runs now expand to English words
  ("310" -> "three hundred and ten", "1,256" -> "one thousand...", "0" ->
  "zero", 4+ digit runs support thousands/millions/billions). Previously the
  digit symbols were silently removed from the token stream, so any line
  containing a number spoke only the words around it.
- **Contractions stay glued**: "what's"/"we're" keep the apostrophe inside the
  word and hit their CMU dictionary entry instead of falling back to
  letter-by-letter pronunciation.
- **All-caps acronyms are spelled** ("TV" -> "tee vee") using real CMU letter
  names (the weak article "a" is remapped to "ay" = /eɪ/).
- **Money and percent attach to numbers**: "$5"/"5$" -> "five dollars",
  "50%" -> "fifty percent"; decimals "3.5" -> "three point five".

### Base TTS synthesis (`CoquiVitsTts`)
- **Sentence joins use the validated 0.1 s gap** (2205 samples at 22.05 kHz)
  instead of Coqui's 450 ms pad — multi-sentence lines no longer plod.
- **Emotion-aware base synthesis**: the VITS noise-scale inputs now respond to
  the line's emotion (angry crisp, sad/scared breathier, happy brighter) so
  the *base* carries the delivery before RVC ever sees it.

### Other
- Voice cache version bumped to v8; old clips regenerate automatically.
- New frontend unit tests for numbers, contractions, acronyms, currency.

## Mob AI & Villager Voice Improvements (Aug 25, 2026)

### Mob AI
- **Enemies stop wall-hacking**: hostile mobs now only refresh their memory of
  the player's position with a clear line of sight (voxel raycaster). They still
  hunt the last seen spot for a few seconds, then lose interest instead of
  walking through walls to your exact position.
- **PathFinder relaxation fix**: A* now keeps the best-known cost per cell and
  re-opens cells only when a strictly cheaper route is found, so a worse
  predecessor can never overwrite the came-from chain (previously produced
  bloated routes on flat terrain and wasted expansions).
- **Villager panic flees around obstacles**: on spotting a threat, villagers
  immediately pathfind an escape point opposite the monster (jittered so two
  villagers don't queue), fall back to the zigzag sprint when no route exists,
  and re-path when pinned against a wall.
- **Villagers pathfind home at night** instead of grinding into walls, and
  **wanderers now detect being stuck** and pick a fresh target instead of
  bumping an obstacle for the full 14 s window.
- New tests: raycaster line of sight, optimal-cost pathfinding, flee-point
  geometry; existing AI/voice suites updated and green.

### RVC voice
- **Per-line noise seed**: each dialogue line now draws its own deterministic
  RVC posterior noise (seed hashed from the clip), so artifacts no longer
  repeat identically on every line while the persistent voice cache stays valid.
- **Energy-scaled excitation**: the RVC noise input is scaled per frame by local
  RMS energy — pauses and unvoiced consonants carry less random excitation,
  cutting the steady hiss RVC synthesized into the gaps between words.
- **Median-smoothed pitch contour**: single-frame autocorrelation outliers
  (which RVC rendered as chirps/crackles) are removed with a 3-tap median over
  voiced runs only; real rises and falls are untouched.
- **Click-free output padding**: if the RVC graph returns a few samples short,
  the tail is now fade-ramped instead of hard-zeroed (no truncation click).

## End Update + Tutorial World Expansion (Aug 24, 2026)

### End Update — a barren void, kept lifeless
- **New blocks** (fixed IDs 900–905, stable across saves):
  - `purpur_block`, `purpur_pillar`, `chorus_plant`, `chorus_flower` (vanilla textures)
  - `end_glass` — new custom texture: pale translucent violet glass
  - `void_steel` — new custom texture: dark End-metal plate (generated by
    `tools/gen_end_textures.py`)
- **End dimension generation overhaul** (`DimensionWorldGenerator`):
  - Ring of 8 glowstone-capped obsidian monoliths around the central island
  - Larger, flatter, thicker outer islands with dead purpur ruins (broken pillar
    stumps, cracked flagstone) and shattered end-brick tiles
  - Rare drifting end-stone shards in the inner void
  - Sparse chorus groves — the void's one signature plant; stems cap with flowers
  - No cities. No mobs. Cold geometry only.

### Tutorial World
- **New Zone 15 "The Barren Isles"** at (-320, 160): floating end-stone isle reached
  by a rising end-brick causeway with end-rod lamps, an obsidian monolith ring, a
  dead purpur pavilion whose end-glass oculus frames the dragon egg pedestal, the
  last chorus grove on its own islet, a void-steel altar, and a chest of End loot.
- **Castle upgrades**: library/enchanting wing (bookshelf rows + two enchanting
  tables) and an iron→gold→diamond beacon pyramid in the south courtyard.
- Bundled world re-exported via `TutorialWorldExporter` (2304 chunk files).

### Recipes — crafting
- End chain: end bricks, purpur block (popped chorus *or* quartz+end-stone variant),
  purpur pillars, end rods, chorus plant/flower, end glass, void steel.
- Fixes & gaps: bread from wheat (was missing entirely), wool from string,
  emerald blocks now compact/decompact from the gem, lapis blocks via blue dye.

### Recipes — furnace
- New smelting: chorus fruit → popped chorus fruit, potato → baked potato,
  netherrack → nether brick item.
- New fuels: blaze rod (120 s), sticks, slabs, bookshelf, chest.

### Create mod
- **Mechanical press**: generalized alloying — copper+zinc → brass AND
  iron+end stone → void steel; new compacting recipes (clay balls → bricks,
  glowstone dust → glowstone).
- **Millstone**: glowstone → dust ×4, blaze rods → powder ×2, sandstone → sand,
  clay → clay balls (recipes now carry output counts).
- **Crushing wheel**: stone/cobblestone recycling chain, coal/diamond/lapis/
  emerald ore crushing, glowstone → dust ×8.
- **Mechanical saw**: logs cut into their *matching* planks (6 per log instead of
  generic oak), planks and stone bricks sawn into slabs (2 per block).

### Verification
- `mvnw compile` clean; boot test ran 75 s at ~35 fps with zero exceptions;
  297 blockstates + 594 item models registered; tutorial bundle regenerated.
