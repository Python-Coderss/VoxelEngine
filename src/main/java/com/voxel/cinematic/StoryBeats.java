package com.voxel.cinematic;

/**
 * Authored MCSM moments: the decisions and quick-time events the shipped worlds
 * use. Kept out of {@link CinematicSystem} so the director stays a player and
 * the writing reads like a script.
 *
 * <p>Choice ids become story flags ({@code choice.<id>}), so a later beat can
 * read them with {@link com.voxel.game.StoryState#is(String, String)} and change
 * a line, a reward or an outcome.</p>
 */
public final class StoryBeats {

    private StoryBeats() {
    }

    /**
     * Tutorial: the builder at the gate asks why the player came. MCSM always
     * offers a full row of four: earnest, proud, flippant and nosy.
     */
    public static CinematicChoice tutorialFork() {
        return new CinematicChoice("tutorial_fork", "Ancient Builder",
                "The builder lowers her ledger. \"Well? What brings you to the castle?\"", 9f)
                .option("I came to learn.",
                        "Then learn fast. The quarry does not grade on effort.")
                .option("I'm here to build something legendary.",
                        "Ambition. I have buried better builders than you.")
                .option("Just passing through.",
                        "Nobody just passes through. The gate remembers.")
                .option("What happened to the last builder?",
                        "...We do not keep that ledger anymore.")
                .defaultOption(0);
    }

    /** Point & Click: choose the first district to visit. */
    public static CinematicChoice pointClickFork() {
        return new CinematicChoice("pointclick_fork", "Plaza Guide",
                "\"Four paths leave the plaza. Where will you start?\"", 10f)
                .option("The workshop.", "Mind the cogs. They bite.")
                .option("The market.", "Coin first, questions later. Sensible.")
                .option("The quarry.", "Stone is honest. People are not.")
                .option("The farm.", "Good. Nobody else volunteers for the farm.")
                .defaultOption(0);
    }

    /** First ore strike: a beam gives way overhead. */
    public static QuickTimeEvent oreQte() {
        return new QuickTimeEvent("The tunnel groans — DUCK!", QuickTimeEvent.Input.SPACE, 2.4f)
                .success("You drop under the beam. Dust rains down behind you.")
                .fail("The beam clips your shoulder, but the tunnel holds.");
    }
}
