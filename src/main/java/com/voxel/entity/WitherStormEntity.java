package com.voxel.entity;

import com.voxel.Player;
import com.voxel.ai.brain.Brains;
import com.voxel.utils.TextureManager;
import org.joml.Vector3f;

/**
 * The Wither Storm — Minecraft: Story Mode's sky-darkening fusion of three
 * withers around a Command Block core.
 *
 * <p>Bespoke model ({@code models/entity/wither_storm.json}) and skin
 * ({@code textures/entity/wither_storm/*}); the behaviour lives in
 * {@link com.voxel.ai.brain.WitherStormBrain}, which makes it stalk, hurl a
 * skull volley, and call wither skeletons up from the ground.</p>
 */
public class WitherStormEntity extends EnemyEntity {

    public static final float BOSS_HEALTH = 400.0f;
    /** Damage each summoned minion is worth when it reaches the player is
     *  handled by its own entity; this is the boss's own melee sweep. */

    private final EntityManager entityManager;
    private ModelPart headCenter, headLeft, headRight;
    private ModelPart[] tentacles = new ModelPart[0];
    private boolean dropped;

    public WitherStormEntity(int id, Vector3f position, TextureManager textureManager,
                             Player player, EntityManager entityManager) {
        super(id, position, textureManager, player);
        this.entityManager = entityManager;
        loadModel("src/main/resources/assets/minecraft/models/entity/wither_storm.json",
                textureManager);
        this.maxHealth = BOSS_HEALTH;
        this.health = BOSS_HEALTH;
        // The Storm is enormous: a generous pick box so clicks connect.
        this.pickWidth = 3.5f;
        this.pickHeight = 3.8f;
        this.dimension = com.voxel.world.DimensionType.OVERWORLD;

        java.util.List<ModelPart> tails = new java.util.ArrayList<ModelPart>();
        for (ModelPart p : parts) {
            if (p.name.equals("head_center")) headCenter = p;
            else if (p.name.equals("head_left")) headLeft = p;
            else if (p.name.equals("head_right")) headRight = p;
            else if (p.name.startsWith("tentacle")) tails.add(p);
        }
        this.tentacles = tails.toArray(new ModelPart[0]);
        this.brain = Brains.newWitherStormBrain(this);
    }

    /** Spawn 1–3 wither skeletons around the body; the brain decides when. */
    public int summonMinions(int count) {
        if (entityManager == null) return 0;
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            float ang = (float) (Math.PI * 2.0 * i / Math.max(1, count) + id);
            float x = getPosX() + (float) Math.cos(ang) * 4f;
            float z = getPosZ() + (float) Math.sin(ang) * 4f;
            GenericMobEntity skeleton = new GenericMobEntity(
                    minionId(), new Vector3f(x, getPosY(), z), modelTextureManager, player,
                    "src/main/resources/assets/minecraft/models/entity/wither_skeleton.json");
            skeleton.setWorld(world);
            skeleton.dimension = dimension;
            entityManager.addEntity(skeleton);
            spawned++;
        }
        return spawned;
    }

    /** Hurl one wither skull toward the brain-supplied heading (no-op headless). */
    public boolean hurlSkull(float headingYaw, float pitch) {
        if (entityManager == null) return false;
        float yawRad = (float) Math.toRadians(headingYaw);
        float pitchRad = (float) Math.toRadians(pitch);
        Vector3f mouth = new Vector3f(getPosX(),
                getPosY() + pickHeight * 0.85f, getPosZ());
        float flat = (float) Math.cos(pitchRad);
        Vector3f heading = new Vector3f((float) Math.cos(yawRad) * flat,
                (float) Math.sin(pitchRad),
                (float) Math.sin(yawRad) * flat);
        WitherSkullEntity skull = new WitherSkullEntity(minionId(), mouth, heading, null);
        skull.world = this.world;
        skull.dimension = this.dimension;
        entityManager.addEntity(skull);
        return true;
    }

    private int minionId() {
        return 92_000 + (entityManager == null ? 0 : entityManager.getEntityCount()) + id;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        // Three heads roil independently; the tentacles trail underneath.
        float t = animTime;
        wobble(headCenter, (float) Math.sin(t * 1.7f) * 9f, (float) Math.sin(t * 1.1f) * 7f);
        wobble(headLeft, (float) Math.sin(t * 1.9f + 1.2f) * 12f,
                (float) Math.sin(t * 1.4f) * 8f);
        wobble(headRight, (float) Math.sin(t * 1.6f + 2.4f) * 12f,
                (float) Math.sin(t * 1.3f + 0.6f) * 8f);
        for (int i = 0; i < tentacles.length; i++) {
            ModelPart tail = tentacles[i];
            tail.rotation.x = 16f + (float) Math.sin(t * 2.4f + i * 1.5f) * 14f;
            tail.rotation.z = (float) Math.sin(t * 1.8f + i) * 10f;
        }
    }

    private static void wobble(ModelPart part, float yaw, float pitch) {
        if (part == null) return;
        part.rotation.y = yaw;
        part.rotation.x = pitch;
    }

    /** One-shot death drops (Main calls this once, like the Wither star). */
    public boolean dropLoot(com.voxel.game.DroppedItemManager droppedItemManager) {
        if (droppedItemManager == null) return false;
        int x = (int) Math.floor(getPosX());
        int y = (int) Math.floor(getPosY()) + 1;
        int z = (int) Math.floor(getPosZ());
        droppedItemManager.spawn("nether_star", 1, x, y, z);
        droppedItemManager.spawn("diamond", 4, x, y, z);
        droppedItemManager.spawn("gold_block", 3, x, y, z);
        return true;
    }

    public boolean markedDropped() { return dropped; }
    public void markDropped() { dropped = true; }
    public int xpDropValue() { return 250; }
}
