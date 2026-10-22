package com.voxel.cinematic;

import com.voxel.world.DimensionType;

import java.util.Random;

/**
 * Voice for every realm. Each dimension gets a first-arrival beat (title +
 * narrator lines), a one-line return line, and ambient chatter the director
 * can drop while the player lingers.
 *
 * <p>Written as data so the writing stays in one place: the director only
 * schedules, and adding a dimension means adding one case, not another scene
 * method. Lines are framing narration, not copied game dialogue.</p>
 */
public final class DimensionDialogue {

    private DimensionDialogue() {
    }

    /** Title card shown on the first arrival. */
    public static String title(DimensionType dimension) {
        switch (dimension == null ? DimensionType.OVERWORLD : dimension) {
            case NETHER: return "THE NETHER";
            case END: return "THE END";
            case AETHER: return "THE AETHER";
            case ERROR502: return "ERROR 502";
            case PORTAL_HALL: return "THE PORTAL HALL";
            case SIFT: return "THE SIFT";
            case OVERWORLD:
            default: return "THE OVERWORLD";
        }
    }

    /** Subtitle under the arrival title. */
    public static String subtitle(DimensionType dimension) {
        switch (dimension == null ? DimensionType.OVERWORLD : dimension) {
            case NETHER: return "A world that burns forever";
            case END: return "Quiet, and not empty";
            case AETHER: return "Gravity is a suggestion";
            case ERROR502: return "The world that failed to load";
            case PORTAL_HALL: return "Seven doors, one ledger";
            case SIFT: return "The tide keeps what it takes";
            case OVERWORLD:
            default: return "Grass, sky, and a horizon";
        }
    }

    /**
     * First-visit narration, timed for a short establishing scene. Two lines
     * per realm: one about the place, one about what the player should do.
     */
    public static CinematicScript arrivalLines(DimensionType dimension) {
        CinematicScript script = new CinematicScript();
        switch (dimension == null ? DimensionType.OVERWORLD : dimension) {
            case NETHER:
                script.at(0.9f, "Narrator", "The Nether does not welcome. It tolerates.");
                script.at(4.6f, "Narrator", "Mind the lava. It has never once apologized.");
                break;
            case END:
                script.at(0.9f, "Narrator", "The End is quiet the way a held breath is quiet.");
                script.at(4.8f, "Narrator", "Something out here remembers the last visitor.");
                break;
            case AETHER:
                script.at(0.9f, "Narrator", "The Aether floats because it refuses good sense.");
                script.at(4.8f, "Narrator", "Watch your step. The clouds only pretend to be clouds.");
                break;
            case ERROR502:
                script.at(0.9f, "Narrator", "This realm failed to load properly. It kept going.");
                script.at(4.6f, "Narrator", "Do not trust the geometry. It is doing its best.");
                break;
            case PORTAL_HALL:
                script.at(0.9f, "Narrator", "Seven doors, one hall, and an unreasonable amount of dust.");
                script.at(4.8f, "Narrator", "The builders left the lights on. Nobody knows for whom.");
                break;
            case SIFT:
                script.at(0.9f, "Narrator", "The Sift moves when you are not looking. Try to be looking.");
                script.at(4.8f, "Narrator", "Sift the tide. Keep what the sea forgot.");
                break;
            case OVERWORLD:
            default:
                script.at(0.9f, "Narrator", "Grass, sky, and a horizon that keeps its promises.");
                script.at(4.6f, "Narrator", "Every legend starts with someone punching a tree.");
                break;
        }
        return script;
    }

    /** A short line for returning to a realm the player has already seen. */
    public static String returnLine(DimensionType dimension) {
        switch (dimension == null ? DimensionType.OVERWORLD : dimension) {
            case NETHER: return "Back to the heat. Try to keep your eyebrows.";
            case END: return "The End has not missed you. It noticed, though.";
            case AETHER: return "The Aether is still floating. Unsettlingly.";
            case ERROR502: return "Still loading. Still wrong. Welcome back.";
            case PORTAL_HALL: return "The hall is exactly as you left it. The dust has moved.";
            case SIFT: return "The tide has shifted. It usually has.";
            case OVERWORLD:
            default: return "The Overworld. Still green, still keeping score.";
        }
    }

    /** One ambient line for the dimension, chosen deterministically per seed. */
    public static String ambientLine(DimensionType dimension, Random rng) {
        String[] lines = ambientLines(dimension);
        if (lines.length == 0) return null;
        int index = rng == null ? 0 : Math.floorMod(rng.nextInt(), lines.length);
        return lines[index];
    }

    /**
     * Lines the director may speak while the player lingers: small observations
     * that make a realm feel inhabited without hijacking the camera.
     */
    public static String[] ambientLines(DimensionType dimension) {
        switch (dimension == null ? DimensionType.OVERWORLD : dimension) {
            case NETHER:
                return new String[]{
                        "Ghasts sound like crying. That is not an invitation.",
                        "Nether brick is patient. It was laid by someone who had time.",
                        "The lava bubbles like it is agreeing with you.",
                        "Everything here is either on fire or considering it."
                };
            case END:
                return new String[]{
                        "The void is not empty. It is just very good at listening.",
                        "Endermen do not blink. Neither does the sky.",
                        "The chorus fruit hums when nobody is watching.",
                        "Somewhere, a dragon is having a very long nap."
                };
            case AETHER:
                return new String[]{
                        "The clouds here have opinions about being walked on.",
                        "Aerwhales sing in a key that does not exist below.",
                        "The islands drift. Slowly. Politely.",
                        "Do not look down. The sky is down here."
                };
            case ERROR502:
                return new String[]{
                        "The chunk you are standing on is technically a rumour.",
                        "Somewhere a generator is very sorry.",
                        "The terrain is correct adjacent to the terrain.",
                        "If you fall, the world will try again."
                };
            case PORTAL_HALL:
                return new String[]{
                        "The ledger lists every world. Some entries are crossed out.",
                        "The doors are warm. All of them.",
                        "Someone swept this hall. Recently. Probably.",
                        "The builders are gone. Their portals are not."
                };
            case SIFT:
                return new String[]{
                        "The tide is high. Or the sky is low. It changes.",
                        "Sift the bed and the sea gives something back.",
                        "The Meadow is calm because it is pretending.",
                        "Carapace clicks when the tide turns."
                };
            case OVERWORLD:
            default:
                return new String[]{
                        "The village is louder than it looks from here.",
                        "Somewhere a cow is judging your build.",
                        "The sun is doing its one job perfectly.",
                        "It is a good day to dig straight down. It always is."
                };
        }
    }
}
