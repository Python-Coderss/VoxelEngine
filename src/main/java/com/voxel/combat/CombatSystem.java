package com.voxel.combat;

import com.voxel.GameLogger;
import com.voxel.Main;
import com.voxel.Player;
import com.voxel.camera.CameraController;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.game.GameContext;
import com.voxel.game.GameContext.CameraMode;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Centralised combat logic: charge attacks, combos, damage numbers, i-frames, dodge rolls,
 * the lock-on / camera-lock helper, and per-tick combat timers.
 *
 * Reads from Main (yaw / playerYaw for facing, combatMode flag, cameraShake, lastAttackTime,
 * lastRollTime, helper accessors) and from GameContext.
 */
public class CombatSystem {
    private static final float COMBO_WINDOW_SECONDS = 0.8f;
    private static final float INVINCIBILITY_FRAMES_SECONDS = 0.5f;
    private static final float CHARGE_MAX_TIME_SECONDS = 1.5f;
    private static final float CHARGE_FULL_TIME_SECONDS = 1.2f;
    private static final float BASE_DAMAGE = 4.0f;
    private static final float CHARGE_DAMAGE_BONUS = 8.0f;
    private static final float NORMAL_ATTACK_COOLDOWN = 0.25f;

    private final GameContext ctx;
    private final Main main;
    private final CameraController camera;

    public CombatSystem(GameContext ctx, Main main, CameraController camera) {
        this.ctx = ctx;
        this.main = main;
        this.camera = camera;
    }

    /** Per-tick combat timer updates: combo window, charge time, i-frames, damage numbers, lock-on camera. */
    public void tickCombat(float dt) {
        // Camera shake decay
        if (main.cameraShake > 0) main.cameraShake -= dt * 5.0f;
        if (ctx.cameraShake > 0) ctx.cameraShake -= dt * 5.0f;

        // Combo window timer
        if (ctx.comboTimer > 0) {
            ctx.comboTimer -= dt;
            if (ctx.comboTimer <= 0) ctx.comboCount = 0;
        }

        // Charge time accumulates while charging
        if (ctx.isCharging) ctx.chargeTime += dt;

        // Invincibility i-frames
        if (ctx.invincible) {
            ctx.iFrameTimer -= dt;
            if (ctx.iFrameTimer <= 0) ctx.invincible = false;
        }

        // Damage numbers float up and expire
        for (int i = ctx.damageNumbers.size() - 1; i >= 0; i--) {
            GameContext.DamageNumber n = ctx.damageNumbers.get(i);
            n.update(dt);
            if (n.isExpired()) ctx.damageNumbers.remove(i);
        }

        // Lock-on camera rotation while in combat mode
        if (main.combatMode && ctx.lockedEntityIndex >= 0) {
            EntityManager em = ctx.entityManager;
            Entity locked = em != null ? em.getEntity(ctx.lockedEntityIndex) : null;
            if (locked instanceof EnemyEntity) {
                EnemyEntity enemy = (EnemyEntity) locked;
                if (!enemy.isDead()) {
                    Vector3f toTarget = new Vector3f(enemy.position).sub(main.player.getPosition());
                    float targetYaw = (float) Math.toDegrees(Math.atan2(toTarget.x, toTarget.z));
                    float diff = ((targetYaw - main.playerYaw) + 180) % 360 - 180;
                    main.playerYaw += diff * Math.min(1.0f, dt * 8.0f);
                    main.yaw = main.playerYaw;
                } else {
                    ctx.lockedEntityIndex = -1;
                }
            } else {
                ctx.lockedEntityIndex = -1;
            }
        }
    }

    /**
     * Roll forward / backward / sideways on WASD-or-forward defaults, applies knockback-style
     * movement, sets i-frames, plays the roll animation. Reads mouse / WASD keys directly via
     * GLFW (must run on the GL/input thread).
     */
    public void tryDodgeRoll(double now) {
        if (now - main.lastRollTime <= 1.0) return;
        // Compute forward / right
        double ry = Math.toRadians(main.yaw);
        float fx = (float) Math.cos(ry), fz = (float) Math.sin(ry);
        float rx = -fz, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl > 0) { rx /= rl; rz /= rl; }

        float rollDx = 0, rollDz = 0;
        if (glfwGetKey(main.window, GLFW_KEY_W) == GLFW_PRESS) { rollDx += fx; rollDz += fz; }
        if (glfwGetKey(main.window, GLFW_KEY_S) == GLFW_PRESS) { rollDx -= fx; rollDz -= fz; }
        if (glfwGetKey(main.window, GLFW_KEY_A) == GLFW_PRESS) { rollDx -= rx; rollDz -= rz; }
        if (glfwGetKey(main.window, GLFW_KEY_D) == GLFW_PRESS) { rollDx += rx; rollDz += rz; }

        float rollLen = (float) Math.sqrt(rollDx * rollDx + rollDz * rollDz);
        if (rollLen > 0.01f) { rollDx /= rollLen; rollDz /= rollLen; }
        else                 { rollDx = fx; rollDz = fz; }

