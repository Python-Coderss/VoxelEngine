package com.voxel.game;

import com.voxel.entity.VillagerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The barter offers villagers make at their job sites. Every profession trades
 * what its work actually produces (a farmer sells grain, a builder sells
 * materials, a reporter sells news), and higher career levels unlock the
 * better offers.
 */
public final class VillagerTrades {

    /** One "give cost, get result" offer. */
    public static final class Offer {
        public final String costItem;
        public final int costCount;
        public final String resultItem;
        public final int resultCount;
        /** Career level required before this offer is available. */
        public final int minLevel;
        /** Job XP the villager earns from the trade. */
        public final int xp;

        Offer(String costItem, int costCount, String resultItem, int resultCount,
              int minLevel) {
            this.costItem = costItem;
            this.costCount = costCount;
            this.resultItem = resultItem;
            this.resultCount = resultCount;
            this.minLevel = minLevel;
            this.xp = VillagerProfessions.XP_TRADE;
        }

        public String label() {
            return costCount + " " + pretty(costItem) + " -> "
                    + resultCount + " " + pretty(resultItem);
        }
    }

    private static final List<Offer> FARMER_OFFERS = build(
            new Offer("wheat", 18, "emerald", 1, 1),
            new Offer("emerald", 1, "bread", 6, 1),
            new Offer("wheat_seeds", 12, "emerald", 1, 2),
            new Offer("emerald", 1, "apple", 4, 3),
            new Offer("emerald", 2, "carrot", 6, 4));

    private static final List<Offer> BUILDER_OFFERS = build(
            new Offer("stone", 24, "emerald", 1, 1),
            new Offer("emerald", 1, "oak_planks", 12, 1),
            new Offer("oak_log", 12, "emerald", 1, 2),
            new Offer("emerald", 2, "torch", 8, 3),
            new Offer("emerald", 1, "iron_ingot", 2, 4));

    private static final List<Offer> SHOPKEEPER_OFFERS = build(
            new Offer("coal", 12, "emerald", 1, 1),
            new Offer("emerald", 1, "stick", 8, 1),
            new Offer("string", 8, "emerald", 1, 2),
            new Offer("emerald", 2, "iron_ingot", 3, 3),
            new Offer("emerald", 4, "diamond", 1, 4));

    private static final List<Offer> NEWS_ANCHOR_OFFERS = build(
            new Offer("emerald", 1, "torch", 4, 1),
            new Offer("wheat", 6, "emerald", 1, 2),
            new Offer("emerald", 1, "string", 4, 3));

    private static List<Offer> build(Offer... offers) {
        List<Offer> list = new ArrayList<Offer>();
        Collections.addAll(list, offers);
        return Collections.unmodifiableList(list);
    }

    private VillagerTrades() {
    }

    /** Offers available to a profession at a career level (never null). */
    public static List<Offer> offers(VillagerEntity.Profession profession, int level) {
        List<Offer> pool = allOffers(profession);
        List<Offer> available = new ArrayList<Offer>();
        for (Offer offer : pool) {
            if (offer.minLevel <= level) {
                available.add(offer);
            }
        }
        return available;
    }

    /** Every offer the profession knows, regardless of level. */
    public static List<Offer> allOffers(VillagerEntity.Profession profession) {
        if (profession == null) return Collections.emptyList();
        switch (profession) {
            case FARMER: return FARMER_OFFERS;
            case BUILDER: return BUILDER_OFFERS;
            case SHOPKEEPER: return SHOPKEEPER_OFFERS;
            case NEWS_ANCHOR: return NEWS_ANCHOR_OFFERS;
            case NITWIT:
            default: return Collections.emptyList();
        }
    }

    /** True when the profession trades at all (nitwits have nothing to sell). */
    public static boolean trades(VillagerEntity.Profession profession) {
        return !allOffers(profession).isEmpty();
    }

    /** "iron_ingot" -> "iron ingot" for status lines and the trade panel. */
    public static String pretty(String itemId) {
        if (itemId == null) return "";
        return itemId.replace('_', ' ');
    }
}
