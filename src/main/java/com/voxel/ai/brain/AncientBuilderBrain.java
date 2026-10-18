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
import com.voxel.ai.speech.AncientBuilderLines;
import com.voxel.entity.AncientBuilderEntity;
import com.voxel.entity.Entity;
import com.voxel.entity.EnemyEntity;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The ancient-builder brain: the smart half of the civilization that raised the
 * world before the extinction.
 *
 * <p>Builders are deliberate, coordinated and loud in the captions (but silent
 * in the world — nothing here touches the audio stack). A builder:</p>
 * <ul>
 *   <li><b>Works its trade</b> — architects raise stone, engineers service the
 *       machines, scholars study and record, curators watch the stores, sentries
 *       walk the walls.</li>
 *   <li><b>Acknowledges the player</b> — a stranger is greeted at a polite
 *       distance, then led toward the heart of the site. Intruders who will not
 *       leave, or who strike a builder, are warned instead.</li>
 *   <li><b>Coordinates</b> — a spotted threat is announced on the
 *       {@link StimulusBus} so nearby builders converge, fall back and sound the
 *       alarm together (and neighbouring villagers overhear it too).</li>
 *   <li><b>Survives</b> — sentries hold their ground at range; everyone else
 *       retreats toward the home site when a threat closes in, exactly as the
 *       site's last defenders would.</li>
 * </ul>
 *
 * <p>Speech is voiced through
 * {@link com.voxel.ai.speech.CharacterVoice} in the ancient-builder profile via
 * the entity's {@code aiSpeakLine}.</p>
 */
public final class AncientBuilderBrain implements MobBrain, StimulusBus.Listener {

    enum Action {
        /** Walking the rounds of the site. */
        PATROL,
        /** At a work spot, laying stone / servicing machines. */
        WORK,
        /** Reading and recording at a study spot. */
        STUDY,
        /** Greeting an arriving stranger. */
        GREET,
        /** Leading the player toward the heart of the site. */
        ESCORT,
        /** Politely but firmly turning an intruder away. */
        WARN,
        /** Falling back to shelter with a threat on the ground. */
        FLEE,
        IDLE
    }

    static final float DECISION_INTERVAL = 0.3f;
    static final float SIGHT_RANGE = 22f;
    static final float THREAT_RANGE = 16f;
    static final float GREET_RANGE = 13f;
    static final float PERSONAL_SPACE = 3.5f;
    static final float WORK_BEAT_SECONDS = 7f;
    static final float ALERT_REPEAT_SECONDS = 6f;
    static final float PATROL_SPOT_SECONDS = 8f;
    private static final float CONGA_RANGE = 18f;

    private final AncientBuilderEntity owner;
    private final Random rng;
    private final GazeController gaze = new GazeController();
    private final EmotePlayer emotes;

    private Action action = Action.IDLE;
    private float decisionAccum;
    private float actionElapsed;
    private float workTimer;
    private float alertCooldown;
    private float workSpeakCooldown;
    private float hostilityTimer;
    private float fearTimer;
    private boolean greeted;

    // ── perception ──
    private float nearestThreatDist = Float.MAX_VALUE;
    private boolean threatVisible;
    private final Vector3f threatPos = new Vector3f();
    private boolean hasThreatPos;
    private boolean playerNear;
    private final Vector3f playerPos = new Vector3f();
    private float playerDist = Float.MAX_VALUE;

    // ── sites ──
    private final Vector3f workSpot = new Vector3f();
    private final Vector3f patrolSpot = new Vector3f();
    private boolean hasPatrolSpot;

    private VoxelView voxels;
    private final List<Vector3i> path = new ArrayList<>();
    private int pathIndex;
    private boolean usePath;
    private float repathAccum;

    public AncientBuilderBrain(AncientBuilderEntity owner) {
        this.owner = owner;
        this.rng = new Random(owner.id * 2654435761L + 17L);
        this.gaze.setSeed(owner.id);
        this.emotes = owner.aiEmotePlayer();
        StimulusBus.GLOBAL.subscribe(this);
    }

