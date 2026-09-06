package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.PathFinder;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.entity.EnemyEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Improved predator brain for hostile mobs — the enemy-side "better mob AI".
 * Intended for {@link com.voxel.entity.ZombieEntity} and other melee hostiles;
 * returning false from {@link #update(float)} falls through to the legacy FSM
 * so unusual subclasses keep their own behavior.
 *
 * <p>What it adds over the legacy observe/hunt/attack FSM:</p>
 * <ul>
 *   <li><b>Sight memory</b> — prey position is refreshed only on real line of
 *       sight and decays over {@link #MEMORY_SECONDS}; the hunter then circles
 *       the last-seen spot (SEARCH) instead of homing in on the player.</li>
 *   <li><b>Pack coordination</b> — spotting prey publishes a
 *       {@link Stimulus.Type#HUNT_CALL}; nearby hunters converge on the shared
 *       point with restored interest. Each hunter paths independently, which
 *       yields natural encirclement.</li>
 *   <li><b>Self-preservation</b> — below {@link #RETREAT_HEALTH_FRACTION}
 *       health the hunter disengages and retreats from its attacker's last
 *       known position; damage taken in combat redirects it via the
 *       {@link Stimulus.Type#DAMAGE_TAKEN} bus event.</li>
 *   <li><b>Path-backed chase</b> — throttled A* repaths with direct steering
 *       as the fallback, sharing {@link PathFinder} with the villager brain.</li>
 * </ul>
 *
 * <p>The decision function is pure and unit-tested; the instance is thin glue
 * over {@link EnemyEntity} hooks. Java 8 only.</p>
 */
public final class HunterBrain implements MobBrain, StimulusBus.Listener {

    enum Action { LURK, CHASE, SEARCH, RETREAT }

    static final float SIGHT_RANGE = 20f;
    static final float MEMORY_SECONDS = 8f;
    static final float HUNT_CALL_RADIUS = 24f;
    static final float RETREAT_HEALTH_FRACTION = 0.25f;
    static final float GIVE_UP_SECONDS = 6f;
    static final float REPATH_INTERVAL = 0.45f;
    static final float DECISION_INTERVAL = 0.2f;
    private static final float CHASE_SPEED = 2.4f;
    private static final float ATTACK_WALK_SPEED = 1.3f;
    private static final float SEARCH_SPEED = 1.6f;
    private static final float RETREAT_SPEED = 2.0f;
    private static final float CALL_REPEAT_DELAY = 1.5f;

    private final EnemyEntity owner;
    private final Random rng;

    private Action action = Action.LURK;

    /** 0..1 hunt interest; refreshed to 1 on sight, shared calls give 0.65. */
    private float memory;
    /** Seconds since prey was last seen directly. */
    private float sinceSeen;
    private float sinceCall = Float.MAX_VALUE;
    private float sinceDecision;
    private float actionElapsed;
    private float repathAccum = REPATH_INTERVAL;

    private final Vector3f lastKnown = new Vector3f();
    private boolean hasLastKnown;

    /** Shared pack target learned from another hunter's HUNT_CALL. */
    private final Vector3f packTarget = new Vector3f();
    private boolean hasPackTarget;
    private float packTargetAge;

    private final List<Vector3i> path = new ArrayList<Vector3i>();
    private int pathIndex;

    private HunterBrain(EnemyEntity owner) {
        this.owner = owner;
        this.rng = new Random(owner.id * 2654435761L + 3L);
        StimulusBus.GLOBAL.subscribe(this);
    }

    /** Install on an enemy; null-safe so callers can chain. */
    public static HunterBrain attach(EnemyEntity owner) {
        return owner == null ? null : new HunterBrain(owner);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN
                && stimulus.sourceId != owner.id) {
            // Being hurt re-aims the hunt at the attacker's position even
            // without line of sight: realistic retaliation, not omniscience.
            lastKnown.set(stimulus.position);
            hasLastKnown = true;
            sinceSeen = 0f;
            memory = Math.max(memory, 0.8f);
            return;
        }
        if (stimulus.type == Stimulus.Type.HUNT_CALL
                && stimulus.sourceId != owner.id
                && stimulus.position.distanceSquared(owner.getPosition())
                        <= HUNT_CALL_RADIUS * HUNT_CALL_RADIUS) {
            packTarget.set(stimulus.position);
            hasPackTarget = true;
            packTargetAge = 0f;
            memory = Math.max(memory, 0.65f);
            sinceSeen = 0f;
        }
    }

    @Override
    public void perceive(Senses senses) {
        Senses.Visible best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < senses.visibleEntities.size(); i++) {
            Senses.Visible v = senses.visibleEntities.get(i);
            // Prey = anything that is not a hostile mob: villagers, the
            // player, passive animals. Hostiles do not hunt each other.
            if (v.entity instanceof EnemyEntity) continue;
            if (v.distanceSquared >= bestDist) continue;
            if (v.distanceSquared > SIGHT_RANGE * SIGHT_RANGE) continue;
            if (!v.lineOfSight()) continue;
            best = v;
            bestDist = v.distanceSquared;
        }
        if (best != null) {
            lastKnown.set(best.entity.getPosition());
            hasLastKnown = true;
            sinceSeen = 0f;
            memory = 1f;
            // Fresh spotting: rally the pack (rate-limited so a crowd of
            // hunters does not spam the bus every tick).
            if ((action == Action.LURK || action == Action.SEARCH)
                    && sinceCall >= CALL_REPEAT_DELAY) {
                StimulusBus.GLOBAL.publish(new Stimulus(
                        Stimulus.Type.HUNT_CALL, owner.id,
                        new Vector3f(lastKnown), 1f, null,
                        System.currentTimeMillis()));
                sinceCall = 0f;
            }
        }
    }

    @Override
    public boolean update(float dt) {
        sinceSeen += dt;
        sinceCall += dt;
        memory = Math.max(0f, memory - dt / MEMORY_SECONDS);
        actionElapsed += dt;
        if (hasPackTarget) {
            packTargetAge += dt;
            if (packTargetAge > MEMORY_SECONDS) hasPackTarget = false;
        }

        float healthFraction = owner.getHealth() / Math.max(1f, owner.getMaxHealth());
        sinceDecision += dt;
        if (sinceDecision >= DECISION_INTERVAL) {
            sinceDecision = 0f;
            Action next = chooseAction(healthFraction, sinceSeen, memory,
                    actionElapsed, hasLastKnown, hasPackTarget);
            if (next != action) transition(next);
        }

        switch (action) {
            case RETREAT:
                retreat(dt);
                return true;
            case CHASE:
                chase(dt);
                return true;
            case SEARCH:
                search(dt);
                return true;
            case LURK:
            default:
                // Nothing to do: defer to the legacy FSM for idle wandering.
                return false;
        }
    }

    /** Pure decision function — unit-tested directly. */
    static Action chooseAction(float healthFraction, float sinceSeenSeconds,
                               float memoryLevel, float searchingForSeconds,
                               boolean hasLastKnownPosition, boolean hasPackCall) {
        if (healthFraction < RETREAT_HEALTH_FRACTION) return Action.RETREAT;
        if (sinceSeenSeconds < MEMORY_SECONDS && memoryLevel > 0f) return Action.CHASE;
        if ((hasLastKnownPosition || hasPackCall) && searchingForSeconds < GIVE_UP_SECONDS) {
            return Action.SEARCH;
        }
        return Action.LURK;
    }

    private void transition(Action next) {
        action = next;
        actionElapsed = 0f;
        repathAccum = REPATH_INTERVAL; // repath immediately on state change
        path.clear();
        pathIndex = 0;
    }

    private void chase(float dt) {
        Vector3f target = currentTarget();
        float dist = owner.getPosition().distance(target);
        // Slow to a walk right before melee range so the wind-up telegraph
        // connects instead of overshooting a sprinting target.
        float speed = dist < 4f ? ATTACK_WALK_SPEED : CHASE_SPEED;
        stepWithPaths(target, speed, dt);
    }

    /** Where to hunt right now: own sighting first, then the pack's call. */
    private Vector3f currentTarget() {
        if (hasLastKnown) return new Vector3f(lastKnown);
        if (hasPackTarget) return new Vector3f(packTarget);
        return owner.getPosition();
    }

    private void search(float dt) {
        // Circle the last-seen spot; the bearing is seeded per-entity so two
        // searchers sweep different arcs instead of stacking in a queue.
        Vector3f around = currentTarget();
        double angle = (owner.id * 2.399) + actionElapsed * 0.9;
        float radius = 3f + 1.5f * (float) Math.sin(actionElapsed * 0.6);
        Vector3f probe = new Vector3f(
                around.x + (float) Math.cos(angle) * radius,
                around.y,
                around.z + (float) Math.sin(angle) * radius);
        stepWithPaths(probe, SEARCH_SPEED, dt);
        if (actionElapsed >= GIVE_UP_SECONDS) {
            hasLastKnown = false;
            hasPackTarget = false;
            memory = 0f;
            sinceSeen = Float.MAX_VALUE;
        }
    }

    private void retreat(float dt) {
        Vector3f away = new Vector3f(owner.getPosition()).sub(currentTarget());
        away.y = 0f;
        if (away.lengthSquared() < 1e-4f) {
            away.set(rng.nextFloat() - 0.5f, 0f, rng.nextFloat() - 0.5f);
        }
        away.normalize();
        Vector3f target = new Vector3f(owner.getPosition()).add(away.mul(6f));
        owner.aiMoveToward(target, dt, RETREAT_SPEED);
    }

    /** Throttled A* movement with direct steering as the fallback. */
    private void stepWithPaths(Vector3f target, float speed, float dt) {
        repathAccum += dt;
        if (owner.world != null && repathAccum >= REPATH_INTERVAL) {
            repathAccum = 0f;
            path.clear();
            pathIndex = 0;
            path.addAll(PathFinder.findPath(owner.world::getVoxel,
                    owner.getPosX(), owner.getPosY(), owner.getPosZ(),
                    target.x, target.y, target.z));
        }
        if (!path.isEmpty() && pathIndex < path.size()) {
            Vector3i node = path.get(pathIndex);
            Vector3f nodePos = new Vector3f(node.x + 0.5f, node.y, node.z + 0.5f);
            float dx = owner.getPosX() - nodePos.x;
            float dz = owner.getPosZ() - nodePos.z;
            if (dx * dx + dz * dz < 0.36f && Math.abs(owner.getPosY() - nodePos.y) < 1.1f) {
                pathIndex++;
                if (pathIndex >= path.size()) {
                    // Route consumed; steer straight for the final approach.
                    owner.aiMoveToward(target, dt, speed);
                    return;
                }
                node = path.get(pathIndex);
                nodePos = new Vector3f(node.x + 0.5f, node.y, node.z + 0.5f);
            }
            owner.aiMoveToward(nodePos, dt, speed);
        } else {
            owner.aiMoveToward(target, dt, speed);
        }
    }

    /** Forget the current prey (target died, despawned, or debug reset). */
    public void forgetPrey() {
        hasLastKnown = false;
        hasPackTarget = false;
        memory = 0f;
        sinceSeen = Float.MAX_VALUE;
    }

    /** Current high-level action, for debugging overlays and tests. */
    Action currentAction() {
        return action;
    }
}
