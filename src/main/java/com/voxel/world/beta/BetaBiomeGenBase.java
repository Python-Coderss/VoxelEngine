package com.voxel.world.beta;

/**
 * Faithful port of Beta 1.8.1's BiomeGenBase, extended with the six legacy
 * 1.7.3 biomes (rainforest, seasonal forest, savanna, shrubland, ice desert,
 * tundra). Biomes 0–9 match vanilla 1.8.1 exactly; 10–15 are the legacy set.
 * field names match the decompiled source for line-by-line verification.
 */
public class BetaBiomeGenBase {
    /** biomeId → BiomeGenBase lookup (field_35486_a). */
    public static final BetaBiomeGenBase[] field_35486_a = new BetaBiomeGenBase[256];

    // Beta block IDs used for surface blocks (declared first: the biome
    // initializers below reference them).
    public static final int B_GRASS = 2;
    public static final int B_DIRT = 3;
    public static final int B_SAND = 12;

    public static final BetaBiomeGenBase field_35484_b = new BetaBiomeGenBase(0).setColor(112).setBiomeName("Ocean").variationHeight(-1.0F, 0.5F);
    public static final BetaBiomeGenBase field_35485_c = new BetaBiomeGenBase(1).setColor(9286496).setBiomeName("Plains").tempDownfall(0.8F, 0.4F).decoTrees(-999).decoFlowers(4).decoTallGrass(10);
    public static final BetaBiomeGenBase desert = new BetaBiomeGenBase(2).setColor(16421912).setBiomeName("Desert").setDisableRain().tempDownfall(2.0F, 0.0F).variationHeight(0.1F, 0.2F).surfaceBlocks(B_SAND, B_SAND).decoTrees(-999).decoDeadBush(2).decoReeds(50).decoCactus(10);
    public static final BetaBiomeGenBase field_35483_e = new BetaBiomeGenBase(3).setColor(6316128).setBiomeName("Extreme Hills").variationHeight(0.2F, 1.8F).tempDownfall(0.2F, 0.3F);
    public static final BetaBiomeGenBase forest = new BetaBiomeGenBase(4).setColor(353825).setBiomeName("Forest").treeType(1).tempDownfall(0.7F, 0.8F).decoTrees(10).decoTallGrass(2);
    public static final BetaBiomeGenBase taiga = new BetaBiomeGenBase(5).setColor(747097).setBiomeName("Taiga").treeType(2).tempDownfall(0.3F, 0.8F).variationHeight(0.1F, 0.4F).decoTrees(10).decoTallGrass(1);
    public static final BetaBiomeGenBase swampland = new BetaBiomeGenBase(6).setColor(522674).setBiomeName("Swampland").treeType(3).variationHeight(-0.2F, 0.1F).tempDownfall(0.8F, 0.9F).decoTrees(2).decoFlowers(-999).decoDeadBush(1).decoMushrooms(8).decoReeds(10);
    public static final BetaBiomeGenBase field_35487_i = new BetaBiomeGenBase(7).setColor(255).setBiomeName("River").variationHeight(-0.5F, 0.0F);
    public static final BetaBiomeGenBase hell = new BetaBiomeGenBase(8).setColor(16711680).setBiomeName("Hell").setDisableRain();
    public static final BetaBiomeGenBase sky = new BetaBiomeGenBase(9).setColor(8421631).setBiomeName("Sky").setDisableRain();

