package com.voxel.ui;

import com.voxel.Main;
import com.voxel.camera.CameraController;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.Entity;
import com.voxel.game.GameContext;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * MCSM-style combat overlay.
 *
 * <p>Combat mode (X) already turns the game into a third-person action fight:
 * attacks are charged, combos multiply damage, Tab locks onto the nearest mob
 * and Left-Alt rolls with i-frames. None of that was visible before — the only
 * feedback was a status line. This layer draws the state continuously and only
 * while combat mode is actually being played:
 *
 * <ul>
 *   <li>corner framing brackets and a COMBAT MODE badge</li>
 *   <li>a lock-on reticle with the target's name, distance and health bar</li>
 *   <li>a charge meter that fills while LMB is held</li>
 *   <li>the current combo multiplier plus its decay bar</li>
 *   <li>the dodge-roll cooldown</li>
 *   <li>floating damage numbers above struck mobs</li>
 * </ul>
 *
 * <p>All elements are hidden whenever combat is not playable (menus, inventory,
 * map, cinematics, loading gates), so the normal HUD is untouched.
 */
public class CombatHud {
    /** Charge percentage reaches 1.0 here; the game keeps charging to 1.5s. */
    private static final float CHARGE_FULL_SECONDS = 1.2f;
    private static final float DODGE_COOLDOWN_SECONDS = 1.0f;
    private static final float COMBO_WINDOW_SECONDS = 0.8f;
    private static final int DAMAGE_POOL = 16;

    private static final Vector4f AMBER = new Vector4f(1f, 0.80f, 0.30f, 1f);
    private static final Vector4f CREAM = new Vector4f(0.98f, 0.95f, 0.86f, 1f);
    private static final Vector4f RED = new Vector4f(0.86f, 0.18f, 0.16f, 1f);
    private static final Vector4f DIM = UiPalette.srgb(0.60f, 0.55f, 0.47f, 1f);

    private final GameContext ctx;
    private final Main main;
    private final CameraController camera;
    private final UILayer layer = new UILayer();
    private final int fontTextureId;

    // ── Combat framing ──
    /** Eight thin quads make the four screen-corner brackets. */
    private final UILayer.UIElement[] cornerBrackets = new UILayer.UIElement[8];
    private UILayer.UITextElement modeBadge;
    private UILayer.UITextElement lockHint;

    // ── Lock-on target ──
    /** Eight thin quads: four arms per corner bracket around the target. */
    private final UILayer.UIElement[] reticle = new UILayer.UIElement[8];
    private UILayer.UITextElement targetName;
    private UILayer.UITextElement targetDistance;
    private UILayer.UIElement targetHealthBg;
    private UILayer.UIElement targetHealthFill;

    // ── Charge attack ──
    private UILayer.UIElement chargeBg;
    private UILayer.UIElement chargeFill;
    private UILayer.UITextElement chargeLabel;
    private UILayer.UITextElement chargeValue;

    // ── Combo ──
    private UILayer.UITextElement comboText;
    private UILayer.UIElement comboBarBg;
    private UILayer.UIElement comboBarFill;

    // ── Dodge roll ──
    private UILayer.UITextElement dodgeText;
    private UILayer.UIElement dodgeBarBg;
    private UILayer.UIElement dodgeBarFill;

    // ── Floating damage numbers ──
    private final UILayer.UITextElement[] damageTexts = new UILayer.UITextElement[DAMAGE_POOL];

    // ── Scratch state (no per-frame allocation) ──
    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private final Vector3f cameraPos = new Vector3f();
    private final Vector3f lookDir = new Vector3f();
    private final Vector3f lookTarget = new Vector3f();
    private static final Vector3f WORLD_UP = new Vector3f(0, 1, 0);
    private final Vector2f screenA = new Vector2f();
    private final Vector2f screenB = new Vector2f();
    private boolean lastActive = false;
    private int labelIndex = -1;
    private String labelCache = "Enemy";

    public CombatHud(GameContext ctx, Main main, CameraController camera, int fontTextureId) {
        this.ctx = ctx;
        this.main = main;
        this.camera = camera;
        this.fontTextureId = fontTextureId;
        build();
    }

    /** The overlay layer; added after the HUD's dynamic layer so it draws on top. */
    public UILayer layer() { return layer; }

