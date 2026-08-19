package com.voxel.world.beta;

import java.util.Random;

/**
 * Faithful port of Beta 1.8.1's BiomeDecorator. Decoration counts/features are
 * driven by the biome's decorator configuration (decoTrees/decoFlowers/…), and
 * the tree shape by the biome's treeType selector.
 */
public class BetaBiomeDecorator {
    private final BetaWorldGenMinable dirtVein, gravelVein, coalVein, ironVein, goldVein;
    private final BetaWorldGenMinable redstoneVein, diamondVein, lapisVein;
    private final BetaWorldGenFlowers yellowFlower, redFlower;
    private final BetaWorldGenFlowers brownMushroom, redMushroom;
    private final BetaWorldGenReed reed;
    private final BetaWorldGenCactus cactus;
    private final BetaWorldGenTallGrass tallGrass;
    private final BetaWorldGenDeadBush deadBush;
    private final BetaWorldGenPumpkin pumpkin;
    private final BetaWorldGenClay clay;
    private final BetaWorldGenSand sandPatch;
    private final BetaWorldGenSand gravelPatch;
    private final BetaWorldGenLakes waterLake, lavaLake;
    private final BetaWorldGenLiquids waterLiquid, lavaLiquid;
    private final BetaWorldGenDungeons dungeon;

    private final BetaWorldGenTrees oakTree = new BetaWorldGenTrees();
    private final BetaWorldGenForest forestTree = new BetaWorldGenForest();
    private final BetaWorldGenSwamp swampTree = new BetaWorldGenSwamp();
    private final BetaWorldGenTaiga1 taiga1 = new BetaWorldGenTaiga1();
    private final BetaWorldGenTaiga2 taiga2 = new BetaWorldGenTaiga2();
    private final BetaWorldGenBigTree bigTree = new BetaWorldGenBigTree();

    public BetaBiomeDecorator(BetaBlocks b) {
        this.dirtVein = new BetaWorldGenMinable(b.dirt, 32);
        this.gravelVein = new BetaWorldGenMinable(b.gravel, 32);
        this.coalVein = new BetaWorldGenMinable(b.coalOre, 16);
        this.ironVein = new BetaWorldGenMinable(b.ironOre, 8);
        this.goldVein = new BetaWorldGenMinable(b.goldOre, 8);
        this.redstoneVein = new BetaWorldGenMinable(b.redstoneOre, 7);
        this.diamondVein = new BetaWorldGenMinable(b.diamondOre, 7);
        this.lapisVein = new BetaWorldGenMinable(b.lapisOre, 6);
        this.yellowFlower = new BetaWorldGenFlowers(b.dandelion);
        this.redFlower = new BetaWorldGenFlowers(b.rose);
        this.brownMushroom = new BetaWorldGenFlowers(b.mushroomBrown);
        this.redMushroom = new BetaWorldGenFlowers(b.mushroomRed);
        this.reed = new BetaWorldGenReed();
        this.cactus = new BetaWorldGenCactus();
        this.tallGrass = new BetaWorldGenTallGrass(b.tallGrass);
        this.deadBush = new BetaWorldGenDeadBush(b.deadBush);
        this.pumpkin = new BetaWorldGenPumpkin();
        this.clay = new BetaWorldGenClay(4);
        this.sandPatch = new BetaWorldGenSand(7, b.sand);
        this.gravelPatch = new BetaWorldGenSand(6, b.gravel);
        this.waterLake = new BetaWorldGenLakes(b.waterStill);
        this.lavaLake = new BetaWorldGenLakes(b.lavaStill);
        this.waterLiquid = new BetaWorldGenLiquids(b.waterStill);
        this.lavaLiquid = new BetaWorldGenLiquids(b.lavaStill);
        this.dungeon = new BetaWorldGenDungeons();
    }

    public void decorate(BetaGenContext ctx, Random rand, int x, int z, BetaBiomeGenBase biome) {
        decorateOres(ctx, rand, x, z);
        decorateFeatures(ctx, rand, x, z, biome);
    }

    /** func_35880_a — ore / dirt / gravel veins. */
    private void decorateOres(BetaGenContext ctx, Random rand, int x, int z) {
        generateVeins(20, dirtVein, ctx, rand, x, z, 0, 128);
        generateVeins(10, gravelVein, ctx, rand, x, z, 0, 128);
        generateVeins(20, coalVein, ctx, rand, x, z, 0, 128);
        generateVeins(20, ironVein, ctx, rand, x, z, 0, 64);
        generateVeins(2, goldVein, ctx, rand, x, z, 0, 32);
        generateVeins(8, redstoneVein, ctx, rand, x, z, 0, 16);
        generateVeins(1, diamondVein, ctx, rand, x, z, 0, 16);
        generateVeinsTriangular(1, lapisVein, ctx, rand, x, z, 16, 16);
    }

    private void generateVeins(int count, BetaWorldGenMinable gen, BetaGenContext ctx,
                               Random rand, int x, int z, int minY, int maxY) {
        for (int i = 0; i < count; ++i) {
            int bx = x + rand.nextInt(16);
            int by = rand.nextInt(maxY - minY) + minY;
            int bz = z + rand.nextInt(16);
            gen.generate(ctx, rand, bx, by, bz);
        }
    }

    private void generateVeinsTriangular(int count, BetaWorldGenMinable gen, BetaGenContext ctx,
                                         Random rand, int x, int z, int center, int spread) {
        for (int i = 0; i < count; ++i) {
            int bx = x + rand.nextInt(16);
            int by = rand.nextInt(spread) + rand.nextInt(spread) + (center - spread);
            int bz = z + rand.nextInt(16);
            gen.generate(ctx, rand, bx, by, bz);
        }
    }

