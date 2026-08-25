package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.ai.Senses;
import com.voxel.ai.Stimulus;
import com.voxel.ai.StimulusBus;
import com.voxel.ai.body.Emote;
import com.voxel.ai.body.EmotePlayer;
import com.voxel.ai.body.GazeController;
import com.voxel.ai.speech.VillagerSpeech;
import com.voxel.entity.Entity;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.VillagerEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;

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
                } else {
                    owner.aiStandAnim(dt);
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
                owner.aiMoveToward(new Vector3f(home.x + 0.5f, owner.getPosY(), home.z + 0.5f),
                        owner.aiWalkSpeed() * 0.8f, dt);
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
    }

    private boolean say(String line) {
        return VillagerSpeech.say(owner.id, line);
    }
}