    // ── Construction ─────────────────────────────────────────────────────────

    private void build() {
        for (int i = 0; i < cornerBrackets.length; i++) {
            UILayer.UIElement arm = new UILayer.UIElement(
                new Vector2f(0, 0), new Vector2f(0, 0),
                new Vector4f(AMBER.x, AMBER.y, AMBER.z, 0.55f));
            arm.visible = false;
            cornerBrackets[i] = arm;
            layer.addElement(arm);
        }

        modeBadge = text("COMBAT MODE", 1.7f, AMBER);
        lockHint = text("", 1.2f, DIM);

        for (int i = 0; i < reticle.length; i++) {
            UILayer.UIElement arm = new UILayer.UIElement(
                new Vector2f(0, 0), new Vector2f(0, 0),
                new Vector4f(1f, 0.86f, 0.42f, 0.95f));
            arm.visible = false;
            reticle[i] = arm;
            layer.addElement(arm);
        }

        targetName = text("", 1.5f, CREAM);
        targetDistance = text("", 1.15f, DIM);

        targetHealthBg = quad(new Vector4f(0.05f, 0.05f, 0.06f, 0.82f));
        targetHealthFill = quad(RED);

        chargeBg = quad(new Vector4f(0.04f, 0.04f, 0.05f, 0.78f));
        chargeFill = quad(new Vector4f(AMBER));
        chargeLabel = text("CHARGE", 1.25f, CREAM);
        chargeValue = text("", 1.25f, CREAM);

        comboText = text("", 1.9f, AMBER);
        comboBarBg = quad(new Vector4f(0.04f, 0.04f, 0.05f, 0.72f));
        comboBarFill = quad(new Vector4f(1f, 0.62f, 0.18f, 0.95f));

        dodgeBarBg = quad(new Vector4f(0.04f, 0.04f, 0.05f, 0.72f));
        dodgeBarFill = quad(UiPalette.gold(0.95f));
        dodgeText = text("", 1.2f, CREAM);

        for (int i = 0; i < DAMAGE_POOL; i++) {
            UILayer.UITextElement dmg = text("", 2.0f, new Vector4f(1f, 0.86f, 0.36f, 1f));
            dmg.outlined = true;
            dmg.outlineColor = new Vector4f(0.12f, 0.05f, 0.02f, 0.9f);
            damageTexts[i] = dmg;
        }
    }

    private UILayer.UIElement quad(Vector4f color) {
        UILayer.UIElement el = new UILayer.UIElement(new Vector2f(0, 0), new Vector2f(0, 0), color);
        el.visible = false;
        layer.addElement(el);
        return el;
    }

    private UILayer.UITextElement text(String value, float scale, Vector4f color) {
        UILayer.UITextElement el = new UILayer.UITextElement(
            new Vector2f(0, 0), value, scale, new Vector4f(color), fontTextureId);
        el.shadow = true;
        el.visible = false;
        layer.addElement(el);
        return el;
    }

    // ── Per-frame update ─────────────────────────────────────────────────────

    public void update(double time) {
        boolean active = isCombatPlayable();
        if (!active) {
            if (lastActive) hideAll();
            lastActive = false;
            return;
        }
        lastActive = true;

        // Camera basis for world → screen projection, matching the cursor-ray
        // and billboard passes so the reticle lands exactly on the mob.
        cameraPos.set(camera.getActiveCameraPosition());
        lookDir.set(main.getLookDirection());
        float fovRad = 2f * (float) Math.atan(camera.getTanHalfFov());
        float aspect = (float) main.width / (float) main.height;
        projection.identity().perspective(fovRad, aspect, 0.1f, 2048f);
        lookTarget.set(cameraPos).add(lookDir);
        view.identity().lookAt(cameraPos, lookTarget, WORLD_UP);
        viewProj.set(projection).mul(view);

        drawFraming();
        drawTarget();
        drawCharge(time);
        drawCombo();
        drawDodge(time);
        drawDamageNumbers();
    }

    /** Combat mode must be on and the player must actually be controlling the game. */
    private boolean isCombatPlayable() {
        if (!main.combatMode) return false;
        if (ctx == null || ctx.player == null || ctx.player.isDead()) return false;
        if (ctx.menuScreen != GameContext.MenuScreen.IN_GAME) return false;
        if (ctx.initializing || ctx.spawnLoading || ctx.teleportLoading) return false;
        if (main.inventoryOpen || main.commandMode) return false;
        if (ctx.pauseMenuOpen || ctx.mapOpen) return false;
        if (ctx.cinematic != null && ctx.cinematic.cameraActive()) return false;
        return true;
    }