    /** func_35882_b — surface features (trees, plants, lakes, …). */
    private void decorateFeatures(BetaGenContext ctx, Random rand, int x, int z, BetaBiomeGenBase biome) {
        int i, bx, bz, by;

        // Gravel patches.
        for (i = 0; i < 3; ++i) {
            bx = x + rand.nextInt(16) + 8;
            bz = z + rand.nextInt(16) + 8;
            gravelPatch.generate(ctx, rand, bx, ctx.getTopSolidOrLiquid(bx, bz), bz);
        }
        // Clay.
        for (i = 0; i < 1; ++i) {
            bx = x + rand.nextInt(16) + 8;
            bz = z + rand.nextInt(16) + 8;
            clay.generate(ctx, rand, bx, ctx.getTopSolidOrLiquid(bx, bz), bz);
        }
        // Sand patches.
        for (i = 0; i < 1; ++i) {
            bx = x + rand.nextInt(16) + 8;
            bz = z + rand.nextInt(16) + 8;
            sandPatch.generate(ctx, rand, bx, ctx.getTopSolidOrLiquid(bx, bz), bz);
        }

        // Trees.
        int trees = biome.decoTrees;
        if (rand.nextInt(10) == 0) ++trees;
        for (i = 0; i < trees; ++i) {
            bx = x + rand.nextInt(16) + 8;
            bz = z + rand.nextInt(16) + 8;
            int y = ctx.getTopSolidOrLiquid(bx, bz) + 1;
            if (y > 0) placeTree(ctx, rand, bx, y, bz, biome);
        }

        // Flowers (yellow, with occasional red).
        for (i = 0; i < biome.decoFlowers; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            yellowFlower.generate(ctx, rand, bx, by, bz);
            if (rand.nextInt(4) == 0) {
                bx = x + rand.nextInt(16) + 8;
                by = rand.nextInt(128);
                bz = z + rand.nextInt(16) + 8;
                redFlower.generate(ctx, rand, bx, by, bz);
            }
        }

        // Tall grass.
        for (i = 0; i < biome.decoTallGrass; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            tallGrass.generate(ctx, rand, bx, by, bz);
        }

        // Dead bush.
        for (i = 0; i < biome.decoDeadBush; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            deadBush.generate(ctx, rand, bx, by, bz);
        }

        // Mushrooms.
        for (i = 0; i < biome.decoMushrooms; ++i) {
            if (rand.nextInt(4) == 0) {
                bx = x + rand.nextInt(16) + 8;
                bz = z + rand.nextInt(16) + 8;
                by = ctx.getTopSolidOrLiquid(bx, bz) + 1;
                brownMushroom.generate(ctx, rand, bx, by, bz);
            }
            if (rand.nextInt(8) == 0) {
                bx = x + rand.nextInt(16) + 8;
                bz = z + rand.nextInt(16) + 8;
                by = rand.nextInt(128);
                redMushroom.generate(ctx, rand, bx, by, bz);
            }
        }

        // Reeds.
        for (i = 0; i < biome.decoReeds; ++i) {
            bx = x + rand.nextInt(16) + 8;
            bz = z + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            reed.generate(ctx, rand, bx, by, bz);
        }
        for (i = 0; i < 10; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            reed.generate(ctx, rand, bx, by, bz);
        }

        // Pumpkin.
        if (rand.nextInt(32) == 0) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            pumpkin.generate(ctx, rand, bx, by, bz);
        }

        // Cactus.
        for (i = 0; i < biome.decoCactus; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(128);
            bz = z + rand.nextInt(16) + 8;
            cactus.generate(ctx, rand, bx, by, bz);
        }

        // Underground water/lava pockets.
        for (i = 0; i < 50; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(rand.nextInt(128 - 8) + 8);
            bz = z + rand.nextInt(16) + 8;
            waterLiquid.generate(ctx, rand, bx, by, bz);
        }
        for (i = 0; i < 20; ++i) {
            bx = x + rand.nextInt(16) + 8;
            by = rand.nextInt(rand.nextInt(rand.nextInt(128 - 16) + 8) + 8);
            bz = z + rand.nextInt(16) + 8;
            lavaLiquid.generate(ctx, rand, bx, by, bz);
        }
    }

    /** Picks and generates the biome-appropriate tree shape. */
    private void placeTree(BetaGenContext ctx, Random rand, int x, int y, int z, BetaBiomeGenBase biome) {
        switch (biome.treeType) {
            case 1: // forest
                if (rand.nextInt(5) == 0) forestTree.generate(ctx, rand, x, y, z);
                else if (rand.nextInt(10) == 0) { bigTree.func_517_a(1.0D, 1.0D, 1.0D); bigTree.generate(ctx, rand, x, y, z); }
                else oakTree.generate(ctx, rand, x, y, z);
                break;
            case 2: // taiga
                if (rand.nextInt(3) == 0) taiga1.generate(ctx, rand, x, y, z);
                else taiga2.generate(ctx, rand, x, y, z);
                break;
            case 3: // swamp
                swampTree.generate(ctx, rand, x, y, z);
                break;
            default:
                if (rand.nextInt(10) == 0) { bigTree.func_517_a(1.0D, 1.0D, 1.0D); bigTree.generate(ctx, rand, x, y, z); }
                else oakTree.generate(ctx, rand, x, y, z);
        }
    }
}
