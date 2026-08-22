package com.voxel.cinematic;

import org.joml.Vector3f;

import com.voxel.game.GameContext;
import com.voxel.world.DimensionType;

/**
 * Movie-mode director on top of the existing cutscene plumbing.
 *
 * Plays scripted camera moments (spawn intro orbit, nightfall drift, portal
 * travel, first Nether reveal, low-health warning, level-up flourish) and
 * drives the shared polish overlays (letterbox bars, fade-to-black, title
 * cards).
 *
 * Pure overlay events (teleport flash, death/respawn fades) never touch the
 * camera; scene events take over yaw/pitch + camera position and restore the
 * player's original orientation when they end.
 *
 * tick(dt) runs on the logic thread. The render thread reads camPos / fade /
 * letterbox / title state through CameraController and HudUI.
 */
public class CinematicSystem {
    private final GameContext ctx;

    // --- Active scene ---
    public boolean active = false;
    public float timer = 0f;
    public float duration = 0f;
    private boolean controlsCamera = false;

    // Linear camera path (world coords)
    private final Vector3f startPos = new Vector3f();
    private final Vector3f endPos = new Vector3f();
    // Optional orbit around a center (overrides path when radius > 0)
    private boolean orbit = false;
    private final Vector3f orbitCenter = new Vector3f();
    private float orbitRadius, orbitStartAngle, orbitEndAngle, orbitHeight;
    private float startYaw, targetYaw, startPitch, targetPitch;
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
    private boolean netherRevealed = false;
    private boolean lowHealthWarned = false;
    private boolean levelUpSeen = false;

    // --- Scene queue (so a flourish doesn't stomp a reveal mid-play) ---
    private final java.util.ArrayDeque<Runnable> pendingScenes = new java.util.ArrayDeque<>();

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
    private final Vector3f camPos = new Vector3f();

    // ── Scenes ────────────────────────────────────────────────────────────────

    /** One-time spawn intro: fade in from black + slow hero orbit + title card. */
    public void playIntro() {
        if (introPlayed || active) return;
        introPlayed = true;
        beginScene(7.0f, true);
        orbit = true;
        Vector3f p = ctx.player.getPosition();
        orbitCenter.set(p.x, p.y, p.z);
        orbitRadius = 6.5f;
        orbitHeight = 2.2f;
        orbitStartAngle = 0.0f;
        orbitEndAngle = (float) (Math.PI * 0.75);
        startFade(1.0f, 0.0f, 1.6f);
        showTitle("A NEW WORLD", "Your story begins", 1.8f, 4.4f);
    }

    /** Dusk moment: brief letterboxed glance at the sky as night falls. */
    public void playNightfall() {
        if (active) return;
        // Never yank the camera out of a UI screen, a manual cutscene, or
        // death — nightfall recurs every cycle, missing one beat is fine.
        if (ctx.inventoryOpen || ctx.commandMode || ctx.player.isDead()
                || ctx.craftingCutsceneActive || ctx.furnaceCutsceneActive
                || ctx.tvCutsceneActive || ctx.pauseMenuOpen) return;
        beginScene(3.5f, true);
        orbit = false;
        Vector3f eye = ctx.player.getPosition();
        startPos.set(eye.x, eye.y + 1.6f, eye.z);
        endPos.set(eye.x, eye.y + 2.4f, eye.z);
        startYaw = ctx.yaw;
        startPitch = ctx.pitch;
        targetYaw = ctx.yaw + 25.0f;
        targetPitch = 35.0f;
        showTitle("NIGHT FALLS", "Survive until dawn", 1.0f, 2.2f);
    }

    /**
     * Portal travel scene: rapid zoom forward through a fade, a brief black
     * hold with a swirling title, then fade back in at the destination.
     * Plays on every portal hop (overlay-only; the world switch happens in
     * GameContext before this is called).
     */
    public void playPortalTravel() {
        if (active) { queueScene(this::playPortalTravel); return; }
        beginScene(2.6f, true);
        orbit = false;
        Vector3f p = ctx.player.getPosition();
        // Push the camera forward a couple of blocks along the look direction,
        // then snap it back — the classic "fly through the portal" gesture.
        Vector3f fwd = lookForward(ctx.yaw, ctx.pitch);
        startPos.set(p.x + fwd.x * 0.3f, p.y + 1.6f + fwd.y * 0.3f, p.z + fwd.z * 0.3f);
        endPos.set(p.x + fwd.x * 2.4f, p.y + 1.6f + fwd.y * 2.4f, p.z + fwd.z * 2.4f);
        startYaw = ctx.yaw;
        startPitch = ctx.pitch;
        targetYaw = ctx.yaw;
        targetPitch = ctx.pitch;
        startFade(fadeAlpha, 1.0f, 0.55f);
        fadeToBlackThenBack(0.9f);
        showTitle("THROUGH THE PORTAL", "", 0.5f, 1.6f);
    }

