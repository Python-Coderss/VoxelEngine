package com.voxel.world;

/**
 * Block ids for the decorative building blocks: doors (both wall
 * orientations), trapdoors, ladders and flower pots.
 *
 * <p>Declared in one place so the three consumers agree on the name → id
 * table: {@code Main.registerDecorativeBlocks()} registers the blocks,
 * {@code ItemDefinitions} gives them items, and the authored worlds
 * ({@link ShowcaseWorldAuthor}, {@link PointClickWorldAuthor}) place them.</p>
 *
 * <p>Ids start at {@link #FIRST_ID} = 950. That range is free: the Sift set
 * ends at 937, the book blocks occupy 940-947, and nothing else is allocated
 * above 947.</p>
 *
 * <p>Beds and standing banners are multiblocks too. A bed is a foot cell plus
 * a head cell on one horizontal axis (four facings); a standing banner is a
 * lower plus an upper cell that shares geometry between north/south and
 * between east/west. Both halves of each are real blocks so breaking or
 * placing either one can address the pair.</p>
 *
 * <p>Doors come in two shapes. {@code *_door_lower}/{@code *_door_upper} are
 * the vanilla panels sitting against the <em>west</em> edge of their cell (full
 * depth in Z), so they belong in a wall that runs along Z.
 * {@code *_door_lower_ew}/{@code *_door_upper_ew} are the same geometry
 * rotated 90° about Y, for walls that run along X. Both halves are real blocks
 * stacked to make a two-block-tall door.</p>
 */
public final class DecorBlocks {

    /** Id of {@link #NAMES}[0]; every other block is FIRST_ID + index. */
    public static final int FIRST_ID = 950;

    /** Registration order. Index + {@link #FIRST_ID} is the block id. */
    public static final String[] NAMES = {
        // Walls running along Z (vanilla door geometry: panel on the west edge).
        "oak_door_lower", "oak_door_upper", "oak_door_lower_ew", "oak_door_upper_ew",
        "spruce_door_lower", "spruce_door_upper",
        "spruce_door_lower_ew", "spruce_door_upper_ew",
        "birch_door_lower", "birch_door_upper", "birch_door_lower_ew", "birch_door_upper_ew",
        "dark_oak_door_lower", "dark_oak_door_upper",
        "dark_oak_door_lower_ew", "dark_oak_door_upper_ew",
        "oak_trapdoor", "iron_trapdoor",
        "ladder", "flower_pot",
        // Beds: canonical north-facing foot first, then the other facings.
        "bed", "bed_head",
        "bed_foot_south", "bed_head_south",
        "bed_foot_east", "bed_head_east",
        "bed_foot_west", "bed_head_west",
        // Standing banners: lower/upper, cloth in the XY or ZY plane.
        "banner", "banner_upper", "banner_ew", "banner_upper_ew",
    };

    // Convenience constants for the authored worlds.
    public static final int OAK_DOOR_LOWER = FIRST_ID + 0;
    public static final int OAK_DOOR_UPPER = FIRST_ID + 1;
    public static final int OAK_DOOR_LOWER_EW = FIRST_ID + 2;
    public static final int OAK_DOOR_UPPER_EW = FIRST_ID + 3;
    public static final int SPRUCE_DOOR_LOWER = FIRST_ID + 4;
    public static final int SPRUCE_DOOR_UPPER = FIRST_ID + 5;
    public static final int SPRUCE_DOOR_LOWER_EW = FIRST_ID + 6;
    public static final int SPRUCE_DOOR_UPPER_EW = FIRST_ID + 7;
    public static final int BIRCH_DOOR_LOWER = FIRST_ID + 8;
    public static final int BIRCH_DOOR_UPPER = FIRST_ID + 9;
    public static final int BIRCH_DOOR_LOWER_EW = FIRST_ID + 10;
    public static final int BIRCH_DOOR_UPPER_EW = FIRST_ID + 11;
    public static final int DARK_OAK_DOOR_LOWER = FIRST_ID + 12;
    public static final int DARK_OAK_DOOR_UPPER = FIRST_ID + 13;
    public static final int DARK_OAK_DOOR_LOWER_EW = FIRST_ID + 14;
    public static final int DARK_OAK_DOOR_UPPER_EW = FIRST_ID + 15;
    public static final int OAK_TRAPDOOR = FIRST_ID + 16;
    public static final int IRON_TRAPDOOR = FIRST_ID + 17;
    public static final int LADDER = FIRST_ID + 18;
    public static final int FLOWER_POT = FIRST_ID + 19;

