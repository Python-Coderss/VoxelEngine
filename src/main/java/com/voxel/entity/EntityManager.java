package com.voxel.entity;

import com.voxel.utils.FixedPoint;
import com.voxel.world.DimensionType;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;

public class EntityManager {
    private List<Entity> entities;
    private int entitySSBO;
    private int partSSBO;
    
    private static final int MAX_ENTITIES = 1024;
    private static final int MAX_PARTS = 8192;
    
    // Entity data size: position(3) + health(1) + rotation(3) + maxHealth(1) + partCount(1) + partOffset(1) + hitFlash(1) + tintColorRGB(3) + tintAmount(1) = 16 floats (64 bytes)
    private static final int ENTITY_STRIDE = 16;
    // Part data size: offset(3) + uvU(1) + absOffset(3) + uvV(1) + size(3) + texIdx(1) + rotation(3) + mapping(1) = 16 floats (64 bytes)
    private static final int PART_STRIDE = 16;

    private static final int CULL_BLOCKS = 64;  // entities beyond this distance from camera are skipped
    private static final long CULL_FP = (long) CULL_BLOCKS * FixedPoint.SCALE;  // per-axis threshold in fixed-point
    private static final long CULL_DIST_SQ_FP = CULL_FP * CULL_FP;  // squared distance threshold

    public EntityManager() {
        this.entities = new ArrayList<>();
        setupBuffers();
    }

    private void setupBuffers() {
        entitySSBO = glCreateBuffers();
        glNamedBufferStorage(entitySSBO, (long) MAX_ENTITIES * ENTITY_STRIDE * 4, GL_DYNAMIC_STORAGE_BIT);

        partSSBO = glCreateBuffers();
        glNamedBufferStorage(partSSBO, (long) MAX_PARTS * PART_STRIDE * 4, GL_DYNAMIC_STORAGE_BIT);
    }

    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    public void update(float dt) {
        for (Entity entity : entities) {
            entity.update(dt);
        }
    }

    /**
     * Uploads all entities to GPU (legacy, for backward compatibility).
     */
    public void uploadToGPU() {
        uploadToGPU(null, null, 0.0f, null);
    }

