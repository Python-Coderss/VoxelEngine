package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.PathFinder;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.ai.VoxelView;
import com.voxel.ai.body.Emote;
import com.voxel.ai.body.EmotePlayer;
import com.voxel.ai.body.GazeController;
import com.voxel.ai.speech.VillagerSpeech;
import com.voxel.entity.Entity;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * First real villager brain: utility-ordered decisions (panic > shelter >
 * work > socialize > wander > idle) driven purely by {@link Senses} and the
 * {@link StimulusBus}. Silent communication uses POINT gestures; fear spreads
 * by earshot through published THREAT_SEEN stimuli. All body language routes
 * through {@link EmotePlayer}; all head aiming through {@link GazeController}.
 */
public final class VillagerBrain implements MobBrain, StimulusBus.Listener {

    enum Action { IDLE, WANDER, PANIC_FLEE, ALERT_LOOK, GO_INDOORS, SOCIALIZE, GO_BUILD }

    private static final float DECISION_INTERVAL = 0.25f;
    private static final float THREAT_SIGHT_RANGE = 16f;
    private static final float EARSHOT_RANGE = 22f;
    private static final long SCREAM_COOLDOWN_MILLIS = 9000L;

    private static final String[] SCREAMS = {"AAAH!", "HELP! HELP!", "RUN AWAY!", "NOOO!"};

    private final VillagerEntity owner;
    private final Random rng;
    private final GazeController gaze = new GazeController();
    private final EmotePlayer emotes = new EmotePlayer();

    private float nearestThreatDist = Float.MAX_VALUE;
    private boolean threatVisible;
    private final Vector3f threatPos = new Vector3f();
    private boolean hasThreatPos;

    private float nearestFriendDist = Float.MAX_VALUE;
    private final Vector3f friendPos = new Vector3f();
    private boolean hasFriend;

    private boolean nightTime;
    private boolean inWater;

    private Action action = Action.IDLE;
    private float decisionAccum;
    private float panicRemaining;
    private float alertRemaining;
    private float fleeTime;
    private float actionElapsed;
    private float socialCooldown;
    private long lastScreamMillis;
    private boolean screamedThisPanic;

    private final Vector3f wanderTarget = new Vector3f();
    private boolean hasWanderTarget;
    private float stuckAccum;
    private float stuckAtDist;
    private final Vector3f stuckStart = new Vector3f();

    // Pathfinding: panic-flee and go-indoors run a cached A* route instead of
    // pushing straight into walls (which previously pinned villagers against
    // the first obstacle while a threat approached).
    private VoxelView voxels;
    private final List<Vector3i> path = new ArrayList<>();
    private int pathIndex;
    private boolean usePath;
    private float repathAccum;
    private int fleePathAttempts;

