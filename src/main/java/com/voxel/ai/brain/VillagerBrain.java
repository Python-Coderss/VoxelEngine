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
import com.voxel.audio.DialogueDirector;
import com.voxel.ai.speech.VillagerSpeech;
import com.voxel.entity.Entity;
import villager.voice.ClipScript;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.VillagerEntity;
import com.voxel.game.VillagerProfessions;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The villager brain: utility-ordered decisions (panic > shelter > work >
 * socialize > wander > idle) driven purely by {@link Senses} and the
 * {@link StimulusBus}, with the shared dumb-human {@link ComedyMind} layer on
 * top. Villagers get distracted mid-task, forget what they were doing, strut
 * up to danger before screaming, argue about nothing, build crooked walls and
 * proudly celebrate them, and follow each other in conga lines while the
 * village-wide {@link Chaos} meter escalates the whole mess.
 *
 * <p>Silent communication uses POINT gestures; fear spreads by earshot through
 * published THREAT_SEEN stimuli. All body language routes through the entity's
 * {@link EmotePlayer}; all head aiming through {@link GazeController}.</p>
 */
public final class VillagerBrain implements MobBrain, StimulusBus.Listener {

    enum Action {
        IDLE, WANDER, PANIC_FLEE, ALERT_LOOK, GO_INDOORS, SOCIALIZE, GO_BUILD,
        /** Working the villager's job site (farm, desk, stall, workbench). */
        WORK,
        // ── dumb-human beats ──
        /** Struts toward danger acting tough (before the screaming starts). */
        STRUT,
        /** Abandons the task to investigate something shiny. */
        DISTRACTED,
        /** Stops dead: "Wait. What was I doing?" */
        FORGOT,
        /** Stands admiring nothing in particular. */
        ADMIRE,
        /** Loiters after the player like a lost tourist. */
        FOLLOW_PLAYER,
        /** Nose-to-nose pointless argument with a neighbor. */
        ARGUE,
        /** Enthusiastic wave at the wrong person. */
        WRONG_WAVE
    }

    private static final float DECISION_INTERVAL = 0.25f;
    /** Seconds between productive actions once a villager is at its job site. */
    private static final float WORK_BEAT_SECONDS = 4f;
    /** Radius (blocks) around a job site a profession works on. */
    private static final int JOB_SITE_RADIUS = 4;
    /** How often a jobless villager re-scans for a nearby job site. */
    private static final float JOB_SCAN_INTERVAL = 5f;
    private static final float THREAT_SIGHT_RANGE = 16f;
    private static final float EARSHOT_RANGE = 22f;
    private static final long SCREAM_COOLDOWN_MILLIS = 9000L;

    private final VillagerEntity owner;
    private final Random rng;
    private final GazeController gaze = new GazeController();
    private final EmotePlayer emotes;
    private final ComedyMind mind;