    /**
     * Uploads entities to GPU (legacy, no interpolation).
     */
    public void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos) {
        uploadToGPU(activeDimension, cameraPos, 0.0f, null, null);
    }

    /**
     * Uploads entities to GPU, optionally filtering by dimension.
     * If activeDimension is null, all entities are uploaded.
     * If cameraPos is provided, only entities within 64 blocks are uploaded.
     * @param partialTicks  render interpolation alpha (0-1)
     * @param player        the physics Player (for PlayerEntity interpolation bypass)
     */
    private static final float HIDDEN_Y = -10000.0f;  // PlayerEntity hidden-position sentinel
    private static final long HIDDEN_Y_FP = com.voxel.utils.FixedPoint.fromFloat(HIDDEN_Y);

    public void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos,
                            float partialTicks, com.voxel.Player player) {
        uploadToGPU(activeDimension, cameraPos, partialTicks, player, null);
    }

    /**
     * Uploads entities to GPU with a world-space offset subtraction so the shader
     * receives buffer-relative positions (always in [0,2048] range → full float32
     * precision at any world coordinate).
     *
     * All position math (culling + buffer-relative translation) is done in 64-bit
     * fixed-point (56.8) to avoid float32 precision loss at extreme coordinates.
     */
    public void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos,
                            float partialTicks, com.voxel.Player player,
                            Vector3f worldOffset) {
        boolean hasOffset = worldOffset != null;
        boolean hasPlayer = player != null;

        // ── Fixed-point camera position for culling ──────────────────
        long camFpX, camFpY, camFpZ;
        boolean useFpCulling;
        if (hasPlayer) {
            // Use player's fixed-point position for the camera (eye height added).
            // Uses logic-clock partialTicks; the slight mismatch vs player's own
            // clock is irrelevant for ±64-block culling.
            camFpX = FixedPoint.lerp(player.getFixedPrevX(), player.getFixedX(), partialTicks);
            camFpY = FixedPoint.lerp(player.getFixedPrevY(), player.getFixedY(), partialTicks)
                    + FixedPoint.fromFloat(1.6f);  // PLAYER_EYE_HEIGHT
            camFpZ = FixedPoint.lerp(player.getFixedPrevZ(), player.getFixedZ(), partialTicks);
            useFpCulling = true;
        } else {
            camFpX = camFpY = camFpZ = 0;
            useFpCulling = false;
        }

        // ── Single pass: cull, collect, and upload in one iteration ──
        // (avoids the two-pass race where entity positions change between count and write)
        java.nio.ByteBuffer entityBuffer = MemoryUtil.memAlloc(entities.size() * ENTITY_STRIDE * 4);
        List<ModelPart> allParts = new ArrayList<>();
        int writtenCount = 0;

        for (Entity entity : entities) {
            if (activeDimension != null && entity.dimension != activeDimension) continue;

            boolean isVisiblePlayer = hasPlayer && entity instanceof com.voxel.entity.PlayerEntity && entity.getFixedY() > HIDDEN_Y_FP;

            // ── Interpolated position (fixed-point) ──────────────────
            long ix, iy, iz;
            if (isVisiblePlayer) {
                // PlayerEntity: use physics Player's fixed-point interpolation
                ix = FixedPoint.lerp(player.getFixedPrevX(), player.getFixedX(), partialTicks);
                iy = FixedPoint.lerp(player.getFixedPrevY(), player.getFixedY(), partialTicks);
                iz = FixedPoint.lerp(player.getFixedPrevZ(), player.getFixedZ(), partialTicks);
            } else {
                ix = FixedPoint.lerp(entity.getFixedPrevX(), entity.getFixedX(), partialTicks);
                iy = FixedPoint.lerp(entity.getFixedPrevY(), entity.getFixedY(), partialTicks);
                iz = FixedPoint.lerp(entity.getFixedPrevZ(), entity.getFixedZ(), partialTicks);
            }

            // ── Culling (fixed-point) ───────────────────────────────
            if (useFpCulling) {
                long dx = ix - camFpX, dy = iy - camFpY, dz = iz - camFpZ;
                if (Math.abs(dx) > CULL_FP || Math.abs(dy) > CULL_FP || Math.abs(dz) > CULL_FP) continue;
                if (dx * dx + dy * dy + dz * dz > CULL_DIST_SQ_FP) continue;
            } else if (cameraPos != null) {
                // Legacy float fallback
                float fx = FixedPoint.toFloat(ix), fy = FixedPoint.toFloat(iy), fz = FixedPoint.toFloat(iz);
                float dx = fx - cameraPos.x, dy = fy - cameraPos.y, dz = fz - cameraPos.z;
                if (dx * dx + dy * dy + dz * dz > CULL_BLOCKS * CULL_BLOCKS) continue;
            }

            int partCount = entity.parts != null ? entity.parts.size() : 0;
            int partOffset = allParts.size();

            // ── Buffer-relative position: subtract worldOffset in fixed-point BEFORE float ──
            float relX, relY, relZ;
            if (hasOffset) {
                long woxFp = FixedPoint.fromFloat(worldOffset.x);
                long woyFp = FixedPoint.fromFloat(worldOffset.y);
                long wozFp = FixedPoint.fromFloat(worldOffset.z);
                relX = FixedPoint.toFloat(ix - woxFp);
                relY = FixedPoint.toFloat(iy - woyFp);
                relZ = FixedPoint.toFloat(iz - wozFp);
            } else {
                relX = FixedPoint.toFloat(ix);
                relY = FixedPoint.toFloat(iy);
                relZ = FixedPoint.toFloat(iz);
            }
            entityBuffer.putFloat(relX).putFloat(relY).putFloat(relZ);

            float health = 1.0f;
            float maxHealth = 1.0f;
            if (entity instanceof EnemyEntity) {
                health = ((EnemyEntity) entity).getHealth();
                maxHealth = ((EnemyEntity) entity).getMaxHealth();
            }
            // health
            entityBuffer.putFloat(health);

            // rotation
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.x));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.y));
            entityBuffer.putFloat((float) Math.toRadians(entity.rotation.z));
            
            // maxHealth
            entityBuffer.putFloat(maxHealth);

            // counts and offsets
            entityBuffer.putInt(partCount);
            entityBuffer.putInt(partOffset);
            // hitFlashTime for telegraphing (combat glow)
            float hitFlash = 0.0f;
            if (entity instanceof EnemyEntity) {
                hitFlash = ((EnemyEntity) entity).hitFlashTime;
            }
            entityBuffer.putFloat(hitFlash);
            // Tint color + amount
            entityBuffer.putFloat(entity.tintColor.x);
            entityBuffer.putFloat(entity.tintColor.y);
            entityBuffer.putFloat(entity.tintColor.z);
            entityBuffer.putFloat(entity.tintAmount);
            entityBuffer.putFloat(0.0f); // Padding for 64-byte alignment
            if (entity.parts != null) allParts.addAll(entity.parts);
            writtenCount++;
        }
        if (writtenCount > 0) {
            entityBuffer.limit(writtenCount * ENTITY_STRIDE * 4);
            entityBuffer.position(0);
            glNamedBufferSubData(entitySSBO, 0, entityBuffer);
        }
        MemoryUtil.memFree(entityBuffer);

        if (!allParts.isEmpty()) {
            int partUploadCount = Math.min(allParts.size(), MAX_PARTS);
            java.nio.ByteBuffer partBuffer = MemoryUtil.memAlloc(partUploadCount * PART_STRIDE * 4);
            for (int i = 0; i < partUploadCount; i++) {
                ModelPart part = allParts.get(i);
                partBuffer.putFloat(part.offset.x).putFloat(part.offset.y).putFloat(part.offset.z);
                partBuffer.putFloat(part.uvOrigin.x); // UV Origin U
                
                partBuffer.putFloat(part.absoluteOffset.x).putFloat(part.absoluteOffset.y).putFloat(part.absoluteOffset.z);
                partBuffer.putFloat(part.uvOrigin.y); // UV Origin V
                
                partBuffer.putFloat(part.size.x).putFloat(part.size.y).putFloat(part.size.z);
                partBuffer.putFloat((float)part.textureIndex);
                
                partBuffer.putFloat((float)Math.toRadians(part.rotation.x));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.y));
                partBuffer.putFloat((float)Math.toRadians(part.rotation.z));
                partBuffer.putFloat((float) part.textureMapping);
            }
            partBuffer.flip();
            glNamedBufferSubData(partSSBO, 0, partBuffer);
            MemoryUtil.memFree(partBuffer);
        }
    }

    public void bind(int entityBinding, int partBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, entityBinding, entitySSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partBinding, partSSBO);
    }
    
    public int getEntityCount() {
        return entities.size();
    }

    /**
     * Returns the count of entities in the given dimension.
     * If dimension is null, returns the total count.
     */
    public int getEntityCount(DimensionType dimension) {
        if (dimension == null) return entities.size();
        int count = 0;
        for (Entity e : entities) {
            if (e.dimension == dimension) count++;
        }
        return count;
    }

    public Entity getEntity(int index) {
        if (index < 0 || index >= entities.size()) return null;
        return entities.get(index);
    }
}