    public VillagerBrain(VillagerEntity owner) {
        this.owner = owner;
        this.rng = new Random(owner.id * 2654435761L + 1L);
        this.gaze.setSeed(owner.id);
        this.wanderTarget.set(owner.getPosition());
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN
                || stimulus.type == Stimulus.Type.THREAT_SEEN
                || stimulus.type == Stimulus.Type.POINT_GESTURE) {
            if (stimulus.position.distanceSquared(owner.getPosition())
                    > EARSHOT_RANGE * EARSHOT_RANGE) {
                return;
            }
            adoptFear(stimulus.position,
                    stimulus.type == Stimulus.Type.POINT_GESTURE ? 2.5f : 4.5f);
        }
    }

    /** Fear adopted from someone else's alarm: silent-alert first, flee if it persists. */
    private void adoptFear(Vector3f sourcePosition, float strength) {
        if (!hasThreatPos || threatPos.distanceSquared(sourcePosition) < 4f
                || nearestThreatDist == Float.MAX_VALUE) {
            threatPos.set(sourcePosition);
            hasThreatPos = true;
        }
        panicRemaining = Math.max(panicRemaining, strength * 0.5f);
        alertRemaining = Math.max(alertRemaining, strength);
        if (action != Action.PANIC_FLEE) {
            transition(Action.ALERT_LOOK);
        }
    }

    @Override
    public void perceive(Senses senses) {
        voxels = senses.voxels;
        nearestThreatDist = Float.MAX_VALUE;
        threatVisible = false;
        nearestFriendDist = Float.MAX_VALUE;
        hasFriend = false;

        List<Senses.Visible> visible = senses.visibleEntities;
        for (int i = 0; i < visible.size(); i++) {
            Senses.Visible v = visible.get(i);
            Entity e = v.entity;
            if (e instanceof EnemyEntity) {
                float d = (float) Math.sqrt(v.distanceSquared);
                if (d < nearestThreatDist && d <= THREAT_SIGHT_RANGE && v.lineOfSight()) {
                    nearestThreatDist = d;
                    threatVisible = true;
                    threatPos.set(e.getPosition());
                    hasThreatPos = true;
                }
            } else if (e instanceof VillagerEntity) {
                float d = (float) Math.sqrt(v.distanceSquared);
                if (d < nearestFriendDist) {
                    nearestFriendDist = d;
                    friendPos.set(e.getPosition());
                    hasFriend = true;
                }
            }
        }

        float tod = senses.timeOfDayMinutes % 1440f;
        nightTime = tod < 360f || tod > 1080f;
        inWater = owner.aiInWater();

        if (threatVisible) {
            panicRemaining = Math.max(panicRemaining, 4f);
        }
    }

    @Override
    public boolean update(float dt) {
        gaze.tick(dt);
        emotes.update(dt);
        panicRemaining = Math.max(0f, panicRemaining - dt);
        alertRemaining = Math.max(0f, alertRemaining - dt);
        socialCooldown = Math.max(0f, socialCooldown - dt);
        actionElapsed += dt;

        decisionAccum += dt;
        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            Action next = chooseAction(
                    threatVisible && nearestThreatDist <= THREAT_SIGHT_RANGE,
                    panicRemaining, alertRemaining,
                    nightTime, inWater,
                    owner.aiHasBuildWork(),
                    hasFriend && nearestFriendDist < 6f && socialCooldown <= 0f);
            if (next != action) transition(next);
        }

        if (inWater) {
            owner.aiSwimUp(dt);
            return true;
        }

        execute(dt);
        return true;
    }

    /** Utility ordering. Package-private and side-effect-free for unit tests. */
    static Action chooseAction(boolean threatNearAndVisible, float panicSeconds,
                               float alertSeconds, boolean night, boolean swimming,
                               boolean buildWorkPending, boolean socialOpportunity) {
        if (swimming) return Action.PANIC_FLEE;
        if (threatNearAndVisible || panicSeconds > 0f) return Action.PANIC_FLEE;
        if (alertSeconds > 0f) return Action.ALERT_LOOK;
        if (night) return Action.GO_INDOORS;
        if (buildWorkPending) return Action.GO_BUILD;
        if (socialOpportunity) return Action.SOCIALIZE;
        return Action.WANDER;
    }

    private void transition(Action next) {
        action = next;
        actionElapsed = 0f;
        fleeTime = 0f;
        usePath = false;
        path.clear();
        if (next == Action.PANIC_FLEE) {
            fleePathAttempts = 0;
            recordStuckBaseline();
            // Start the escape route immediately (before any wall contact) so
            // panicking villagers run around obstacles instead of into them.
            requestPanicPath();
        } else if (next == Action.GO_INDOORS) {
            Vector3i home = owner.getVillageCenter();
            if (home != null) {
                tryPathTo(new Vector3f(home.x + 0.5f, owner.getPosY(), home.z + 0.5f));
            }
        }
        switch (next) {
            case PANIC_FLEE:
                screamedThisPanic = false;
                if (rng.nextFloat() < 0.3f) {
                    emotes.play(Emote.COWER);
                } else {
                    emotes.play(Emote.WAVE_FRANTIC);
                }
                broadcastAlarm();
                break;
            case ALERT_LOOK:
                emotes.play(Emote.COWER);
                break;
            case SOCIALIZE:
                emotes.play(Emote.NOD);
                say("Hmm.");
                break;
            default:
                break;
        }
    }

    /** Shout + point: audible contagion and the silent pointing channel. */
    private void broadcastAlarm() {
        if (!hasThreatPos) return;
        StimulusBus.GLOBAL.publish(new Stimulus(
                Stimulus.Type.THREAT_SEEN, owner.id,
                new Vector3f(owner.getPosX(), owner.getPosY(), owner.getPosZ()),
                1f, null, System.currentTimeMillis()));
        StimulusBus.GLOBAL.publish(new Stimulus(
                Stimulus.Type.POINT_GESTURE, owner.id,
                new Vector3f(threatPos),
                1f, null, System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        if (!screamedThisPanic && now - lastScreamMillis > SCREAM_COOLDOWN_MILLIS) {
            if (say(SCREAMS[rng.nextInt(SCREAMS.length)])) {
                screamedThisPanic = true;
                lastScreamMillis = now;
            }
        }
    }

    private void execute(float dt) {
        switch (action) {
            case PANIC_FLEE:
                fleeTime += dt;
                if (usePath && !path.isEmpty()) {
                    stepAlongPath(dt, owner.aiFleeSpeed());
                    // Route finished but still frightened: line up the next
                    // one. If no route exists (sealed room), the direct
                    // zigzag runner resumes on the next tick as a fallback.
                    if (!usePath && panicRemaining > 0f) {
                        requestPanicPath();
                    }
                } else {
                    fleeDirect(dt);
                }
                if (emotes.isActive() && !emotes.isPlaying(Emote.COWER)
                        && !emotes.isPlaying(Emote.WAVE_FRANTIC) && rng.nextFloat() < 0.02f) {
                    emotes.play(Emote.WAVE_FRANTIC);
                }
                break;

            case ALERT_LOOK:
                owner.aiStandAnim(dt);
                if (hasThreatPos) {
                    gaze.lookAt(threatPos, 30f, 1.0f);
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                    owner.aiFaceYaw((float) Math.toDegrees(
                            Math.atan2(threatPos.x - owner.getPosX(),
                                    threatPos.z - owner.getPosZ())));
                } else {
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                }
                break;

            case GO_INDOORS:
                Vector3i home = owner.getVillageCenter();
                if (home == null) {
                    transition(Action.WANDER);
                    break;
                }
                Vector3f homePos = new Vector3f(home.x + 0.5f, owner.getPosY(), home.z + 0.5f);
                float homeDist = homePos.distance(owner.getPosition());
                if (homeDist < 3f) {
                    owner.aiMoveToward(homePos, owner.aiWalkSpeed() * 0.8f, dt);
                    if (homeDist < 1.2f) {
                        transition(Action.WANDER);
                    }
                    break;
                }
                if (!usePath && repathAccum >= 4f) {
                    repathAccum = 0f;
                    tryPathTo(homePos);
                }
                repathAccum += dt;
                if (usePath && !path.isEmpty()) {
                    stepAlongPath(dt, owner.aiWalkSpeed() * 0.8f);
                } else {
                    owner.aiMoveToward(homePos, owner.aiWalkSpeed() * 0.8f, dt);
                }
                break;

            case GO_BUILD:
                Vector3i task = owner.aiNextBuildTarget();
                if (task == null) {
                    transition(Action.IDLE);
                    break;
                }
                Vector3f stand = new Vector3f(task.x + 0.5f, owner.getPosY(), task.z + 0.5f);
                float distSq = stand.distanceSquared(owner.getPosition());
                if (distSq > 4f) {
                    owner.aiMoveToward(stand, owner.aiWalkSpeed(), dt);
                } else {
                    owner.aiStandAnim(dt);
                    gaze.lookAt(new Vector3f(task.x + 0.5f, task.y + 0.5f, task.z + 0.5f),
                            10f, 0.5f);
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                    emotes.play(Emote.HAMMER);
                    owner.aiFaceYaw((float) Math.toDegrees(
                            Math.atan2(task.x + 0.5f - owner.getPosX(),
                                    task.z + 0.5f - owner.getPosZ())));
                }
                break;

            case SOCIALIZE:
                if (!hasFriend) {
                    socialCooldown = 20f;
                    transition(Action.IDLE);
                    break;
                }
                if (nearestFriendDist > 1.8f) {
                    owner.aiMoveToward(friendPos, owner.aiWalkSpeed() * 0.5f, dt);
                } else {
                    owner.aiStandAnim(dt);
                    gaze.lookAt(friendPos, 5f, 0.5f);
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                    if (actionElapsed > 5f + rng.nextFloat() * 4f) {
                        socialCooldown = 25f + rng.nextFloat() * 35f;
                        emotes.play(Emote.NOD);
                        transition(Action.IDLE);
                    }
                }
                break;

            case WANDER:
                if (!hasWanderTarget
                        || wanderTarget.distanceSquared(owner.getPosition()) < 1f
                        || actionElapsed > 14f) {
                    pickWanderTarget();
                    actionElapsed = 0f;
                }
                owner.aiMoveToward(wanderTarget, owner.aiWalkSpeed() * 0.7f, dt);
                // Unstick: if a wall or another villager blocks the route, the
                // wandering villager barely advances (legacy behavior was to
                // grind against the obstacle for the full 14 s window).
                stuckAccum += dt;
                if (stuckAccum >= 2.5f) {
                    float moved = stuckStart.distance(owner.getPosition());
                    if (moved < Math.max(0.5f, stuckAtDist * 0.2f)) {
                        pickWanderTarget();
                        actionElapsed = 0f;
                    }
                    recordStuckBaseline();
                    stuckAccum = 0f;
                }
                break;

            case IDLE:
            default:
                owner.aiStandAnim(dt);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                if (rng.nextFloat() < 0.004f) emotes.play(rng.nextBoolean() ? Emote.NOD : Emote.HEAD_SHAKE);
                if (rng.nextFloat() < 0.0015f) say("Hmm.");
                break;
        }
    }

    /** Legacy zigzag sprint straight away from the threat. */
    private void fleeDirect(float dt) {
        if (hasThreatPos) {
            Vector3f away = new Vector3f(owner.getPosition()).sub(threatPos);
            away.y = 0;
            if (away.lengthSquared() < 1e-4f) {
                away.set(rng.nextFloat() - 0.5f, 0, rng.nextFloat() - 0.5f);
            }
            away.normalize();
            double zig = Math.toRadians(
                    (float) Math.sin(fleeTime * 7f + owner.id) * 35f);
            float cos = (float) Math.cos(zig), sin = (float) Math.sin(zig);
            Vector3f dir = new Vector3f(
                    away.x * cos - away.z * sin, 0,
                    away.x * sin + away.z * cos);
            Vector3f target = new Vector3f(owner.getPosition()).add(dir.mul(3f));
            owner.aiMoveToward(target, owner.aiFleeSpeed(), dt);
            owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(dir.x, dir.z)));

            // Contact with a wall during panic: hand off to the pathfinder so
            // the villager runs around the obstacle rather than grinding on it.
            stuckAccum += dt;
            if (stuckAccum >= 0.7f && fleePathAttempts < 3) {
                float moved = stuckStart.distance(owner.getPosition());
                if (moved < 0.35f) {
                    requestPanicPath();
                }
                recordStuckBaseline();
                stuckAccum = 0f;
            }
        } else {
            owner.aiStandAnim(dt);
        }
    }

    /** Walk cached path nodes; clears {@code usePath} once the route is done. */
    private void stepAlongPath(float dt, float speed) {
        if (path.isEmpty() || pathIndex >= path.size()) {
            usePath = false;
            return;
        }
        Vector3i node = path.get(pathIndex);
        Vector3f target = new Vector3f(node.x + 0.5f, node.y, node.z + 0.5f);
        owner.aiMoveToward(target, speed, dt);
        owner.aiFaceYaw((float) Math.toDegrees(
                Math.atan2(target.x - owner.getPosX(), target.z - owner.getPosZ())));
        float dx = owner.getPosX() - target.x;
        float dz = owner.getPosZ() - target.z;
        if (dx * dx + dz * dz < 0.36f && Math.abs(owner.getPosY() - target.y) < 1.1f) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                usePath = false;
            }
        }
    }

    /** Pathfind away from the current threat; false when no route exists. */
    private boolean requestPanicPath() {
        if (voxels == null || !hasThreatPos || fleePathAttempts >= 3) {
            return false;
        }
        fleePathAttempts++;
        usePath = false;
        Vector3f point = pickFleePoint(owner.getPosition(), threatPos, rng);
        path.clear();
        pathIndex = 0;
        path.addAll(PathFinder.findPath(voxels,
                owner.getPosX(), owner.getPosY(), owner.getPosZ(),
                point.x, point.y, point.z));
        if (!path.isEmpty()) {
            usePath = true;
            recordStuckBaseline();
            return true;
        }
        return false;
    }

    /** Pathfind toward a fixed landmark (village center at night). */
    private void tryPathTo(Vector3f goal) {
        if (voxels == null) return;
        usePath = false;
        path.clear();
        pathIndex = 0;
        path.addAll(PathFinder.findPath(voxels,
                owner.getPosX(), owner.getPosY(), owner.getPosZ(),
                goal.x, goal.y, goal.z));
        usePath = !path.isEmpty();
    }

    /**
     * Pick a sprint point roughly opposite the threat. Pure geometry (no world
     * access) so the direction logic is unit-testable.
     */
    static Vector3f pickFleePoint(Vector3f position, Vector3f threat, Random rng) {
        Vector3f away = new Vector3f(position).sub(threat);
        away.y = 0;
        if (away.lengthSquared() < 1e-4f) {
            away.set(1f, 0f, 0f);
        }
        away.normalize();
        // Jitter the bearing so two fleeing villagers do not follow one queue.
        double zig = Math.toRadians((rng.nextFloat() - 0.5f) * 70.0);
        float cos = (float) Math.cos(zig), sin = (float) Math.sin(zig);
        Vector3f dir = new Vector3f(
                away.x * cos - away.z * sin, 0f,
                away.x * sin + away.z * cos);
        return new Vector3f(position).add(dir.mul(8f));
    }

    private void recordStuckBaseline() {
        stuckStart.set(owner.getPosition());
    }

    private void pickWanderTarget() {
        Vector3i center = owner.getVillageCenter();
        if (center != null) {
            wanderTarget.set(
                    center.x + (rng.nextFloat() - 0.5f) * 40f,
                    owner.getPosY(),
                    center.z + (rng.nextFloat() - 0.5f) * 40f);
        } else {
            wanderTarget.set(
                    owner.getPosX() + (rng.nextFloat() - 0.5f) * 24f,
                    owner.getPosY(),
                    owner.getPosZ() + (rng.nextFloat() - 0.5f) * 24f);
        }
        hasWanderTarget = true;
        stuckAtDist = wanderTarget.distance(owner.getPosition());
        recordStuckBaseline();
        stuckAccum = 0f;
    }

    private boolean say(String line) {
        return VillagerSpeech.say(owner.id, line);
    }
}
