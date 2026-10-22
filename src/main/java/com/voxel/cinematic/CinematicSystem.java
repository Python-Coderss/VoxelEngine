package com.voxel.cinematic;

import org.joml.Vector3f;

import com.voxel.game.GameContext;
import com.voxel.world.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Movie-mode director — MCSM-inspired shot direction on top of the cutscene
 * plumbing.
 *
 * Every scene is now a LIST OF SHOTS ({@link CameraShot}): within a shot the
 * camera moves with an eased dolly/arc and tracks its subject; between shots it
 * CUTS, the way Minecraft: Story Mode edits. Framing uses film conventions —
 * establishing wides, medium two-shots, tight close-ups on faces and props,
 * over-the-shoulder reactions, low-angle hero shots and dutch tilts.
 *
 * Gameplay actions get their own short beats ({@link #playActionBeat}): a cut
 * in to a close-up of the thing you acted on (the table, the furnace, the ore,
 * the chest lid), then a reaction framing. Milestone actions always play;
 * routine actions are rate-limited so the game stays playable.
 *
 * Pure overlay events (teleport flash, death/respawn fades) never touch the
 * camera; scene events take over yaw/pitch + camera position and restore the
 * player's original orientation when they end.
 *
 * tick(dt) runs on the logic thread. The render thread reads camPos / roll /
 * fov / fade / letterbox / title state through CameraController and HudUI.
 */
public class CinematicSystem {
    private final GameContext ctx;

    // --- Active scene ---
    public boolean active = false;
    public float timer = 0f;
    public float duration = 0f;
    private boolean controlsCamera = false;

    // --- Shot sequence playback ---
    private final List<CameraShot> shots = new ArrayList<>();
    private int shotIndex = 0;
    private float shotTimer = 0f;
    private float sceneExtraHold = 0f;   // linger after the last shot
    /** Timed dialogue for the active scene; published to the live captions. */
    private CinematicScript script = new CinematicScript();

    // Sampled camera state for this tick (world coords)
    private final Vector3f camPos = new Vector3f();
    private final Vector3f camLook = new Vector3f();
    private float camFov = 90f;
    private float camRoll = 0f;

    private float savedYaw, savedPitch, savedPlayerYaw;

    // --- Overlay state (read by HudUI every frame) ---
    public volatile float fadeAlpha = 0f;      // 0 clear .. 1 black
    public volatile float fadeRed = 0f;        // 0 black .. 1 red tint for death
    public volatile float letterbox = 0f;      // 0 off .. 1 full bars
    public volatile String title = "";
    public volatile String subtitle = "";
    public volatile float textAlpha = 0f;
    /** True while a skippable scene is playing (HUD shows "ESC — skip"). */
    public volatile boolean skipHintVisible = false;

    // Fade animation
    private float fadeFrom, fadeTo, fadeTimer, fadeDuration;
    private boolean fading = false;

    // Nightfall trigger: watch worldTime crossing 18:00 (1080) once per cycle
    private double lastWorldTime = -1.0;
    private boolean introPlayed = false;

    // --- First-time event tracking (so reveals only play once) ---
    private boolean lowHealthWarned = false;
    private boolean levelUpSeen = false;
    /** Dusk scenes are a flourish, not a cutscene every night. */
    private int nightfallSeen = 0;
    private static final int MAX_NIGHTFALL_SCENES = 3;

    // --- Scene queue (so a flourish doesn't stomp a reveal mid-play) ---
    private final java.util.ArrayDeque<Runnable> pendingScenes = new java.util.ArrayDeque<>();

    // --- Action beat throttling (routine beats shouldn't spam) ---
    private double beatCooldownUntil = -1.0;
    private static final double BEAT_COOLDOWN_SECONDS = 8.0;

    // --- First-of-kind tracking (milestone beats) ---
    private final java.util.Set<String> firstsSeen = new java.util.HashSet<>();

    // --- MCSM decisions and quick-time events ---------------------------------
    // Both are overlay state: they never take the camera, they can play over a
    // scene, and the render thread only reads volatile snapshots.
    private volatile CinematicChoice choice;
    private volatile float choiceRemaining;
    /** -2 = nothing queued; otherwise the option index chosen by input. */
    private volatile int pendingChoicePick = -2;
    private java.util.function.Consumer<Integer> choiceCallback;

    private volatile QuickTimeEvent qte;
    private volatile float qteRemaining;
    /** -1 = nothing queued; 1 success, 0 miss. */
    private volatile int pendingQteResult = -1;
    private java.util.function.Consumer<Boolean> qteCallback;

    /**
     * Returns true exactly once per key — "first diamond mined", "first cake
     * crafted" and friends earn an un-throttled milestone beat.
     */
    public boolean firstTime(String key) {
        return firstsSeen.add(key);
    }

    public CinematicSystem(GameContext ctx) {
        this.ctx = ctx;
    }

    /** True while a scene owns the camera; CameraController returns getCamPos(). */
    public boolean cameraActive() {
        return active && controlsCamera;
    }

    /** Current directed camera position (valid when cameraActive()). */
    public Vector3f getCamPos() {
        return camPos;
    }

    /** Dutch roll (degrees) for the current shot; 0 when idle. */
    public float getRoll() {
        return cameraActive() ? camRoll : 0f;
    }

    /** Lens FOV (degrees) for the current shot; 90 (gameplay) when idle. */
    public float getFovDegrees() {
        return cameraActive() ? camFov : com.voxel.camera.CameraController.DEFAULT_FOV_DEGREES;
    }

    // ── Scenes (MCSM-style shot sequences) ────────────────────────────────────

    /** One-time spawn intro: establishing wide → close-up on the hero →
     *  over-the-shoulder reveal of the horizon, under a fade-in + title card. */
    public void playIntro() {
        if (introPlayed || active) return;
        introPlayed = true;
        java.util.function.Supplier<Vector3f> hero = this::heroPoint;

        List<CameraShot> seq = new ArrayList<>();
        // 1) Establishing wide: slow orbit around the player (fade in from black).
        seq.add(CameraShot.wide(hero.get(), 20, 75, 7.0f, 2.6f, 3.2f).tracking(hero));
        // 2) Cut in: close-up creeping toward the player's face.
        seq.add(CameraShot.closeUp(hero.get(), 120, 2.2f, 0.9f, 2.2f).tracking(hero));
        // 3) Reaction: over-the-shoulder toward the horizon they'll explore.
        Vector3f face = hero.get();
        Vector3f horizon = new Vector3f(face.x + 8f, face.y + 1.5f, face.z);
        seq.add(CameraShot.overTheShoulder(face, horizon, 2.4f).tracking(() ->
            new Vector3f(heroPoint().x + 8f, heroPoint().y + 1.5f, heroPoint().z)));

        beginScene(seq, true);
        startFade(1.0f, 0.0f, 1.6f);
        showTitle("THE PACK.PNG WORLD", "Seed 3257840388504953787 · you know this hill", 1.2f, 5.2f);
    }

    /**
     * An opening scene tailored to the Tutorial World: an establishing orbit
     * of the castle, a crane over the guided-station arc, then a close-up and
     * an over-the-shoulder look toward the east gate. Narration names the tour
     * so a first-time player knows the world is teaching, not just decorating.
     */
    public void playTutorialIntro() {
        if (introPlayed || active) return;
        introPlayed = true;
        java.util.function.Supplier<Vector3f> hero = this::heroPoint;
        Vector3f start = hero.get();
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.wide(start, 205, 250, 26f, 9f, 3.2f).tracking(hero));
        seq.add(CameraShot.crane(start, 250f, 20f, 3f, 15f, 3.4f).tracking(hero));
        seq.add(CameraShot.closeUp(start, 300, 2.6f, 1.1f, 2.2f).tracking(hero));
        Vector3f gate = new Vector3f(start.x + 18f, start.y + 1.2f, start.z + 6f);
        seq.add(CameraShot.overTheShoulder(start, gate, 2.6f).tracking(hero));
        CinematicScript lines = new CinematicScript()
                .at(1.0f, "Narrator", "This world was built to teach you everything it can do.")
                .at(4.6f, "Narrator", "Six stations circle the castle. Follow the gold markers.")
                .at(8.2f, "Narrator", "Mine, craft, smelt, forge, travel — then raid the dungeon hall.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.8f);
        startFade(1.0f, 0.0f, 1.6f);
        showTitle("THE TUTORIAL", "Six steps from your first block to your first raid", 1.0f, 5.4f);
        // The first decision lands as soon as the intro hands control back:
        // playChoice queues behind the scene instead of stomping it.
        playChoice(StoryBeats.tutorialFork(), null);
    }

    /** A short, confident establishing pass over the creative Showcase hub. */
    public void playShowcaseIntro() {
        if (introPlayed || active) return;
        introPlayed = true;
        java.util.function.Supplier<Vector3f> hero = this::heroPoint;
        Vector3f start = hero.get();
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.wide(start, 190, 240, 24f, 10f, 3.0f).tracking(hero));
        seq.add(CameraShot.crane(start, 240f, 14f, 2f, 11f, 3.0f).tracking(hero));
        seq.add(CameraShot.medium(start, 310, 3.4f, 1.2f, 2.2f).tracking(hero));
        CinematicScript lines = new CinematicScript()
                .at(0.8f, "Narrator", "Creative mode. Every block, every machine, every dimension.")
                .at(4.0f, "Narrator", "The gateway holds a lit portal for each realm. Pick one.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.75f);
        startFade(1.0f, 0.0f, 1.4f);
        showTitle("SHOWCASE WORLD", "Every feature, one hub", 0.9f, 4.6f);
    }

    /** Point & Click demo: show the plaza stations before handing over control. */
    public void playPointClickIntro() {
        if (introPlayed || active) return;
        introPlayed = true;
        java.util.function.Supplier<Vector3f> hero = this::heroPoint;
        Vector3f start = hero.get();
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.crane(start, 225f, 9f, 2f, 7f, 2.8f).tracking(hero));
        seq.add(CameraShot.medium(start, 300, 3.2f, 1.0f, 2.0f).tracking(hero));
        seq.add(CameraShot.overTheShoulder(start,
                new Vector3f(start.x, start.y, start.z - 8f), 2.2f).tracking(hero));
        CinematicScript lines = new CinematicScript()
                .at(0.6f, "Narrator", "Point at anything. The world answers with a billboard.")
                .at(3.2f, "Narrator", "Four paths leave the plaza: workshop, market, quarry, farm.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.7f);
        startFade(1.0f, 0.0f, 1.2f);
        showTitle("POINT & CLICK", "Click the world. It clicks back.", 0.6f, 3.6f);
        // Ask where to go first; the pick is remembered as choice.pointclick_fork.
        playChoice(StoryBeats.pointClickFork(), null);
    }

    /** Dusk moment: a low-angle glance up at the darkening sky as night falls. */
    public void playNightfall() {
        if (active) return;
        if (nightfallSeen >= MAX_NIGHTFALL_SCENES) return;
        nightfallSeen++;
        // Never yank the camera out of a UI screen, a manual cutscene, or
        // death — nightfall recurs every cycle, missing one beat is fine.
        if (ctx.inventoryOpen || ctx.commandMode || ctx.player.isDead()
                || ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive
                || ctx.tvCutsceneActive || ctx.pauseMenuOpen) return;

        Vector3f feet = ctx.player.getPosition();
        Vector3f low = new Vector3f(feet.x, feet.y + 0.4f, feet.z);
        Vector3f high = new Vector3f(feet.x, feet.y + 1.9f, feet.z);
        CameraShot drift = CameraShot.dolly(low, high, new Vector3f(feet.x, feet.y + 22f, feet.z),
            75.0f, 3.5f);
        drift.ease = CameraShot.Ease.SMOOTH;
        drift.dutch(-3.0f, 3.0f);
        List<CameraShot> seq = new ArrayList<>();
        seq.add(drift);
        beginScene(seq, true);
        showTitle("NIGHT FALLS", "Survive until dawn", 0.8f, 2.4f);
    }

    /**
     * Portal travel: a tight push-in at the portal plane, cut to black mid-move,
     * the classic "through the looking glass" gesture. Plays on every portal hop
     * (overlay-only world switch happens in GameContext before this is called).
     */
    public void playPortalTravel() {
        if (active) { queueScene(this::playPortalTravel); return; }
        Vector3f p = ctx.player.getPosition();
        Vector3f fwd = lookForward(ctx.yaw, ctx.pitch);
        Vector3f eye = new Vector3f(p.x, p.y + 1.6f, p.z);
        Vector3f ahead = new Vector3f(eye).fma(2.2f, fwd);
        // Straight push through, looking at the point we're flying toward.
        CameraShot push = CameraShot.dolly(
            new Vector3f(eye).fma(0.2f, fwd), ahead, ahead, 65.0f, 2.2f);
        push.ease = CameraShot.Ease.EASE_OUT;
        List<CameraShot> seq = new ArrayList<>();
        seq.add(push);
        beginScene(seq, true);
        startFade(fadeAlpha, 1.0f, 0.7f);
        fadeToBlackThenBack(0.8f);
        showTitle("THROUGH THE PORTAL", "", 0.3f, 1.4f);
    }

    /** Dimensions the player has already entered in this world. */
    private final java.util.Set<com.voxel.world.DimensionType> dimensionsSeen =
            new java.util.HashSet<com.voxel.world.DimensionType>();
    /** Wall-clock time of the last ambient realm line (seconds). */
    private float lastAmbientAt = -1f;
    private final java.util.Random ambientRng = new java.util.Random(0x5EED5EEDL);
    /** Seconds between ambient realm observations. */
    public static final float AMBIENT_INTERVAL_SECONDS = 160f;

    /**
     * Speak for the realm the player just entered. The first visit is a real
     * scene (establishing wide, then a medium on the player) with that
     * dimension's narration; later visits get a one-line overlay so portal
     * hopping never becomes a slideshow.
     */
    public void playDimensionArrival(com.voxel.world.DimensionType dimension) {
        if (dimension == null || ctx == null || ctx.player == null) return;
        if (active) {
            final com.voxel.world.DimensionType queued = dimension;
            queueScene(() -> playDimensionArrival(queued));
            return;
        }
        boolean firstVisit = dimensionsSeen.add(dimension);
        if (firstVisit) {
            java.util.function.Supplier<Vector3f> hero = this::heroPoint;
            List<CameraShot> sequence = new ArrayList<CameraShot>();
            sequence.add(CameraShot.wide(hero.get(), 200, 235, 22f, 8f, 3.0f)
                    .tracking(hero));
            sequence.add(CameraShot.medium(hero.get(), 300, 3.2f, 1.0f, 2.2f)
                    .tracking(hero));
            beginScene(sequence, true, 0f, DimensionDialogue.arrivalLines(dimension));
            startFade(0.55f, 0f, 1.2f);
            showTitle(DimensionDialogue.title(dimension),
                    DimensionDialogue.subtitle(dimension), 0.8f, 4.2f);
            return;
        }
        CinematicScript line = new CinematicScript()
                .at(0.25f, "Narrator", DimensionDialogue.returnLine(dimension));
        beginOverlayScene(4.0f, line);
    }

    /**
     * One small observation about the current realm, rate-limited. Called from
     * the logic tick; does nothing during menus, cutscenes or death.
     */
    public void playDimensionAmbient(com.voxel.world.DimensionType dimension) {
        if (dimension == null || active || ctx == null || ctx.player == null) return;
        if (ctx.inventoryOpen || ctx.commandMode || ctx.pauseMenuOpen
                || ctx.player.isDead() || ctx.initializing) return;
        float now = (float) org.lwjgl.glfw.GLFW.glfwGetTime();
        if (lastAmbientAt >= 0f && now - lastAmbientAt < AMBIENT_INTERVAL_SECONDS) return;
        String line = DimensionDialogue.ambientLine(dimension, ambientRng);
        if (line == null) return;
        lastAmbientAt = now;
        beginOverlayScene(4.2f, new CinematicScript().at(0.2f, "Narrator", line));
    }

    /**
     * Low-health warning: a one-time letterboxed overlay with a red vignette
     * and a desperate title when the player first drops into critical health.
     * Overlay-only so it never rips camera control mid-combat.
     */
    public void playLowHealthWarning() {
        if (lowHealthWarned || active) return;
        lowHealthWarned = true;
        beginOverlayScene(3.0f);
        fadeRed = 0.5f;
        startFade(0.0f, 0.45f, 0.8f);
        fadeToBlackThenBack(1.6f);
        showTitle("LOW HEALTH", "Find shelter — fast", 0.3f, 1.8f);
    }

    /**
     * Level-up flourish: a brief triumphant beat the first time the player
     * crosses an XP level milestone (level 1, 5, 10, ...). Overlay-only so it
     * doesn't rip camera control during combat.
     */
    public void playLevelUp(int newLevel) {
        boolean milestone = (newLevel == 1) || (newLevel % 5 == 0);
        if (!milestone || levelUpSeen) return;
        if (active) { final int lvl = newLevel; queueScene(() -> playLevelUp(lvl)); return; }
        levelUpSeen = true;
        beginOverlayScene(2.4f); // overlay-only; keep the player in control
        startFade(0.0f, 0.35f, 0.45f);
        fadeToBlackThenBack(1.4f);
        showTitle("LEVEL " + newLevel, "You grow stronger", 0.2f, 1.6f);
    }

    // ── Action beats — "there is a cutscene for every action" ─────────────────

    /** The kinds of player action that earn their own moment on screen. */
    public enum Beat {
        CRAFT, SMELT, CHEST, TABLE, FURNACE, TV, PORTAL,
        BOSS_SPAWN, BOSS_DEFEAT, ORE, VILLAGER, TAME, RIDE, EAT, ENCHANT, BUILD
    }

    /**
     * Play a short MCSM-style beat for a gameplay action: a cut-in close-up of
     * the thing acted on, then a reaction framing. {@code milestone} beats
     * (first-of-kind, boss moments) always play; routine beats are throttled to
     * one every {@link #BEAT_COOLDOWN_SECONDS} seconds.
     *
     * @param beat     what happened
     * @param subject  world point to frame (block center, mob chest, face, ...)
     */
    public void playActionBeat(Beat beat, Vector3f subject, boolean milestone) {
        if (active) return; // a real scene outranks a beat; skip silently
        double now = org.lwjgl.glfw.GLFW.glfwGetTime();
        if (!milestone && now < beatCooldownUntil) return;
        // Beats are cutaway shots; they may play over an open UI (that's when
        // most actions happen) but never over another camera owner or death.
        if (ctx.commandMode || ctx.player.isDead()
                || ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive
                || ctx.tvCutsceneActive || ctx.pauseMenuOpen || ctx.mapOpen) return;
        beatCooldownUntil = now + BEAT_COOLDOWN_SECONDS;

        List<CameraShot> seq = new ArrayList<>();
        Vector3f s = new Vector3f(subject);
        java.util.function.Supplier<Vector3f> face = this::heroPoint;
        switch (beat) {
            case CRAFT:
            case TABLE: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 1.1f, s.z + 0.5f);
                seq.add(CameraShot.closeUp(prop, 45, 1.9f, 0.7f, 1.1f));
                seq.add(CameraShot.medium(face.get(), 215, 2.6f, 0.5f, 1.1f).tracking(face));
                showTitle("CRAFTED", "", 0.2f, 1.1f);
                break;
            }
            case SMELT:
            case FURNACE: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 0.7f, s.z + 0.5f);
                CameraShot glow = CameraShot.closeUp(prop, 25, 1.7f, 0.35f, 1.4f);
                glow.dutch(2.5f, -1.0f);
                seq.add(glow);
                seq.add(CameraShot.medium(face.get(), 205, 2.4f, 0.4f, 1.0f).tracking(face));
                showTitle("THE FORGE", "", 0.15f, 1.2f);
                break;
            }
            case CHEST: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 0.9f, s.z + 0.5f);
                seq.add(CameraShot.closeUp(prop, 60, 1.8f, 0.8f, 1.3f));
                seq.add(CameraShot.medium(face.get(), 225, 2.3f, 0.4f, 1.0f).tracking(face));
                showTitle("TREASURE", "", 0.15f, 1.1f);
                break;
            }
            case ORE: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 0.5f, s.z + 0.5f);
                CameraShot strike = CameraShot.closeUp(prop, 95, 1.5f, 0.2f, 1.3f);
                strike.ease = CameraShot.Ease.EASE_OUT;
                seq.add(strike);
                showTitle("RICHES", "", 0.2f, 1.0f);
                break;
            }
            case TV: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 0.7f, s.z + 0.5f);
                seq.add(CameraShot.closeUp(prop, 0, 2.2f, 0.3f, 1.4f));
                showTitle("VILLAGER NEWS", "", 0.1f, 1.3f);
                break;
            }
            case BOSS_SPAWN: {
                seq.add(CameraShot.lowAngle(s, 30, 4.5f, 1.6f));
                seq.add(CameraShot.wide(s, 60, 110, 8.0f, 3.5f, 2.2f));
                showTitle("A CHALLENGER", "Defeat it to claim the prize", 0.2f, 2.6f);
                break;
            }
            case BOSS_DEFEAT: {
                seq.add(CameraShot.wide(s, 140, 205, 7.5f, 3.0f, 2.2f));
                seq.add(CameraShot.closeUp(face.get(), 250, 2.2f, 0.9f, 1.3f).tracking(face));
                showTitle("VICTORY", "", 0.2f, 2.0f);
                break;
            }
            case VILLAGER: {
                Vector3f head = new Vector3f(s.x, s.y + 1.35f, s.z);
                seq.add(CameraShot.overTheShoulder(face.get(), head, 2.2f).tracking(
                    () -> new Vector3f(s.x, s.y + 1.35f, s.z)));
                seq.add(CameraShot.closeUp(head, 300, 1.8f, 0.35f, 1.6f).tracking(
                    () -> new Vector3f(s.x, s.y + 1.35f, s.z)));
                break;
            }
            case EAT: {
                seq.add(CameraShot.closeUp(face.get(), 200, 1.7f, 0.25f, 1.2f).tracking(face));
                break;
            }
            case TAME:
            case RIDE: {
                seq.add(CameraShot.medium(s, 70, 3.2f, 1.0f, 1.3f));
                seq.add(CameraShot.medium(face.get(), 230, 2.5f, 0.5f, 1.1f).tracking(face));
                showTitle(beat == Beat.TAME ? "A NEW FRIEND" : "RIDE OF THE DAY", "", 0.15f, 1.3f);
                break;
            }
            case ENCHANT: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 1.2f, s.z + 0.5f);
                CameraShot swirl = CameraShot.closeUp(prop, 120, 2.0f, 0.9f, 1.6f);
                swirl.dutch(-4.0f, 4.0f);
                seq.add(swirl);
                showTitle("ENCHANTED", "", 0.15f, 1.2f);
                break;
            }
            case BUILD: {
                Vector3f prop = new Vector3f(s.x + 0.5f, s.y + 0.5f, s.z + 0.5f);
                seq.add(CameraShot.closeUp(prop, 150, 1.9f, 0.6f, 1.1f));
                break;
            }
            case PORTAL:
            default: {
                seq.add(CameraShot.medium(s, 45, 3.0f, 1.2f, 1.4f));
                break;
            }
        }
        beginScene(seq, true, 0f);
        // The first ore is a scripted scare: a beam gives way overhead and the
        // player gets one timed press. A miss only changes the flavour line.
        if (beat == Beat.ORE && firstTime("qte.ore")) {
            playQte(StoryBeats.oreQte());
        }
        // Beats never leave a black frame behind — only a whisper of bars.
        letterbox = Math.max(letterbox, 0.6f);
    }

    // ── Overlay-only events (camera untouched) ────────────────────────────────

    /** Quick blackout when stepping through a portal. */
    public void teleportFlash() {
        fadeRed = 0f;
        startFade(fadeAlpha, 1.0f, 0.30f);
        fadeToBlackThenBack(0.45f);
    }

    /** Death: slow sink into dark red. Safe to call repeatedly while dead. */
    public void deathFade() {
        if (fading && fadeTo >= 0.8f) return;
        fadeRed = 1f;
        startFade(fadeAlpha, 0.85f, 1.2f);
    }

    /** Respawn: wake up from black. */
    public void respawnFade() {
        fadeRed = 0f;
        fadeAlpha = 1f;
        startFade(1.0f, 0.0f, 1.4f);
    }

    /** Brief cinematic bars without a scene (e.g. milestone moments). */
    public void barsMoment(float seconds) {
        barsHoldUntil = org.lwjgl.glfw.GLFW.glfwGetTime() + seconds;
    }
    private double barsHoldUntil = -1.0;

    /** Reset first-time tracking (used when a brand-new world is created). */
    public void resetFirstTimeFlags() {
        lowHealthWarned = false;
        levelUpSeen = false;
        introPlayed = false;
        nightfallSeen = 0;
        dimensionsSeen.clear();
        lastAmbientAt = -1f;
        pendingScenes.clear();
    }

    /** Clear only the low-health warning flag (on respawn) so the reveal can
     *  re-trigger on a future near-death. */
    public void resetLowHealthFlag() {
        lowHealthWarned = false;
    }

    // ── Per-tick update ───────────────────────────────────────────────────────

    public void tick(float dt) {
        // Decisions and QTEs advance even while a scene owns the camera.
        tickChoiceAndQte(dt);
        // Fade animation always advances
        if (fading) {
            fadeTimer += dt;
            float t = Math.min(1.0f, fadeTimer / fadeDuration);
            fadeAlpha = fadeFrom + (fadeTo - fadeFrom) * t;
            if (t >= 1.0f) {
                fading = false;
                if (pendingFadeBack) {
                    pendingFadeBack = false;
                    startFade(1.0f, 0.0f, fadeBackDuration);
                }
            }
        }
        // Letterbox easing toward its target
        double now = org.lwjgl.glfw.GLFW.glfwGetTime();
        float barsTarget = (active || now < barsHoldUntil) ? 1f : 0f;
        letterbox += (barsTarget - letterbox) * Math.min(1.0f, dt * 5.0f);

        // Title card timing
        if (titleVisible) {
            textAlpha = computeTitleAlpha(now);
            if (now > titleHideAt) titleVisible = false;
        } else {
            textAlpha = 0f;
        }

        // Scene playback
        if (!active) {
            detectNightfall();
            // Every realm gets a voice: a small observation now and then, never
            // during menus, death or another scene.
            playDimensionAmbient(ctx.activeDimension);
            drainQueuedScenes();
            return;
        }
        float previousTimer = timer;
        timer += dt;
        publishScript(previousTimer, timer);

        if (controlsCamera) {
            advanceShots(dt);
            liftCameraOutOfTerrain();
            // Publish the shot's orientation in the engine's yaw/pitch convention
            // (forward = [cos yaw·cos pitch, sin pitch, sin yaw·cos pitch]).
            Vector3f look = new Vector3f(camLook).sub(camPos);
            if (look.lengthSquared() > 1e-6f) {
                look.normalize();
                ctx.yaw = (float) Math.toDegrees(Math.atan2(look.z, look.x));
                ctx.pitch = (float) Math.toDegrees(Math.asin(
                    Math.max(-1.0f, Math.min(1.0f, look.y))));
            }
            ctx.playerYaw = ctx.yaw;
        }

        if (timer >= duration) endScene();
    }

    /**
     * Advances the shot list: CUTS to the next shot when one ends (film edit —
     * no interpolation across the boundary) and samples the active shot into
     * camPos/camLook/camFov/camRoll.
     */
    private void advanceShots(float dt) {
        if (shots.isEmpty()) return;
        shotTimer += dt;
        while (shotIndex < shots.size() - 1 && shotTimer >= shots.get(shotIndex).duration) {
            shotTimer -= shots.get(shotIndex).duration;
            shotIndex++; // hard cut
        }
        CameraShot shot = shots.get(shotIndex);
        float t = shot.duration > 0 ? Math.min(1.0f, shotTimer / shot.duration) : 1.0f;
        CameraShot.Sample s = shot.sample(t);
        camPos.set(s.pos);
        camLook.set(s.lookAt);
        camFov = s.fov;
        camRoll = s.roll;
    }

    /**
     * Nudges the directed camera upward until it sits in a non-solid voxel
     * (bounded attempts so it always terminates). No-op when the world isn't
     * committed yet.
     */
    private void liftCameraOutOfTerrain() {
        if (ctx.world == null) return;
        for (int i = 0; i < 16; i++) {
            int bx = (int) Math.floor(camPos.x);
            int by = (int) Math.floor(camPos.y);
            int bz = (int) Math.floor(camPos.z);
            int v = ctx.world.getVoxel(bx, by, bz);
            boolean solid = v > 0 && (ctx.blockDataManager == null
                    || ctx.blockDataManager.isFullBlock(v));
            if (!solid) return;
            camPos.y += 1.0f;
        }
    }

    private void detectNightfall() {
        double wt = ctx.worldTime;
        if (lastWorldTime >= 0 && !introJustFinished()) {
            double lastMod = lastWorldTime % 1440.0;
            double curMod = wt % 1440.0;
            boolean crossed = (lastMod < 1080.0 && curMod >= 1080.0) || (lastMod > curMod && curMod >= 1080.0);
            if (crossed) playNightfall();
        }
        lastWorldTime = wt;
    }
    private double introEndTime = -1.0;
    private boolean introJustFinished() {
        return introEndTime > 0 && org.lwjgl.glfw.GLFW.glfwGetTime() - introEndTime < 10.0;
    }

    /** If nothing is active, start the next queued scene (if any). */
    private void drainQueuedScenes() {
        if (pendingScenes.isEmpty()) return;
        Runnable next = pendingScenes.poll();
        next.run();
    }

    private void queueScene(Runnable scene) {
        pendingScenes.add(scene);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Player head point — the default subject for reaction framings. */
    private Vector3f heroPoint() {
        Vector3f p = ctx.player.getPosition();
        return new Vector3f(p.x, p.y + 1.45f, p.z);
    }

    /** Start a shot-sequence scene that owns the camera. */
    private void beginScene(List<CameraShot> sequence, boolean takeCamera) {
        beginScene(sequence, takeCamera, 0f);
    }

    private void beginScene(List<CameraShot> sequence, boolean takeCamera, float extraHold) {
        beginScene(sequence, takeCamera, extraHold, null);
    }

    private void beginScene(List<CameraShot> sequence, boolean takeCamera, float extraHold,
                            CinematicScript lines) {
        active = true;
        script = lines == null ? new CinematicScript() : lines;
        controlsCamera = takeCamera;
        timer = 0f;
        sceneExtraHold = extraHold;
        shots.clear();
        shots.addAll(sequence);
        shotIndex = 0;
        shotTimer = 0f;
        duration = 0f;
        for (CameraShot s : shots) duration += s.duration;
        duration += sceneExtraHold;
        skipHintVisible = true;
        if (takeCamera) {
            savedYaw = ctx.yaw;
            savedPitch = ctx.pitch;
            savedPlayerYaw = ctx.playerYaw;
            // Start exactly on the first shot (a cut, not a lurch from the old
            // smoothed camera state).
            if (!shots.isEmpty()) {
                CameraShot.Sample s = shots.get(0).sample(0f);
                camPos.set(s.pos);
                camLook.set(s.lookAt);
                camFov = s.fov;
                camRoll = s.roll;
            } else {
                camPos.set(ctx.player.getPosition());
            }
        }
    }

    /** Start an overlay-only scene (no camera control). */
    private void beginOverlayScene(float durationSec) {
        beginOverlayScene(durationSec, null);
    }

    /** Overlay-only scene that still speaks a timed script. */
    private void beginOverlayScene(float durationSec, CinematicScript lines) {
        active = true;
        controlsCamera = false;
        timer = 0f;
        duration = durationSec;
        shots.clear();
        script = lines == null ? new CinematicScript() : lines;
        skipHintVisible = true;
    }

    /**
     * Speak any dialogue lines whose time has just passed. Every speaker gets a
     * voice: the player ("You"), the narrator, builders and bosses are spoken
     * through their character profile, and villager labels keep the recorded
     * clip voice. Captions always carry the line that was actually spoken.
     */
    private void publishScript(float from, float to) {
        if (script == null || script.isEmpty()) return;
        for (CinematicScript.Line line : script.due(from, to)) {
            if (CinematicScript.isMonologue(line)) {
                // The player's inner voice is Jesse's own, spoken as monologue
                // rather than as a third-person character beat.
                com.voxel.ai.speech.CharacterVoice.speakMonologue(line.text);
            } else {
                com.voxel.ai.speech.CharacterVoice.speakScripted(line.speaker, line.text);
            }
        }
    }

    // ── Scripted scenes (shots + dialogue) ───────────────────────────────────

    /**
     * Play a shot sequence that carries timed dialogue. The lines are published
     * to the live captions as the scene plays.
     */
    public void playScriptedScene(String titleText, String subText,
                                  List<CameraShot> sequence, CinematicScript lines) {
        if (active) {
            queueScene(() -> playScriptedScene(titleText, subText, sequence, lines));
            return;
        }
        beginScene(sequence, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.6f);
        if (titleText != null && !titleText.isEmpty()) {
            showTitle(titleText, subText == null ? "" : subText, 0.3f, 2.8f);
        }
    }

    /**
     * Reveal a structure the player has just found: a wide establishing orbit
     * cut to a closer look, with a line of dialogue (usually an ancient
     * builder speaking about their own work).
     */
    public void playStructureReveal(String siteName, Vector3f subject,
                                    String speaker, String line) {
        if (active) {
            queueScene(() -> playStructureReveal(siteName, subject, speaker, line));
            return;
        }
        if (ctx.player.isDead() || ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive
                || ctx.tvCutsceneActive || ctx.pauseMenuOpen || ctx.inventoryOpen
                || ctx.commandMode) {
            return;
        }
        Vector3f focus = new Vector3f(subject);
        java.util.function.Supplier<Vector3f> track = () -> new Vector3f(focus);
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.wide(subject, 210, 330, 16f, 4.5f, 3.4f).tracking(track));
        // Crane up over the site so its scale reads before we cut in.
        seq.add(CameraShot.crane(subject, 280f, 12f, 3f, 12f, 3.0f).tracking(track));
        seq.add(CameraShot.closeUp(
                new Vector3f(subject.x, subject.y + 2.5f, subject.z), 300, 5.0f, 1.0f, 2.6f)
                .tracking(track));
        // Two builder lines: the call-out and the why behind the work.
        CinematicScript lines = new CinematicScript();
        String who = speaker == null || speaker.isEmpty() ? "Ancient Builder" : speaker;
        if (line != null && !line.isEmpty()) lines.at(0.7f, who, line);
        lines.at(3.2f, who, "We built it to outlast us — remember that.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.7f);
        showTitle(siteName == null ? "" : siteName.toUpperCase(Locale.ROOT),
                "A work of the ancient builders", 0.4f, 3.2f);
    }

    /**
     * Boss arrival: a low-angle hero shot of the challenger cut to a wide, with
     * its taunt spoken in the captions. The MCSM "the villain has entered"
     * beat.
     */
    public void playBossIntro(String name, String taunt, Vector3f subject) {
        if (active) { queueScene(() -> playBossIntro(name, taunt, subject)); return; }
        if (!canDirectScene() || subject == null) return;
        Vector3f focus = new Vector3f(subject);
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.lowAngle(subject, 30, 4.5f, 1.8f));
        seq.add(CameraShot.wide(subject, 60, 110, 8.0f, 3.5f, 2.4f).tracking(() -> new Vector3f(focus)));
        // Cut to the hero for their answer — the MCSM shot/reverse-shot beat.
        seq.add(CameraShot.pushIn(heroPoint(), 200, 3.0f, 1.6f, 1.5f, 1.4f).tracking(this::heroPoint));
        CinematicScript lines = new CinematicScript();
        if (taunt != null && !taunt.isEmpty()) lines.at(0.8f, name, taunt);
        lines.at(4.2f, "You", "Not today.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.8f);
        showTitle(name == null ? "" : name, "", 0.2f, 3.0f);
    }

    /** Boss defeat: a victory wide cut to the hero, with a closing line. */
    public void playBossDefeat(String name, String line, Vector3f subject) {
        if (active) { queueScene(() -> playBossDefeat(name, line, subject)); return; }
        if (!canDirectScene() || subject == null) return;
        Vector3f focus = new Vector3f(subject);
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.wide(subject, 140, 205, 7.5f, 3.0f, 2.4f).tracking(() -> new Vector3f(focus)));
        seq.add(CameraShot.closeUp(heroPoint(), 250, 2.2f, 0.9f, 1.4f).tracking(this::heroPoint));
        CinematicScript lines = new CinematicScript();
        if (line != null && !line.isEmpty()) lines.at(0.6f, name, line);
        lines.at(3.2f, "Narrator", "The Overworld is a little safer tonight.");
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.6f);
        showTitle("VICTORY", name == null ? "" : name, 0.3f, 2.4f);
    }

    /**
     * Dungeon arrival: a slow push through the entrance to a wide reveal of the
     * room, with a narrated line. Used when the player first enters a dungeon.
     */
    public void playDungeonArrival(String name, String line, Vector3f subject) {
        if (active) { queueScene(() -> playDungeonArrival(name, line, subject)); return; }
        if (!canDirectScene() || subject == null) return;
        Vector3f eye = heroPoint();
        Vector3f dir = new Vector3f(subject).sub(eye);
        if (dir.lengthSquared() < 1e-4f) dir.set(1, 0, 0);
        dir.normalize();
        CameraShot push = CameraShot.dolly(
                new Vector3f(eye).fma(-1.5f, dir), new Vector3f(subject), subject, 70f, 3.2f);
        push.ease = CameraShot.Ease.SMOOTH;
        List<CameraShot> seq = new ArrayList<>();
        seq.add(push);
        seq.add(CameraShot.orbit(subject, 9f, 3.5f, -20f, 40f, 3.0f));
        CinematicScript lines = new CinematicScript();
        if (line != null && !line.isEmpty()) lines.at(0.7f, "Narrator", line);
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.65f);
        showTitle(name == null ? "" : name, "", 0.3f, 2.8f);
    }

    /**
     * A scripted conversation: a two-shot of the player and the subject, cut to
     * a close-up on the speaker, then back to the player for the reply. Use
     * {@link CinematicScript#conversation} / {@link CinematicScript#exchange}
     * to build the lines; each one is voiced in its speaker's profile.
     */
    public void playBuilderDialogue(String titleText, Vector3f subject, CinematicScript lines) {
        if (active) { queueScene(() -> playBuilderDialogue(titleText, subject, lines)); return; }
        if (!canDirectScene() || subject == null) return;
        Vector3f focus = new Vector3f(subject);
        List<CameraShot> seq = new ArrayList<>();
        seq.add(CameraShot.twoShot(heroPoint(), subject, 3.0f));
        seq.add(CameraShot.closeUp(
                new Vector3f(subject.x, subject.y + 1.4f, subject.z), 250, 2.4f, 0.6f, 2.4f)
                .tracking(() -> new Vector3f(focus)));
        seq.add(CameraShot.medium(heroPoint(), 200, 3.0f, 1.2f, 1.8f).tracking(this::heroPoint));
        beginScene(seq, true, 0f, lines);
        letterbox = Math.max(letterbox, 0.7f);
        if (titleText != null && !titleText.isEmpty()) {
            showTitle(titleText, "", 0.3f, 2.6f);
        }
    }

    /** True when a camera-owning scene is allowed to take over right now. */
    private boolean canDirectScene() {
        return !ctx.player.isDead() && !ctx.craftingCutsceneActive
                && !ctx.furnaceCutsceneActive && !ctx.tvCutsceneActive
                && !ctx.pauseMenuOpen && !ctx.inventoryOpen && !ctx.commandMode;
    }

    private void endScene() {
        if (controlsCamera) {
            // Hand control back exactly where the player left it
            ctx.yaw = savedYaw;
            ctx.pitch = savedPitch;
            ctx.playerYaw = savedPlayerYaw;
            introEndTime = org.lwjgl.glfw.GLFW.glfwGetTime();
        }
        active = false;
        controlsCamera = false;
        skipHintVisible = false;
        shots.clear();
        camFov = com.voxel.camera.CameraController.DEFAULT_FOV_DEGREES;
        camRoll = 0f;
        // Clear any residual red tint left by warning scenes.
        fadeRed = 0f;
    }

    /**
     * Abort the current scene as if it had finished (camera + control are
     * restored). Used by the ESC-to-skip handler; safe to call when idle.
     */
    public void skip() {
        abort();
    }

    /** Abort any scene immediately (pause menu, death, ...) and restore control. */
    public void abort() {
        if (active) endScene();
        titleVisible = false;
        textAlpha = 0f;
        pendingScenes.clear();
        choice = null;
        choiceCallback = null;
        choiceRemaining = 0f;
        pendingChoicePick = -2;
        qte = null;
        qteCallback = null;
        qteRemaining = 0f;
        pendingQteResult = -1;
    }

    // ── MCSM decisions (timed choices) ───────────────────────────────────────

    public boolean hasActiveChoice() { return choice != null; }

    public CinematicChoice getActiveChoice() { return choice; }

    public float getChoiceRemaining() { return choiceRemaining; }

    /** Remaining fraction of the choice timer, 0..1. */
    public float getChoiceProgress() {
        CinematicChoice current = choice;
        if (current == null || current.seconds() <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, choiceRemaining / current.seconds()));
    }

    /**
     * Show a timed decision. While a camera scene is playing the choice is
     * queued until the scene hands control back, so an intro and its question
     * never overlap.
     */
    public void playChoice(CinematicChoice next, java.util.function.Consumer<Integer> onPick) {
        if (next == null || next.optionCount() == 0) return;
        if (choice != null) return; // one decision at a time
        if (active) {
            queueScene(() -> playChoice(next, onPick));
            return;
        }
        if (ctx.player == null || ctx.player.isDead()) return;
        choice = next;
        choiceRemaining = next.seconds();
        pendingChoicePick = -2;
        choiceCallback = onPick;
        letterbox = Math.max(letterbox, 0.45f);
        barsMoment(next.seconds());
        com.voxel.ai.speech.CharacterVoice.speakScripted(next.speaker(), next.prompt());
    }

    /** Input entry point (keyboard on the logic thread, mouse on the GL thread). */
    public void requestChoice(int index) {
        if (choice != null) pendingChoicePick = index;
    }

    private void tickChoiceAndQte(float dt) {
        CinematicChoice current = choice;
        if (current != null) {
            int pick = pendingChoicePick;
            pendingChoicePick = -2;
            if (pick != -2) {
                resolveChoice(pick);
            } else {
                choiceRemaining -= dt;
                if (choiceRemaining <= 0f) resolveChoice(current.defaultIndex());
            }
        }

        QuickTimeEvent event = qte;
        if (event != null) {
            int result = pendingQteResult;
            pendingQteResult = -1;
            if (result != -1) {
                resolveQte(result == 1);
            } else {
                qteRemaining -= dt;
                if (qteRemaining <= 0f) resolveQte(ctx.qteAssist);
            }
        }
    }

    private void resolveChoice(int index) {
        CinematicChoice current = choice;
        if (current == null) return;
        int picked = Math.max(0, Math.min(current.optionCount() - 1, index));
        CinematicChoice.Option option = current.optionAt(picked);
        String flag = "choice." + current.id();
        ctx.story.set(flag, option.label);
        ctx.story.recordChoice(current.prompt(), option.label);
        if (option.response != null && !option.response.isEmpty()) {
            com.voxel.ai.speech.CharacterVoice.speakScripted(current.speaker(), option.response);
        }
        ctx.setStatus("You chose: " + option.label);
        java.util.function.Consumer<Integer> callback = choiceCallback;
        choice = null;
        choiceCallback = null;
        choiceRemaining = 0f;
        pendingChoicePick = -2;
        if (callback != null) {
            try {
                callback.accept(picked);
            } catch (RuntimeException error) {
                System.err.println("CinematicSystem: choice callback failed: " + error);
            }
        }
    }

    // ── MCSM quick-time events ───────────────────────────────────────────────

    public boolean hasActiveQte() { return qte != null; }

    public QuickTimeEvent getActiveQte() { return qte; }

    public float getQteRemaining() { return qteRemaining; }

    public float getQteProgress() {
        QuickTimeEvent current = qte;
        if (current == null || current.seconds() <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, qteRemaining / current.seconds()));
    }

    /** Start a timed prompt. Misses are handled by the callback, never by stalling. */
    public void playQte(QuickTimeEvent event) {
        playQte(event, null);
    }

    public void playQte(QuickTimeEvent event, java.util.function.Consumer<Boolean> onResult) {
        if (event == null || qte != null) return;
        if (ctx.player == null || ctx.player.isDead()) return;
        qte = event;
        qteRemaining = event.seconds();
        pendingQteResult = -1;
        qteCallback = onResult;
        barsMoment(Math.min(2.0f, event.seconds()));
        if (event.prompt() != null && !event.prompt().isEmpty()) {
            com.voxel.ai.speech.CharacterVoice.speakScripted(event.speaker(), event.prompt());
        }
    }

    /** Input entry point. */
    public void requestQte(boolean success) {
        if (qte != null) pendingQteResult = success ? 1 : 0;
    }

    private void resolveQte(boolean success) {
        QuickTimeEvent current = qte;
        if (current == null) return;
        String line = success ? current.successLine() : current.failLine();
        if (line != null && !line.isEmpty()) {
            com.voxel.ai.speech.CharacterVoice.speakScripted(current.speaker(), line);
        }
        ctx.setStatus(success ? "Nice reactions!" : "You stumble — but keep going.");
        java.util.function.Consumer<Boolean> callback = qteCallback;
        qte = null;
        qteCallback = null;
        qteRemaining = 0f;
        pendingQteResult = -1;
        if (callback != null) {
            try {
                callback.accept(success);
            } catch (RuntimeException error) {
                System.err.println("CinematicSystem: QTE callback failed: " + error);
            }
        }
    }

    private void startFade(float from, float to, float dur) {
        fadeFrom = from;
        fadeTo = to;
        fadeTimer = 0f;
        fadeDuration = Math.max(0.01f, dur);
        fadeAlpha = from;
        fading = true;
    }
    private boolean pendingFadeBack = false;
    private float fadeBackDuration = 0.5f;

    private void fadeToBlackThenBack(float holdAndReturnDur) {
        pendingFadeBack = true;
        fadeBackDuration = holdAndReturnDur;
    }

    // Title card scheduling
    private boolean titleVisible = false;
    private double titleShowAt, titleHideAt;

    private void showTitle(String mainText, String subText, double delaySec, double visibleSec) {
        title = mainText;
        subtitle = subText;
        double now = org.lwjgl.glfw.GLFW.glfwGetTime();
        titleShowAt = now + delaySec;
        titleHideAt = titleShowAt + visibleSec;
        titleVisible = true;
    }

    private float computeTitleAlpha(double now) {
        if (now < titleShowAt || now > titleHideAt) return 0f;
        float a = 1.0f;
        if (now - titleShowAt < 0.6) a = (float) ((now - titleShowAt) / 0.6);          // fade in
        else if (titleHideAt - now < 0.8) a = (float) ((titleHideAt - now) / 0.8);     // fade out
        return Math.max(0f, Math.min(1f, a));
    }

    /** Unit forward vector from yaw/pitch (matches Main.getLookDirection). */
    private static Vector3f lookForward(float yawDeg, float pitchDeg) {
        return com.voxel.camera.CameraController.directionFromAngles(yawDeg, pitchDeg);
    }

    /** Shortest-arc angle interpolation (degrees) — never spins the long way. */
    public static float lerpAngle(float from, float to, float t) {
        float diff = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + diff * t;
    }
}
