package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;

/**
 * End Crystal — the "regen anchors" Mojang places on top of obsidian pillars
 * around the central fountain. While alive they pulse health into the
 * Ender Dragon once per tick; destroying them disables that regen.
 *
 * <p>The bedrock pillar sits at {@code (100, 48, 0)} in the End dimension
 * (raised by DimensionWorldGenerator.decorate). The crystal floats one
 * block above the obsidian top with a subtle sinusoidal bob.</p>
 */
public class EndCrystalEntity extends Entity {

    /** Health pool. The player destroys the crystal with one hit. */
    public float health = 1.0f;
    private boolean dead = false;
    private final Vector3f anchor;
    private float phaseTime = 0.0f;

    private EnderDragonEntity dragon;

    public EndCrystalEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager) {
        super(id, position);
        this.anchor = new Vector3f(position);
        this.dimension = com.voxel.world.DimensionType.END;
        loadModel("src/main/resources/assets/minecraft/models/entity/end_crystal.json", textureManager);
    }

    /** Wire the dragon this crystal will heal. Set by Main.tick. */
    public void setDragon(EnderDragonEntity dragon) { this.dragon = dragon; }
    public EnderDragonEntity getDragon() { return dragon; }

    public boolean isDead() { return dead; }

    /** Called when the player hits the crystal with a melee swing. */
    public void onPunch() {
        health = 0.0f;
        dead = true;
    }

    public World world;

    @Override
    public void update(float dt) {
        if (dead) return;
        super.update(dt);
        snapshotPrev();

        // Subtle bob: 0.4m vertical sine wave at 2.5 Hz.
        float bob = (float) Math.sin(phaseTime * 2.5f) * 0.4f;
        float spin = (float) Math.toRadians(phaseTime * 60.0f);
        setPositionD(anchor.x + 0.0f, anchor.y + bob, anchor.z + 0.0f);
        rotation.y = spin;

        // Heal the dragon once per tick while alive.
        if (dragon != null && !dragon.isDead()) {
            dragon.heal(0.02f);
        }
    }

    public void tickPhase(float dt) { phaseTime += dt; }
}