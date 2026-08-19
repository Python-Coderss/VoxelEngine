package com.voxel.world.beta;

import java.util.Random;

/**
 * Faithful ports of Beta 1.8.1's WorldGen* feature classes, adapted to write
 * engine block ids into the VoxelEngine world through {@link BetaGenContext}.
 */

/** Block access surface used by the WorldGen feature classes. */
interface BetaGenContext {
    BetaBlocks b();
    int getBlock(int x, int y, int z);
    void setBlock(int x, int y, int z, int id);
    default boolean isAir(int x, int y, int z) { return getBlock(x, y, z) == 0; }
    default boolean isWater(int x, int y, int z) { return getBlock(x, y, z) == b().waterStill; }
    default boolean isSolid(int x, int y, int z) {
        int id = getBlock(x, y, z);
        return id != 0 && id != b().leaves && id != b().waterStill && id != b().lavaStill;
    }
    int getTopSolidOrLiquid(int x, int z);
}

/** WorldGenMinable — ore/dirt/gravel veins. */
class BetaWorldGenMinable {
    private final int blockId;
    private final int count;
    BetaWorldGenMinable(int blockId, int count) { this.blockId = blockId; this.count = count; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        float angle = rand.nextFloat() * (float) Math.PI;
        double sx = (double) ((float) (x + 8) + BetaMathHelper.sin(angle) * (float) this.count / 8.0F);
        double ex = (double) ((float) (x + 8) - BetaMathHelper.sin(angle) * (float) this.count / 8.0F);
        double sz = (double) ((float) (z + 8) + BetaMathHelper.cos(angle) * (float) this.count / 8.0F);
        double ez = (double) ((float) (z + 8) - BetaMathHelper.cos(angle) * (float) this.count / 8.0F);
        double sy = (double) (y + rand.nextInt(3) - 2);
        double ey = (double) (y + rand.nextInt(3) - 2);

        for (int i = 0; i <= this.count; ++i) {
            double cx = sx + (ex - sx) * (double) i / (double) this.count;
            double cy = sy + (ey - sy) * (double) i / (double) this.count;
            double cz = sz + (ez - sz) * (double) i / (double) this.count;
            double r = rand.nextDouble() * (double) this.count / 16.0D;
            double rx = (double) (BetaMathHelper.sin((float) i * (float) Math.PI / (float) this.count) + 1.0F) * r + 1.0D;
            double ry = (double) (BetaMathHelper.sin((float) i * (float) Math.PI / (float) this.count) + 1.0F) * r + 1.0D;
            int minX = BetaMathHelper.floor_double(cx - rx / 2.0D);
            int minY = BetaMathHelper.floor_double(cy - ry / 2.0D);
            int minZ = BetaMathHelper.floor_double(cz - rx / 2.0D);
            int maxX = BetaMathHelper.floor_double(cx + rx / 2.0D);
            int maxY = BetaMathHelper.floor_double(cy + ry / 2.0D);
            int maxZ = BetaMathHelper.floor_double(cz + rx / 2.0D);

            for (int bx = minX; bx <= maxX; ++bx) {
                double dx = ((double) bx + 0.5D - cx) / (rx / 2.0D);
                if (dx * dx < 1.0D) {
                    for (int by = minY; by <= maxY; ++by) {
                        double dy = ((double) by + 0.5D - cy) / (ry / 2.0D);
                        if (dx * dx + dy * dy < 1.0D) {
                            for (int bz = minZ; bz <= maxZ; ++bz) {
                                double dz = ((double) bz + 0.5D - cz) / (rx / 2.0D);
                                if (dx * dx + dy * dy + dz * dz < 1.0D && ctx.getBlock(bx, by, bz) == ctx.b().stone) {
                                    ctx.setBlock(bx, by, bz, this.blockId);
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}

/** WorldGenTrees — small oak. */
class BetaWorldGenTrees {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int height = rand.nextInt(3) + 4;
        boolean flag = true;
        if (y >= 1 && y + height + 1 <= 128) {
            int i;
            for (i = y; i <= y + 1 + height; ++i) {
                int radius = 1;
                if (i == y) radius = 0;
                if (i >= y + 1 + height - 2) radius = 2;
                for (int xx = x - radius; xx <= x + radius && flag; ++xx) {
                    for (int zz = z - radius; zz <= z + radius && flag; ++zz) {
                        if (i >= 0 && i < 128) {
                            int id = ctx.getBlock(xx, i, zz);
                            if (id != 0 && id != ctx.b().leaves) flag = false;
                        } else flag = false;
                    }
                }
            }
            if (!flag) return false;
            int below = ctx.getBlock(x, y - 1, z);
            if (below == ctx.b().grass || below == ctx.b().dirt) {
                if (y < 128 - height - 1) {
                    ctx.setBlock(x, y - 1, z, ctx.b().dirt);
                    int yy;
                    for (yy = y - 3 + height; yy <= y + height; ++yy) {
                        int off = yy - (y + height);
                        int radius = 1 - off / 2;
                        for (int xx = x - radius; xx <= x + radius; ++xx) {
                            int dx = xx - x;
                            for (int zz = z - radius; zz <= z + radius; ++zz) {
                                int dz = zz - z;
                                if ((Math.abs(dx) != radius || Math.abs(dz) != radius || rand.nextInt(2) != 0 && off != 0)
                                        && !ctx.isSolid(xx, yy, zz)) {
                                    ctx.setBlock(xx, yy, zz, ctx.b().leaves);
                                }
                            }
                        }
                    }
                    for (yy = 0; yy < height; ++yy) {
                        int id = ctx.getBlock(x, y + yy, z);
                        if (id == 0 || id == ctx.b().leaves) {
                            ctx.setBlock(x, y + yy, z, ctx.b().wood);
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}

/** WorldGenForest — birch (5–7 tall, 2-wide crown). */
class BetaWorldGenForest {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int height = rand.nextInt(3) + 5;
        boolean flag = true;
        if (y >= 1 && y + height + 1 <= 128) {
            int i;
            for (i = y; i <= y + 1 + height; ++i) {
                int radius = 1;
                if (i == y) radius = 0;
                if (i >= y + 1 + height - 2) radius = 2;
                for (int xx = x - radius; xx <= x + radius && flag; ++xx) {
                    for (int zz = z - radius; zz <= z + radius && flag; ++zz) {
                        if (i >= 0 && i < 128) {
                            int id = ctx.getBlock(xx, i, zz);
                            if (id != 0 && id != ctx.b().leaves) flag = false;
                        } else flag = false;
                    }
                }
            }
            if (!flag) return false;
            int below = ctx.getBlock(x, y - 1, z);
            if (below == ctx.b().grass || below == ctx.b().dirt) {
                if (y < 128 - height - 1) {
                    ctx.setBlock(x, y - 1, z, ctx.b().dirt);
                    for (i = y - 3 + height; i <= y + height; ++i) {
                        int off = i - (y + height);
                        int radius = 1 - off / 2;
                        for (int xx = x - radius; xx <= x + radius; ++xx) {
                            int dx = xx - x;
                            for (int zz = z - radius; zz <= z + radius; ++zz) {
                                int dz = zz - z;
                                if ((Math.abs(dx) != radius || Math.abs(dz) != radius || rand.nextInt(2) != 0 && off != 0)
                                        && !ctx.isSolid(xx, i, zz)) {
                                    ctx.setBlock(xx, i, zz, ctx.b().leaves);
                                }
                            }
                        }
                    }
                    for (i = 0; i < height; ++i) {
                        int id = ctx.getBlock(x, y + i, z);
                        if (id == 0 || id == ctx.b().leaves) ctx.setBlock(x, y + i, z, ctx.b().wood);
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}

/** WorldGenSwamp — swamp oak with vines. */
class BetaWorldGenSwamp {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int height;
        for (height = rand.nextInt(4) + 5; ctx.isWater(x, y - 1, z); --y) {
        }
        boolean flag = true;
        if (y >= 1 && y + height + 1 <= 128) {
            int i;
            for (i = y; i <= y + 1 + height; ++i) {
                int radius = 1;
                if (i == y) radius = 0;
                if (i >= y + 1 + height - 2) radius = 3;
                for (int xx = x - radius; xx <= x + radius && flag; ++xx) {
                    for (int zz = z - radius; zz <= z + radius && flag; ++zz) {
                        if (i >= 0 && i < 128) {
                            int id = ctx.getBlock(xx, i, zz);
                            if (id == 0 || id == ctx.b().leaves) continue;
                            if (id != ctx.b().waterStill && id != ctx.b().waterStill) flag = false;
                            else if (i > y) flag = false;
                        } else flag = false;
                    }
                }
            }
            if (!flag) return false;
            int below = ctx.getBlock(x, y - 1, z);
            if (below == ctx.b().grass || below == ctx.b().dirt) {
                if (y < 128 - height - 1) {
                    ctx.setBlock(x, y - 1, z, ctx.b().dirt);
                    for (i = y - 3 + height; i <= y + height; ++i) {
                        int off = i - (y + height);
                        int radius = 2 - off / 2;
                        for (int xx = x - radius; xx <= x + radius; ++xx) {
                            int dx = xx - x;
                            for (int zz = z - radius; zz <= z + radius; ++zz) {
                                int dz = zz - z;
                                if ((Math.abs(dx) != radius || Math.abs(dz) != radius || rand.nextInt(2) != 0 && off != 0)
                                        && !ctx.isSolid(xx, i, zz)) {
                                    ctx.setBlock(xx, i, zz, ctx.b().leaves);
                                }
                            }
                        }
                    }
                    for (i = 0; i < height; ++i) {
                        int id = ctx.getBlock(x, y + i, z);
                        if (id == 0 || id == ctx.b().leaves || id == ctx.b().waterStill) {
                            ctx.setBlock(x, y + i, z, ctx.b().wood);
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}

/** WorldGenTaiga1 — tall spruce. */
class BetaWorldGenTaiga1 {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int height = rand.nextInt(5) + 7;
        int crownStart = height - rand.nextInt(2) - 3;
        int crownHeight = height - crownStart;
        int crownWidth = 1 + rand.nextInt(crownHeight + 1);
        boolean flag = true;
        if (y >= 1 && y + height + 1 <= 128) {
            int i;
            for (i = y; i <= y + 1 + height && flag; ++i) {
                int radius = (i - y < crownStart) ? 0 : crownWidth;
                for (int xx = x - radius; xx <= x + radius && flag; ++xx) {
                    for (int zz = z - radius; zz <= z + radius && flag; ++zz) {
                        if (i >= 0 && i < 128) {
                            int id = ctx.getBlock(xx, i, zz);
                            if (id != 0 && id != ctx.b().leaves) flag = false;
                        } else flag = false;
                    }
                }
            }
            if (!flag) return false;
            int below = ctx.getBlock(x, y - 1, z);
            if (below == ctx.b().grass || below == ctx.b().dirt) {
                if (y < 128 - height - 1) {
                    ctx.setBlock(x, y - 1, z, ctx.b().dirt);
                    int width = 0;
                    for (i = y + height; i >= y + crownStart; --i) {
                        for (int xx = x - width; xx <= x + width; ++xx) {
                            int dx = xx - x;
                            for (int zz = z - width; zz <= z + width; ++zz) {
                                int dz = zz - z;
                                if ((Math.abs(dx) != width || Math.abs(dz) != width || width <= 0)
                                        && !ctx.isSolid(xx, i, zz)) {
                                    ctx.setBlock(xx, i, zz, ctx.b().leaves);
                                }
                            }
                        }
                        if (width >= 1 && i == y + crownStart + 1) --width;
                        else if (width < crownWidth) ++width;
                    }
                    for (i = 0; i < height - 1; ++i) {
                        int id = ctx.getBlock(x, y + i, z);
                        if (id == 0 || id == ctx.b().leaves) ctx.setBlock(x, y + i, z, ctx.b().wood);
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}

/** WorldGenTaiga2 — pine. */
class BetaWorldGenTaiga2 {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int height = rand.nextInt(4) + 6;
        int crownStart = 1 + rand.nextInt(2);
        int crownHeight = height - crownStart;
        int crownWidth = 2 + rand.nextInt(2);
        boolean flag = true;
        if (y >= 1 && y + height + 1 <= 128) {
            int i;
            for (i = y; i <= y + 1 + height && flag; ++i) {
                int radius = (i - y < crownStart) ? 0 : crownWidth;
                for (int xx = x - radius; xx <= x + radius && flag; ++xx) {
                    for (int zz = z - radius; zz <= z + radius && flag; ++zz) {
                        if (i >= 0 && i < 128) {
                            int id = ctx.getBlock(xx, i, zz);
                            if (id != 0 && id != ctx.b().leaves) flag = false;
                        } else flag = false;
                    }
                }
            }
            if (!flag) return false;
            int below = ctx.getBlock(x, y - 1, z);
            if (below == ctx.b().grass || below == ctx.b().dirt) {
                if (y < 128 - height - 1) {
                    ctx.setBlock(x, y - 1, z, ctx.b().dirt);
                    int width = rand.nextInt(2);
                    int target = 1;
                    int reset = 0;
                    for (i = 0; i <= crownHeight; ++i) {
                        int yy = y + height - i;
                        for (int xx = x - width; xx <= x + width; ++xx) {
                            int dx = xx - x;
                            for (int zz = z - width; zz <= z + width; ++zz) {
                                int dz = zz - z;
                                if ((Math.abs(dx) != width || Math.abs(dz) != width || width <= 0)
                                        && !ctx.isSolid(xx, yy, zz)) {
                                    ctx.setBlock(xx, yy, zz, ctx.b().leaves);
                                }
                            }
                        }
                        if (width >= target) {
                            width = reset;
                            reset = 1;
                            ++target;
                            if (target > crownWidth) target = crownWidth;
                        } else {
                            ++width;
                        }
                    }
                    int skip = rand.nextInt(3);
                    for (i = 0; i < height - skip; ++i) {
                        int id = ctx.getBlock(x, y + i, z);
                        if (id == 0 || id == ctx.b().leaves) ctx.setBlock(x, y + i, z, ctx.b().wood);
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}

/** WorldGenBigTree — large branching oak. */
class BetaWorldGenBigTree {
    static final byte[] otherCoordPairs = new byte[]{(byte) 2, (byte) 0, (byte) 0, (byte) 1, (byte) 2, (byte) 1};
    Random rand = new Random();
    BetaGenContext worldObj;
    int[] basePos = new int[]{0, 0, 0};
    int heightLimit = 0;
    int height;
    double heightAttenuation = 0.618D;
    double field_875_h = 1.0D;
    double field_874_i = 0.381D;
    double field_873_j = 1.0D;
    double field_872_k = 1.0D;
    int trunkSize = 1;
    int heightLimitLimit = 12;
    int leafDistanceLimit = 4;
    int[][] leafNodes;

    void generateLeafNodeList() {
        this.height = (int) ((double) this.heightLimit * this.heightAttenuation);
        if (this.height >= this.heightLimit) this.height = this.heightLimit - 1;
        int nodeCount = (int) (1.382D + Math.pow(this.field_872_k * (double) this.heightLimit / 13.0D, 2.0D));
        if (nodeCount < 1) nodeCount = 1;
        int[][] nodes = new int[nodeCount * this.heightLimit][4];
        int baseY = this.basePos[1] + this.heightLimit - this.leafDistanceLimit;
        int idx = 1;
        int topY = this.basePos[1] + this.height;
        int relY = baseY - this.basePos[1];
        nodes[0][0] = this.basePos[0];
        nodes[0][1] = baseY;
        nodes[0][2] = this.basePos[2];
        nodes[0][3] = topY;
        --baseY;

        while (relY >= 0) {
            int n = 0;
            float radius = this.func_528_a(relY);
            if (radius < 0.0F) {
                --baseY;
                --relY;
            } else {
                for (double d = 0.5D; n < nodeCount; ++n) {
                    double rr = this.field_873_j * (double) radius * ((double) this.rand.nextFloat() + 0.328D);
                    double ang = (double) this.rand.nextFloat() * 2.0D * 3.14159D;
                    int nx = BetaMathHelper.floor_double(rr * Math.sin(ang) + (double) this.basePos[0] + d);
                    int nz = BetaMathHelper.floor_double(rr * Math.cos(ang) + (double) this.basePos[2] + d);
                    int[] leafPos = new int[]{nx, baseY, nz};
                    int[] leafTop = new int[]{nx, baseY + this.leafDistanceLimit, nz};
                    if (this.checkBlockLine(leafPos, leafTop) == -1) {
                        int[] basePos2 = new int[]{this.basePos[0], this.basePos[1], this.basePos[2]};
                        double dist = Math.sqrt(Math.pow((double) Math.abs(this.basePos[0] - leafPos[0]), 2.0D)
                                + Math.pow((double) Math.abs(this.basePos[2] - leafPos[2]), 2.0D));
                        double dy = dist * this.field_874_i;
                        if ((double) leafPos[1] - dy > (double) topY) basePos2[1] = topY;
                        else basePos2[1] = (int) ((double) leafPos[1] - dy);
                        if (this.checkBlockLine(basePos2, leafPos) == -1) {
                            nodes[idx][0] = nx;
                            nodes[idx][1] = baseY;
                            nodes[idx][2] = nz;
                            nodes[idx][3] = basePos2[1];
                            ++idx;
                        }
                    }
                }
                --baseY;
                --relY;
            }
        }
        this.leafNodes = new int[idx][4];
        System.arraycopy(nodes, 0, this.leafNodes, 0, idx);
    }

    void func_523_a(int x, int y, int z, float radius, byte axis, int block) {
        int r = (int) ((double) radius + 0.618D);
        byte a1 = otherCoordPairs[axis];
        byte a2 = otherCoordPairs[axis + 3];
        int[] pos = new int[]{x, y, z};
        int[] off = new int[]{0, 0, 0};
        int i = -r;
        int j = -r;
        label:
        for (off[axis] = pos[axis]; i <= r; ++i) {
            off[a1] = pos[a1] + i;
            j = -r;
            while (true) {
                if (j > r) continue label;
                double dist = Math.sqrt(Math.pow((double) Math.abs(i) + 0.5D, 2.0D) + Math.pow((double) Math.abs(j) + 0.5D, 2.0D));
                if (dist > (double) radius) {
                    ++j;
                } else {
                    off[a2] = pos[a2] + j;
                    int id = this.worldObj.getBlock(off[0], off[1], off[2]);
                    if (id != 0 && id != this.worldObj.b().leaves) ++j;
                    else {
                        this.worldObj.setBlock(off[0], off[1], off[2], block);
                        ++j;
                    }
                }
            }
        }
    }

    float func_528_a(int y) {
        if ((double) y < (double) ((float) this.heightLimit) * 0.3D) return -1.618F;
        else {
            float half = (float) this.heightLimit / 2.0F;
            float diff = (float) this.heightLimit / 2.0F - (float) y;
            float radius;
            if (diff == 0.0F) radius = half;
            else if (Math.abs(diff) >= half) radius = 0.0F;
            else radius = (float) Math.sqrt(Math.pow((double) Math.abs(half), 2.0D) - Math.pow((double) Math.abs(diff), 2.0D));
            radius *= 0.5F;
            return radius;
        }
    }

    float func_526_b(int y) {
        return y >= 0 && y < this.leafDistanceLimit
                ? (y != 0 && y != this.leafDistanceLimit - 1 ? 3.0F : 2.0F) : -1.0F;
    }

    void generateLeafNode(int x, int y, int z) {
        for (int yy = y; yy < y + this.leafDistanceLimit; ++yy) {
            float radius = this.func_526_b(yy - y);
            this.func_523_a(x, yy, z, radius, (byte) 1, this.worldObj.b().leaves);
        }
    }

    void placeBlockLine(int[] a, int[] b, int block) {
        int[] delta = new int[]{0, 0, 0};
        byte axis = 0;
        for (byte i = 0; i < 3; ++i) {
            delta[i] = b[i] - a[i];
            if (Math.abs(delta[i]) > Math.abs(delta[axis])) axis = i;
        }
        if (delta[axis] != 0) {
            byte a1 = otherCoordPairs[axis];
            byte a2 = otherCoordPairs[axis + 3];
            byte dir = delta[axis] > 0 ? (byte) 1 : (byte) -1;
            double d1 = (double) delta[a1] / (double) delta[axis];
            double d2 = (double) delta[a2] / (double) delta[axis];
            int[] pos = new int[]{0, 0, 0};
            int i = 0;
            for (int end = delta[axis] + dir; i != end; i += dir) {
                pos[axis] = BetaMathHelper.floor_double((double) (a[axis] + i) + 0.5D);
                pos[a1] = BetaMathHelper.floor_double((double) a[a1] + (double) i * d1 + 0.5D);
                pos[a2] = BetaMathHelper.floor_double((double) a[a2] + (double) i * d2 + 0.5D);
                this.worldObj.setBlock(pos[0], pos[1], pos[2], block);
            }
        }
    }

    void generateLeaves() {
        for (int[] leaf : this.leafNodes) {
            this.generateLeafNode(leaf[0], leaf[1], leaf[2]);
        }
    }

    boolean leafNodeNeedsBase(int y) {
        return (double) y >= (double) this.heightLimit * 0.2D;
    }

    void generateTrunk() {
        int[] a = new int[]{this.basePos[0], this.basePos[1], this.basePos[2]};
        int[] b = new int[]{this.basePos[0], this.basePos[1] + this.height, this.basePos[2]};
        this.placeBlockLine(a, b, this.worldObj.b().wood);
        if (this.trunkSize == 2) {
            ++a[0]; ++b[0];
            this.placeBlockLine(a, b, this.worldObj.b().wood);
            ++a[2]; ++b[2];
            this.placeBlockLine(a, b, this.worldObj.b().wood);
            a[0] += -1; b[0] += -1;
            this.placeBlockLine(a, b, this.worldObj.b().wood);
        }
    }

    void generateLeafNodeBases() {
        int[] base = new int[]{this.basePos[0], this.basePos[1], this.basePos[2]};
        for (int[] leaf : this.leafNodes) {
            int[] pos = new int[]{leaf[0], leaf[1], leaf[2]};
            base[1] = leaf[3];
            int rel = base[1] - this.basePos[1];
            if (this.leafNodeNeedsBase(rel)) this.placeBlockLine(base, pos, this.worldObj.b().wood);
        }
    }

    int checkBlockLine(int[] a, int[] b) {
        int[] delta = new int[]{0, 0, 0};
        byte axis = 0;
        for (byte i = 0; i < 3; ++i) {
            delta[i] = b[i] - a[i];
            if (Math.abs(delta[i]) > Math.abs(delta[axis])) axis = i;
        }
        if (delta[axis] == 0) return -1;
        else {
            byte a1 = otherCoordPairs[axis];
            byte a2 = otherCoordPairs[axis + 3];
            byte dir = delta[axis] > 0 ? (byte) 1 : (byte) -1;
            double d1 = (double) delta[a1] / (double) delta[axis];
            double d2 = (double) delta[a2] / (double) delta[axis];
            int[] pos = new int[]{0, 0, 0};
            int i = 0;
            int end;
            for (end = delta[axis] + dir; i != end; i += dir) {
                pos[axis] = a[axis] + i;
                pos[a1] = BetaMathHelper.floor_double((double) a[a1] + (double) i * d1);
                pos[a2] = BetaMathHelper.floor_double((double) a[a2] + (double) i * d2);
                int id = this.worldObj.getBlock(pos[0], pos[1], pos[2]);
                if (id != 0 && id != this.worldObj.b().leaves) break;
            }
            return i == end ? -1 : Math.abs(i);
        }
    }

    boolean validTreeLocation() {
        int[] a = new int[]{this.basePos[0], this.basePos[1], this.basePos[2]};
        int[] b = new int[]{this.basePos[0], this.basePos[1] + this.heightLimit - 1, this.basePos[2]};
        int below = this.worldObj.getBlock(this.basePos[0], this.basePos[1] - 1, this.basePos[2]);
        if (below != this.worldObj.b().grass && below != this.worldObj.b().dirt) return false;
        int v = this.checkBlockLine(a, b);
        if (v == -1) return true;
        else if (v < 6) return false;
        else { this.heightLimit = v; return true; }
    }

    void func_517_a(double h, double w, double scale) {
        this.heightLimitLimit = (int) (h * 12.0D);
        if (h > 0.5D) this.leafDistanceLimit = 5;
        this.field_873_j = w;
        this.field_872_k = scale;
    }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        this.worldObj = ctx;
        this.rand.setSeed(rand.nextLong());
        this.basePos[0] = x;
        this.basePos[1] = y;
        this.basePos[2] = z;
        if (this.heightLimit == 0) {
            this.heightLimit = 5 + this.rand.nextInt(this.heightLimitLimit);
        }
        if (!this.validTreeLocation()) return false;
        else {
            this.generateLeafNodeList();
            this.generateLeaves();
            this.generateTrunk();
            this.generateLeafNodeBases();
            return true;
        }
    }
}

/** WorldGenFlowers — scatter a flower. */
class BetaWorldGenFlowers {
    private final int plantId;
    BetaWorldGenFlowers(int plantId) { this.plantId = plantId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        for (int i = 0; i < 64; ++i) {
            int xx = x + rand.nextInt(8) - rand.nextInt(8);
            int yy = y + rand.nextInt(4) - rand.nextInt(4);
            int zz = z + rand.nextInt(8) - rand.nextInt(8);
            if (ctx.isAir(xx, yy, zz) && ctx.getBlock(xx, yy - 1, zz) == ctx.b().grass) {
                ctx.setBlock(xx, yy, zz, this.plantId);
            }
        }
        return true;
    }
}

/** WorldGenTallGrass — scatter tall grass. */
class BetaWorldGenTallGrass {
    private final int plantId;
    BetaWorldGenTallGrass(int plantId) { this.plantId = plantId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        boolean done = false;
        int base;
        while (true) {
            base = ctx.getBlock(x, y, z);
            if (base != 0 && base != ctx.b().leaves || y <= 0) {
                for (int i = 0; i < 128; ++i) {
                    int xx = x + rand.nextInt(8) - rand.nextInt(8);
                    int yy = y + rand.nextInt(4) - rand.nextInt(4);
                    int zz = z + rand.nextInt(8) - rand.nextInt(8);
                    if (ctx.isAir(xx, yy, zz) && ctx.getBlock(xx, yy - 1, zz) == ctx.b().grass) {
                        ctx.setBlock(xx, yy, zz, this.plantId);
                    }
                }
                return true;
            }
            --y;
        }
    }
}

/** WorldGenDeadBush — scatter dead bush on sand. */
class BetaWorldGenDeadBush {
    private final int plantId;
    BetaWorldGenDeadBush(int plantId) { this.plantId = plantId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        int base;
        while (true) {
            base = ctx.getBlock(x, y, z);
            if (base != 0 && base != ctx.b().leaves || y <= 0) {
                for (int i = 0; i < 4; ++i) {
                    int xx = x + rand.nextInt(8) - rand.nextInt(8);
                    int yy = y + rand.nextInt(4) - rand.nextInt(4);
                    int zz = z + rand.nextInt(8) - rand.nextInt(8);
                    if (ctx.isAir(xx, yy, zz) && ctx.getBlock(xx, yy - 1, zz) == ctx.b().sand) {
                        ctx.setBlock(xx, yy, zz, this.plantId);
                    }
                }
                return true;
            }
            --y;
        }
    }
}

/** WorldGenReed — sugar cane by water. */
class BetaWorldGenReed {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        for (int i = 0; i < 20; ++i) {
            int xx = x + rand.nextInt(4) - rand.nextInt(4);
            int zz = z + rand.nextInt(4) - rand.nextInt(4);
            if (ctx.isAir(xx, y, zz)
                    && (ctx.isWater(xx - 1, y - 1, zz) || ctx.isWater(xx + 1, y - 1, zz)
                        || ctx.isWater(xx, y - 1, zz - 1) || ctx.isWater(xx, y - 1, zz + 1))) {
                int h = 2 + rand.nextInt(rand.nextInt(3) + 1);
                for (int yy = 0; yy < h; ++yy) {
                    int below = ctx.getBlock(xx, y + yy - 1, zz);
                    if (ctx.isAir(xx, y + yy, zz)
                            && (below == ctx.b().grass || below == ctx.b().dirt || below == ctx.b().sand)) {
                        ctx.setBlock(xx, y + yy, zz, ctx.b().reeds);
                    }
                }
            }
        }
        return true;
    }
}

/** WorldGenCactus — cactus on sand. */
class BetaWorldGenCactus {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        for (int i = 0; i < 10; ++i) {
            int xx = x + rand.nextInt(8) - rand.nextInt(8);
            int yy = y + rand.nextInt(4) - rand.nextInt(4);
            int zz = z + rand.nextInt(8) - rand.nextInt(8);
            if (ctx.isAir(xx, yy, zz)) {
                int h = 1 + rand.nextInt(rand.nextInt(3) + 1);
                for (int j = 0; j < h; ++j) {
                    if (ctx.isAir(xx, yy + j, zz) && ctx.getBlock(xx, yy + j - 1, zz) == ctx.b().sand) {
                        ctx.setBlock(xx, yy + j, zz, ctx.b().cactus);
                    }
                }
            }
        }
        return true;
    }
}

/** WorldGenPumpkin — pumpkin on grass. */
class BetaWorldGenPumpkin {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        for (int i = 0; i < 64; ++i) {
            int xx = x + rand.nextInt(8) - rand.nextInt(8);
            int yy = y + rand.nextInt(4) - rand.nextInt(4);
            int zz = z + rand.nextInt(8) - rand.nextInt(8);
            if (ctx.isAir(xx, yy, zz) && ctx.getBlock(xx, yy - 1, zz) == ctx.b().grass) {
                ctx.setBlock(xx, yy, zz, ctx.b().pumpkin);
            }
        }
        return true;
    }
}

/** WorldGenClay — clay disc underwater. */
class BetaWorldGenClay {
    private final int count;
    BetaWorldGenClay(int count) { this.count = count; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        if (!ctx.isWater(x, y, z)) return false;
        int r = rand.nextInt(this.count - 2) + 2;
        for (int xx = x - r; xx <= x + r; ++xx) {
            for (int zz = z - r; zz <= z + r; ++zz) {
                int dx = xx - x, dz = zz - z;
                if (dx * dx + dz * dz <= r * r) {
                    for (int yy = y - 1; yy <= y + 1; ++yy) {
                        int id = ctx.getBlock(xx, yy, zz);
                        if (id == ctx.b().dirt || id == ctx.b().clay) ctx.setBlock(xx, yy, zz, ctx.b().clay);
                    }
                }
            }
        }
        return true;
    }
}

/** WorldGenSand — sand/gravel disc underwater. */
class BetaWorldGenSand {
    private final int blockId;
    private final int count;
    BetaWorldGenSand(int count, int blockId) { this.count = count; this.blockId = blockId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        if (!ctx.isWater(x, y, z)) return false;
        int r = rand.nextInt(this.count - 2) + 2;
        for (int xx = x - r; xx <= x + r; ++xx) {
            for (int zz = z - r; zz <= z + r; ++zz) {
                int dx = xx - x, dz = zz - z;
                if (dx * dx + dz * dz <= r * r) {
                    for (int yy = y - 2; yy <= y + 2; ++yy) {
                        int id = ctx.getBlock(xx, yy, zz);
                        if (id == ctx.b().dirt || id == ctx.b().grass) ctx.setBlock(xx, yy, zz, this.blockId);
                    }
                }
            }
        }
        return true;
    }
}

/** WorldGenLiquids — underground water/lava pockets. */
class BetaWorldGenLiquids {
    private final int liquidId;
    BetaWorldGenLiquids(int liquidId) { this.liquidId = liquidId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        if (ctx.getBlock(x, y + 1, z) != ctx.b().stone) return false;
        if (ctx.getBlock(x, y - 1, z) != ctx.b().stone) return false;
        if (ctx.getBlock(x, y, z) != 0 && ctx.getBlock(x, y, z) != ctx.b().stone) return false;
        int solid = 0;
        if (ctx.getBlock(x - 1, y, z) == ctx.b().stone) ++solid;
        if (ctx.getBlock(x + 1, y, z) == ctx.b().stone) ++solid;
        if (ctx.getBlock(x, y, z - 1) == ctx.b().stone) ++solid;
        if (ctx.getBlock(x, y, z + 1) == ctx.b().stone) ++solid;
        int air = 0;
        if (ctx.isAir(x - 1, y, z)) ++air;
        if (ctx.isAir(x + 1, y, z)) ++air;
        if (ctx.isAir(x, y, z - 1)) ++air;
        if (ctx.isAir(x, y, z + 1)) ++air;
        if (solid == 3 && air == 1) {
            ctx.setBlock(x, y, z, this.liquidId);
        }
        return true;
    }
}

/** WorldGenLakes — surface water/lava lake. */
class BetaWorldGenLakes {
    private final int blockId;
    BetaWorldGenLakes(int blockId) { this.blockId = blockId; }

    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        x -= 8;
        z -= 8;
        while (y > 0 && ctx.isAir(x, y, z)) --y;
        y -= 4;
        boolean[] filled = new boolean[2048];
        int n = rand.nextInt(4) + 4;
        int i, j, k;
        for (i = 0; i < n; ++i) {
            double rx = rand.nextDouble() * 6.0D + 3.0D;
            double ry = rand.nextDouble() * 4.0D + 2.0D;
            double rz = rand.nextDouble() * 6.0D + 3.0D;
            double cx = rand.nextDouble() * (16.0D - rx - 2.0D) + 1.0D + rx / 2.0D;
            double cy = rand.nextDouble() * (8.0D - ry - 4.0D) + 2.0D + ry / 2.0D;
            double cz = rand.nextDouble() * (16.0D - rz - 2.0D) + 1.0D + rz / 2.0D;
            for (int xx = 1; xx < 15; ++xx)
                for (int zz = 1; zz < 15; ++zz)
                    for (int yy = 1; yy < 7; ++yy) {
                        double dx = ((double) xx - cx) / (rx / 2.0D);
                        double dy = ((double) yy - cy) / (ry / 2.0D);
                        double dz = ((double) zz - cz) / (rz / 2.0D);
                        if (dx * dx + dy * dy + dz * dz < 1.0D) filled[(xx * 16 + zz) * 8 + yy] = true;
                    }
        }
        for (i = 0; i < 16; ++i)
            for (k = 0; k < 16; ++k)
                for (j = 0; j < 8; ++j) {
                    boolean edge = !filled[(i * 16 + k) * 8 + j]
                            && (i < 15 && filled[((i + 1) * 16 + k) * 8 + j]
                                || i > 0 && filled[((i - 1) * 16 + k) * 8 + j]
                                || k < 15 && filled[(i * 16 + k + 1) * 8 + j]
                                || k > 0 && filled[(i * 16 + (k - 1)) * 8 + j]
                                || j < 7 && filled[(i * 16 + k) * 8 + j + 1]
                                || j > 0 && filled[(i * 16 + k) * 8 + (j - 1)]);
                    if (edge) {
                        boolean liquid = ctx.isWater(x + i, y + j, z + k) || ctx.getBlock(x + i, y + j, z + k) == ctx.b().lavaStill;
                        if (j >= 4 && liquid) return false;
                        if (j < 4 && !ctx.isSolid(x + i, y + j, z + k)
                                && ctx.getBlock(x + i, y + j, z + k) != this.blockId) return false;
                    }
                }
        for (i = 0; i < 16; ++i)
            for (k = 0; k < 16; ++k)
                for (j = 0; j < 8; ++j)
                    if (filled[(i * 16 + k) * 8 + j]) {
                        ctx.setBlock(x + i, y + j, z + k, j >= 4 ? 0 : this.blockId);
                    }
        for (i = 0; i < 16; ++i)
            for (k = 0; k < 16; ++k)
                for (j = 4; j < 8; ++j)
                    if (filled[(i * 16 + k) * 8 + j]
                            && ctx.getBlock(x + i, y + j - 1, z + k) == ctx.b().dirt) {
                        ctx.setBlock(x + i, y + j - 1, z + k, ctx.b().grass);
                    }
        if (this.blockId == ctx.b().lavaStill) {
            for (i = 0; i < 16; ++i)
                for (k = 0; k < 16; ++k)
                    for (j = 0; j < 8; ++j) {
                        boolean edge = !filled[(i * 16 + k) * 8 + j]
                                && (i < 15 && filled[((i + 1) * 16 + k) * 8 + j]
                                    || i > 0 && filled[((i - 1) * 16 + k) * 8 + j]
                                    || k < 15 && filled[(i * 16 + k + 1) * 8 + j]
                                    || k > 0 && filled[(i * 16 + (k - 1)) * 8 + j]
                                    || j < 7 && filled[(i * 16 + k) * 8 + j + 1]
                                    || j > 0 && filled[(i * 16 + k) * 8 + (j - 1)]);
                        if (edge && (j < 4 || rand.nextInt(2) != 0) && ctx.isSolid(x + i, y + j, z + k)) {
                            ctx.setBlock(x + i, y + j, z + k, ctx.b().stone);
                        }
                    }
        }
        return true;
    }
}

/** WorldGenDungeons — cobblestone room with spawner + chests. */
class BetaWorldGenDungeons {
    boolean generate(BetaGenContext ctx, Random rand, int x, int y, int z) {
        byte h = 3;
        int rx = rand.nextInt(2) + 2;
        int rz = rand.nextInt(2) + 2;
        int air = 0;
        int i, j, k;
        for (i = x - rx - 1; i <= x + rx + 1; ++i)
            for (j = y - 1; j <= y + h + 1; ++j)
                for (k = z - rz - 1; k <= z + rz + 1; ++k) {
                    if (j == y - 1 && !ctx.isSolid(i, j, k)) return false;
                    if (j == y + h + 1 && !ctx.isSolid(i, j, k)) return false;
                    if ((i == x - rx - 1 || i == x + rx + 1 || k == z - rz - 1 || k == z + rz + 1)
                            && j == y && ctx.isAir(i, j, k) && ctx.isAir(i, j + 1, k)) ++air;
                }
        if (air >= 1 && air <= 5) {
            for (i = x - rx - 1; i <= x + rx + 1; ++i)
                for (j = y + h; j >= y - 1; --j)
                    for (k = z - rz - 1; k <= z + rz + 1; ++k) {
                        if (i != x - rx - 1 && j != y - 1 && k != z - rz - 1
                                && i != x + rx + 1 && j != y + h + 1 && k != z + rz + 1) {
                            ctx.setBlock(i, j, k, 0);
                        } else if (j >= 0 && !ctx.isSolid(i, j - 1, k)) {
                            ctx.setBlock(i, j, k, 0);
                        } else if (ctx.isSolid(i, j, k)) {
                            if (j == y - 1 && rand.nextInt(4) != 0) ctx.setBlock(i, j, k, ctx.b().mossyCobble);
                            else ctx.setBlock(i, j, k, ctx.b().cobblestone);
                        }
                    }
            ctx.setBlock(x, y, z, ctx.b().spawner);
            for (i = 0; i < 2; ++i) {
                for (j = 0; j < 3; ++j) {
                    int cx = x + rand.nextInt(rx * 2 + 1) - rx;
                    int cz = z + rand.nextInt(rz * 2 + 1) - rz;
                    if (ctx.isAir(cx, y, cz)) {
                        int walls = 0;
                        if (ctx.isSolid(cx - 1, y, cz)) ++walls;
                        if (ctx.isSolid(cx + 1, y, cz)) ++walls;
                        if (ctx.isSolid(cx, y, cz - 1)) ++walls;
                        if (ctx.isSolid(cx, y, cz + 1)) ++walls;
                        if (walls == 1) ctx.setBlock(cx, y, cz, ctx.b().chest);
                    }
                }
            }
            return true;
        }
        return false;
    }
}
