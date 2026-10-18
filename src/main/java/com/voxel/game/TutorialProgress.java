package com.voxel.game;

/**
 * The Tutorial World's guided progression: six small goals that walk a new
 * player from "punch a rock" to "raid the dungeon hall".
 *
 * <p>Pure and allocation-light, so it is unit tested headlessly and can be
 * driven from any subsystem. Each goal is completed by a named action reported
 * from the code that already performs it (block break, craft result, furnace
 * output, portal travel, chest open), which means the tracker reflects what
 * the player actually did instead of where they happened to walk.</p>
 *
 * <p>{@link #consumeJustCompleted()} hands the HUD/Main one completion event at
 * a time so a step can be celebrated exactly once.</p>
 */
public final class TutorialProgress {

    public static final int STEP_COUNT = 6;

    public static final String MINE = "mine";
    public static final String CRAFT = "craft";
    public static final String SMELT = "smelt";
    public static final String IRON = "iron";
    public static final String PORTAL = "portal";
    public static final String LOOT = "loot";

    private static final String[] ACTIONS = { MINE, CRAFT, SMELT, IRON, PORTAL, LOOT };
    private static final String[] TITLES = {
        "MINE SOME STONE",
        "CRAFT A TOOL",
        "SMELT AN ORE",
        "FORGE IRON GEAR",
        "STEP THROUGH A PORTAL",
        "RAID THE DUNGEON HALL"
    };
    private static final String[] HINTS = {
        "Hold left click on stone, coal or iron. The Step 1 station has an exposed ore face.",
        "Use the crafting table at the Step 2 station: planks in a column make sticks.",
        "Right-click a furnace with ore and coal, then take the ingot from the output slot.",
        "Craft an iron pickaxe, axe or sword once you have smelted iron.",
        "The Step 5 station has a lit Nether portal and an Aether shrine beside it.",
        "The gold-chested dungeon hall is west of the arc. Open its chest for tiered loot."
    };

    private final boolean[] done = new boolean[STEP_COUNT];
    private final java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
    private int lastReported = -1;

    /** True when this action belongs to a step. */
    public static boolean isAction(String action) {
        if (action == null) return false;
        for (String a : ACTIONS) if (a.equals(action)) return true;
        return false;
    }

    public static String title(int step) {
        return step >= 0 && step < STEP_COUNT ? TITLES[step] : "";
    }

    public static String hint(int step) {
        return step >= 0 && step < STEP_COUNT ? HINTS[step] : "";
    }

    public static String actionFor(int step) {
        return step >= 0 && step < STEP_COUNT ? ACTIONS[step] : "";
    }

    /**
     * Marks the step that {@code action} belongs to.
     *
     * @return true when this call completed a step for the first time
     */
    public boolean complete(String action) {
        int step = stepFor(action);
        if (step < 0 || done[step]) return false;
        done[step] = true;
        pending.addLast(step);
        return true;
    }

    /** Marks a step directly (used by tests and the /tutorial command). */
    public boolean completeStep(int step) {
        if (step < 0 || step >= STEP_COUNT || done[step]) return false;
        done[step] = true;
        pending.addLast(step);
        return true;
    }

    public static int stepFor(String action) {
        if (action == null) return -1;
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTIONS[i].equals(action)) return i;
        }
        return -1;
    }

    public boolean isDone(int step) {
        return step >= 0 && step < STEP_COUNT && done[step];
    }

    /** 0-based index of the next unfinished step, or -1 when the tour is done. */
    public int currentIndex() {
        for (int i = 0; i < STEP_COUNT; i++) if (!done[i]) return i;
        return -1;
    }

    public int completedCount() {
        int n = 0;
        for (boolean b : done) if (b) n++;
        return n;
    }

    public boolean isComplete() {
        return completedCount() == STEP_COUNT;
    }

    /** One pending celebration, or -1. Cleared by reading. */
    public int consumeJustCompleted() {
        Integer step = pending.pollFirst();
        if (step == null) return -1;
        lastReported = step;
        return step;
    }

    public int lastReportedStep() {
        return lastReported;
    }

    /** Tracker headline: "STEP 3/6 · SMELT AN ORE". */
    public String headline() {
        int current = currentIndex();
        if (current < 0) return "TOUR COMPLETE · " + STEP_COUNT + "/" + STEP_COUNT;
        return "STEP " + (current + 1) + "/" + STEP_COUNT + " · " + TITLES[current];
    }

    /** Tracker second line: how to do the current step. */
    public String hintLine() {
        int current = currentIndex();
        if (current < 0) return "Every station visited. The world is yours.";
        return HINTS[current];
    }

    public void reset() {
        java.util.Arrays.fill(done, false);
        pending.clear();
        lastReported = -1;
    }
}
