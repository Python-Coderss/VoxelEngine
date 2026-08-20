package com.voxel.world;

/**
 * Procedural Stronghold placement, mirroring Mojang's 1.12.2 algorithm.
 *
 * <p>Mojang's stronghold ring picks a random angle θ and radius r per save,
 * then places 1..3 strongholds on the ring centered at world (0, 0):</p>
 *
 * <pre>
 *   θ ∈ [0, 2π)
 *   r ∈ [0.9 · 1400, 1.1 · 1500] chunks   (≈ 1400..1650 chunks from spawn)
 *   strongholdChunkX = (int) (cos θ · r)
 *   strongholdChunkZ = (int) (sin θ · r)
 * </pre>
 *
 * <p>We emit a single stronghold per save to keep the Overworld scope
 * bounded. Adding more is a one-line change in {@link #resolve(long)}.</p>
 *
 * <p>Determinism: given the same world seed, the stronghold lands at the
 * same chunk. We use {@link java.util.Random} with the seed long so two
 * saves with identical seeds pick identical strongholds.</p>
 */
public final class StrongholdPlacement {
    private StrongholdPlacement() {}

    /** Inner-radius in chunks (Mojang's 0.9 × 1400). */
    private static final int RING_MIN_CHUNKS = 1260;
    /** Outer-radius in chunks (Mojang's 1.1 × 1500). */
    private static final int RING_MAX_CHUNKS = 1650;
    /** Fallback surface-Y when {@code getHeight()} returns 0 (e.g. all-air). */
    private static final int FALLBACK_SURFACE_Y = 64;

    /** Resolution result: chunk coordinates and the matching surface Y. */
    public static final class Resolution {
        public final int chunkX;
        public final int chunkZ;
        public final int baseY;

        public Resolution(int chunkX, int chunkZ, int baseY) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.baseY = baseY;
        }
    }

    /**
     * Resolve a stronghold location for the given world seed. Always picks
     * the FIRST stronghold slot in the ring; subsequent slots are not yet
     * implemented but the math is identical.
     *
     * @param worldSeed  the per-save seed (0L means classic demo world)
     * @return the chunk coords + suggested base Y for the stronghold room
     */
    public static Resolution resolve(long worldSeed) {
        // The seed MUST be non-zero; Random(0) throws. Use a hashed value
        // when the player has the default seed.
        long rngSeed = worldSeed == 0L ? 0xC0FFEEL : worldSeed;
        java.util.Random rng = new java.util.Random(rngSeed);

        double theta = rng.nextDouble() * 2.0 * Math.PI;
        int radius = RING_MIN_CHUNKS + rng.nextInt(RING_MAX_CHUNKS - RING_MIN_CHUNKS);

        int chunkX = (int) Math.round(Math.cos(theta) * radius);
        int chunkZ = (int) Math.round(Math.sin(theta) * radius);

        // The base Y is the terrain surface minus a small overhang so the
        // portal room sits with its floor on solid stone. We don't sample
        // the generator here because we don't have a handle to it; Main
        // adjust this after-the-fact.
        int baseY = FALLBACK_SURFACE_Y;
        return new Resolution(chunkX, chunkZ, baseY);
    }
}