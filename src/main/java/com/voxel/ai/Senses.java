package com.voxel.ai;

import com.voxel.entity.Entity;
import com.voxel.entity.EntityManager;
import com.voxel.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick perception snapshot for one entity. Entities outside the view
 * radius are omitted; line-of-sight is computed lazily (and cached per
 * snapshot) so brains that never check it cost nothing.
 */
public final class Senses {

    public static final float EYE_HEIGHT = 1.5f;

    public final Entity self;
    public final VoxelView voxels;
    public final List<Visible> visibleEntities;
    /** World time of day in minutes (0-1440), matching VillagerEntity convention. */
    public final float timeOfDayMinutes;

    private Senses(Entity self, VoxelView voxels,
                   List<Visible> visibleEntities, float timeOfDayMinutes) {
        this.self = self;
        this.voxels = voxels;
        this.visibleEntities = visibleEntities;
        this.timeOfDayMinutes = timeOfDayMinutes;
    }

    /**
     * Builds a snapshot by scanning the entity manager around {@code self}.
     *
     * @param viewRadius      max detection distance in blocks
     * @param timeOfDayMinutes current world time in minutes (pass 720 for noon)
     */
    public static Senses scan(Entity self, World world, EntityManager entityManager,
                              float viewRadius, float timeOfDayMinutes) {
        List<Visible> visible = new ArrayList<>();
        if (world != null && entityManager != null) {
            Vector3f selfPos = self.getPosition();
            float radiusSq = viewRadius * viewRadius;
            int count = entityManager.getEntityCount();
            for (int i = 0; i < count; i++) {
                Entity other = entityManager.getEntity(i);
                if (other == null || other == self) continue;
                if (other.dimension != self.dimension) continue;
                if (other instanceof com.voxel.entity.EnemyEntity
                        && ((com.voxel.entity.EnemyEntity) other).isDead()) continue;
                float distSq = selfPos.distanceSquared(other.getPosition());
                if (distSq > radiusSq) continue;
                visible.add(new Visible(other, distSq, world::getVoxel, selfPos));
            }
        }
        return new Senses(self, world == null ? null : world::getVoxel, visible, timeOfDayMinutes);
    }

    public static final class Visible {
        public final Entity entity;
        public final float distanceSquared;

        private final VoxelView voxels;
        private final Vector3f fromPos;
        private Boolean los;

        Visible(Entity entity, float distanceSquared, VoxelView voxels, Vector3f fromPos) {
            this.entity = entity;
            this.distanceSquared = distanceSquared;
            this.voxels = voxels;
            this.fromPos = fromPos;
        }

        public boolean lineOfSight() {
            if (los == null) {
                Vector3f to = entity.getPosition();
                los = Raycaster.lineOfSight(voxels,
                        fromPos.x, fromPos.y + EYE_HEIGHT, fromPos.z,
                        to.x, to.y + EYE_HEIGHT, to.z);
            }
            return los.booleanValue();
        }
    }
}
