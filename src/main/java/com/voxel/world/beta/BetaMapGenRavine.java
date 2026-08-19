package com.voxel.world.beta;

import java.util.Random;

/** Faithful port of Beta 1.8.1's MapGenRavine. */
public class BetaMapGenRavine extends BetaMapGenBase {
    private float[] field_35627_a = new float[1024];

    protected void func_35626_a(long seed, int chunkX, int chunkZ, byte[] blocks,
                                double x, double y, double z, float size, float yaw, float pitch,
                                int from, int to, double yScale) {
        Random rand = new Random(seed);
        double centerX = (double) (chunkX * 16 + 8);
        double centerZ = (double) (chunkZ * 16 + 8);
        float dYaw = 0.0F;
        float dPitch = 0.0F;
        if (to <= 0) {
            int range = this.field_1306_a * 16 - 16;
            to = range - rand.nextInt(range / 4);
        }
        boolean initial = false;
        if (from == -1) {
            from = to / 2;
            initial = true;
        }
        float width = 1.0F;
        int i = 0;
        while (true) {
            if (i >= 128) break;
            if (i == 0 || rand.nextInt(3) == 0) {
                width = 1.0F + rand.nextFloat() * rand.nextFloat() * 1.0F;
            }
            this.field_35627_a[i] = width * width;
            ++i;
        }

        for (; from < to; ++from) {
            double radius = 1.5D + (double) (BetaMathHelper.sin((float) from * (float) Math.PI / (float) to) * size * 1.0F);
            double radiusY = radius * yScale;
            radius *= (double) rand.nextFloat() * 0.25D + 0.75D;
            radiusY *= (double) rand.nextFloat() * 0.25D + 0.75D;
            float cosPitch = BetaMathHelper.cos(pitch);
            float sinPitch = BetaMathHelper.sin(pitch);
            x += (double) (BetaMathHelper.cos(yaw) * cosPitch);
            y += (double) sinPitch;
            z += (double) (BetaMathHelper.sin(yaw) * cosPitch);
            pitch *= 0.7F;
            pitch += dPitch * 0.05F;
            yaw += dYaw * 0.05F;
            dPitch *= 0.8F;
            dYaw *= 0.5F;
            dPitch += (rand.nextFloat() - rand.nextFloat()) * rand.nextFloat() * 2.0F;
            dYaw += (rand.nextFloat() - rand.nextFloat()) * rand.nextFloat() * 4.0F;

            if (initial || rand.nextInt(4) != 0) {
                double dx = x - centerX;
                double dz = z - centerZ;
                double remaining = (double) (to - from);
                double limit = (double) (size + 2.0F + 16.0F);
                if (dx * dx + dz * dz - remaining * remaining > limit * limit) {
                    return;
                }
                if (x >= centerX - 16.0D - radius * 2.0D && z >= centerZ - 16.0D - radius * 2.0D
                        && x <= centerX + 16.0D + radius * 2.0D && z <= centerZ + 16.0D + radius * 2.0D) {
                    int minX = BetaMathHelper.floor_double(x - radius) - chunkX * 16 - 1;
                    int maxX = BetaMathHelper.floor_double(x + radius) - chunkX * 16 + 1;
                    int minY = BetaMathHelper.floor_double(y - radiusY) - 1;
                    int maxY = BetaMathHelper.floor_double(y + radiusY) + 1;
                    int minZ = BetaMathHelper.floor_double(z - radius) - chunkZ * 16 - 1;
                    int maxZ = BetaMathHelper.floor_double(z + radius) - chunkZ * 16 + 1;
                    if (minX < 0) minX = 0;
                    if (maxX > 16) maxX = 16;
                    if (minY < 1) minY = 1;
                    if (maxY > 128 - 8) maxY = 128 - 8;
                    if (minZ < 0) minZ = 0;
                    if (maxZ > 16) maxZ = 16;

                    boolean hitWater = false;
                    int ix, iz, iy;
                    for (ix = minX; !hitWater && ix < maxX; ++ix) {
                        for (iz = minZ; !hitWater && iz < maxZ; ++iz) {
                            for (iy = maxY + 1; !hitWater && iy >= minY - 1; --iy) {
                                int idx = (ix * 16 + iz) * 128 + iy;
                                if (iy >= 0 && iy < 128) {
                                    if (blocks[idx] == B_WATER_MOVING || blocks[idx] == B_WATER_STILL) {
                                        hitWater = true;
                                    }
                                    if (iy != minY - 1 && ix != minX && ix != maxX - 1 && iz != minZ && iz != maxZ - 1) {
                                        iy = minY;
                                    }
                                }
                            }
                        }
                    }

                    if (!hitWater) {
                        for (ix = minX; ix < maxX; ++ix) {
                            double nx = ((double) (ix + chunkX * 16) + 0.5D - x) / radius;
                            for (iz = minZ; iz < maxZ; ++iz) {
                                double nz = ((double) (iz + chunkZ * 16) + 0.5D - z) / radius;
                                int idx = (ix * 16 + iz) * 128 + maxY;
                                boolean grassAbove = false;
                                if (nx * nx + nz * nz < 1.0D) {
                                    for (int yy = maxY - 1; yy >= minY; --yy) {
                                        double ny = ((double) yy + 0.5D - y) / radiusY;
                                        if ((nx * nx + nz * nz) * (double) this.field_35627_a[yy] + ny * ny / 6.0D < 1.0D) {
                                            byte block = blocks[idx];
                                            if (block == B_GRASS) grassAbove = true;
                                            if (block == B_STONE || block == B_DIRT || block == B_GRASS) {
                                                if (yy < 10) {
                                                    blocks[idx] = (byte) B_LAVA_MOVING;
                                                } else {
                                                    blocks[idx] = 0;
                                                    if (grassAbove && blocks[idx - 1] == B_DIRT) {
                                                        blocks[idx - 1] = (byte) B_GRASS;
                                                    }
                                                }
                                            }
                                        }
                                        --idx;
                                    }
                                }
                            }
                        }
                        if (initial) break;
                    }
                }
            }
        }
    }

    @Override
    protected void recursiveGenerate(int cx, int cz, int chunkX, int chunkZ, byte[] blocks) {
        if (this.rand.nextInt(50) == 0) {
            double x = (double) (cx * 16 + this.rand.nextInt(16));
            double y = (double) (this.rand.nextInt(this.rand.nextInt(40) + 8) + 20);
            double z = (double) (cz * 16 + this.rand.nextInt(16));
            for (int i = 0; i < 1; ++i) {
                float yaw = this.rand.nextFloat() * (float) Math.PI * 2.0F;
                float pitch = (this.rand.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float size = (this.rand.nextFloat() * 2.0F + this.rand.nextFloat()) * 2.0F;
                this.func_35626_a(this.rand.nextLong(), chunkX, chunkZ, blocks, x, y, z,
                        size, yaw, pitch, 0, 0, 3.0D);
            }
        }
    }
}
