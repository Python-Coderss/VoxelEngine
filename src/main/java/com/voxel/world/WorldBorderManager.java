package com.voxel.world;

import com.voxel.Player;
import com.voxel.utils.FixedPoint;

/**
 * Hard world border that clamps the player at the Far Lands boundary.
 * The border radius is derived from the X/Z int bit width configured
 * on the active dimension's BetaPrecisionTuning.
 */
public class WorldBorderManager {
    private long borderRadius;
    private volatile String borderMessage = null;
    private long borderMessageExpireNanos = 0;
    private static final long MESSAGE_DURATION_NANOS = 3_000_000_000L; // 3 seconds

    public WorldBorderManager(int xzIntBits) {
        setBorderFromBits(xzIntBits);
    }

    /** Recompute the border from a new int bit width. */
    public void setBorderFromBits(int xzIntBits) {
        this.borderRadius = (1L << (xzIntBits - 1)) - 16L;
        if (borderRadius < 0) borderRadius = Long.MAX_VALUE;
    }

    public long getBorderRadius() { return borderRadius; }

    /**
     * Clamps the player's fixed-point position to within the border on X and Z.
     * Returns true if the player was pushed back (hit the border).
     */
    public boolean clamp(Player player) {
        boolean hit = false;
        long px = player.getFixedX();
        long pz = player.getFixedZ();
        long borderFP = FixedPoint.fromDouble((double) borderRadius);

        if (px > borderFP) {
            player.setFixedX(borderFP);
            hit = true;
        } else if (px < -borderFP) {
            player.setFixedX(-borderFP);
            hit = true;
        }

        if (pz > borderFP) {
            player.setFixedZ(borderFP);
            hit = true;
        } else if (pz < -borderFP) {
            player.setFixedZ(-borderFP);
            hit = true;
        }

        if (hit) {
            player.resetVelocity();
            borderMessage = "You have reached the world border!";
            borderMessageExpireNanos = System.nanoTime() + MESSAGE_DURATION_NANOS;
        }
        return hit;
    }

    /** Returns a transient border message for HUD display, or null. */
    public String getBorderMessage() {
        if (borderMessage == null) return null;
        if (System.nanoTime() > borderMessageExpireNanos) {
            borderMessage = null;
            return null;
        }
        String msg = borderMessage;
        borderMessage = null; // Fire once per hit
        return msg;
    }
}