    // ── Legacy 1.7.3 biomes ──
    public static final BetaBiomeGenBase rainforest = new BetaBiomeGenBase(10).setColor(5470985).setBiomeName("Rainforest").treeType(1).tempDownfall(0.95F, 0.9F).variationHeight(0.1F, 0.4F).decoTrees(10).decoTallGrass(10);
    public static final BetaBiomeGenBase seasonalForest = new BetaBiomeGenBase(11).setColor(12566463).setBiomeName("Seasonal Forest").treeType(1).tempDownfall(0.7F, 0.8F).variationHeight(0.3F, 0.6F).decoTrees(8).decoFlowers(4).decoTallGrass(2);
    public static final BetaBiomeGenBase savanna = new BetaBiomeGenBase(12).setColor(12431967).setBiomeName("Savanna").tempDownfall(1.2F, 0.0F).variationHeight(0.125F, 0.05F).decoTrees(1).decoTallGrass(1);
    public static final BetaBiomeGenBase shrubland = new BetaBiomeGenBase(13).setColor(10595616).setBiomeName("Shrubland").tempDownfall(0.8F, 0.2F).variationHeight(0.2F, 0.3F).decoTrees(1).decoTallGrass(2);
    public static final BetaBiomeGenBase iceDesert = new BetaBiomeGenBase(14).setColor(14211288).setBiomeName("Ice Desert").setDisableRain().tempDownfall(0.0F, 0.0F).variationHeight(0.05F, 0.1F).surfaceBlocks(B_SAND, B_SAND).decoTrees(-999);
    public static final BetaBiomeGenBase tundra = new BetaBiomeGenBase(15).setColor(12638463).setBiomeName("Tundra").tempDownfall(0.0F, 0.5F).variationHeight(0.05F, 0.1F).decoTrees(-999).decoTallGrass(1);

    public String biomeName;
    public int color;
    public int topBlock = B_GRASS;
    public int fillerBlock = B_DIRT;
    /** variation (field_35492_q) */
    public float field_35492_q = 0.1F;
    /** height (field_35491_r) */
    public float field_35491_r = 0.3F;
    /** temperature (field_35490_s) */
    public float field_35490_s = 0.5F;
    /** downfall (field_35489_t) */
    public float field_35489_t = 0.5F;
    /** biome id (field_35494_y) */
    public final int field_35494_y;
    private boolean enableRain = true;

    // Decorator configuration (mirrors the BiomeDecorator field_359xx_xx tweaks
    // made by each vanilla biome subclass constructor).
    public int decoTrees = 0;
    public int decoFlowers = 2;
    public int decoTallGrass = 1;
    public int decoDeadBush = 0;
    public int decoMushrooms = 0;
    public int decoReeds = 0;
    public int decoCactus = 0;
    /** Tree selector: 0 = default (oak/big), 1 = forest, 2 = taiga, 3 = swamp. */
    public int treeType = 0;

    protected BetaBiomeGenBase(int id) {
        this.field_35494_y = id;
        field_35486_a[id] = this;
    }

    /** temperature = temp, downfall = down (func_35478_a). */
    private BetaBiomeGenBase tempDownfall(float temp, float down) {
        this.field_35490_s = temp;
        this.field_35489_t = down;
        return this;
    }

    /** variation = var, height = h (func_35479_b). */
    private BetaBiomeGenBase variationHeight(float var, float h) {
        this.field_35492_q = var;
        this.field_35491_r = h;
        return this;
    }

    private BetaBiomeGenBase setDisableRain() { this.enableRain = false; return this; }
    private BetaBiomeGenBase surfaceBlocks(int top, int filler) { this.topBlock = top; this.fillerBlock = filler; return this; }
    private BetaBiomeGenBase setBiomeName(String n) { this.biomeName = n; return this; }
    private BetaBiomeGenBase setColor(int c) { this.color = c; return this; }
    private BetaBiomeGenBase treeType(int t) { this.treeType = t; return this; }
    private BetaBiomeGenBase decoTrees(int v) { this.decoTrees = v; return this; }
    private BetaBiomeGenBase decoFlowers(int v) { this.decoFlowers = v; return this; }
    private BetaBiomeGenBase decoTallGrass(int v) { this.decoTallGrass = v; return this; }
    private BetaBiomeGenBase decoDeadBush(int v) { this.decoDeadBush = v; return this; }
    private BetaBiomeGenBase decoMushrooms(int v) { this.decoMushrooms = v; return this; }
    private BetaBiomeGenBase decoReeds(int v) { this.decoReeds = v; return this; }
    private BetaBiomeGenBase decoCactus(int v) { this.decoCactus = v; return this; }

    /** func_35476_e — downfall as a 16-bit fixed-point value. */
    public final int func_35476_e() {
        return (int) (this.field_35489_t * 65536.0F);
    }

    /** func_35474_f — temperature as a 16-bit fixed-point value. */
    public final int func_35474_f() {
        return (int) (this.field_35490_s * 65536.0F);
    }

    public boolean getEnableRain() { return this.enableRain; }
}
