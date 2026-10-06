package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.entity.ArchIllagerEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.PlayerEntity;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The Arch-Illager's brain: a cowardly caster who keeps his distance, rakes
 * the player with fang lines, blinks away when crowded and whistles up vexes.
 *
 * <p>He never closes to melee — the pressure comes from spacing, teleports and
 * summoned minions. The action ladder is pure.</p>
 */
public final class ArchIllagerBrain implements MobBrain, StimulusBus.Listener {

    public enum Action {
        /** Drifting to maintain a casting stand-off distance. */
        HOVER,
        /** Raking the player with a fang line. */
        FANGS,
        /** Calling vexes to swarm. */
        SUMMON,
        /** Blinking out of melee range. */
        BLINK
    }

    static final float SIGHT_RANGE = 34f;
    static final float CAST_RANGE = 9f;
    static final float TOO_CLOSE = 3f;
    static final float HOLD_DISTANCE = 10f;
    static final float DECISION_INTERVAL = 0.25f;
    static final float FANG_COOLDOWN = 2.0f;
    static final float SUMMON_INTERVAL = 16f;
    static final float BLINK_COOLDOWN = 5f;
    private static final float DRIFT_SPEED = 1.9f;

    private final ArchIllagerEntity owner;
    private final Random rng = new Random(0xA1C1AL);

    private Action action = Action.HOVER;
    private float decisionAccum;
    private float fangCooldown;
    private float summonTimer = 7f;
    private float blinkCooldown;

    private final Vector3f playerPos = new Vector3f();
    private float playerDistance = Float.MAX_VALUE;
    private boolean hasPlayer;

    public ArchIllagerBrain(ArchIllagerEntity owner) {
        this.owner = owner;
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN && stimulus.sourceId != owner.id) {
            // Struck: vanish first, ask questions later.
            blinkCooldown = Math.min(blinkCooldown, 0.05f);
        }
    }

    @Override
    public void perceive(Senses senses) {
        hasPlayer = false;
        playerDistance = Float.MAX_VALUE;
        for (int i = 0; i < senses.visibleEntities.size(); i++) {
            Entity e = senses.visibleEntities.get(i).entity;
            if (!(e instanceof PlayerEntity)) continue;
            float d = (float) Math.sqrt(senses.visibleEntities.get(i).distanceSquared);
            if (d < playerDistance && d <= SIGHT_RANGE) {
                playerDistance = d;
                playerPos.set(e.getPosition());
                hasPlayer = true;
            }
        }
        if (!hasPlayer && owner.player != null) {
            playerPos.set(owner.player.getPosition());
            playerDistance = owner.getPosition().distance(playerPos);
            hasPlayer = playerDistance <= SIGHT_RANGE;
        }
    }

    @Override
    public boolean update(float dt) {
        decisionAccum += dt;
        fangCooldown = Math.max(0f, fangCooldown - dt);
        summonTimer = Math.max(0f, summonTimer - dt);
        blinkCooldown = Math.max(0f, blinkCooldown - dt);

        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            float healthFraction = owner.getHealth() / Math.max(1f, owner.getMaxHealth());
            Action next = chooseAction(playerDistance, healthFraction,
                    summonTimer <= 0f, blinkCooldown <= 0f);
            if (next != action) action = next;
        }

        switch (action) {
            case BLINK:
                if (hasPlayer) owner.blinkAwayFrom(playerPos, 7f);
                blinkCooldown = BLINK_COOLDOWN;
                action = Action.HOVER;
                return true;
            case SUMMON:
                owner.summonVexes(1 + rng.nextInt(2));
                summonTimer = SUMMON_INTERVAL;
                action = Action.HOVER;
                return true;
            case FANGS:
                if (hasPlayer) {
                    owner.rotation.y = (float) Math.toDegrees(Math.atan2(
                            playerPos.x - owner.getPosX(), playerPos.z - owner.getPosZ()));
                    if (fangCooldown <= 0f) {
                        owner.callFangs(playerPos);
                        fangCooldown = FANG_COOLDOWN;
                    }
                }
                return true;
            case HOVER:
            default:
                if (hasPlayer) {
                    if (playerDistance < HOLD_DISTANCE - 1.5f) {
                        // Backing off to casting range.
                        Vector3f away = new Vector3f(owner.getPosition()).sub(playerPos);
                        away.y = 0f;
                        if (away.lengthSquared() > 1e-4f) {
                            away.normalize().mul(HOLD_DISTANCE);
                            Vector3f retreat = new Vector3f(playerPos).add(away);
                            owner.aiMoveToward(retreat, dt, DRIFT_SPEED);
                        }
                    } else if (playerDistance > HOLD_DISTANCE + 3f) {
                        owner.aiMoveToward(playerPos, dt, DRIFT_SPEED);
                    }
                }
                return true;
        }
    }

    /** Pure action ladder: blink (crowded) > summon > fangs > hover. */
    static Action chooseAction(float distance, float healthFraction,
                               boolean summonReady, boolean blinkReady) {
        if (blinkReady && distance <= TOO_CLOSE) return Action.BLINK;
        if (summonReady) return Action.SUMMON;
        if (distance <= CAST_RANGE) return Action.FANGS;
        return Action.HOVER;
    }

    // ── test seams ──────────────────────────────────────────────────────
    Action currentAction() { return action; }
}