    private void drawFraming() {
        float arm = 34f, thick = 3f, m = 16f;
        float w = main.width, h = main.height;
        // Top-left, top-right, bottom-left, bottom-right — horizontal then
        // vertical arm for each corner.
        setCorner(0, m, m, arm, thick, arm, thick, 1, 1);
        setCorner(2, w - m - arm, m, arm, thick, arm, thick, 1, 1);
        setCorner(4, m, h - m - thick, arm, thick, arm, thick, 1, -1);
        setCorner(6, w - m - arm, h - m - thick, arm, thick, arm, thick, 1, -1);

        modeBadge.pos.set(w - 176f, 18f);
        modeBadge.visible = true;

        Entity locked = lockedEnemy();
        lockHint.text = locked != null ? "TAB  release lock" : "TAB  lock nearest";
        lockHint.pos.set(w - 176f, 18f + 18f * modeBadge.scale);
        lockHint.color.w = 0.9f;
        lockHint.visible = true;
    }

    private void setCorner(int index, float x, float y, float armW, float armH,
                           float vertW, float vertH, int dirX, int dirY) {
        UILayer.UIElement hArm = cornerBrackets[index];
        hArm.pos.set(x, y);
        hArm.size.set(armW, armH);
        hArm.visible = true;
        UILayer.UIElement vArm = cornerBrackets[index + 1];
        vArm.pos.set(dirX > 0 ? x : x + armW - vertW, dirY > 0 ? y : y + armH - vertH);
        vArm.size.set(vertW, vertH);
        vArm.visible = true;
    }

    private void drawTarget() {
        Entity locked = lockedEnemy();
        boolean on = locked != null;
        if (on) {
            // Aim at the mob's chest, then name/health above, distance below.
            if (!project(locked.getPosX(), locked.getPosY() + 1.05f, locked.getPosZ(), screenA)
                    || screenA.x < -80 || screenA.x > main.width + 80
                    || screenA.y < -80 || screenA.y > main.height + 80) {
                on = false;
            }
        }
        // Hide every reticle arm first; the bracket only draws when it projects.
        for (UILayer.UIElement arm : reticle) arm.visible = false;
        targetName.visible = false;
        targetDistance.visible = false;
        targetHealthBg.visible = false;
        targetHealthFill.visible = false;
        if (!on) return;

        float cx = screenA.x, cy = screenA.y;
        float half = 30f, arm = 13f, thick = 3f;
        // Top-left
        reticle[0].pos.set(cx - half, cy - half); reticle[0].size.set(arm, thick);
        reticle[1].pos.set(cx - half, cy - half); reticle[1].size.set(thick, arm);
        // Top-right
        reticle[2].pos.set(cx + half - arm, cy - half); reticle[2].size.set(arm, thick);
        reticle[3].pos.set(cx + half - thick, cy - half); reticle[3].size.set(thick, arm);
        // Bottom-left
        reticle[4].pos.set(cx - half, cy + half - thick); reticle[4].size.set(arm, thick);
        reticle[5].pos.set(cx - half, cy + half - arm); reticle[5].size.set(thick, arm);
        // Bottom-right
        reticle[6].pos.set(cx + half - arm, cy + half - thick); reticle[6].size.set(arm, thick);
        reticle[7].pos.set(cx + half - thick, cy + half - arm); reticle[7].size.set(thick, arm);
        for (UILayer.UIElement armEl : reticle) armEl.visible = true;

        EnemyEntity enemy = (EnemyEntity) locked;
        if (ctx.lockedEntityIndex != labelIndex) {
            labelIndex = ctx.lockedEntityIndex;
            labelCache = enemyLabel(enemy);
        }
        String label = labelCache;
        float health = Math.max(0f, Math.min(1f, enemy.getHealth() / Math.max(0.001f, enemy.getMaxHealth())));
        float barW = 132f, barH = 7f;
        float barX = cx - barW / 2f, barY = cy - half - 26f;

        targetName.text = label;
        targetName.pos.set(cx - label.length() * 8f * targetName.scale / 2f, barY - 20f);
        targetName.visible = true;

        targetHealthBg.pos.set(barX, barY);
        targetHealthBg.size.set(barW, barH);
        targetHealthBg.visible = true;
        targetHealthFill.pos.set(barX + 1f, barY + 1f);
        targetHealthFill.size.set((barW - 2f) * health, barH - 2f);
        targetHealthFill.visible = true;

        float dist = cameraPos.distance(locked.getPosition());
        targetDistance.text = String.format(java.util.Locale.ROOT, "%.1f m", dist);
        targetDistance.pos.set(cx - targetDistance.text.length() * 8f * targetDistance.scale / 2f,
                cy + half + 8f);
        targetDistance.visible = true;
    }

