package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.entity.Entity;
import com.voxel.entity.GreatHogEntity;
import com.voxel.entity.PlayerEntity;
import org.joml.Vector3f;

import java.util.Random;

/**
 * The Great Hog's brain: a ground bruiser that charges, slams a two-handed
 * hammer down, and rallies the horde when the fight drags.
 *
 * <p>The whole mob is a battering ram — it commits to charges, telegraphs the
 * pound, and only calls for help on a timer. The action ladder is pure.</p>
 */
public final class GreatHogBrain implements MobBrain, StimulusBus.Listener {

    public enum Action {
        /** Walking the target down at a heavy pace. */
        STALK,
        /** Committed full-speed run at the player. */
        CHARGE,
        /** In range: winding up the hammer, then slamming. */
        POUND,
        /** Bellowing; piglins of the horde converge. */
        SUMMON
    }

    static final float SIGHT_RANGE = 36f;
    static final float CHARGE_RANGE = 15f;
    static final float MELEE_RANGE = 3.5f;
    static final float DECISION_INTERVAL = 0.25f;
    static final float POUND_WINDUP = 0.7f;
    static final float POUND_COOLDOWN = 2.2f;
    static final float SUMMON_INTERVAL = 14f;
    private static final float STALK_SPEED = 1.1f;
    private static final float CHARGE_SPEED = 3.2f;

    private final GreatHogEntity owner;
    private final Random rng = new Random(0x60C1AL);

    private Action action = Action.STALK;
    private float decisionAccum;
    private float summonTimer = 6f;
    private float poundCooldown;
    private float windUp;
    private boolean winding;

    private final Vector3f playerPos = new Vector3f();
    private float playerDistance = Float.MAX_VALUE;
    private boolean hasPlayer;

    public GreatHogBrain(GreatHogEntity owner) {
        this.owner = owner;
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN && stimulus.sourceId != owner.id) {
            // A hit provokes an immediate charge at the attacker.
            playerPos.set(stimulus.position);
            hasPlayer = true;
            action = Action.CHARGE;
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
        summonTimer = Math.max(0f, summonTimer - dt);
        poundCooldown = Math.max(0f, poundCooldown - dt);

        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            float healthFraction = owner.getHealth() / Math.max(1f, owner.getMaxHealth());
            Action next = chooseAction(playerDistance, healthFraction,
                    summonTimer <= 0f, poundCooldown <= 0f);
            if (next != action) {
                action = next;
                windUp = 0f;
                winding = false;
            }
        }

        switch (action) {
            case SUMMON:
                owner.summonHorde(2 + rng.nextInt(2));
                summonTimer = SUMMON_INTERVAL;
                action = Action.STALK;
                return true;
            case CHARGE:
                if (hasPlayer) {
                    owner.aiMoveToward(playerPos, dt, CHARGE_SPEED);
                }
                return true;
            case POUND:
                pound(dt);
                return true;
            case STALK:
            default:
                if (hasPlayer) {
                    owner.aiMoveToward(playerPos, dt, STALK_SPEED);
                }
                return true;
        }
    }

    /** Pure action ladder: summon > pound (in range) > charge > stalk. */
    static Action chooseAction(float distance, float healthFraction,
                               boolean summonReady, boolean poundReady) {
        if (summonReady) return Action.SUMMON;
        if (distance <= MELEE_RANGE && poundReady) return Action.POUND;
        if (distance <= CHARGE_RANGE) return Action.CHARGE;
        return Action.STALK;
    }

    private void pound(float dt) {
        if (!hasPlayer) return;
        owner.rotation.y = (float) Math.toDegrees(Math.atan2(
                playerPos.x - owner.getPosX(), playerPos.z - owner.getPosZ()));
        if (playerDistance > MELEE_RANGE + 0.5f) {
            owner.aiMoveToward(playerPos, dt, STALK_SPEED);
            winding = false;
            windUp = 0f;
            return;
        }
        if (!winding) {
            winding = true;
            windUp = 0f;
            owner.hitFlashTime = 0.01f;
            return;
        }
        windUp += dt;
        float progress = Math.min(1f, windUp / POUND_WINDUP);
        owner.hitFlashTime = 0.4f + progress * 0.6f;
        if (windUp >= POUND_WINDUP) {
            owner.pound(playerPos);
            poundCooldown = POUND_COOLDOWN;
            winding = false;
            windUp = 0f;
            owner.hitFlashTime = 0f;
        }
    }

    // ── test seams ──────────────────────────────────────────────────────
    Action currentAction() { return action; }
}
