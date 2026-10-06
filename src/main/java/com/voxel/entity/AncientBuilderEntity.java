package com.voxel.entity;

import com.voxel.World;
import com.voxel.ai.body.Emote;
import com.voxel.ai.body.EmotePlayer;
import com.voxel.ai.brain.AncientBuilderBrain;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.FixedPoint;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * An ancient builder — one of the people who raised the great works of the
 * world in the age before the extinction.
 *
 * <p>Builders are the civilization's living remnant: architects, scholars,
 * engineers, curators and sentries who still tend the sites they built. They
 * are <b>voiceless by design</b>: nothing they say reaches the audio stack.
 * Every line they speak is published caption-only through
 * {@link com.voxel.ai.speech.CaptionSpeech}, so the player reads them in the
 * live captions exactly like village speech.</p>
 *
 * <p>Movement mirrors {@link VillagerEntity}'s private helpers so the same
 * terrain, gravity and collision rules apply; decisions come from
 * {@link AncientBuilderBrain}.</p>
 */
public class AncientBuilderEntity extends Entity {

    /** What the builder did before the wipeout, and still does. */
    public enum Role {
        ARCHITECT, SCHOLAR, ENGINEER, SENTINEL, CURATOR;

        /** Caption/flavor title ("Master Architect", ...). */
        public String title() {
            switch (this) {
                case SCHOLAR: return "Ancient Scholar";
                case ENGINEER: return "Ancient Engineer";
                case SENTINEL: return "Ancient Sentry";
                case CURATOR: return "Ancient Curator";
                case ARCHITECT:
                default: return "Ancient Architect";
            }
        }
    }

    // ── Model parts (from player.json — a humanoid) ──
    private ModelPart head, body, leftArm, rightArm, leftLeg, rightLeg;

    // ── Movement ──
    private float animTime = 0.0f;
    private float bobAccum = 0.0f;
    private float moveSpeed = 1.25f;
    private float fleeSpeed = 2.35f;

    // ── Identity ──
    private Role role = Role.ARCHITECT;
    private Vector3i homeSite;
    private String siteName = "";

    private World world;

    /** Body language layer, driven by the brain. */
    private final EmotePlayer emotePlayer = new EmotePlayer();

    /** Static mirror so builders avoid each other (same pattern as villagers). */
    public static EntityManager entityManager;
    public static void setEntityManager(EntityManager em) { entityManager = em; }

    public AncientBuilderEntity(int id, Vector3f position,
                                com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        loadModel("src/main/resources/assets/minecraft/models/entity/player.json",
                textureManager);
        for (ModelPart part : parts) {
            switch (part.name) {
                case "head": head = part; break;
                case "body": body = part; break;
                case "left_arm": leftArm = part; break;
                case "right_arm": rightArm = part; break;
                case "left_leg": leftLeg = part; break;
                case "right_leg": rightLeg = part; break;
                default: break;
            }
        }
        this.brain = Brains.newBuilderBrain(this);
    }

    // ════════════════════════════════════════════════════════════════
    //  IDENTITY
    // ════════════════════════════════════════════════════════════════

    public void setWorld(World w) { this.world = w; }
    public World getWorld() { return world; }

    public void setRole(Role r) { this.role = r == null ? Role.ARCHITECT : r; }
    public Role getRole() { return role; }
    public String getRoleTitle() { return role.title(); }

    /** Anchor of the site this builder tends (null when it is a lone wanderer). */
    public void setHomeSite(Vector3i site, String name) {
        this.homeSite = site == null ? null : new Vector3i(site);
        this.siteName = name == null ? "" : name;
    }

    public boolean hasHomeSite() { return homeSite != null; }
    public Vector3i getHomeSite() {
        return homeSite == null ? null : new Vector3i(homeSite);
    }
    public String getSiteName() { return siteName; }

    /** Caption name, e.g. "Architect of the Grand Archive". */
    public String getDisplayName() {
        String base = role.title().replace("Ancient ", "");
        return siteName.isEmpty() ? base : base + " of the " + siteName;
    }

    public Vector3f eyePosition() {
        return new Vector3f(getPosX(), getPosY() + 1.5f, getPosZ());
    }

    // ════════════════════════════════════════════════════════════════
    //  UPDATE
    // ════════════════════════════════════════════════════════════════

    @Override
    public void update(float dt) {
        super.update(dt);
        if (world == null) {
            return;
        }
        snapshotPrev();
        applyGravity(dt);

        if (brain != null) {
            brain.perceive(com.voxel.ai.Senses.scan(this, world, entityManager, 26f,
                    VillagerEntity.getGlobalWorldTime()));
            brain.update(dt);
        }

        animTime += dt;
    }

