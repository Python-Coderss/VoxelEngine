package com.voxel.world.beta;

import java.util.Random;

/**
 * Faithful port of Beta 1.8.1's MapGenBase. Operates on a full 16×128×16
 * column (byte[16*128*16], index = (x*16+z)*128+y) with classic beta block ids.
 */
public abstract class BetaMapGenBase {
    // Classic beta block ids referenced by the cave/ravine generators.
    protected static final int B_AIR = 0;
    protected static final int B_STONE = 1;
    protected static final int B_GRASS = 2;
    protected static final int B_DIRT = 3;
    protected static final int B_WATER_MOVING = 8;
    protected static final int B_WATER_STILL = 9;
    protected static final int B_LAVA_MOVING = 10;

    protected int field_1306_a = 8;
    protected Random rand = new Random();

    public void generate(long worldSeed, int chunkX, int chunkZ, byte[] blocks) {
        int range = this.field_1306_a;
        this.rand.setSeed(worldSeed);
        long seedA = this.rand.nextLong();
        long seedB = this.rand.nextLong();
        for (int cx = chunkX - range; cx <= chunkX + range; ++cx) {
            for (int cz = chunkZ - range; cz <= chunkZ + range; ++cz) {
                long a = (long) cx * seedA;
                long b = (long) cz * seedB;
                this.rand.setSeed(a ^ b ^ worldSeed);
                this.recursiveGenerate(cx, cz, chunkX, chunkZ, blocks);
            }
        }
    }

    protected void recursiveGenerate(int cx, int cz, int chunkX, int chunkZ, byte[] blocks) {
    }
}
