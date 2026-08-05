package com.voxel.audio;

import com.voxel.entity.VillagerEntity;

/** Selects short, repeatable villager lines for synthesis and cache keys. */
public final class VillagerDialogue {
    private VillagerDialogue() {
    }

    public static String choose(VillagerEntity villager, float worldTime, int interactionIndex) {
        if (villager == null) {
            return "Hmm...";
        }
        String[] lines = linesFor(villager.getProfession(), period(worldTime));
        int seed = villager.id * 31 + interactionIndex * 17 + period(worldTime).ordinal() * 7;
        return lines[Math.floorMod(seed, lines.length)];
    }

    private static Period period(float worldTime) {
        float time = worldTime % 1440.0f;
        if (time < 360.0f) return Period.NIGHT;
        if (time < 600.0f) return Period.MORNING;
        if (time < 1080.0f) return Period.DAY;
        return Period.EVENING;
    }

    private static String[] linesFor(VillagerEntity.Profession profession, Period period) {
        switch (profession) {
            case FARMER:
                switch (period) {
                    case MORNING: return new String[]{
                            "The crops will be ready soon, hmm.",
                            "A good morning for planting, hmm.",
                            "Mind the carrots; they are coming along nicely."
                    };
                    case EVENING: return new String[]{
                            "The fields can wait until morning, hmm.",
                            "Another harvest safely gathered.",
                            "Please keep off the seedlings tonight."
                    };
                    case NIGHT: return new String[]{
                            "Even farmers need their rest, hmm.",
                            "The crops are sleeping too.",
                            "No trading until sunrise, friend."
                    };
                    default: return new String[]{
                            "The wheat is looking excellent today.",
                            "A fair day for a little farming.",
                            "Do you need something from the market?"
                    };
                }
            case BUILDER:
                switch (period) {
                    case MORNING: return new String[]{
                            "I have a wall to finish before noon.",
                            "The village needs another roof, hmm.",
                            "Bring materials if you want to help."
                    };
                    case EVENING: return new String[]{
                            "One more block, then I am done for today.",
                            "The walls are strong enough for tonight.",
                            "Do not move that beam; it is important."
                    };
                    case NIGHT: return new String[]{
                            "Construction is closed until morning.",
                            "Even stone masons need a bed, hmm.",
                            "The gates are secure. You may rest."
                    };
                    default: return new String[]{
                            "I am building something sturdy for the village.",
                            "That corner could use a roof.",
                            "Measure twice, place once, hmm."
                    };
                }
            case NEWS_ANCHOR:
                switch (period) {
                    case MORNING: return new String[]{
                            "Good morning, villagers. Here is today's news.",
                            "The morning report is looking remarkably calm.",
                            "Stay tuned for important village updates."
                    };
                    case EVENING: return new String[]{
                            "Good evening. This has been your village report.",
                            "Tonight's headline: everyone is still quite square.",
                            "That concludes the evening news, hmm."
                    };
                    case NIGHT: return new String[]{
                            "This is a late bulletin. Please return indoors.",
                            "No breaking news, only breaking blocks.",
                            "The night report is quiet for once."
                    };
                    default: return new String[]{
                            "Welcome to the village news desk.",
                            "Our top story: a surprisingly large potato.",
                            "We report the facts, preferably with a hmm."
                    };
                }
            case SHOPKEEPER:
                switch (period) {
                    case MORNING: return new String[]{
                            "The shop is open. Let us make a fair deal.",
                            "Fresh stock arrived this morning, hmm.",
                            "You look like someone who appreciates quality goods."
                    };
                    case EVENING: return new String[]{
                            "Last trades before closing, friend.",
                            "I can offer one final bargain tonight.",
                            "The ledger balances. A satisfying evening."
                    };
                    case NIGHT: return new String[]{
                            "The shop is closed until sunrise.",
                            "Come back tomorrow with something interesting to trade.",
                            "No deals in the dark, hmm."
                    };
                    default: return new String[]{
                            "Have you seen anything worth trading today?",
                            "A fine day for commerce, hmm.",
                            "I know a good deal when I see one."
                    };
                }
            default:
                switch (period) {
                    case MORNING: return new String[]{
                            "Hmm. You are awake early.",
                            "The village is already busy today.",
                            "Good morning, traveler."
                    };
                    case EVENING: return new String[]{
                            "The sun is going down, hmm.",
                            "A long day of standing around.",
                            "Do you have any interesting news?"
                    };
                    case NIGHT: return new String[]{
                            "It is late. You should find a bed, hmm.",
                            "The night is quiet around here.",
                            "Please do not wake the village."
                    };
                    default: return new String[]{
                            "Hmm... What do you want, traveler?",
                            "A fine day for doing absolutely nothing.",
                            "You again. Hmm."
                    };
                }
        }
    }

    private enum Period { NIGHT, MORNING, DAY, EVENING }
}
