package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;

/** Hostile Enderman with the tall Minecraft model, eyes, and stare reaction. */
public class EndermanEntity extends EnemyEntity {
    private ModelPart head, headEyes, jaw;
    private ModelPart leftArm, rightArm, leftLeg, rightLeg;
    private boolean staringAtPlayer;
    private float stareBlend;

    public EndermanEntity(int id, Vector3f position,
                          com.voxel.utils.TextureManager textureManager, Player player) {
        super(id, position, textureManager, player);
        loadModel("src/main/resources/assets/minecraft/models/entity/enderman.json", textureManager);
        for (ModelPart part : parts) {
            switch (part.name) {
                case "head": head = part; break;
                case "head_eyes": headEyes = part; break;
                case "jaw": jaw = part; break;
                case "left_arm": leftArm = part; break;
                case "right_arm": rightArm = part; break;
                case "left_leg": leftLeg = part; break;
                case "right_leg": rightLeg = part; break;
                default: break;
            }
        }
    }

    @Override
    public void update(float dt) {
        // AI is updated later in the tick than entity animation. Recheck here so
        // staring freezes the position and the base EnemyEntity walk/bob update
        // cannot advance for one frame before updateAI sees the gaze.
        staringAtPlayer = isPlayerStaring();
        if (!staringAtPlayer) {
            super.update(dt);
        } else {
            // Remove any pending interpolation from the previous moving frame.
            snapshotPrev();
        }
        updateStarePose(dt);
    }

    private void updateStarePose(float dt) {
        float targetBlend = staringAtPlayer ? 1.0f : 0.0f;
        stareBlend += (targetBlend - stareBlend) * Math.min(1.0f, dt * 10.0f);
        float headLift = stareBlend * 5.0f; // ModelEnderman attack state lowers source Y by 5.

        if (head != null) head.absoluteOffset.y = 38.0f + headLift;
        if (headEyes != null) headEyes.absoluteOffset.y = 38.0f + headLift;
        if (jaw != null) {
            jaw.absoluteOffset.y = 39.0f + headLift;
            jaw.rotation.x = -35.0f * stareBlend;
        }

        // Do not advance or alter limb poses while the Enderman is being stared at.
        if (staringAtPlayer) return;
        float walk = (float) Math.sin(animTime * 5.0f) * 22.0f;
        if (leftArm != null) leftArm.rotation.x = -walk * 0.5f;
        if (rightArm != null) rightArm.rotation.x = walk * 0.5f;
        if (leftLeg != null) leftLeg.rotation.x = walk;
        if (rightLeg != null) rightLeg.rotation.x = -walk;
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        staringAtPlayer = isPlayerStaring();
        if (staringAtPlayer) {
            // Minecraft Endermen lock in place while the player holds eye contact.
            return;
        }
        super.updateAI(playerPos, playerVelocity, dt);
    }

    private boolean isPlayerStaring() {
        if (player == null) return false;
        Vector3f playerFeet = player.getPosition();
        Vector3f eye = new Vector3f(playerFeet).add(0.0f, 1.6f, 0.0f);
        Vector3f toEnderman = new Vector3f(getPosition()).add(0.0f, 1.5f, 0.0f).sub(eye);
        float distance = toEnderman.length();
        if (distance < 0.01f || distance > 48.0f) return false;
        toEnderman.div(distance);

        // CameraController uses this exact yaw/pitch convention. Do not use the
        // player's body yaw here: in third person it follows movement direction.
        float yaw = (float) Math.toRadians(player.getLookYaw());
        float pitch = (float) Math.toRadians(player.getLookPitch());
        Vector3f gaze = new Vector3f(
                (float) (Math.cos(yaw) * Math.cos(pitch)),
                (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * Math.cos(pitch))
        ).normalize();
        return gaze.dot(toEnderman) > 0.985f;
    }

    public boolean isStaringAtPlayer() {
        return staringAtPlayer;
    }
}
