package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.entity.AncientBuilderEntity;
import com.voxel.entity.ArchIllagerEntity;
import com.voxel.entity.EnemyEntity;
import com.voxel.entity.GreatHogEntity;
import com.voxel.entity.HeartOfEnderEntity;
import com.voxel.entity.VillagerEntity;
import com.voxel.entity.WitherStormEntity;
import org.joml.Vector3f;

import java.lang.reflect.Method;

/**
 * Brain installation gate. Brains are on by default; the system property
 * {@code voxel.ai.brains.off=true} restores pure legacy FSM behavior
 * (useful for A/B comparison and regression triage).
 */
public final class Brains {

    public static final boolean ENABLED =
            !Boolean.getBoolean("voxel.ai.brains.off");

    private Brains() {
    }

    /** @return the brain to install on a new villager, or null when disabled. */
    public static MobBrain newVillagerBrain(VillagerEntity owner) {
        return ENABLED ? new VillagerBrain(owner) : null;
    }

    /**
     * @return the brain to install on a new ancient builder, or null when
     *         brains are disabled (builders then simply stand where placed).
     */
    public static MobBrain newBuilderBrain(AncientBuilderEntity owner) {
        return ENABLED ? new AncientBuilderBrain(owner) : null;
    }

    /** @return the predator brain for a hostile mob, or null when disabled. */
    public static MobBrain newHunterBrain(EnemyEntity owner) {
        return ENABLED ? HunterBrain.attach(owner) : null;
    }

    /** @return the Wither Storm boss brain, or null when brains are disabled. */
    public static MobBrain newWitherStormBrain(WitherStormEntity owner) {
        return ENABLED ? new WitherStormBrain(owner) : null;
    }

    /** @return the Great Hog boss brain, or null when brains are disabled. */
    public static MobBrain newGreatHogBrain(GreatHogEntity owner) {
        return ENABLED ? new GreatHogBrain(owner) : null;
    }

    /** @return the Arch-Illager boss brain, or null when brains are disabled. */
    public static MobBrain newArchIllagerBrain(ArchIllagerEntity owner) {
        return ENABLED ? new ArchIllagerBrain(owner) : null;
    }

    /** @return the Heart of Ender boss brain, or null when brains are disabled. */
    public static MobBrain newHeartOfEnderBrain(HeartOfEnderEntity owner) {
        return ENABLED ? new HeartOfEnderBrain(owner) : null;
    }

    /**
     * Install the pack-hunter brain only on enemies that keep the legacy
     * {@code updateAI(playerPos, playerVelocity, dt)} contract — i.e. they do
     * not override it with their own FSM. Called from the EnemyEntity base
     * constructor; at that point {@code getClass()} is the concrete subclass,
     * so a declared {@code updateAI} override is detected reliably.
     */
    public static MobBrain newHunterBrainIfLegacy(EnemyEntity owner) {
        if (!ENABLED || owner == null || overridesLegacyAi(owner.getClass())) {
            return null;
        }
        return HunterBrain.attach(owner);
    }

    /** Package-private test seam over {@link #overridesLegacyAi}. */
    static boolean overridesLegacyAiForTest(Class<?> type) {
        return overridesLegacyAi(type);
    }

    private static boolean overridesLegacyAi(Class<?> type) {
        // Walk the hierarchy below EnemyEntity: any class in the chain that
        // declares its own updateAI means custom FSM behavior wins.
        Class<?> current = type;
        while (current != null && !EnemyEntity.class.equals(current)) {
            try {
                current.getDeclaredMethod("updateAI",
                        Vector3f.class, Vector3f.class, float.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return false;
    }
}