    // Beds (canonical block names are the north-facing pair).
    public static final int BED_FOOT_NORTH = FIRST_ID + 20;
    public static final int BED_HEAD_NORTH = FIRST_ID + 21;
    public static final int BED_FOOT_SOUTH = FIRST_ID + 22;
    public static final int BED_HEAD_SOUTH = FIRST_ID + 23;
    public static final int BED_FOOT_EAST = FIRST_ID + 24;
    public static final int BED_HEAD_EAST = FIRST_ID + 25;
    public static final int BED_FOOT_WEST = FIRST_ID + 26;
    public static final int BED_HEAD_WEST = FIRST_ID + 27;

    // Standing banners (lower/upper, two cloth planes).
    public static final int BANNER_LOWER_NS = FIRST_ID + 28;
    public static final int BANNER_UPPER_NS = FIRST_ID + 29;
    public static final int BANNER_LOWER_EW = FIRST_ID + 30;
    public static final int BANNER_UPPER_EW = FIRST_ID + 31;

    /** Horizontal direction a bed or banner faces. */
    public enum Facing {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        public final int dx;
        public final int dz;

        Facing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        /** Facing whose axis dominates a look vector. */
        public static Facing fromLook(float lookX, float lookZ) {
            if (Math.abs(lookX) >= Math.abs(lookZ)) {
                return lookX >= 0 ? EAST : WEST;
            }
            return lookZ >= 0 ? SOUTH : NORTH;
        }
    }

    /**
     * Extra-data bit (bit 0 of the 8 extra bits) marking an opened door.
     * Both halves carry it so the state survives a reload and can be toggled
     * back without guessing the wall orientation.
     */
    public static final int DOOR_OPEN_BIT = 1;

    /** Doors occupy the first 16 ids: 4 woods x (lower, upper, lower_ew, upper_ew). */
    private static final int DOOR_BLOCK_COUNT = 16;