    @Override
    public void onStimulus(Stimulus stimulus) {
        if (stimulus.type == Stimulus.Type.DAMAGE_TAKEN && stimulus.sourceId == owner.id) {
            // Struck by someone: stop being polite.
            hostilityTimer = Math.max(hostilityTimer, 20f);
            fearTimer = Math.max(fearTimer, 3f);
            threatPos.set(stimulus.position);
            hasThreatPos = true;
            if (action != Action.FLEE) transition(Action.WARN);
            return;
        }
        if (stimulus.type == Stimulus.Type.THREAT_SEEN
                && stimulus.sourceId != owner.id) {
            if (stimulus.position.distanceSquared(owner.getPosition())
                    > SIGHT_RANGE * SIGHT_RANGE) {
                return;
            }
            threatPos.set(stimulus.position);
            hasThreatPos = true;
            fearTimer = Math.max(fearTimer, 2.5f);
            if (action == Action.PATROL || action == Action.WORK
                    || action == Action.STUDY || action == Action.IDLE) {
                transition(Action.FLEE);
            }
        }
    }

    @Override
    public void perceive(Senses senses) {
        voxels = senses.voxels;
        nearestThreatDist = Float.MAX_VALUE;
        threatVisible = false;
        playerNear = false;
        playerDist = Float.MAX_VALUE;

        List<Senses.Visible> visible = senses.visibleEntities;
        for (int i = 0; i < visible.size(); i++) {
            Senses.Visible v = visible.get(i);
            Entity e = v.entity;
            if (e instanceof EnemyEntity) {
                float d = (float) Math.sqrt(v.distanceSquared);
                if (d < nearestThreatDist && d <= SIGHT_RANGE && v.lineOfSight()) {
                    nearestThreatDist = d;
                    threatVisible = true;
                    threatPos.set(e.getPosition());
                    hasThreatPos = true;
                }
            } else if (e instanceof com.voxel.entity.PlayerEntity) {
                float d = (float) Math.sqrt(v.distanceSquared);
                if (d < playerDist) {
                    playerDist = d;
                    playerPos.set(e.getPosition());
                    playerNear = d <= GREET_RANGE;
                }
            }
        }
    }

    @Override
    public boolean update(float dt) {
        gaze.tick(dt);
        emotes.update(dt);
        actionElapsed += dt;
        alertCooldown = Math.max(0f, alertCooldown - dt);
        workSpeakCooldown = Math.max(0f, workSpeakCooldown - dt);
        hostilityTimer = Math.max(0f, hostilityTimer - dt);
        fearTimer = Math.max(0f, fearTimer - dt);

        // A visible threat keeps the alarm fresh; the site answers as one.
        if (threatVisible && nearestThreatDist <= THREAT_RANGE) {
            fearTimer = Math.max(fearTimer, 4f);
            announceThreat();
        }

        decisionAccum += dt;
        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            Action next = chooseAction(
                    threatVisible && nearestThreatDist <= THREAT_RANGE,
                    hostilityTimer > 0f,
                    playerNear,
                    workTimer >= WORK_BEAT_SECONDS,
                    owner.aiHasHomeSite(),
                    owner.aiRole() == AncientBuilderEntity.Role.SENTINEL);
            if (next != action) transition(next);
        }

        if (owner.aiInWater()) {
            owner.aiSwimUp(dt);
            return true;
        }