    private float comedyHold;
    private float strutTime;
    private float strutLimit;
    private float buildWorkTime;
    private float workTimer;
    private float jobScanCooldown;
    private boolean congaFollowing;
    private final Vector3f distractPoint = new Vector3f();
    private boolean hasDistractPoint;
    private final Vector3f admirePoint = new Vector3f();
    private final Vector3f playerPos = new Vector3f();
    private boolean hasPlayer;

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
        // Drive the entity's own body-language layer so emotes reach the model.
        this.emotes = owner.aiEmotePlayer();
        this.mind = ComedyMind.forEntity(owner.id);
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
            return;
        }
        if (stimulus.type == Stimulus.Type.SPEECH_HEARD
                && stimulus.sourceId != owner.id) {
            // Gossip: a frightened neighbor's shout spreads mood through the
            // crowd. Weaker than seeing the threat yourself.
            if (stimulus.position.distanceSquared(owner.getPosition())
                    > EARSHOT_RANGE * EARSHOT_RANGE) {
                return;
            }
            DialogueDirector.onGossip(owner.id, stimulus.severity);
            if (stimulus.severity >= 0.5f) {
                adoptFear(stimulus.position, stimulus.severity * 2.0f);
            }
        }
    }

    /** Fear adopted from someone else's alarm: silent-alert first, flee if it persists. */
    private void adoptFear(Vector3f sourcePosition, float strength) {
        // Escalating chaos: panic spreads louder and sticks longer as the
        // village works itself into a state.
        strength *= 1f + Chaos.level() * 0.8f;
        if (!hasThreatPos || threatPos.distanceSquared(sourcePosition) < 4f
                || nearestThreatDist == Float.MAX_VALUE) {
            threatPos.set(sourcePosition);
            hasThreatPos = true;
        }
        panicRemaining = Math.max(panicRemaining, strength * 0.5f);
        alertRemaining = Math.max(alertRemaining, strength);
        if (action != Action.PANIC_FLEE && action != Action.STRUT) {
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
        hasPlayer = false;

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
            } else if (e instanceof com.voxel.entity.PlayerEntity) {
                float d = (float) Math.sqrt(v.distanceSquared);
                if (d < 14f) {
                    hasPlayer = true;
                    playerPos.set(e.getPosition());
                }
            }
        }

        float tod = senses.timeOfDayMinutes % 1440f;
        nightTime = tod < 360f || tod > 1080f;
        inWater = owner.aiInWater();

        if (threatVisible) {
            panicRemaining = Math.max(panicRemaining, 4f);
            // Feed the dialogue mood: a visible threat makes the villager
            // talk scared until the fear decays again.
            DialogueDirector.onThreatSeen(owner.id,
                    1f - nearestThreatDist / THREAT_SIGHT_RANGE);
        }
    }

    @Override
    public boolean update(float dt) {
        DialogueDirector.tick(owner.id, dt);
        gaze.tick(dt);
        emotes.update(dt);
        mind.tick(dt);
        comedyHold = Math.max(0f, comedyHold - dt);
        panicRemaining = Math.max(0f, panicRemaining - dt);
        alertRemaining = Math.max(0f, alertRemaining - dt);
        socialCooldown = Math.max(0f, socialCooldown - dt);
        jobScanCooldown = Math.max(0f, jobScanCooldown - dt);
        actionElapsed += dt;

        // The threat episode is over: the next one may earn fresh bravado.
        if (!threatVisible && panicRemaining <= 0f && alertRemaining <= 0f
                && mind.isSpooked()) {
            mind.resetAttention();
        }

        decisionAccum += dt;
        if (decisionAccum >= DECISION_INTERVAL) {
            decisionAccum = 0f;
            Action next = chooseAction(
                    threatVisible && nearestThreatDist <= THREAT_SIGHT_RANGE,
                    panicRemaining, alertRemaining,
                    nightTime, inWater,
                    owner.aiHasBuildWork(),
                    hasFriend && nearestFriendDist < 6f && socialCooldown <= 0f);
            // Utility order: panic > shelter > build > work > social > wander.
            // Work only redirects the low-priority plans, so a villager never
            // abandons a panic or a queued build to go stand at a job site.
            if (next == Action.WANDER || next == Action.SOCIALIZE || next == Action.IDLE) {
                if (jobSiteReady()) {
                    next = Action.WORK;
                }
            }
            // Confident-then-cowardly: strut at the danger first — the strut
            // resolves into PANIC_FLEE on its own once it gets too close.
            if (next == Action.PANIC_FLEE && action != Action.PANIC_FLEE) {
                if (action == Action.STRUT) {
                    next = Action.STRUT;
                } else if (threatVisible && mind.shouldBluff(rng, Chaos.level())) {
                    next = Action.STRUT;
                }
            } else if (next != Action.PANIC_FLEE && next != Action.GO_INDOORS) {
                if (isComedyAction(action) && comedyHold > 0f) {
                    next = action; // busy being dumb; danger already ruled out
                } else {
                    next = applyComedy(next);
                }
            }
            if (next != action) transition(next);
        }

        if (inWater) {
            owner.aiSwimUp(dt);
            return true;
        }

        execute(dt);
        return true;
    }

    /**
     * Whether this villager has a job to go do right now. A jobless villager
     * (a nitwit, or one whose work site was broken) adopts the trade of the
     * nearest job site instead — which is how a village's professions follow
     * from the blocks standing in it.
     */
    private boolean jobSiteReady() {
        if (nightTime) return false;
        if (owner.aiHasJobSite() && !owner.aiWorkstationStillValid()) {
            // Somebody broke the workbench: clock off and look for another.
            owner.clearWorkstation();
            jobScanCooldown = 0f;
        }
        if (owner.aiHasJobSite()) return true;
        if (jobScanCooldown > 0f) return false;
        jobScanCooldown = JOB_SCAN_INTERVAL;
        return owner.aiAdoptJobSite() && owner.aiHasJobSite();
    }

    /** One productive action at the job site: earn a living, earn some XP. */
    private void performJobBeat(Vector3i site) {
        switch (owner.aiProfession()) {
            case FARMER: {
                int harvested = owner.aiHarvestRipeWheat(site, JOB_SITE_RADIUS);
                if (harvested > 0) {
                    emotes.play(Emote.TUG);
                    say(ClipScript.Topic.SHINY);
                    owner.aiAddProfessionXp(VillagerProfessions.XP_HARVEST * harvested);
                    break;
                }
                int planted = owner.aiPlantWheat(site, JOB_SITE_RADIUS);
                if (planted > 0) {
                    emotes.play(Emote.NOD);
                    owner.aiAddProfessionXp(VillagerProfessions.XP_PLANT * Math.min(planted, 4));
                    break;
                }
                // Nothing to plant or pick: still on the clock (tending).
                owner.aiAddProfessionXp(VillagerProfessions.XP_TEND);
                if (rng.nextFloat() < 0.25f) say(ClipScript.Topic.SMALLTALK);
                break;
            }
            case NEWS_ANCHOR:
                emotes.play(Emote.POINT,
                        new Vector3f(site.x + 0.5f, site.y + 0.8f, site.z + 0.5f));
                say(ClipScript.Topic.GOSSIP);
                owner.aiAddProfessionXp(VillagerProfessions.XP_BROADCAST);
                break;
            case SHOPKEEPER:
                emotes.play(Emote.NOD);
                if (rng.nextFloat() < 0.4f) say(ClipScript.Topic.SMALLTALK);
                owner.aiAddProfessionXp(VillagerProfessions.XP_TEND);
                break;
            case BUILDER:
                // Building itself is GO_BUILD; standing at the workbench is
                // still workshop time.
                emotes.play(Emote.HAMMER);
                owner.aiAddProfessionXp(VillagerProfessions.XP_BUILD);
                break;
            default:
                break;
        }
    }

    private static boolean isComedyAction(Action action) {
        return action == Action.STRUT || action == Action.DISTRACTED
                || action == Action.FORGOT || action == Action.ADMIRE
                || action == Action.FOLLOW_PLAYER || action == Action.ARGUE
                || action == Action.WRONG_WAVE;
    }

    /**
     * Chance a mundane plan gets replaced by a dumb-human beat. Escalating
     * chaos makes comedy more frequent; the returned action holds for a beat
     * via {@link #comedyHold}.
     */
    private Action applyComedy(Action planned) {
        boolean friendNearby = hasFriend && nearestFriendDist < 7f;
        ComedyMind.Event event = mind.poll(DECISION_INTERVAL, Chaos.level(), rng,
                friendNearby, hasPlayer);
        switch (event) {
            case ARGUE:
                return friendNearby ? Action.ARGUE : planned;
            case WRONG_WAVE:
                return friendNearby ? Action.WRONG_WAVE : planned;
            case FOLLOW_PLAYER:
                return hasPlayer ? Action.FOLLOW_PLAYER : planned;
            case DISTRACT:
                return Action.DISTRACTED;
            case FORGET:
                return Action.FORGOT;
            case ADMIRE:
                return Action.ADMIRE;
            case NONE:
            default:
                return planned;
        }
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
                // The crowd runs as one: some villagers just follow whoever is
                // ahead instead of choosing their own escape direction.
                congaFollowing = hasFriend && nearestFriendDist < 10f
                        && mind.herdFollow(rng, Chaos.level());
                if (rng.nextFloat() < 0.3f) {
                    emotes.play(Emote.COWER);
                } else {
                    emotes.play(Emote.WAVE_FRANTIC);
                }
                Chaos.raise(0.07f);
                broadcastAlarm();
                break;
            case ALERT_LOOK:
                emotes.play(Emote.COWER);
                break;
            case SOCIALIZE:
                emotes.play(Emote.NOD);
                say(ClipScript.Topic.GOSSIP);
                break;
            case STRUT:
                strutTime = 0f;
                strutLimit = mind.bluffSeconds(rng);
                emotes.play(Emote.POINT, hasThreatPos ? new Vector3f(threatPos) : null);
                say(ClipScript.Topic.BLUFF);
                break;
            case DISTRACTED:
                comedyHold = mind.comedyHoldSeconds(rng);
                hasDistractPoint = false;
                emotes.play(Emote.POINT, null);
                say(ClipScript.Topic.SHINY);
                break;
            case FORGOT:
                comedyHold = Math.max(1.2f, mind.comedyHoldSeconds(rng) * 0.7f);
                hasWanderTarget = false; // the old goal is part of the forgetting
                emotes.play(Emote.HEAD_SHAKE);
                say(ClipScript.Topic.FORGET);
                break;
            case ADMIRE:
                comedyHold = mind.comedyHoldSeconds(rng);
                admirePoint.set(
                        owner.getPosX() + (rng.nextFloat() - 0.5f) * 8f,
                        owner.getPosY() + 1.2f + rng.nextFloat() * 1.5f,
                        owner.getPosZ() + (rng.nextFloat() - 0.5f) * 8f);
                emotes.play(Emote.NOD);
                say(ClipScript.Topic.ADMIRE);
                break;
            case FOLLOW_PLAYER:
                comedyHold = 5f + rng.nextFloat() * 5f;
                emotes.play(Emote.WAVE_FRANTIC);
                say(ClipScript.Topic.FOLLOW);
                break;
            case ARGUE:
                comedyHold = 2.5f + rng.nextFloat() * 2.5f;
                emotes.play(Emote.HEAD_SHAKE);
                say(ClipScript.Topic.ARGUE);
                Chaos.raise(0.03f);
                break;
            case WRONG_WAVE:
                comedyHold = 1.8f + rng.nextFloat() * 1.6f;
                emotes.play(Emote.WAVE_FRANTIC);
                say(ClipScript.Topic.WRONG_WAVE);
                break;
            case GO_BUILD:
                buildWorkTime = 0f;
                break;
            case WORK:
                workTimer = 0f;
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
            String scream = say(ClipScript.Topic.PANIC);
            if (scream != null) {
                screamedThisPanic = true;
                lastScreamMillis = now;
                // The shout itself carries the alarm: gossip listeners adopt
                // fear toward the threat without seeing it.
                StimulusBus.GLOBAL.publish(new Stimulus(
                        Stimulus.Type.SPEECH_HEARD, owner.id,
                        new Vector3f(threatPos), 0.8f, scream,
                        System.currentTimeMillis()));
            }
        }
    }

    private void execute(float dt) {
        switch (action) {
            case PANIC_FLEE:
                fleeTime += dt;
                // Conga line: follow the villager ahead like a duckling instead
                // of choosing an escape direction (the classic crowd pile-up).
                if (congaFollowing && hasFriend && nearestFriendDist < 14f) {
                    owner.aiMoveToward(friendPos, owner.aiFleeSpeed(), dt);
                    owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                            friendPos.x - owner.getPosX(), friendPos.z - owner.getPosZ())));
                    if (emotes.isActive() && !emotes.isPlaying(Emote.WAVE_FRANTIC)
                            && rng.nextFloat() < 0.02f) {
                        emotes.play(Emote.WAVE_FRANTIC);
                    }
                    break;
                }
                congaFollowing = false;
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
                    // Job incompetence: finishing a step is a coin toss between
                    // proud success, painful failure, and forgetting the job.
                    buildWorkTime += dt;
                    if (buildWorkTime >= 5f) {
                        buildWorkTime = 0f;
                        float roll = rng.nextFloat() - mind.incompetence * 0.35f;
                        if (roll > 0.45f) {
                            owner.aiCompleteBuildTarget();
                            emotes.play(Emote.JUMP_CHEER);
                            say(ClipScript.Topic.BUILD_PROUD);
                        } else if (roll > 0.18f) {
                            emotes.play(Emote.TUG);
                            say(ClipScript.Topic.BUILD_OOPS);
                            Chaos.raise(0.02f);
                        } else {
                            transition(Action.FORGOT);
                        }
                    }
                }
                break;

            case WORK: {
                Vector3i site = owner.aiWorkstationPos();
                if (site == null || !owner.aiWorkstationStillValid()) {
                    owner.clearWorkstation();
                    transition(Action.WANDER);
                    break;
                }
                Vector3f workPoint = new Vector3f(site.x + 0.5f, site.y + 0.6f, site.z + 0.5f);
                Vector3f workStand = new Vector3f(site.x + 0.5f, owner.getPosY(), site.z + 0.5f);
                gaze.lookAt(workPoint, 10f, 0.5f);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                if (workStand.distance(owner.getPosition()) > 2.2f) {
                    owner.aiMoveToward(workStand, owner.aiWalkSpeed() * 0.9f, dt);
                    break;
                }
                owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                        workPoint.x - owner.getPosX(), workPoint.z - owner.getPosZ())));
                workTimer += dt;
                if (workTimer < WORK_BEAT_SECONDS) {
                    owner.aiStandAnim(dt);
                    break;
                }
                workTimer = 0f;
                performJobBeat(site);
                break;
            }

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

            case STRUT: {
                strutTime += dt;
                if (!threatVisible && panicRemaining <= 0f) {
                    transition(Action.WANDER);
                    break;
                }
                // The cowardly payoff: close enough (or brave for long enough)
                // and the whole act collapses into screaming.
                if ((threatVisible && nearestThreatDist < mind.cowardDistance())
                        || strutTime >= strutLimit) {
                    mind.spook();
                    panicRemaining = Math.max(panicRemaining, 4.5f);
                    transition(Action.PANIC_FLEE);
                    say(ClipScript.Topic.CHICKEN_OUT);
                    break;
                }
                if (hasThreatPos) {
                    // Strutting = walking, not running. Deliberately.
                    owner.aiMoveToward(threatPos, owner.aiWalkSpeed() * 0.85f, dt);
                    owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                            threatPos.x - owner.getPosX(), threatPos.z - owner.getPosZ())));
                    gaze.lookAt(threatPos, 30f, 1.0f);
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                    if (rng.nextFloat() < 0.01f) {
                        emotes.play(Emote.POINT, new Vector3f(threatPos));
                    }
                } else {
                    owner.aiStandAnim(dt);
                }
                break;
            }

            case DISTRACTED:
                if (!hasDistractPoint) {
                    // The shiny thing: a nearby entity, or just some air.
                    if (hasFriend && rng.nextBoolean()) {
                        distractPoint.set(friendPos);
                    } else {
                        distractPoint.set(
                                owner.getPosX() + (rng.nextFloat() - 0.5f) * 10f,
                                owner.getPosY() + 1.3f,
                                owner.getPosZ() + (rng.nextFloat() - 0.5f) * 10f);
                    }
                    hasDistractPoint = true;
                }
                if (owner.getPosition().distance(distractPoint) > 2.5f) {
                    owner.aiMoveToward(distractPoint, owner.aiWalkSpeed() * 0.8f, dt);
                } else {
                    owner.aiStandAnim(dt);
                }
                owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                        distractPoint.x - owner.getPosX(), distractPoint.z - owner.getPosZ())));
                gaze.lookAt(distractPoint, 10f, 0.8f);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                break;

            case FORGOT:
                owner.aiStandAnim(dt);
                // Empty saccades: the classic thousand-yard stare.
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                if (rng.nextFloat() < 0.004f) {
                    emotes.play(Emote.HEAD_SHAKE);
                }
                break;

            case ADMIRE:
                owner.aiStandAnim(dt);
                gaze.lookAt(admirePoint, 8f, 0.35f);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                        admirePoint.x - owner.getPosX(), admirePoint.z - owner.getPosZ())));
                if (rng.nextFloat() < 0.006f) {
                    emotes.play(Emote.NOD);
                }
                break;

            case FOLLOW_PLAYER:
                if (!hasPlayer) {
                    transition(Action.IDLE);
                    break;
                }
                if (owner.getPosition().distance(playerPos) > 2.2f) {
                    owner.aiMoveToward(playerPos, owner.aiWalkSpeed() * 0.6f, dt);
                } else {
                    owner.aiStandAnim(dt);
                    owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                            playerPos.x - owner.getPosX(), playerPos.z - owner.getPosZ())));
                }
                gaze.lookAt(playerPos, 8f, 0.7f);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                break;

            case ARGUE:
                if (!hasFriend) {
                    socialCooldown = 20f;
                    transition(Action.IDLE);
                    break;
                }
                owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                        friendPos.x - owner.getPosX(), friendPos.z - owner.getPosZ())));
                if (nearestFriendDist > 1.8f) {
                    owner.aiMoveToward(friendPos, owner.aiWalkSpeed() * 0.5f, dt);
                } else {
                    owner.aiStandAnim(dt);
                    gaze.lookAt(friendPos, 5f, 0.6f);
                    owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                    if (rng.nextFloat() < 0.02f) {
                        emotes.play(rng.nextBoolean() ? Emote.HEAD_SHAKE : Emote.NOD);
                        say(ClipScript.Topic.ARGUE);
                    }
                }
                break;

            case WRONG_WAVE:
                owner.aiStandAnim(dt);
                // Enthusiastically greeting someone who is definitely not the
                // friend standing right there.
                Vector3f wrongWay = new Vector3f(
                        owner.getPosX() + (rng.nextFloat() - 0.5f) * 12f,
                        owner.getPosY() + 1.5f,
                        owner.getPosZ() + (rng.nextFloat() - 0.5f) * 12f);
                gaze.lookAt(wrongWay, 6f, 0.5f);
                owner.aiAimHead(gaze.resolve(owner.eyePosition(), owner.rotation.y, dt));
                owner.aiFaceYaw((float) Math.toDegrees(Math.atan2(
                        wrongWay.x - owner.getPosX(), wrongWay.z - owner.getPosZ())));
                break;

            case WANDER:
                if (!hasWanderTarget
                        || wanderTarget.distanceSquared(owner.getPosition()) < 1f
                        || actionElapsed > 14f) {
                    pickWanderTarget();
                    actionElapsed = 0f;
                }
                // Occasional trip over absolutely nothing.
                if (rng.nextFloat() < 0.0015f * (1f + Chaos.level() * 2f)) {
                    emotes.play(Emote.NOD);
                    say(ClipScript.Topic.TRIP);
                    Chaos.raise(0.01f);
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
                if (rng.nextFloat() < 0.0015f) say(ClipScript.Topic.SMALLTALK);
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

    /**
     * Speak the recorded clip line that fits the moment. Only real clip
     * transcripts are ever played or captioned.
     *
     * @return the spoken clip transcript, or null when silent
     */
    private String say(ClipScript.Topic topic) {
        return VillagerSpeech.sayTopic(owner.id, owner.aiDisplayName(), topic,
                new villager.voice.SpeechOptions(
                        1.0, 0.0, 1.0, 0.0,
                        ClipScript.emotionFor(topic), 0.0, false));
    }
}