    // ════════════════════════════════════════════════════════════════
    //  AI-BRAIN API
    // ════════════════════════════════════════════════════════════════

    public float aiWalkSpeed() { return moveSpeed; }
    public float aiFleeSpeed() { return fleeSpeed; }
    public World aiWorld() { return world; }
    public Role aiRole() { return role; }
    public String aiDisplayName() { return getDisplayName(); }
    public Vector3i aiHomeSite() { return getHomeSite(); }
    public boolean aiHasHomeSite() { return homeSite != null; }

    /**
     * Speak a line. There is no audio behind this — the line is shown in the
     * live captions only.
     */
    public boolean aiSpeak(String line) {
        return com.voxel.ai.speech.CaptionSpeech.say(id, getDisplayName(), line);
    }

    public boolean aiSpeak(String line, float durationSeconds) {
        return com.voxel.ai.speech.CaptionSpeech.say(id, getDisplayName(), line,
                durationSeconds);
    }

    public boolean aiInWater() {
        int fx = (int) Math.floor(getPosX());
        int fy = (int) Math.floor(getPosY() + 0.5f);
        int fz = (int) Math.floor(getPosZ());
        return world != null && world.getVoxel(fx, fy, fz) == 15;
    }

    public void aiSwimUp(float dt) {
        if (!aiInWater()) return;
        tryMove(0, 1.5f * dt, 0);
        updateSwimAnimation(dt);
        applyEmoteOverlay();
    }

    public void aiMoveToward(Vector3f target, float speed, float dt) {
        moveToward(target, dt, speed);
        updateWalkAnimation(dt);
        applyEmoteOverlay();
    }

    public void aiFaceYaw(float yawDeg) {
        rotation.y = yawDeg;
        applyEmoteOverlay();
    }

    public void aiStandAnim(float dt) {
        updateIdleAnimation(dt);
        applyEmoteOverlay();
    }

    /** Applies resolved gaze angles {pitchDeg, yawOffsetDeg} to the head. */
    public void aiAimHead(float[] gazeAngles) {
        if (head == null || gazeAngles == null || gazeAngles.length < 2) return;
        head.rotation.set(-gazeAngles[0], gazeAngles[1], 0);
    }

    public boolean aiPlayEmote(Emote emote, Vector3f pointAt) {
        return emotePlayer.play(emote, pointAt);
    }

    public EmotePlayer aiEmotePlayer() { return emotePlayer; }

    // ════════════════════════════════════════════════════════════════
    //  MOVEMENT (same rules as villagers)
    // ════════════════════════════════════════════════════════════════