    /** Block id registered under {@code name}, or -1 when unknown. */
    public static int id(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) return FIRST_ID + i;
        }
        return -1;
    }

    // ── Door multiblock ────────────────────────────────────────────────────
    // A door is one item backed by two stacked blocks. The block ids are laid
    // out in groups of four per wood: lower, upper, lower_ew, upper_ew. The
    // "_ew" pair is the same panel rotated 90°, which is exactly the open
    // state of the vanilla door — so opening/closing is an id swap and needs
    // no new models.

    /** True for any of the 16 door blocks. */
    public static boolean isDoor(int blockId) {
        return blockId >= FIRST_ID && blockId < FIRST_ID + DOOR_BLOCK_COUNT;
    }

    /** True for the bottom half of a door (either wall orientation). */
    public static boolean isDoorLower(int blockId) {
        return isDoor(blockId) && (blockId - FIRST_ID) % 2 == 0;
    }

    /** True for the top half of a door (either wall orientation). */
    public static boolean isDoorUpper(int blockId) {
        return isDoor(blockId) && (blockId - FIRST_ID) % 2 == 1;
    }

    /** The other half of the same door column, or -1 for non-door ids. */
    public static int doorPartner(int blockId) {
        if (!isDoor(blockId)) return -1;
        return isDoorLower(blockId) ? blockId + 1 : blockId - 1;
    }

    /** The lower block id of this door column (either half, either orientation). */
    public static int doorLowerOf(int blockId) {
        if (!isDoor(blockId)) return -1;
        return isDoorLower(blockId) ? blockId : blockId - 1;
    }

    /** The same half of the door rotated 90° — i.e. the open/closed counterpart. */
    public static int doorToggled(int blockId) {
        if (!isDoor(blockId)) return -1;
        // Ids run lower, upper, lower_ew, upper_ew per wood: the rotated pair
        // is always two ids away, in whichever direction we are facing now.
        return (blockId - FIRST_ID) % 4 < 2 ? blockId + 2 : blockId - 2;
    }

    /**
     * Lower-half block id for a wood, oriented for the wall it sits in.
     *
     * @param lowerId a lower door id (any wood); its wall orientation is ignored
     * @param wallRunsAlongZ true for a north-south wall (panel thin in X)
     */
    public static int doorLowerForWall(int lowerId, boolean wallRunsAlongZ) {
        if (!isDoorLower(lowerId)) return -1;
        int woodBase = FIRST_ID + ((lowerId - FIRST_ID) / 4) * 4;
        return woodBase + (wallRunsAlongZ ? 0 : 2);
    }

    /** True when the door's extra data says it is open. */
    public static boolean isDoorOpen(int extraData) {
        return (extraData & DOOR_OPEN_BIT) != 0;
    }

    // ── Beds (two horizontal cells) ────────────────────────────────────────

    /** True for any bed half. */
    public static boolean isBed(int blockId) {
        return blockId >= BED_FOOT_NORTH && blockId <= BED_HEAD_WEST;
    }

    /** True for a head half (the pillow end). */
    public static boolean isBedHead(int blockId) {
        return isBed(blockId) && (blockId - BED_FOOT_NORTH) % 2 == 1;
    }

    /** True for a foot half (the cell the player places). */
    public static boolean isBedFoot(int blockId) {
        return isBed(blockId) && !isBedHead(blockId);
    }

    /** The other half of the same bed, or -1. */
    public static int bedPartner(int blockId) {
        if (!isBed(blockId)) return -1;
        return isBedHead(blockId) ? blockId - 1 : blockId + 1;
    }

    /** Foot block for a facing (the canonical "bed" item uses NORTH). */
    public static int bedFootForFacing(Facing facing) {
        switch (facing == null ? Facing.NORTH : facing) {
            case SOUTH: return BED_FOOT_SOUTH;
            case EAST: return BED_FOOT_EAST;
            case WEST: return BED_FOOT_WEST;
            case NORTH:
            default: return BED_FOOT_NORTH;
        }
    }

    /** Head block matching a foot id, or -1. */
    public static int bedHeadForFoot(int footId) {
        return isBedFoot(footId) ? footId + 1 : -1;
    }

    /** Facing of a bed half, derived from the id (head and foot agree). */
    public static Facing bedFacing(int blockId) {
        if (!isBed(blockId)) return null;
        int foot = isBedHead(blockId) ? blockId - 1 : blockId;
        int index = (foot - BED_FOOT_NORTH) / 2;
        switch (index) {
            case 1: return Facing.SOUTH;
            case 2: return Facing.EAST;
            case 3: return Facing.WEST;
            default: return Facing.NORTH;
        }
    }

    /** X offset from either bed half to its partner. */
    public static int bedPartnerDx(int blockId) {
        Facing facing = bedFacing(blockId);
        if (facing == null) return 0;
        return isBedHead(blockId) ? -facing.dx : facing.dx;
    }

    /** Z offset from either bed half to its partner. */
    public static int bedPartnerDz(int blockId) {
        Facing facing = bedFacing(blockId);
        if (facing == null) return 0;
        return isBedHead(blockId) ? -facing.dz : facing.dz;
    }

    // ── Standing banners (two vertical cells) ─────────────────────────────

    /** True for any banner half. */
    public static boolean isBanner(int blockId) {
        return blockId >= BANNER_LOWER_NS && blockId <= BANNER_UPPER_EW;
    }

    /** True for the bottom half of a banner. */
    public static boolean isBannerLower(int blockId) {
        return isBanner(blockId) && (blockId - BANNER_LOWER_NS) % 2 == 0;
    }

    /** The other half of the same banner, or -1. */
    public static int bannerPartner(int blockId) {
        if (!isBanner(blockId)) return -1;
        return isBannerLower(blockId) ? blockId + 1 : blockId - 1;
    }

    /** Lower banner block for a facing: the cloth plane follows the look axis. */
    public static int bannerLowerForFacing(Facing facing) {
        Facing value = facing == null ? Facing.NORTH : facing;
        return value == Facing.EAST || value == Facing.WEST
                ? BANNER_LOWER_EW : BANNER_LOWER_NS;
    }

    /** Upper banner block matching a lower id, or -1. */
    public static int bannerUpperForLower(int lowerId) {
        return isBannerLower(lowerId) ? lowerId + 1 : -1;
    }

    private DecorBlocks() {}
}
