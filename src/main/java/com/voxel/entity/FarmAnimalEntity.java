package com.voxel.entity;

import com.voxel.World;
import org.joml.Vector3f;
import java.util.Random;

/** Shared passive behavior for the small farm-animal mobs. */
public class FarmAnimalEntity extends Entity {
    protected final Random random = new Random();
    protected final ModelPart[] legs;
    protected float animTime = 0.0f;
    private float wanderTimer = 0.0f;
    private float wanderYaw = 0.0f;
    private float limbSwing = 0.0f;

    protected World world;

    protected FarmAnimalEntity(int id, Vector3f position,
                               com.voxel.utils.TextureManager textureManager,
                               String modelPath, String... legNames) {
        super(id, position);
        loadModel(modelPath, textureManager);
        legs = new ModelPart[legNames.length];
        for (int i = 0; i < legNames.length; i++) {
            legs[i] = findPart(legNames[i]);
        }
    }

    public void setWorld(World world) {
        this.world = world;
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        animTime += dt;
        snapshotPrev();

        wanderTimer -= dt;
        if (wanderTimer <= 0.0f) {
            wanderTimer = 1.5f + random.nextFloat() * 3.0f;
            if (random.nextFloat() < 0.35f) {
                wanderYaw = Float.NaN; // idle for this interval
            } else {
                wanderYaw = random.nextFloat() * (float) Math.PI * 2.0f;
            }
        }

        boolean moved = false;
        if (!Float.isNaN(wanderYaw) && world != null) {
            float speed = 0.9f;
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
                moved = true;
            }
        }

        float amount = moved ? 1.0f : 0.0f;
        if (moved) limbSwing += dt * 6.0f;
        float swing = (float) Math.cos(limbSwing * 0.6662f) * 1.4f * amount;
        float swingOpposite = (float) Math.cos(limbSwing * 0.6662f + Math.PI) * 1.4f * amount;
        for (int i = 0; i < legs.length; i++) {
            if (legs[i] != null) {
                legs[i].rotation.x = (i % 2 == 0 ? swing : swingOpposite)
                        * 180.0f / (float) Math.PI;
            }
        }
    }
}
