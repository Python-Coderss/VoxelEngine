package com.voxel.world.beta;

/** Engine block ids used by generation/decoration. */
public final class BetaBlocks {
    public final int stone, grass, dirt, bedrock;
    public final int waterStill, lavaStill, sand, gravel, sandstone;
    public final int ice, snow, leaves, wood;
    public final int dandelion, rose, tallGrass, deadBush, cactus, pumpkin;
    public final int mushroomBrown, mushroomRed, reeds, clay;
    public final int coalOre, ironOre, goldOre, diamondOre, redstoneOre, lapisOre;
    public final int cobblestone, mossyCobble, chest, spawner;

    public BetaBlocks(int stone, int grass, int dirt, int bedrock, int waterStill, int lavaStill,
                      int sand, int gravel, int sandstone, int ice, int snow, int leaves, int wood,
                      int dandelion, int rose, int tallGrass, int deadBush, int cactus, int pumpkin,
                      int mushroomBrown, int mushroomRed, int reeds, int clay,
                      int coalOre, int ironOre, int goldOre, int diamondOre, int redstoneOre,
                      int lapisOre, int cobblestone, int mossyCobble, int chest, int spawner) {
        this.stone = stone; this.grass = grass; this.dirt = dirt; this.bedrock = bedrock;
        this.waterStill = waterStill; this.lavaStill = lavaStill; this.sand = sand; this.gravel = gravel;
        this.sandstone = sandstone; this.ice = ice; this.snow = snow; this.leaves = leaves; this.wood = wood;
        this.dandelion = dandelion; this.rose = rose; this.tallGrass = tallGrass; this.deadBush = deadBush;
        this.cactus = cactus; this.pumpkin = pumpkin;
        this.mushroomBrown = mushroomBrown; this.mushroomRed = mushroomRed; this.reeds = reeds; this.clay = clay;
        this.coalOre = coalOre; this.ironOre = ironOre; this.goldOre = goldOre; this.diamondOre = diamondOre;
        this.redstoneOre = redstoneOre; this.lapisOre = lapisOre;
        this.cobblestone = cobblestone; this.mossyCobble = mossyCobble; this.chest = chest; this.spawner = spawner;
    }
}
