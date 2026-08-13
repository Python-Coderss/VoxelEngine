package com.voxel.entity;

import com.voxel.Player;
import com.voxel.world.ChunkManager;
import org.joml.Vector3f;

/**
 * Creeper: a stealthy mob that stalks the player and detonates on contact.
 * Its skin is recolored to the grayscale grass palette and biome-tinted at
 * spawn so its body blends into the surrounding grass.
 */
public class CreeperEntity extends EnemyEntity {

    /** Blast radius in blocks. */
    private static final float EXPLOSION_RADIUS = 3.0f;
    /** Bedrock block id - indestructible, explosions must skip it. */
    private static final int BEDROCK = 7;
    /** Maximum explosion damage at point-blank range. */
    private static final float EXPLOSION_DAMAGE = 22.0f;

    private ModelPart leftFrontLeg, rightFrontLeg, leftHindLeg, rightHindLeg;
    private boolean exploded = false;

    /** Shared ChunkManager so explosions can destroy blocks with dirty-marking. */
    public static volatile ChunkManager chunkManager;
    public static void setChunkManager(ChunkManager cm) { chunkManager = cm; }

    public CreeperEntity(int id, Vector3f position, com.voxel.utils.TextureManager textureManager, Player p) {
        super(id, position, textureManager, p);
        loadModel("src/main/resources/assets/minecraft/models/entity/creeper.json", textureManager);
        for (ModelPart part : parts) {
            if (part.name.equals("left_front_leg")) leftFrontLeg = part;
            else if (part.name.equals("right_front_leg")) rightFrontLeg = part;
            else if (part.name.equals("left_hind_leg")) leftHindLeg = part;
            else if (part.name.equals("right_hind_leg")) rightHindLeg = part;
        }
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        if (isDead()) return;

        // Diagonal-trot leg swing (quadruped gait): opposing legs move together.
        float swing = (float) Math.sin(animTime * 7.0f) * 24.0f;
        if (leftFrontLeg != null) leftFrontLeg.rotation.x = swing;
        if (rightHindLeg != null) rightHindLeg.rotation.x = swing;
        if (rightFrontLeg != null) rightFrontLeg.rotation.x = -swing;
        if (leftHindLeg != null) leftHindLeg.rotation.x = -swing;
    }

    /** The creeper does not melee; reaching the player triggers its fuse. */
    @Override
    public void performAttack(Vector3f playerPos) {
        explode(playerPos);
    }

    private void explode(Vector3f playerPos) {
        if (exploded) return;
        exploded = true;

        Vector3f here = getPosition();

        // Destroy blocks in a rough sphere with distance falloff.
        if (chunkManager != null && world != null) {
            int r = (int) Math.ceil(EXPLOSION_RADIUS);
            int bx = (int) Math.floor(here.x);
            int by = (int) Math.floor(here.y);
            int bz = (int) Math.floor(here.z);
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist > EXPLOSION_RADIUS) continue;
                        // Farther blocks are less likely to be destroyed.
                        if (Math.random() < dist / (EXPLOSION_RADIUS + 0.5f) + 0.35f) continue;
                        int block = world.getVoxel(bx + dx, by + dy, bz + dz);
                        if (block != 0 && block != BEDROCK) {
                            chunkManager.setVoxelWithFlags(bx + dx, by + dy, bz + dz, 0, 0, 0);
                        }
                    }
                }
            }
        }

        // Damage the player, falling off with distance.
        if (player != null) {
            float dist = here.distance(playerPos);
            float dmg = EXPLOSION_DAMAGE * Math.max(0.0f, 1.0f - dist / (EXPLOSION_RADIUS * 1.5f));
            if (dmg > 0.0f) player.takeDamage(dmg);
        }

        die();
    }
}