        execute(dt);
        return true;
    }

    /**
     * Utility ordering. Pure and side-effect-free so the priority ladder is
     * unit-tested directly.
     *
     * <p>Order: threat (sentry holds unless it is nearly on top of it) > warn
     * an attacker/intruder > greet/escort a visitor > do the day's work > walk
     * the rounds.</p>
     */
    static Action chooseAction(boolean threatNear, boolean playerHostile,
                               boolean playerNear, boolean workDue,
                               boolean hasSite, boolean sentinel) {
        if (threatNear && !sentinel) return Action.FLEE;
        if (playerHostile) return Action.WARN;
        if (playerNear) return Action.GREET;
        if (workDue) return Action.WORK;
        return hasSite ? Action.PATROL : Action.IDLE;
    }

    private void transition(Action next) {
        action = next;
        actionElapsed = 0f;
        usePath = false;
        path.clear();
        pathIndex = 0;
        switch (next) {
            case GREET:
                greeted = false;
                break;
            case WARN:
                emotes.play(Emote.HEAD_SHAKE);
                say(AncientBuilderLines.Topic.WARNING);
                break;
            case FLEE:
                emotes.play(rng.nextBoolean() ? Emote.COWER : Emote.WAVE_FRANTIC);
                say(AncientBuilderLines.Topic.RETREAT);
                announceThreat();
                break;
            case WORK:
                workTimer = 0f;
                break;
            case ESCORT:
                emotes.play(Emote.POINT, owner.hasHomeSite()
                        ? new Vector3f(owner.getHomeSite().x + 0.5f, 0,
                                owner.getHomeSite().z + 0.5f)
                        : null);
                break;
            case STUDY:
                emotes.play(Emote.NOD);
                say(AncientBuilderLines.Topic.STUDY);
                break;
            case PATROL:
                pickPatrolSpot();
                break;
            default:
                break;
        }
    }

    private void execute(float dt) {
        switch (action) {
            case FLEE:
                flee(dt);
                break;
            case WARN:
                warn(dt);
                break;
            case GREET:
                greet(dt);
                break;
            case ESCORT:
                escort(dt);
                break;
            case WORK:
                work(dt);
                break;
            case STUDY:
                study(dt);
                break;
            case PATROL:
                patrol(dt);
                break;
            case IDLE:
            default:
                owner.aiStandAnim(dt);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                break;
        }
    }

    // ── behaviours ──────────────────────────────────────────────────────

    /** Run for the site: retreat to the home anchor (or away from the threat). */
    private void flee(float dt) {
        if (!threatVisible && fearTimer <= 0f) {
            transition(owner.aiHasHomeSite() ? Action.PATROL : Action.IDLE);
            return;
        }
        Vector3f goal = homeGoal();
        if (goal == null) {
            // No site to fall back to: back away from the threat directly.
            if (hasThreatPos) {
                Vector3f away = new Vector3f(owner.getPosition()).sub(threatPos);
                away.y = 0;
                if (away.lengthSquared() < 1e-4f) away.set(1, 0, 0);
                away.normalize();
                goal = new Vector3f(owner.getPosition()).add(away.mul(8f));
            } else {
                owner.aiStandAnim(dt);
                return;
            }
        }
        stepWithPaths(goal, owner.aiFleeSpeed(), dt);
        gaze.lookAt(threatPos, 30f, 1.0f);
        owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
        if (rng.nextFloat() < 0.01f) emotes.play(Emote.WAVE_FRANTIC);
    }

    /** Face the intruder down and keep backing them off the site. */
    private void warn(float dt) {
        if (hostilityTimer <= 0f) {
            transition(playerNear ? Action.GREET : Action.PATROL);
            return;
        }
        Vector3f target = playerNear ? new Vector3f(playerPos) : null;
        if (target == null && hasThreatPos) target = new Vector3f(threatPos);
        owner.aiStandAnim(dt);
        if (target != null) {
            faceAndWatch(target, dt);
            // Hold a firm stand-off distance: advance if far, hold if close.
            if (owner.getPosition().distance(target) > PERSONAL_SPACE + 1f) {
                stepWithPaths(target, owner.aiWalkSpeed() * 0.7f, dt);
            }
        }
        if (rng.nextFloat() < 0.01f) {
            emotes.play(Emote.POINT, target);
            say(AncientBuilderLines.Topic.WARNING);
        }
    }

    /** Acknowledge the visitor, then start leading them inward. */
    private void greet(float dt) {
        if (!playerNear) {
            transition(owner.aiHasHomeSite() ? Action.PATROL : Action.IDLE);
            return;
        }
        faceAndWatch(playerPos, dt);
        float d = owner.getPosition().distance(playerPos);
        if (d > PERSONAL_SPACE + 1.5f) {
            stepWithPaths(playerPos, owner.aiWalkSpeed() * 0.8f, dt);
        } else {
            owner.aiStandAnim(dt);
        }
        if (!greeted && d < GREET_RANGE) {
            greeted = true;
            emotes.play(Emote.NOD);
            say(AncientBuilderLines.Topic.GREETING);
        }
        if (actionElapsed > 6f && owner.aiHasHomeSite()) {
            transition(Action.ESCORT);
        }
    }

    /** Lead the player toward the heart of the site, pointing the way. */
    private void escort(float dt) {
        if (!playerNear && actionElapsed > 3f) {
            transition(Action.PATROL);
            return;
        }
        Vector3f goal = homeGoal();
        if (goal == null) {
            transition(Action.IDLE);
            return;
        }
        stepWithPaths(goal, owner.aiWalkSpeed(), dt);
        if (rng.nextFloat() < 0.01f) {
            emotes.play(Emote.POINT, goal);
        }
        if (rng.nextFloat() < 0.006f) {
            say(AncientBuilderLines.Topic.OBSERVE);
        }
        if (actionElapsed > 18f || owner.getPosition().distance(goal) < 3f) {
            transition(Action.WORK);
        }
    }

    /** Work the trade: stand at a work spot, hammer/study, speak the craft. */
    private void work(float dt) {
        if (!playerNear && rng.nextFloat() < 0.004f) {
            transition(Action.PATROL);
            return;
        }
        updateWorkSpot();
        Vector3f stand = new Vector3f(workSpot.x + 0.5f, owner.getPosY(), workSpot.z + 0.5f);
        gaze.lookAt(new Vector3f(workSpot.x + 0.5f, workSpot.y + 0.6f, workSpot.z + 0.5f),
                10f, 0.5f);
        owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
        if (stand.distance(owner.getPosition()) > 2.2f) {
            stepWithPaths(stand, owner.aiWalkSpeed() * 0.9f, dt);
            return;
        }
        owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                workSpot.x + 0.5f - owner.getPosX(), workSpot.z + 0.5f - owner.getPosZ())));
        owner.aiStandAnim(dt);
        if (rng.nextFloat() < 0.05f) emotes.play(Emote.HAMMER);
        if (rng.nextFloat() < 0.02f) emotes.play(Emote.TUG);
        workSpeakCooldown -= dt;
        if (workSpeakCooldown <= 0f) {
            workSpeakCooldown = 12f + rng.nextFloat() * 8f;
            say(workTopic());
        }
        if (actionElapsed > 20f) {
            workTimer = 0f;
            transition(Action.PATROL);
        }
    }

    /** Read, measure, record — the scholar's and curator's day. */
    private void study(float dt) {
        Vector3f spot = homeGoal();
        if (spot == null) {
            transition(Action.WORK);
            return;
        }
        owner.aiStandAnim(dt);
        gaze.lookAt(new Vector3f(owner.getPosX() + (rng.nextFloat() - 0.5f) * 6f,
                owner.getPosY() + 2f, owner.getPosZ() + (rng.nextFloat() - 0.5f) * 6f),
                6f, 0.3f);
        owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
        if (rng.nextFloat() < 0.006f) {
            emotes.play(Emote.NOD);
            say(AncientBuilderLines.Topic.STUDY);
        }
        if (actionElapsed > 10f) transition(Action.WORK);
    }

    /** Walk the rounds: pick a spot on the site and patrol to it. */
    private void patrol(float dt) {
        if (!owner.aiHasHomeSite()) {
            if (!hasPatrolSpot) pickPatrolSpot();
        }
        if (!hasPatrolSpot) {
            transition(Action.IDLE);
            return;
        }
        Vector3f target = new Vector3f(patrolSpot);
        float d = owner.getPosition().distance(target);
        if (d < 2f || actionElapsed > PATROL_SPOT_SECONDS) {
            pickPatrolSpot();
            actionElapsed = 0f;
            if (rng.nextFloat() < 0.4f) {
                say(AncientBuilderLines.Topic.PATROL);
            }
            return;
        }
        stepWithPaths(target, owner.aiWalkSpeed() * 0.75f, dt);
        if (rng.nextFloat() < 0.003f) {
            emotes.play(Emote.POINT, target);
        }
    }

    private void faceAndWatch(Vector3f target, float dt) {
        owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                target.x - owner.getPosX(), target.z - owner.getPosZ())));
        gaze.lookAt(target, 8f, 0.7f);
        owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The site's heart, standing on the builder's own ground level. */
    private Vector3f homeGoal() {
        Vector3i home = owner.aiHomeSite();
        if (home == null) return null;
        return new Vector3f(home.x + 0.5f, owner.getPosY(), home.z + 0.5f);
    }

    /** Deterministic work spot near the site anchor (per builder). */
    private void updateWorkSpot() {
        if (workSpot.lengthSquared() > 0f) return;
        Vector3i home = owner.aiHomeSite();
        int bx = home == null ? (int) Math.floor(owner.getPosX()) : home.x;
        int bz = home == null ? (int) Math.floor(owner.getPosZ()) : home.z;
        workSpot.set(bx + (rng.nextFloat() - 0.5f) * 10f,
                owner.getPosY(),
                bz + (rng.nextFloat() - 0.5f) * 10f);
    }

    private void pickPatrolSpot() {
        Vector3i home = owner.aiHomeSite();
        if (home == null) {
            hasPatrolSpot = false;
            return;
        }
        patrolSpot.set(home.x + (rng.nextFloat() - 0.5f) * 26f,
                owner.getPosY(),
                home.z + (rng.nextFloat() - 0.5f) * 26f);
        hasPatrolSpot = true;
    }

    private AncientBuilderLines.Topic workTopic() {
        switch (owner.aiRole()) {
            case SCHOLAR:
            case CURATOR:
                return AncientBuilderLines.Topic.STUDY;
            default:
                return AncientBuilderLines.Topic.WORK;
        }
    }

    /**
     * Announce a spotted threat to everyone on the bus. Rate-limited so a site
     * full of builders does not spam the channel.
     */
    private void announceThreat() {
        if (alertCooldown > 0f || !hasThreatPos) return;
        alertCooldown = ALERT_REPEAT_SECONDS;
        StimulusBus.GLOBAL.publish(new Stimulus(
                Stimulus.Type.THREAT_SEEN, owner.id,
                new Vector3f(owner.getPosX(), owner.getPosY(), owner.getPosZ()),
                1f, null, System.currentTimeMillis()));
        say(AncientBuilderLines.Topic.ALARM);
    }

    /** Throttled A* movement with direct steering as the fallback. */
    private void stepWithPaths(Vector3f target, float speed, float dt) {
        repathAccum += dt;
        if (voxels != null && repathAccum >= 0.5f) {
            repathAccum = 0f;
            path.clear();
            pathIndex = 0;
            path.addAll(PathFinder.findPath(voxels,
                    owner.getPosX(), owner.getPosY(), owner.getPosZ(),
                    target.x, target.y, target.z));
            usePath = !path.isEmpty();
        }
        if (usePath && pathIndex < path.size()) {
            Vector3i node = path.get(pathIndex);
            Vector3f nodePos = new Vector3f(node.x + 0.5f, node.y, node.z + 0.5f);
            float dx = owner.getPosX() - nodePos.x;
            float dz = owner.getPosZ() - nodePos.z;
            if (dx * dx + dz * dz < 0.36f && Math.abs(owner.getPosY() - nodePos.y) < 1.1f) {
                pathIndex++;
                if (pathIndex >= path.size()) {
                    usePath = false;
                    owner.aiMoveToward(target, speed, dt);
                    return;
                }
                node = path.get(pathIndex);
                nodePos = new Vector3f(node.x + 0.5f, node.y, node.z + 0.5f);
            }
            owner.aiMoveToward(nodePos, speed, dt);
        } else {
            owner.aiMoveToward(target, speed, dt);
        }
    }

    /** Speak a line in the builder's voice. */
    private void say(AncientBuilderLines.Topic topic) {
        owner.aiSpeak(AncientBuilderLines.pick(topic, rng));
    }

    // ── debug / test seams ──────────────────────────────────────────────

    Action currentAction() { return action; }

    int currentPathLength() { return path.size(); }

    boolean isCongaRange(float distance) { return distance <= CONGA_RANGE; }
}
