package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.entity.Entity;
import com.voxel.entity.PlayerEntity;
import com.voxel.entity.WitherStormEntity;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The Wither Storm's brain: a flying, three-headed siege that stalks from a
 * distance, hurls wither skulls, and calls the dead up around it.
 *
 * <p>Unlike the shared pack-hunter brain this is a boss controller — it never
 * retreats, never gets distracted, and escalates into a permanent enrage once
 * it has been hurt enough (or struck hard enough in one burst). The action
 * ladder is a pure static function so it is unit-tested directly.</p>
 */
public final class WitherStormBrain implements MobBrain, StimulusBus.Listener {

    public enum Phase {
        /** Hovering pursuit, holding a menacing stand-off distance. */
        STALK,
        /** In range: skull volleys and a close tentacle sweep. */
        VOLLEY,
        /** Calling wither skeletons up from the ground. */
        SUMMON,
        /** Wounded: faster, angrier, volleys more often. */
        ENRAGED
    }

    static final float SIGHT_RANGE = 44f;
    static final float MELEE_RANGE = 4.5f;
    static final float HOLD_DISTANCE = 9f;
    static final float DECISION_INTERVAL = 0.25f;
    static final float VOLLEY_COOLDOWN = 2.6f;
    static final float ENRAGED_VOLLEY_COOLDOWN = 1.2f;
    static final float SUMMON_INTERVAL = 12f;
    static final float ENRAGE_FRACTION = 0.35f;
    private static final float STALK_SPEED = 1.6f;
    private static final float ENRAGED_SPEED = 2.5f;

    private final WitherStormEntity owner;
    private final Random rng = new Random(0x57057L);

    private Phase phase = Phase.STALK;
    private float decisionAccum;
    private float phaseElapsed;
    private float summonTimer = 4f;
    private float volleyTimer = 1.5f;
    private float enrageHold;

    private final Vector3f playerPos = new Vector3f();
    private float playerDistance = Float.MAX_VALUE;
    private boolean hasPlayer;

    public WitherStormBrain(WitherStormEntity owner) {
        this.owner = owner;
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN && stimulus.sourceId != owner.id) {
            // Every hit feeds the Storm's fury.
            enrageHold = Math.max(enrageHold, 8f);
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
        // Fall back to the tracked player even without a manager (headless/tests).
        if (!hasPlayer && owner.player != null) {
            playerPos.set(owner.player.getPosition());
            playerDistance = owner.getPosition().distance(playerPos);
            hasPlayer = playerDistance <= SIGHT_RANGE;
        }
    }

    @Override
    public boolean update(float dt) {
        decisionAccum += dt;
        phaseElapsed += dt;
        summonTimer = Math.max(0f, summonTimer - dt);
        volleyTimer = Math.max(0f, volleyTimer - dt);
        enrageHold = Math.max(0f, enrageHold - dt);

        float healthFraction = owner.getHealth() / Math.max(1f, owner.getMaxHealth());
        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            Phase next = choosePhase(healthFraction, playerDistance,
                    summonTimer <= 0f, enrageHold > 0f);
            if (next != phase) {
                phase = next;
                phaseElapsed = 0f;
            }
        }

        switch (phase) {
            case SUMMON:
                // One burst per visit, then back to the hunt.
                if (phaseElapsed > 0.6f) {
                    owner.summonMinions(2 + rng.nextInt(2));
                    summonTimer = SUMMON_INTERVAL;
                    phase = Phase.STALK;
                    phaseElapsed = 0f;
                }
                return true;
            case ENRAGED:
                if (hasPlayer) {
                    approach(ENRAGED_SPEED, dt);
                    fireIfReady(ENRAGED_VOLLEY_COOLDOWN, dt);
                }
                return true;
            case VOLLEY:
                if (hasPlayer) {
                    face(playerPos);
                    fireIfReady(VOLLEY_COOLDOWN, dt);
                    if (playerDistance <= MELEE_RANGE) {
                        owner.performAttack(playerPos);
                    } else if (playerDistance > HOLD_DISTANCE) {
                        approach(STALK_SPEED, dt);
                    }
                }
                return true;
            case STALK:
            default:
                if (hasPlayer && playerDistance > HOLD_DISTANCE) {
                    approach(STALK_SPEED, dt);
                }
                return true;
        }
    }

    /** Pure action ladder: enrage > summon > volley > stalk. */
    static Phase choosePhase(float healthFraction, float playerDistance,
                             boolean summonReady, boolean enraged) {
        if (enraged || healthFraction < ENRAGE_FRACTION) return Phase.ENRAGED;
        if (summonReady) return Phase.SUMMON;
        if (playerDistance <= HOLD_DISTANCE) return Phase.VOLLEY;
        return Phase.STALK;
    }

    private void approach(float speed, float dt) {
        owner.aiMoveToward(playerPos, dt, speed);
    }

    private void face(Vector3f target) {
        owner.rotation.y = (float) Math.toDegrees(Math.atan2(
                target.x - owner.getPosX(), target.z - owner.getPosZ()));
    }

    private void fireIfReady(float cooldown, float dt) {
        if (volleyTimer > 0f) return;
        volleyTimer = cooldown;
        float dy = playerPos.y + 1.0f - (owner.getPosY() + owner.getPickHeight() * 0.85f);
        float dx = playerPos.x - owner.getPosX();
        float dz = playerPos.z - owner.getPosZ();
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.max(0.001f, flat)));
        owner.hurlSkull(owner.rotation.y, Math.max(-40f, Math.min(40f, pitch)));
    }

    // ── test seams ──────────────────────────────────────────────────────
    Phase currentPhase() { return phase; }
}
