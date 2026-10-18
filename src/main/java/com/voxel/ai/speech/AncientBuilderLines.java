package com.voxel.ai.speech;

import java.util.Random;

/**
 * The words of the ancient builders — the civilization that raised the world
 * before the extinction. They are scholars, architects, engineers and sentries
 * who still patrol their works, so every line is spoken in that voice: the
 * player is a late arrival walking through a civilization that has not yet
 * learned it is doomed.
 *
 * <p>Lines are grouped by the moment that calls for them and picked with a
 * per-entity {@link Random}, so two builders never say the same thing at once.
 * They are spoken verbatim by the builder voice profile — see
 * {@link CharacterVoice}.</p>
 */
public final class AncientBuilderLines {

    /** The moment a line is spoken in. */
    public enum Topic {
        /** First contact: the builder notices the player. */
        GREETING,
        /** The player has intruded too far, or hurt a builder. */
        WARNING,
        /** On the job: laying stone, raising a beam. */
        WORK,
        /** Reading, measuring, recording. */
        STUDY,
        /** Walking the rounds of a site. */
        PATROL,
        /** Danger spotted; the site is being called to arms. */
        ALARM,
        /** Retreating to shelter while a threat stands. */
        RETREAT,
        /** Quiet observation of the player or the world. */
        OBSERVE
    }

    private static final String[] GREETING = {
            "Another traveler. The roads have been quiet for an age.",
            "You stand on stone we raised before your grandfathers were born.",
            "Welcome, wanderer. Mind the scaffolding — it is older than it looks.",
            "You have found one of our works. Few still do.",
            "Hold there. I would know your purpose before you walk further.",
            "Ah — a face we did not carve into the archive. Curious.",
            "Walk softly. These halls remember every footstep.",
            "So the world still makes people. Good. We were beginning to wonder."
    };

    private static final String[] WARNING = {
            "That is far enough. The inner chambers are not open to visitors.",
            "You have crossed the survey line. Step back, and we will forget it.",
            "Enough. We do not raise our works to have them looted.",
            "The next stone you break will be answered.",
            "I will say this once: yield the path.",
            "You are not the first to come for our stores. You will not be the last."
    };

    private static final String[] WORK = {
            "Two hands, one level. That is how a wall stays true.",
            "Hold — the joint has not cured. There. Now it will outlast us.",
            "Measure twice. The First Builders never measured once.",
            "This course is crooked. Again, from the corner.",
            "Bring the crane around. Gently. Gently.",
            "The keystone goes in last. Always last.",
            "Sand and lime and patience. Nothing else holds.",
            "We build for a thousand years. The schedule can wait an hour."
    };

    private static final String[] STUDY = {
            "The charts agree with the survey. That is twice this season.",
            "Every star we named is still where we left it. A small comfort.",
            "I record it for the archive. Someone must.",
            "The old stone sings when the wind finds the right seam.",
            "Three theories, and the evidence will not choose between them.",
            "We were measuring the sky long before we thought to look down.",
            "The ink is older than the page. The page is older than me."
    };

    private static final String[] PATROL = {
            "All quiet along the outer wall.",
            "The lamps burn. The gates hold. Good.",
            "Nothing on the horizon but dust and the far towers.",
            "I walk these rounds so others may sleep.",
            "The perimeter is sound. Mark it in the log.",
            "Still standing. Still ours."
    };

    private static final String[] ALARM = {
            "To arms! Something moves in the dark beyond the wall!",
            "Signal the others — we are not alone here!",
            "Bar the inner door! Sound the beacon!",
            "They have come for the works again. Hold the line!",
            "Wake the sentries! Ring the stones!",
            "This is no traveler. This is a hunt."
    };

    private static final String[] RETREAT = {
            "Fall back to the hall. We cannot hold the yard.",
            "Inside! The walls will decide this, not us.",
            "We lose the outer ring. Let it go. Let it go!",
            "Down the stair — the vault is sealed behind us.",
            "Regroup at the archive. Count yourselves there.",
            "The site is lost for today. The stone will remember us."
    };

    private static final String[] OBSERVE = {
            "Curious. It watches us the way we watch the sky.",
            "A stranger's boots. Note the wear. They have walked far.",
            "Every age sends someone to ask what we were building.",
            "It has not drawn a blade. Interesting.",
            "Perhaps it is only lost. Many are, out here.",
            "I will keep my hands where it can see them."
    };

    private AncientBuilderLines() {
    }

    /** Pick a line for a moment. Never returns null or empty. */
    public static String pick(Topic topic, Random rng) {
        String[] pool = pool(topic);
        if (rng == null) return pool[0];
        return pool[Math.floorMod(rng.nextInt(), pool.length)];
    }

    /** The phrase pool for a moment (used by tests and tools). */
    public static String[] pool(Topic topic) {
        if (topic == null) return GREETING;
        switch (topic) {
            case GREETING: return GREETING;
            case WARNING: return WARNING;
            case WORK: return WORK;
            case STUDY: return STUDY;
            case PATROL: return PATROL;
            case ALARM: return ALARM;
            case RETREAT: return RETREAT;
            case OBSERVE:
            default: return OBSERVE;
        }
    }
}
