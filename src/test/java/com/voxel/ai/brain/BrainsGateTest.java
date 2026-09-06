package com.voxel.ai.brain;

import com.voxel.ai.brain.Brains;
import com.voxel.entity.BlazeEntity;
import com.voxel.entity.CockatriceEntity;
import com.voxel.entity.CreeperEntity;
import com.voxel.entity.EndermanEntity;
import com.voxel.entity.EndermiteEntity;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.GenericMobEntity;
import com.voxel.entity.MagmaCubeEntity;
import com.voxel.entity.MimicEntity;
import com.voxel.entity.SentryEntity;
import com.voxel.entity.SkeletonEntity;
import com.voxel.entity.SilverfishEntity;
import com.voxel.entity.SpiderEntity;
import com.voxel.entity.SwetEntity;
import com.voxel.entity.ValkyrieEntity;
import com.voxel.entity.ZombieEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the brain installation gate: pack-hunter brains attach only to
 * enemies that keep the legacy updateAI contract.
 */
public class BrainsGateTest {

    @Test
    public void zombieKeepsLegacyContractSoGetsBrain() {
        // ZombieEntity does not override updateAI -> the reflection gate
        // should classify it as legacy (brain installs in the constructor).
        assertFalse(Brains.overridesLegacyAiForTest(ZombieEntity.class));
    }

    @Test
    public void customFsmSubclassesAreExcluded() {
        assertTrue(Brains.overridesLegacyAiForTest(BlazeEntity.class));
        assertTrue(Brains.overridesLegacyAiForTest(SkeletonEntity.class));
        assertTrue(Brains.overridesLegacyAiForTest(SpiderEntity.class));
        assertTrue(Brains.overridesLegacyAiForTest(EndermanEntity.class));
        assertTrue(Brains.overridesLegacyAiForTest(SentryEntity.class));
    }

    @Test
    public void baseEnemyIsNotAnOverride() {
        assertFalse(Brains.overridesLegacyAiForTest(EnemyEntity.class));
    }

    @Test
    public void gateIsConsistentWithReflection() throws Exception {
        // The gate must agree with plain reflection on the same question.
        Class<?>[] sig = {org.joml.Vector3f.class, org.joml.Vector3f.class, float.class};
        Class<?>[] checked = {
                ZombieEntity.class, CreeperEntity.class, CockatriceEntity.class,
                EndermiteEntity.class, MagmaCubeEntity.class, SilverfishEntity.class,
                GenericMobEntity.class, ValkyrieEntity.class,
                BlazeEntity.class, MimicEntity.class, SwetEntity.class
        };
        for (Class<?> type : checked) {
            boolean reflected = false;
            Class<?> c = type;
            while (c != null && c != EnemyEntity.class) {
                try {
                    c.getDeclaredMethod("updateAI", sig);
                    reflected = true;
                    break;
                } catch (NoSuchMethodException ignored) {
                    c = c.getSuperclass();
                }
            }
            assertEquals("gate mismatch for " + type.getSimpleName(),
                    reflected, Brains.overridesLegacyAiForTest(type));
        }
    }
}
