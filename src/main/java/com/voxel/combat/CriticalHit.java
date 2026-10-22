package com.voxel.combat;

/**
 * Minecraft's critical-hit rule: a melee swing made while falling deals
 * {@link #MULTIPLIER}× damage. A jump that is still rising does not qualify,
 * and neither does a grounded swing — so the player has to time the attack
 * around the apex, exactly like vanilla.
 *
 * <p>Pure and static so the rule can be regression-tested without a world.</p>
 */
public final class CriticalHit {

    /** Vanilla critical multiplier. */
    public static final float MULTIPLIER = 1.5f;

    /** Downward speed (blocks/tick) below which a swing counts as falling. */
    public static final double FALLING_THRESHOLD = -0.05;

    private CriticalHit() {
    }

    /** True when a swing from this state is a critical hit. */
    public static boolean isCritical(boolean onGround, double velocityY) {
        return !onGround && velocityY < FALLING_THRESHOLD;
    }

    /** Final damage for a swing, given the attacker's vertical motion. */
    public static float damage(float baseDamage, boolean onGround, double velocityY) {
        return isCritical(onGround, velocityY) ? baseDamage * MULTIPLIER : baseDamage;
    }

    /** Final damage when the critical test has already been made. */
    public static float damage(float baseDamage, boolean critical) {
        return critical ? baseDamage * MULTIPLIER : baseDamage;
    }
}