    private void drawCharge(double time) {
        boolean charging = ctx.isCharging;
        // While charging the bar tracks the hold; right after a swing it flashes
        // the hit's damage relative to a full-power blow.
        float percent = charging
                ? chargePercent(ctx.chargeTime)
                : Math.min(1f, ctx.lastAttackDamage / 12f);
        boolean show = charging || (time - main.lastAttackTime < 0.35);

        chargeBg.visible = show;
        chargeFill.visible = show;
        chargeLabel.visible = show;
        chargeValue.visible = show;
        if (!show) return;

        float barW = 232f, barH = 9f;
        float x = main.width / 2f - barW / 2f;
        float y = main.height - 92f;

        chargeBg.pos.set(x, y);
        chargeBg.size.set(barW, barH);
        chargeFill.pos.set(x + 1f, y + 1f);
        chargeFill.size.set((barW - 2f) * percent, barH - 2f);
        // Amber while charging, hot orange once the swing is at full power.
        if (percent >= 0.999f) {
            chargeFill.color.set(1f, 0.42f, 0.16f, 0.98f);
        } else {
            chargeFill.color.set(0.95f, 0.78f, 0.28f, 0.92f);
        }

        chargeLabel.text = charging ? "CHARGE" : "SWING";
        chargeLabel.pos.set(x, y - 20f);
        chargeValue.text = charging ? Math.round(percent * 100f) + "%" : "";
        chargeValue.pos.set(x + barW - chargeValue.text.length() * 8f * chargeValue.scale, y - 20f);
    }

    private void drawCombo() {
        boolean on = ctx.comboCount > 0 && ctx.comboTimer > 0f;
        comboText.visible = on;
        comboBarBg.visible = on;
        comboBarFill.visible = on;
        if (!on) return;

        float multiplier = ctx.comboCount == 2 ? 2.5f : 1.5f;
        comboText.text = String.format(java.util.Locale.ROOT, "x%.1f  COMBO", multiplier);
        comboText.pos.set(main.width / 2f - comboText.text.length() * 8f * comboText.scale / 2f,
                main.height - 142f);

        float barW = 120f, barH = 4f;
        float x = main.width / 2f - barW / 2f;
        float y = main.height - 126f;
        comboBarBg.pos.set(x, y);
        comboBarBg.size.set(barW, barH);
        comboBarFill.pos.set(x, y);
        comboBarFill.size.set(barW * Math.max(0f, Math.min(1f, ctx.comboTimer / COMBO_WINDOW_SECONDS)), barH);
    }

    private void drawDodge(double time) {
        double elapsed = time - main.lastRollTime;
        boolean ready = elapsed >= DODGE_COOLDOWN_SECONDS;
        dodgeText.visible = true;
        dodgeBarBg.visible = true;
        dodgeBarFill.visible = true;

        float barW = 116f, barH = 6f;
        float x = main.width - 16f - barW;
        float y = main.height - 34f;
        dodgeBarBg.pos.set(x, y);
        dodgeBarBg.size.set(barW, barH);
        dodgeBarFill.pos.set(x, y);
        float t = ready ? 1f : (float) Math.max(0, Math.min(1, elapsed / DODGE_COOLDOWN_SECONDS));
        dodgeBarFill.size.set(barW * t, barH);
        if (ready) {
            dodgeBarFill.color.set(UiPalette.gold(0.95f));
            dodgeText.text = "L-ALT  DODGE";
            dodgeText.color.set(CREAM.x, CREAM.y, CREAM.z, 0.95f);
        } else {
            dodgeBarFill.color.set(UiPalette.muted(0.9f));
            dodgeText.text = String.format(java.util.Locale.ROOT, "DODGE %.1fs",
                    Math.max(0.0, DODGE_COOLDOWN_SECONDS - elapsed));
            dodgeText.color.set(DIM.x, DIM.y, DIM.z, 0.95f);
        }
        dodgeText.pos.set(x + barW - dodgeText.text.length() * 8f * dodgeText.scale, y - 18f);
    }

