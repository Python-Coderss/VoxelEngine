package com.voxel.entity;

import com.voxel.World;
import com.voxel.utils.FixedPoint;
import org.joml.Vector3f;
import java.util.Random;

/** Shared peaceful wandering behavior for utility mobs such as golems. */
public abstract class UtilityMobEntity extends Entity {
    protected final Random random = new Random();
    protected final ModelPart[] animatedParts;
    protected World world;
    protected boolean movedThisTick;
    protected float walkTime;

    protected UtilityMobEntity(int id, Vector3f position,
                               com.voxel.utils.TextureManager textureManager,
                               String modelPath, String... animatedPartNames) {
        super(id, position);
        loadModel(modelPath, textureManager);
        animatedParts = new ModelPart[animatedPartNames.length];
        for (int i = 0; i < animatedPartNames.length; i++) {
            animatedParts[i] = findPart(animatedPartNames[i]);
        }
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public boolean movedThisTick() {
        return movedThisTick;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        snapshotPrev();
        movedThisTick = false;

        wanderTimer -= dt;
        if (wanderTimer <= 0.0f) {
            wanderTimer = 1.5f + random.nextFloat() * 3.0f;
            wanderYaw = random.nextFloat() * (float) Math.PI * 2.0f;
            if (random.nextFloat() < 0.3f) wanderYaw = Float.NaN;
        }

        if (!Float.isNaN(wanderYaw) && world != null) {
            float speed = 0.7f;
            float dx = (float) Math.sin(wanderYaw) * speed * dt;
            float dz = (float) Math.cos(wanderYaw) * speed * dt;
            float nx = getPosX() + dx;
            float nz = getPosZ() + dz;
            int bx = (int) Math.floor(nx);
            int by = (int) Math.floor(getPosY());
            int bz = (int) Math.floor(nz);
            if (world.getVoxel(bx, by - 1, bz) != 0
                    && world.getVoxel(bx, by, bz) == 0
                    && world.getVoxel(bx, by + 1, bz) == 0) {
                addPosition(dx, 0.0f, dz);
                rotation.y = (float) Math.toDegrees(wanderYaw);
                movedThisTick = true;
                walkTime += dt * 6.0f;
            }
        }
    }

    protected float triangleWave(float phase, float period) {
        return (Math.abs(phase % period - period * 0.5f) - period * 0.25f)
                / (period * 0.25f);
    }

    protected float toDegrees(float radians) {
        return radians * 180.0f / (float) Math.PI;
    }

    private float wanderTimer = 0.0f;
    private float wanderYaw = Float.NaN;
}
