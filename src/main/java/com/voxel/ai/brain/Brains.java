package com.voxel.ai.brain;

import com.voxel.ai.MobBrain;
import com.voxel.entity.VillagerEntity;

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
}