    private void drawDamageNumbers() {
        int shown = 0;
        // Copy-on-write list: safe to iterate from the render thread while the
        // logic thread adds hits and expires old numbers.
        for (GameContext.DamageNumber number : ctx.damageNumbers) {
            if (shown >= DAMAGE_POOL) break;
            UILayer.UITextElement el = damageTexts[shown];
            if (!project(number.worldX, number.worldY, number.worldZ, screenB)) {
                el.visible = false;
                continue;
            }
            String value = number.critical
                    ? "CRIT " + String.format(java.util.Locale.ROOT, "%.0f", number.damage)
                    : String.format(java.util.Locale.ROOT, "%.0f", number.damage);
            el.text = value;
            el.scale = number.critical ? 1.45f : 1.0f;
            el.pos.set(screenB.x - value.length() * 8f * el.scale / 2f, screenB.y);
            // Fade the last third of the number's life; crits read gold.
            float alpha = number.getAlpha();
            if (number.critical) {
                el.color.set(1f, 0.78f, 0.25f, Math.min(1f, alpha * 1.4f));
            } else {
                el.color.set(1f, number.damage >= 8f ? 0.55f : 0.82f,
                        number.damage >= 8f ? 0.22f : 0.34f, Math.min(1f, alpha * 1.4f));
            }
            el.visible = true;
            shown++;
        }
        for (int i = shown; i < DAMAGE_POOL; i++) damageTexts[i].visible = false;
    }

    private void hideAll() {
        for (UILayer.UIElement arm : cornerBrackets) arm.visible = false;
        for (UILayer.UIElement arm : reticle) arm.visible = false;
        for (UILayer.UITextElement dmg : damageTexts) dmg.visible = false;
        for (UILayer.UIElement el : new UILayer.UIElement[]{
                modeBadge, lockHint, targetName, targetDistance,
                targetHealthBg, targetHealthFill,
                chargeBg, chargeFill, chargeLabel, chargeValue,
                comboText, comboBarBg, comboBarFill,
                dodgeText, dodgeBarBg, dodgeBarFill}) {
            el.visible = false;
        }
    }

    private Entity lockedEnemy() {
        if (ctx.entityManager == null || ctx.lockedEntityIndex < 0) return null;
        Entity e = ctx.entityManager.getEntity(ctx.lockedEntityIndex);
        if (!(e instanceof EnemyEntity)) return null;
        EnemyEntity enemy = (EnemyEntity) e;
        if (enemy.isDead() || enemy.dimension != main.activeDimension) return null;
        return enemy;
    }

    private boolean project(float wx, float wy, float wz, Vector2f out) {
        clip.set(wx, wy, wz, 1f);
        viewProj.transform(clip);
        if (clip.w <= 0.01f) return false;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        out.set((ndcX * 0.5f + 0.5f) * main.width,
                (1f - (ndcY * 0.5f + 0.5f)) * main.height);
        return true;
    }

    // ── Pure helpers (unit-tested) ────────────────────────────────────────────

    /** Charge fraction shown by the meter: 1.0 at the full-power swing. */
    static float chargePercent(float chargeSeconds) {
        return Math.max(0f, Math.min(1f, chargeSeconds / CHARGE_FULL_SECONDS));
    }

    /** Display name for a locked mob ("Arch-Illager", "Zombie", ...). */
    static String enemyLabel(EnemyEntity enemy) {
        if (enemy == null) return "Enemy";
        String simple = enemy.getClass().getSimpleName();
        if (simple.endsWith("Entity")) simple = simple.substring(0, simple.length() - 6);
        switch (simple) {
            case "ArchIllager": return "Arch-Illager";
            case "AechorPlant": return "Aechor Plant";
            case "EnderDragon": return "Ender Dragon";
            case "EndCrystal": return "End Crystal";
            case "": return "Enemy";
            default: return simple;
        }
    }
}
