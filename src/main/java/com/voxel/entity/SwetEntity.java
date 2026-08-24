package com.voxel.entity;

import com.voxel.Player;
import org.joml.Vector3f;
import java.util.Random;

/**
 * Swet - hopping slime that engulfs the player and carries them
 * (Blue Swet; Golden variant via constructor flag).
 */
public class SwetEntity extends EnemyEntity {
    public static final String MODEL = "src/main/resources/assets/aether/models/entity/swet.json";

    public final boolean golden;
    private boolean engulfing = false;
    private float engulfTime = 0.0f;
    private float hopTimer = 0.0f;
    private final Random rng = new Random();

    private final com.voxel.utils.TextureManager textureManager;

    public SwetEntity(int id, Vector3f position, com.voxel.utils.TextureManager tm,
                      Player p, boolean golden) {
        super(id, position, tm, p);
        this.textureManager = tm;
        loadModel(MODEL, tm);
        if (golden) swapSwetTexture("swet/swet_golden");
        this.golden = golden;
        this.health = this.maxHealth = golden ? 25.0f : 16.0f;
        pickWidth = 0.95f;
        pickHeight = 0.95f;
    }

    private void swapSwetTexture(String name) {
        int idx = textureManager.getEntityTextureIndex(name);
        setPartTexture("outer", idx);
        setPartTexture("eyes", idx);
    }

    @Override
    public void updateAI(Vector3f playerPos, Vector3f playerVelocity, float dt) {
        if (world == null || isDead()) return;
        attackCooldown = Math.max(0, attackCooldown - dt);

        if (engulfing) {
            engulfTime += dt;
            // Carry the player along with us
            addPosition(0, (float) Math.abs(Math.sin(animTime * 4)) * dt * 0.5f - dt * 0.05f, 0);
            if (player != null) {
                player.setPosition(getPosX(), getPosY() + 0.6f, getPosZ());
                if (engulfTime % 1.0f < dt) player.takeDamage(golden ? 2.0f : 1.0f);
            }
            if (engulfTime > 3.0f || getPosition().distance(playerPos) > 6.0f) {
                engulfing = false;
                engulfTime = 0.0f;
            }
            return;
        }

        float dist = playerPos != null ? getPosition().distance(playerPos) : Float.MAX_VALUE;
        if (dist < 20.0f) {
            rotation.y = (float) Math.toDegrees(Math.atan2(
                    playerPos.x - getPosX(), playerPos.z - getPosZ()));
            hopTimer -= dt;
            if (hopTimer <= 0) {
                hopTimer = 0.55f;
                // Hop toward the player
                Vector3f dir = new Vector3f(playerPos).sub(getPosition()).normalize();
                moveToward(playerPos, 0.45f, 5.5f);
                hop(dt);
                if (dist < 1.4f) {
                    engulfing = true;   // Engulf!
                    hitFlashTime = 0.4f;
                }
            }
        } else {
            wanderHop(dt);
        }
    }

    private void wanderHop(float dt) {
        hopTimer -= dt;
        if (hopTimer <= 0) {
            hopTimer = 0.8f + rng.nextFloat();
            float yaw = rng.nextFloat() * (float) Math.PI * 2.0f;
            Vector3f target = new Vector3f(
                    getPosX() + (float) Math.sin(yaw) * 3,
                    getPosY(),
                    getPosZ() + (float) Math.cos(yaw) * 3);
            moveToward(target, 0.35f, 3.0f);
            hop(dt);
        }
    }

    /** Small vertical bounce. */
    private void hop(float dt) {
        addPosition(0.0f, 0.28f, 0.0f);
    }

    @Override
    public int xpDropValue() { return golden ? 12 : 7; }
}