    /**
     * First Nether reveal: a one-time slow pan across the burning realm with a
     * foreboding title. Skipped on every subsequent Nether visit.
     * B5: queues behind an active scene instead of dropping, so on the first
     * Nether entry the portal-travel zoom plays first and the reveal drains
     * after it at the destination.
     */
    public void playFirstNether() {
        if (netherRevealed) return;
        if (active) { queueScene(this::playFirstNether); return; }
        netherRevealed = true;
        beginScene(6.5f, true);
        orbit = true;
        Vector3f p = ctx.player.getPosition();
        orbitCenter.set(p.x, p.y + 1.0f, p.z);
        orbitRadius = 8.0f;
        orbitHeight = 3.0f;
        orbitStartAngle = (float) -Math.PI;
        orbitEndAngle = (float) (-Math.PI + Math.PI * 1.1);
        startFade(0.6f, 0.0f, 1.4f);
        showTitle("THE NETHER", "A world that burns forever", 1.4f, 4.2f);
    }

    /**
     * Low-health warning: a one-time letterboxed overlay with a red vignette
     * and a desperate title when the player first drops into critical health.
     * Overlay-only so it never rips camera control mid-combat.
     */
    public void playLowHealthWarning() {
        if (lowHealthWarned || active) return;
        lowHealthWarned = true;
        beginScene(3.0f, false);
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
        // Only flourish at meaningful milestones, and only the first time we
        // hit one of them so spamming mobs early on doesn't gate the player.
        // The seen-flag is set only when the scene actually starts — otherwise
        // a queued replay (busy mid-scene) would be silently swallowed.
        boolean milestone = (newLevel == 1) || (newLevel % 5 == 0);
        if (!milestone || levelUpSeen) return;
        if (active) { final int lvl = newLevel; queueScene(() -> playLevelUp(lvl)); return; }
        levelUpSeen = true;
        beginScene(2.4f, false); // overlay-only; keep the player in control
        startFade(0.0f, 0.35f, 0.45f);
        fadeToBlackThenBack(1.4f);
        showTitle("LEVEL " + newLevel, "You grow stronger", 0.2f, 1.6f);
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
        netherRevealed = false;
        lowHealthWarned = false;
        levelUpSeen = false;
        introPlayed = false;
        pendingScenes.clear();
    }

    /** Clear only the low-health warning flag (on respawn) so the reveal can
     *  re-trigger on a future near-death. */
    public void resetLowHealthFlag() {
        lowHealthWarned = false;
    }

    // ── Per-tick update ───────────────────────────────────────────────────────

    public void tick(float dt) {
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
            drainQueuedScenes();
            return;
        }
        timer += dt;
        float t = Math.min(1.0f, timer / duration);
        float s = t * t * (3.0f - 2.0f * t); // smoothstep ease-in-out

        if (controlsCamera) {
            if (orbit) {
                float ang = orbitStartAngle + (orbitEndAngle - orbitStartAngle) * s;
                camPos.set(
                    orbitCenter.x + (float) Math.cos(ang) * orbitRadius,
                    orbitCenter.y + orbitHeight,
                    orbitCenter.z + (float) Math.sin(ang) * orbitRadius);
                // Look at the player
                float dx = orbitCenter.x - camPos.x;
                float dy = (orbitCenter.y + 1.2f) - camPos.y;
                float dz = orbitCenter.z - camPos.z;
                float horiz = (float) Math.sqrt(dx * dx + dz * dz);
                ctx.yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
                ctx.pitch = (float) Math.toDegrees(Math.atan2(dy, horiz));
            } else {
                camPos.set(
                    startPos.x + (endPos.x - startPos.x) * s,
                    startPos.y + (endPos.y - startPos.y) * s,
                    startPos.z + (endPos.z - startPos.z) * s);
                ctx.yaw = startYaw + (targetYaw - startYaw) * s;
                ctx.pitch = startPitch + (targetPitch - startPitch) * s;
            }
            // Directed cameras can sweep through hills/walls; slide up out of
            // solid voxels so the scene never renders from inside terrain.
            liftCameraOutOfTerrain();
            ctx.playerYaw = ctx.yaw;
        }

        if (t >= 1.0f) endScene();
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

    private void detectNightfall() {        double wt = ctx.worldTime;
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

    private void beginScene(float durationSec, boolean takeCamera) {
        active = true;
        controlsCamera = takeCamera;
        timer = 0f;
        duration = durationSec;
        orbit = false;
        skipHintVisible = true;
        if (takeCamera) {
            savedYaw = ctx.yaw;
            savedPitch = ctx.pitch;
            savedPlayerYaw = ctx.playerYaw;
            camPos.set(ctx.player.getPosition());
        }
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
        return new Vector3f(
            (float) (Math.cos(Math.toRadians(yawDeg)) * Math.cos(Math.toRadians(pitchDeg))),
            (float) Math.sin(Math.toRadians(pitchDeg)),
            (float) (Math.sin(Math.toRadians(yawDeg)) * Math.cos(Math.toRadians(pitchDeg)))
        ).normalize();
    }
}