        main.playerEntity.startRoll();
        main.player.move(rollDx * 20, 0.5f, rollDz * 20, 10.0f);
        main.lastRollTime = now;
        ctx.invincible = true;
        ctx.iFrameTimer = INVINCIBILITY_FRAMES_SECONDS;
        main.setStatus("Dodge!");
    }

    /** Begin charging an attack (called when left mouse is first pressed in combat mode). */
    public void beginCharge() {
        ctx.isCharging = true;
        ctx.chargeTime = 0.0f;
        ctx.comboTimer = 0.0f;
    }

    /**
     * Release the charge / fire the attack. Returns true if a charged swing actually fired.
     * If charging and the player releases the mouse OR charge time exceeded, fire.
     */
    public boolean releaseChargeIfReady() {
        if (!ctx.isCharging) return false;
        if (main.leftMouseHeld && ctx.chargeTime < CHARGE_MAX_TIME_SECONDS) return false;

        ctx.isCharging = false;
        float chargePercent = Math.min(1.0f, ctx.chargeTime / CHARGE_FULL_TIME_SECONDS);
        float comboMult;
        switch (ctx.comboCount) {
            case 1: comboMult = 1.5f; break;
            case 2: comboMult = 2.5f; break;
            default: comboMult = 1.0f;
        }
        float damage = (BASE_DAMAGE + chargePercent * CHARGE_DAMAGE_BONUS) * comboMult;
        ctx.lastAttackDamage = damage;
        if (main.playerEntity != null) main.playerEntity.startAttack();
        performCombatAttack(damage);
        ctx.comboCount = (ctx.comboCount + 1) % 3;
        ctx.comboTimer = COMBO_WINDOW_SECONDS;
        main.lastAttackTime = glfwGetTime();
        ctx.cameraShake = 0.8f + chargePercent * 1.2f;
        return true;
    }

    /** Non-combat mode: attack immediately on click if off cooldown. */
    public boolean normalAttackIfReady(double now) {
        if (now - main.lastAttackTime <= NORMAL_ATTACK_COOLDOWN) return false;
        if (main.playerEntity != null) main.playerEntity.startAttack();
        performCombatAttack(BASE_DAMAGE);
        main.lastAttackTime = now;
        return true;
    }

    /**
     * Single melee swing — cone test against enemies within maxDist, scaled by combat mode.
     * Applies damage, knockback, combo status text and a damage number for each hit enemy.
     */
    public void performCombatAttack(float damage) {
        Player p = main.player;
        Vector3f pPos = p.getPosition();
        Vector3f pDir = camera.getLookDirection();

        for (int i = 0; i < main.entityManager.getEntityCount(); i++) {
            Entity e = main.entityManager.getEntity(i);
            if (e.dimension != main.activeDimension) continue;
            if (!(e instanceof EnemyEntity)) continue;
            EnemyEntity enemy = (EnemyEntity) e;
            if (enemy.isDead()) continue;

            Vector3f toEnemy = new Vector3f(enemy.position).sub(pPos);
            float dist = toEnemy.length();

            float minDot = main.combatMode ? 0.35f : 0.45f;
            float maxDist = main.combatMode ? 5.0f : 4.5f;

            if (dist < maxDist) {
                toEnemy.normalize();
                float dot = toEnemy.dot(pDir);
                if (dot > minDot) {
                    Vector3f knockback = new Vector3f(toEnemy).mul(0.8f + damage * 0.05f);
                    enemy.takeDamage(damage, knockback);
                    main.cameraShake = 0.8f + damage * 0.08f;
                    ctx.damageNumbers.add(new GameContext.DamageNumber(
                        enemy.position.x, enemy.position.y + 2.0f, enemy.position.z,
                        damage
                    ));
                    enemy.hitFlashTime = 0.3f;

                    String[] comboText = {"Hit!", "Double!", "TRIPLE!"};
                    int comboIdx = Math.max(0, Math.min(ctx.comboCount, 2));
                    ctx.setStatus(comboText[comboIdx] + " (" + String.format("%.0f", damage) + " dmg)");
                }
            }
        }
    }

    /** Tab key handler in combat mode: find nearest enemy within 25 blocks and lock, or unlock. */
    public boolean tryToggleLockOn() {
        if (!main.combatMode) return false;
        if (ctx.lockedEntityIndex >= 0) {
            ctx.lockedEntityIndex = -1;
            main.setStatus("Lock-off");
            return true;
        }
        Vector3f pPos = main.player.getPosition();
        float nearestDist = 25.0f;
        int nearestIdx = -1;
        for (int i = 0; i < main.entityManager.getEntityCount(); i++) {
            Entity e = main.entityManager.getEntity(i);
            if (e.dimension != main.activeDimension) continue;
            if (!(e instanceof EnemyEntity)) continue;
            EnemyEntity enemy = (EnemyEntity) e;
            if (enemy.isDead()) continue;
            float dist = pPos.distance(enemy.position);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestIdx = i;
            }
        }
        ctx.lockedEntityIndex = nearestIdx;
        if (nearestIdx >= 0) main.setStatus("Locked on!");
        else                  main.setStatus("No enemies to lock");
        return true;
    }
}