    private void moveToward(Vector3f target, float dt, float speed) {
        Vector3f dir = new Vector3f(target).sub(getPosition());
        float len = dir.length();
        if (len > 0.35f) {
            dir.normalize().mul(speed * dt);
            tryMove(dir.x, dir.y, dir.z);
            if (new Vector2f(dir.x, dir.z).length() > 0.001f) {
                rotation.y = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));
            }
        }
    }

    private void tryMove(float dx, float dy, float dz) {
        float cx = getPosX(), cy = getPosY(), cz = getPosZ();
        if (canOccupy(cx + dx, cy + dy, cz + dz)
                && !isCollidingWithOtherEntities(cx + dx, cy + dy, cz + dz)) {
            addPosition(dx, dy, dz);
            return;
        }
        if (canOccupy(cx + dx, cy + 1.0f, cz + dz)
                && !isCollidingWithOtherEntities(cx + dx, cy + 1.0f, cz + dz)) {
            addPosition(dx, 1.0f, dz);
            return;
        }
        if (canOccupy(cx + dx, cy - 1.0f, cz + dz)
                && !isCollidingWithOtherEntities(cx + dx, cy - 1.0f, cz + dz)) {
            addPosition(dx, -1.0f, dz);
            return;
        }
        if (canOccupy(cx + dx, cy, cz) && !isCollidingWithOtherEntities(cx + dx, cy, cz)) {
            posX += FixedPoint.fromFloat(dx);
        }
        if (canOccupy(cx, cy, cz + dz) && !isCollidingWithOtherEntities(cx, cy, cz + dz)) {
            posZ += FixedPoint.fromFloat(dz);
        }
    }

    private boolean canOccupy(float x, float y, float z) {
        if (world == null) return true;
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y + 0.1f);
        int iz = (int) Math.floor(z);
        return isWalkable(ix, iy, iz);
    }

    private boolean isWalkable(int x, int y, int z) {
        if (world.getVoxel(x, y - 1, z) == 0) return false;
        if (world.getVoxel(x, y, z) != 0) return false;
        if (world.getVoxel(x, y + 1, z) != 0) return false;
        return true;
    }

    private boolean isCollidingWithOtherEntities(float x, float y, float z) {
        if (entityManager == null) return false;
        Vector3f target = new Vector3f(x, y, z);
        for (int i = 0; i < entityManager.getEntityCount(); i++) {
            Entity other = entityManager.getEntity(i);
            if (other == null || other == this) continue;
            if (other instanceof EnemyEntity && ((EnemyEntity) other).isDead()) continue;
            if (target.distanceSquared(other.getPosition()) < 0.36f) return true;
        }
        return false;
    }

    private void applyGravity(float dt) {
        int fx = (int) Math.floor(getPosX());
        int fy = (int) Math.floor(getPosY() - 0.01f);
        int fz = (int) Math.floor(getPosZ());
        if (world.getVoxel(fx, fy, fz) == 0) {
            tryMove(0, -9.8f * dt, 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ANIMATION
    // ════════════════════════════════════════════════════════════════

    private void updateIdleAnimation(float dt) {
        float breathe = (float) Math.sin(animTime * 1.5f) * 0.03f;
        posY += FixedPoint.fromFloat(breathe - bobAccum);
        bobAccum = breathe;
        if (leftArm != null) leftArm.rotation.set(0, 0, 0);
        if (rightArm != null) rightArm.rotation.set(0, 0, 0);
        if (leftLeg != null) leftLeg.rotation.x *= 0.8f;
        if (rightLeg != null) rightLeg.rotation.x *= 0.8f;
        if (head != null) {
            head.rotation.set(
                    (float) Math.sin(animTime * 0.7f) * 2,
                    (float) Math.sin(animTime * 0.5f + 1.0f) * 3,
                    0);
        }
        rotation.z = (float) Math.sin(animTime * 0.5f) * 0.5f;
    }

    private void updateWalkAnimation(float dt) {
        float walkSpeed = 7.5f;
        float swing = (float) Math.sin(animTime * walkSpeed) * 30.0f;
        float bob = (float) Math.abs(Math.sin(animTime * walkSpeed)) * 0.1f;
        posY += FixedPoint.fromFloat(bob - bobAccum);
        bobAccum = bob;
        if (leftLeg != null) leftLeg.rotation.x = -swing;
        if (rightLeg != null) rightLeg.rotation.x = swing;
        if (leftArm != null) leftArm.rotation.set(swing, 0, 0);
        if (rightArm != null) rightArm.rotation.set(-swing, 0, 0);
        rotation.z = (float) Math.sin(animTime * walkSpeed) * 3.0f;
        if (head != null) head.rotation.set(0, 0, 0);
    }

    private void updateSwimAnimation(float dt) {
        float swimWave = (float) Math.sin(animTime * 6.0f) * 20.0f;
        if (leftArm != null) leftArm.rotation.set(swimWave - 80, 0, 10);
        if (rightArm != null) rightArm.rotation.set(-swimWave - 80, 0, -10);
        if (leftLeg != null) leftLeg.rotation.x = swimWave * 0.3f;
        if (rightLeg != null) rightLeg.rotation.x = -swimWave * 0.3f;
        if (head != null) head.rotation.set(0, 0, 0);
    }

    private void applyEmoteOverlay() {
        if (!emotePlayer.isActive()) return;

        float raise = emotePlayer.armRaise();
        if (raise > 0f) {
            if (leftArm != null) leftArm.rotation.set(-110f * raise, 0, 18f * raise);
            if (rightArm != null) rightArm.rotation.set(-110f * raise, 0, -18f * raise);
        }

        float cower = emotePlayer.cowerAmount();
        if (cower > 0f && head != null) head.rotation.x += cower * 22f;

        float nod = emotePlayer.nodPhase();
        if (nod != 0f && head != null) head.rotation.x += nod * 12f;
        float shake = emotePlayer.shakePhase();
        if (shake != 0f && head != null) head.rotation.y += shake * 14f;

        float reach = emotePlayer.armReachSwing();
        if (reach > 0f && rightArm != null) {
            if (emotePlayer.isPlaying(Emote.POINT)) {
                rightArm.rotation.set(-85f, 0, 0);
                if (emotePlayer.hasPointTarget()) {
                    Vector3f t = emotePlayer.pointTarget();
                    float relYaw = com.voxel.ai.body.GazeController.wrap180(
                            (float) Math.toDegrees(Math.atan2(t.x - getPosX(), t.z - getPosZ()))
                                    - rotation.y);
                    relYaw = Math.max(-60f, Math.min(60f, relYaw));
                    rightArm.rotation.y = relYaw;
                }
            } else {
                rightArm.rotation.set(-40f - reach * 55f, 0, 0);
            }
        }

        if (emotePlayer.consumeHop()) addPosition(0, 0.22f, 0);
    }
}
