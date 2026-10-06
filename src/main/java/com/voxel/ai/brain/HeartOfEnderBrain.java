package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.entity.Entity;
import com.voxel.entity.HeartOfEnderEntity;
import com.voxel.entity.PlayerEntity;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The Heart of Ender's brain: a hovering orb that drifts toward the player,
 * discharges nova pulses and disgorges endermites.
 *
 * <p>It has no melee: the threat is the pulse radius and the swarm. The action
 * ladder is pure.</p>
 */
public final class HeartOfEnderBrain implements MobBrain, StimulusBus.Listener {

    public enum Action {
        /** Slow, inevitable drift toward the player. */
        DRIFT,
        /** Point-blank nova discharge. */
        PULSE,
        /** Disgorging endermites from the orb. */
        SPAWN
    }

    static final float SIGHT_RANGE = 38f;
    static final float NOVA_RANGE = 5f;
    static final float HOLD_DISTANCE = 6f;
    static final float DECISION_INTERVAL = 0.25f;
    static final float PULSE_COOLDOWN = 3f;
    static final float SPAWN_INTERVAL = 18f;
    private static final float DRIFT_SPEED = 1.3f;

    private final HeartOfEnderEntity owner;
    private final Random rng = new Random(0xE2D3AL);

    private Action action = Action.DRIFT;
    private float decisionAccum;
    private float pulseCooldown;
    private float spawnTimer = 8f;

    private final Vector3f playerPos = new Vector3f();
    private float playerDistance = Float.MAX_VALUE;
    private boolean hasPlayer;

    public HeartOfEnderBrain(HeartOfEnderEntity owner) {
        this.owner = owner;
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN && stimulus.sourceId != owner.id) {
            // Being hurt accelerates the next discharge.
            pulseCooldown = Math.min(pulseCooldown, 0.4f);
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
        pulseCooldown = Math.max(0f, pulseCooldown - dt);
        spawnTimer = Math.max(0f, spawnTimer - dt);

        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            float healthFraction = owner.getHealth() / Math.max(1f, owner.getMaxHealth());
            Action next = chooseAction(playerDistance, healthFraction,
                    spawnTimer <= 0f, pulseCooldown <= 0f);
            if (next != action) action = next;
        }

        switch (action) {
            case SPAWN:
                owner.spawnMites(1 + rng.nextInt(3));
                spawnTimer = SPAWN_INTERVAL;
                action = Action.DRIFT;
                return true;
            case PULSE:
                owner.pulseNova();
                pulseCooldown = PULSE_COOLDOWN;
                action = Action.DRIFT;
                return true;
            case DRIFT:
            default:
                if (hasPlayer && playerDistance > HOLD_DISTANCE) {
                    owner.aiMoveToward(playerPos, dt, DRIFT_SPEED);
                }
                return true;
        }
    }

    /** Pure action ladder: spawn > pulse (in radius) > drift. */
    static Action chooseAction(float distance, float healthFraction,
                               boolean spawnReady, boolean pulseReady) {
        if (spawnReady) return Action.SPAWN;
        if (pulseReady && distance <= NOVA_RANGE) return Action.PULSE;
        return Action.DRIFT;
    }

    // ── test seams ──────────────────────────────────────────────────────
    Action currentAction() { return action; }
}
