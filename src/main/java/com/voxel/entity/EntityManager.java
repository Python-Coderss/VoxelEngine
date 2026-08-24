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
    // Part data size: offset(3) + uvU(1) + absOffset(3) + uvV(1) + size(3) + texIdx(1)
    // + rotation(3) + uvSize(3) + mapping(1) + emissive(1) + padding(3) = 24 floats (96 bytes)
    private static final int PART_STRIDE = 24;

    private static final int CULL_BLOCKS = 64;  // entities beyond this distance from camera are skipped
    private static final long CULL_FP = (long) CULL_BLOCKS * FixedPoint.SCALE;  // per-axis threshold in fixed-point
    private static final long CULL_DIST_SQ_FP = CULL_FP * CULL_FP;  // squared distance threshold

    // Buffer bounds (REGION_SIZE=128 * CHUNK_SIZE=16 = 2048), valid range [0, 2047] in fixed-point
    private static final long BUF_MAX_FP = 2047L * FixedPoint.SCALE;

    /** Number of entities actually uploaded last frame (after dimension filter + culling). */
    private int uploadedEntityCount = 0;

    // ── Persistent CPU staging memory ─────────────────────────────
    // uploadToGPU() runs every rendered frame; allocating + freeing direct
    // ByteBuffers and an ArrayList each call showed up as steady GC pressure.
    // These are sized once for the worst case (full SSBO contents) and reused;
    // safe because all uploadToGPU calls happen on the single GL thread.
    private final java.nio.ByteBuffer entityStage =
            MemoryUtil.memAlloc(MAX_ENTITIES * ENTITY_STRIDE * 4);
    private final java.nio.ByteBuffer partStage =
            MemoryUtil.memAlloc(MAX_PARTS * PART_STRIDE * 4);
    private final List<ModelPart> partScratch = new ArrayList<>(MAX_PARTS);

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
        if (worldOffset != null) {
            uploadToGPU(activeDimension, cameraPos, partialTicks, player,
                FixedPoint.fromFloat(worldOffset.x),
                FixedPoint.fromFloat(worldOffset.y),
                FixedPoint.fromFloat(worldOffset.z));
        } else {
            uploadToGPU(activeDimension, cameraPos, partialTicks, player, 0, 0, 0, false);
        }
    }

    /**
     * Uploads entities to GPU with exact fixed-point world-space offset.
     * Use this overload directly to avoid float precision loss at extreme coords
     * (>16.7M blocks, where float can't represent exact integers).
     *
     * @param offsetXFp  world-space X offset in fixed-point (e.g., (long)wox * FixedPoint.SCALE)
     * @param offsetYFp  world-space Y offset in fixed-point
     * @param offsetZFp  world-space Z offset in fixed-point
     */
    public void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos,
                            float partialTicks, com.voxel.Player player,
                            long offsetXFp, long offsetYFp, long offsetZFp) {
        uploadToGPU(activeDimension, cameraPos, partialTicks, player, offsetXFp, offsetYFp, offsetZFp, true);
    }

    private void uploadToGPU(DimensionType activeDimension, Vector3f cameraPos,
                            float partialTicks, com.voxel.Player player,
                            long woxFp, long woyFp, long wozFp, boolean hasOffset) {
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
        java.nio.ByteBuffer entityBuffer = entityStage;
        List<ModelPart> allParts = partScratch;
        allParts.clear();
        entityBuffer.clear();  // reset position AND stale limit from the previous frame
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
            // Also cull entities whose absolute position doesn't fall within the current
            // world buffer [worldOffset, worldOffset+2047]. Entities outside the buffer
            // produce out-of-range relative coords that break the shader ray marcher.
            long rxFp, ryFp, rzFp;
            if (hasOffset) {
                rxFp = ix - woxFp;
                ryFp = iy - woyFp;
                rzFp = iz - wozFp;
                // Buffer-relative coords must be within [0, 2047] (bufSize = REGION_SIZE * CHUNK_SIZE = 2048)
                if (rxFp < 0 || ryFp < 0 || rzFp < 0
                    || rxFp > BUF_MAX_FP || ryFp > BUF_MAX_FP || rzFp > BUF_MAX_FP) continue;
            } else {
                rxFp = ix;
                ryFp = iy;
                rzFp = iz;
            }
            float relX = FixedPoint.toFloat(rxFp);
            float relY = FixedPoint.toFloat(ryFp);
            float relZ = FixedPoint.toFloat(rzFp);
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
            // Conservative bounding radius (blocks) — consumed by the shader's
            // per-entity sphere pre-reject so rays that can't hit any part skip
            // the whole OBB loop. Rotations preserve lengths, so the exact
            // reach bound needs no trig: |pivot| + |geomCenter - pivot| +
            // |halfSize| covers every rotated pose of the part.
            entityBuffer.putFloat(computeBoundingRadius(entity));
            if (entity.parts != null) allParts.addAll(entity.parts);
            writtenCount++;
        }
        if (writtenCount > 0) {
            entityBuffer.limit(writtenCount * ENTITY_STRIDE * 4);
            entityBuffer.position(0);
            glNamedBufferSubData(entitySSBO, 0, entityBuffer);
        }
        uploadedEntityCount = writtenCount;

        int partUploadCount = Math.min(allParts.size(), MAX_PARTS);
        if (partUploadCount > 0) {
            java.nio.ByteBuffer partBuffer = partStage;
            partBuffer.clear();
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
                partBuffer.putFloat(0.0f); // std430 padding before the next vec3
                partBuffer.putFloat(part.uvSize.x).putFloat(part.uvSize.y).putFloat(part.uvSize.z);
                partBuffer.putFloat((float) part.textureMapping);
                partBuffer.putFloat(part.emissive ? 1.0f : 0.0f);
                partBuffer.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
            }
            partBuffer.flip();
            glNamedBufferSubData(partSSBO, 0, partBuffer);
        }
    }

    public void bind(int entityBinding, int partBinding) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, entityBinding, entitySSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, partBinding, partSSBO);
    }

    /**
     * Conservative bounding-sphere radius around the entity origin that is
     * guaranteed to contain every rendered part (in any rotated pose) plus the
     * billboarded health bar. Uploaded in EntityData.padding; the raytracer
     * rejects entities whose sphere the ray misses or that sit fully behind a
     * closer hit without touching their per-part buffers.
     */
    private static float computeBoundingRadius(Entity entity) {
        // Health bar reach: center at y+2.3 plus its half diagonal (~0.503) ≈ 2.81.
        float r = 3.0f;
        if (entity.parts != null) {
            for (ModelPart p : entity.parts) {
                Vector3f geomCenter = new Vector3f(p.offset).div(16f).add(new Vector3f(p.size).div(32f));
                Vector3f pivot = new Vector3f(p.absoluteOffset).div(16f);
                float reach = pivot.length()
                        + new Vector3f(geomCenter).sub(pivot).length()
                        + p.size.length() / 32f;
                if (reach > r) r = reach;
            }
        }
        return Math.min(r + 0.25f, 64f);
    }
    
    public int getEntityCount() {
        return entities.size();
    }

    /** Returns the number of entities actually uploaded to the GPU last frame
     *  (after dimension filtering and culling). Use this for the shader's entity count. */
    public int getUploadedEntityCount() {
        return uploadedEntityCount;
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

    /** Defensive snapshot for callers that need to iterate without risking
     *  ConcurrentModificationException when other code mutates the entity
     *  list (e.g. when a tick adds a dropped item while we scan). */
    public java.util.List<Entity> getEntitiesSnapshot() {
        return new java.util.ArrayList<>(entities);
    }

    /** Remove dead enemies and expired fireballs from the entity list. */
    public void pruneExpired() {
        entities.removeIf(e -> {
            if (e instanceof FireballEntity) return ((FireballEntity) e).isExpired();
            if (e instanceof ArrowEntity) return ((ArrowEntity) e).isExpired();
            if (e instanceof AetherProjectileEntity) return ((AetherProjectileEntity) e).isExpired();
            if (e instanceof EnemyEntity) return ((EnemyEntity) e).isDead();
            return false;
        });
    }
}
